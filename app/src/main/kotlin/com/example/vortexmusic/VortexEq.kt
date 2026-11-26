package io.github.vortexmusic.player

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tanh

// Vortex'in kendi ses işlemcisi (telefonun ses efektlerine bağlı değildir).
// Sıra: 10 bantlı ekolayzır -> harmonik bas -> stereo genişlik -> ses seviyesi -> sınırlayıcı.
// Tüm ayarlar yumuşatılarak uygulanır, bu yüzden kaydırıcıyı oynatınca tıkırtı olmaz.
// Sınırlayıcı sesi bozmadan tavanda tutar (2 ms ileriye bakar).
@UnstableApi
class VortexEqProcessor : BaseAudioProcessor() {

    companion object {
        val FREQS = intArrayOf(31, 62, 125, 250, 500, 1000, 2000, 4000, 8000, 16000)
        const val BANDS = 10
        private const val EXTRA = 3
        private const val SLOTS = BANDS + EXTRA
        private const val MAX_CH = 8
        private const val Q = 1.41
        // Ekolayzır artışının bu kadarı kadar ses kısılır (sınırlayıcı kalanını yakalar)
        private const val PREAMP_FACTOR = 0.75
        private const val CEIL = 0.95
        private const val BASS_MIX = 1.3
        private const val BASS_DRIVE = 4.0
        private const val SMOOTH_SEC = 0.02
        private const val FFT_N = 1024
        // Bas koruyucu: sadece 140 Hz altını sınırlar (kulaklıkta sert bas vuruşunu yumuşatır)
        private const val BLIM_FC = 140.0
        private const val BLIM_T = 0.40
        const val SPEC_BANDS = 32
        // Otomatik dengeleme: hedef ortalama seviye (dBFS), en çok kısma/yükseltme (dB), ölçüm süresi
        private const val AUTO_TARGET_DB = -18.0
        private const val AUTO_MIN_DB = -6.0
        private const val AUTO_MAX_DB = 2.0
        private const val AUTO_SEC = 12.0
    }

    // Arayüz iş parçacığından yazılır, ses iş parçacığından okunur
    @Volatile private var pendingGains: DoubleArray = DoubleArray(BANDS)
    @Volatile private var on = false
    @Volatile private var bassAmt = 0f
    @Volatile private var loudDb = 0f
    @Volatile private var widthAmt = 0f
    @Volatile private var autoOn = false

    // Çalan sesin anlık seviyesi (0-1). Girdap animasyonu "Ritim" stili okur.
    @Volatile private var liveLevel = 0f
    // Bas vuruşu sayacı: her davul vuruşunda 1 artar (animasyon bunu izler)
    @Volatile private var liveBeats = 0

    // Spektrum: 32 bant (40 Hz - 16 kHz), 0-1 arası. Girdap animasyonunun "Spektrum" stili okur.
    @Volatile private var liveSpectrum = FloatArray(SPEC_BANDS)

    // Görsel senkron: ses çıkışa (hoparlör, kulaklık, Bluetooth) biraz gecikmeyle ulaşır.
    // Animasyonların okuduğu değerler bu kadar geriden gelir, böylece ses ve görüntü birlikte hareket eder.
    @Volatile var syncDelayMs = 120L

    private class Frame(val t: Long, val spec: FloatArray, val level: Float, val beats: Int)
    private val hist = arrayOfNulls<Frame>(64)
    private var histPos = 0
    private val histLock = Any()

    private fun pushFrame(spec: FloatArray) {
        val f = Frame(android.os.SystemClock.elapsedRealtime(), spec, liveLevel, liveBeats)
        synchronized(histLock) {
            hist[histPos] = f
            histPos = (histPos + 1) % hist.size
        }
    }

    private fun clearFrames() {
        synchronized(histLock) {
            java.util.Arrays.fill(hist, null)
            histPos = 0
        }
    }

    // Şu andan syncDelayMs önceki kare (yoksa en eskisi)
    private fun delayedFrame(): Frame? {
        val target = android.os.SystemClock.elapsedRealtime() - syncDelayMs
        synchronized(histLock) {
            var oldest: Frame? = null
            for (k in 1..hist.size) {
                val f = hist[(histPos - k + hist.size) % hist.size] ?: break
                if (f.t <= target) return f
                oldest = f
            }
            return oldest
        }
    }

    val level: Float get() = delayedFrame()?.level ?: liveLevel
    val beatCount: Int get() = delayedFrame()?.beats ?: liveBeats
    val spectrum: FloatArray get() = delayedFrame()?.spec ?: liveSpectrum

    private val fftRe = DoubleArray(FFT_N)
    private val fftIm = DoubleArray(FFT_N)
    private val fftWin = DoubleArray(FFT_N) { 0.5 - 0.5 * kotlin.math.cos(2.0 * PI * it / (FFT_N - 1)) }
    private var fftFill = 0
    private val bandBin = IntArray(SPEC_BANDS + 1)
    private var bandSr = 0

    private fun pushSample(v: Double) {
        fftRe[fftFill] = v * fftWin[fftFill]
        fftIm[fftFill] = 0.0
        if (++fftFill >= FFT_N) {
            fftFill = 0
            computeSpectrum()
        }
    }

    private fun fft() {
        val n = FFT_N
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while ((j and bit) != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j xor bit
            if (i < j) {
                val tr = fftRe[i]; fftRe[i] = fftRe[j]; fftRe[j] = tr
                val ti = fftIm[i]; fftIm[i] = fftIm[j]; fftIm[j] = ti
            }
        }
        var len = 2
        while (len <= n) {
            val ang = -2.0 * PI / len
            val wr = kotlin.math.cos(ang)
            val wi = sin(ang)
            var i = 0
            while (i < n) {
                var cr = 1.0
                var ci = 0.0
                val half = len / 2
                for (k in 0 until half) {
                    val a = i + k
                    val b = a + half
                    val xr = fftRe[b] * cr - fftIm[b] * ci
                    val xi = fftRe[b] * ci + fftIm[b] * cr
                    fftRe[b] = fftRe[a] - xr
                    fftIm[b] = fftIm[a] - xi
                    fftRe[a] += xr
                    fftIm[a] += xi
                    val ncr = cr * wr - ci * wi
                    ci = cr * wi + ci * wr
                    cr = ncr
                }
                i += len
            }
            len = len shl 1
        }
    }

    private fun computeSpectrum() {
        if (bandSr != sampleRate) {
            for (b in 0..SPEC_BANDS) {
                val f = 40.0 * (16000.0 / 40.0).pow(b.toDouble() / SPEC_BANDS)
                bandBin[b] = (f * FFT_N / sampleRate).toInt().coerceIn(1, FFT_N / 2)
            }
            for (b in 1..SPEC_BANDS) if (bandBin[b] <= bandBin[b - 1]) bandBin[b] = minOf(bandBin[b - 1] + 1, FFT_N / 2)
            bandSr = sampleRate
        }
        fft()
        val out = FloatArray(SPEC_BANDS)
        for (b in 0 until SPEC_BANDS) {
            val lo = bandBin[b]
            val hi = minOf(maxOf(bandBin[b + 1], lo + 1), FFT_N / 2)
            var mx = 0.0
            for (k in lo until hi) {
                val m = fftRe[k] * fftRe[k] + fftIm[k] * fftIm[k]
                if (m > mx) mx = m
            }
            val amp = sqrt(mx) * 4.0 / FFT_N
            // Yüksek frekanslar doğal olarak zayıf: görsel için hafifçe dengele
            val db = 20.0 * log10(amp + 1e-9) + b * 0.5
            out[b] = ((db + 72.0) / 56.0).coerceIn(0.0, 1.0).toFloat()
        }
        liveSpectrum = out
        pushFrame(out)
    }

    private var bassLp = 0.0
    private var bassSlow = 0.0
    private var lastBeatNs = 0L
    @Volatile private var corrType = 0
    private var appliedCorr = -1

    // Otomatik ses dengeleme: yavaş ölçülen ses seviyesini hedefe yaklaştırır
    private var meterMs = 0.0
    private var meterCount = 0L
    private var meterCoef = 0.0
    private var autoLin = 1.0
    // Otomatik dengeleme: hedef ve gerçek kazanç (gerçek kazanç yumuşakça hedefe gider)
    private var autoGoal = 1.0

    // Bas koruyucu durumu
    private var lb0 = 0.0
    private var lb1 = 0.0
    private var lb2 = 0.0
    private var la1 = 0.0
    private var la2 = 0.0
    private val lz = DoubleArray(4 * MAX_CH)
    private val lowv = DoubleArray(MAX_CH)
    private var blGain = 1.0
    private var blAtk = 0.0
    private var blRel = 0.0
    private var autoCur = 1.0
    private var autoUp = 0.0
    private var autoDown = 0.0
    private var autoTick = 0

    private var appliedGains: DoubleArray? = null
    private var sampleRate = 44100
    private var channels = 2
    private var isFloat = false
    private var primed = false
    // Atlama / yeni şarkı / sarma sonrası ilk ~12 ms'de ses sıfırdan yükselir (örnek hassasiyetinde).
    // Ani başlayan sesin yarattığı tıkı önler; ana işlemdeki ses kısma hilesine gerek bırakmaz.
    private var rampLeft = 0
    private var rampTotal = 1

    // Ekolayzır süzgeçleri
    private val b0 = DoubleArray(SLOTS)
    private val b1 = DoubleArray(SLOTS)
    private val b2 = DoubleArray(SLOTS)
    private val a1 = DoubleArray(SLOTS)
    private val a2 = DoubleArray(SLOTS)
    private val activeIdx = IntArray(SLOTS)
    private val wasActive = BooleanArray(SLOTS)
    private var activeCount = 0
    private var preamp = 1.0
    private val z1 = DoubleArray(SLOTS * MAX_CH)
    private val z2 = DoubleArray(SLOTS * MAX_CH)

    // Harmonik bas: alçak geçiren -> bozundur -> yüksek geçiren -> alçak geçiren
    private val sb0 = DoubleArray(3)
    private val sb1 = DoubleArray(3)
    private val sb2 = DoubleArray(3)
    private val sa1 = DoubleArray(3)
    private val sa2 = DoubleArray(3)
    private val sz1 = DoubleArray(3 * MAX_CH)
    private val sz2 = DoubleArray(3 * MAX_CH)

    // Yumuşatılmış ayarlar
    private var gainCur = 1.0
    private var bassCur = 0.0
    private var widthCur = 0.0
    private var smoothCoef = 0.001

    // Sınırlayıcı
    private var lookN = 88
    private var dly = DoubleArray(0)
    private var grBuf = DoubleArray(0)
    private var limPos = 0
    private var env = 1.0
    private var atk = 0.04
    private var rel = 0.0003

    private val xs = DoubleArray(MAX_CH)

    // gainsDb: 10 bant için dB; bass 0-1; loudnessDb 0-10 dB; width 0-1
    fun setParams(gainsDb: DoubleArray, enabled: Boolean, bass: Float, loudnessDb: Float, width: Float, autoLevel: Boolean = false, correction: Int = 0) {
        val g = DoubleArray(BANDS)
        for (i in 0 until BANDS) g[i] = gainsDb.getOrElse(i) { 0.0 }
        pendingGains = g
        bassAmt = bass.coerceIn(0f, 1f)
        loudDb = loudnessDb.coerceIn(0f, 12f)
        widthAmt = width.coerceIn(0f, 1f)
        on = enabled
        autoOn = autoLevel
        corrType = correction
    }

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        val enc = inputAudioFormat.encoding
        if (enc != C.ENCODING_PCM_16BIT && enc != C.ENCODING_PCM_FLOAT) {
            return AudioProcessor.AudioFormat.NOT_SET
        }
        if (inputAudioFormat.channelCount < 1 || inputAudioFormat.channelCount > MAX_CH) {
            return AudioProcessor.AudioFormat.NOT_SET
        }
        sampleRate = inputAudioFormat.sampleRate
        channels = inputAudioFormat.channelCount
        isFloat = enc == C.ENCODING_PCM_FLOAT
        appliedGains = null
        appliedCorr = -1
        primed = false
        java.util.Arrays.fill(wasActive, false)
        java.util.Arrays.fill(z1, 0.0)
        java.util.Arrays.fill(z2, 0.0)
        java.util.Arrays.fill(sz1, 0.0)
        java.util.Arrays.fill(sz2, 0.0)
        java.util.Arrays.fill(lz, 0.0)
        blGain = 1.0

        val fs = sampleRate.toDouble()
        designBass(fs)
        run {
            val w = 2.0 * PI * BLIM_FC / fs
            val cw = cos(w)
            val al = sin(w) / (2.0 * 0.70710678)
            val a0 = 1.0 + al
            lb0 = (1.0 - cw) / 2.0 / a0
            lb1 = (1.0 - cw) / a0
            lb2 = lb0
            la1 = -2.0 * cw / a0
            la2 = (1.0 - al) / a0
            blAtk = 1.0 - exp(-1.0 / (0.0015 * fs))
            blRel = 1.0 - exp(-1.0 / (0.12 * fs))
            java.util.Arrays.fill(lz, 0.0)
            blGain = 1.0
        }
        smoothCoef = 1.0 - exp(-1.0 / (SMOOTH_SEC * fs))
        lookN = maxOf(8, (0.003 * fs).toInt())
        dly = DoubleArray(lookN * channels)
        grBuf = DoubleArray(lookN) { 1.0 }
        limPos = 0
        env = 1.0
        meterCoef = 1.0 - exp(-1.0 / (AUTO_SEC * fs))
        meterMs = 0.0
        meterCount = 0L
        atk = 1.0 - exp(-1.0 / (0.001 * fs))
        rel = 1.0 - exp(-1.0 / (0.10 * fs))
        // Kısarken 1.5 sn, açarken 4 sn: ani "pat" olmaz
        autoDown = 1.0 - exp(-1.0 / (1.5 * fs))
        autoUp = 1.0 - exp(-1.0 / (4.0 * fs))
        return inputAudioFormat
    }

    // Her 8. kareyi örnekleyerek hızlıca ortalama seviye bulur
    private fun measure(buf: ByteBuffer, start: Int, end: Int) {
        val bytes = if (isFloat) 4 else 2
        val step = bytes * channels
        var sum = 0.0
        var n = 0
        var p = start
        while (p + step <= end) {
            val v = if (isFloat) buf.getFloat(p).toDouble() else buf.getShort(p) / 32768.0
            sum += v * v
            n++
            p += step * 8
        }
        if (n > 0) liveLevel = sqrt(sum / n).toFloat()

        // Bas vuruşu: 1. kanalın düşük frekans enerjisi, yavaş ortalamasının üstüne fırlayınca vuruş say
        val a = 1.0 - exp(-2.0 * PI * 150.0 / sampleRate)
        var bs = 0.0
        var bn = 0
        var q = start
        while (q + step <= end) {
            val v = if (isFloat) buf.getFloat(q).toDouble() else buf.getShort(q) / 32768.0
            bassLp += (v - bassLp) * a
            pushSample(v)
            bs += bassLp * bassLp
            bn++
            q += step
        }
        if (bn > 0) {
            val e = sqrt(bs / bn)
            val now = System.nanoTime()
            if (e > bassSlow * 1.4 + 0.015 && now - lastBeatNs > 140_000_000L) {
                lastBeatNs = now
                liveBeats++
            }
            // Yaklaşık 1 sn'lik yavaş ortalama (tampon başına güncellenir)
            val dt = bn.toDouble() / sampleRate
            bassSlow += (e - bassSlow) * (1.0 - exp(-dt / 1.0))
        }
    }

    override fun onFlush() {
        // Atlama veya yeni şarkıda ölçümü baştan başlat (kazanç yumuşakça kalır)
        meterMs = 0.0
        meterCount = 0L
        clearFrames()
        // Atlama ya da yeni şarkıda eski sesten kalan parçalar çıkmasın (tık sesini önler)
        java.util.Arrays.fill(dly, 0.0)
        java.util.Arrays.fill(grBuf, 1.0)
        limPos = 0
        env = 1.0
        java.util.Arrays.fill(z1, 0.0)
        java.util.Arrays.fill(z2, 0.0)
        java.util.Arrays.fill(sz1, 0.0)
        java.util.Arrays.fill(sz2, 0.0)
        // Bas koruyucu belleği de temizlenir (eski şarkıdan kalan kısma kalmasın)
        java.util.Arrays.fill(lz, 0.0)
        blGain = 1.0
        bassCur = if (on) bassAmt.toDouble() else 0.0
        primed = false
        rampTotal = maxOf(1, (sampleRate * 0.012).toInt())
        rampLeft = rampTotal
    }

    // Kulaklık düzeltme katmanı: hafif, genel amaçlı eğriler (ölçüme dayalı değildir).
    // Dönüş: [tür, frekans, dB, Q]
    private fun extraStage(type: Int, k: Int): DoubleArray = when (type) {
        // Kulak içi: bas ve tizi biraz destekle, 3-4 kHz sertliğini yumuşat
        1 -> when (k) {
            0 -> doubleArrayOf(1.0, 110.0, 3.0, 0.7)
            1 -> doubleArrayOf(0.0, 3500.0, -1.5, 1.2)
            else -> doubleArrayOf(2.0, 10000.0, 1.5, 0.7)
        }
        // Kulak üstü: hafif bas desteği, orta-tizi yumuşat
        2 -> when (k) {
            0 -> doubleArrayOf(1.0, 90.0, 2.0, 0.7)
            1 -> doubleArrayOf(0.0, 3000.0, -1.0, 1.0)
            else -> doubleArrayOf(2.0, 9000.0, 1.0, 0.7)
        }
        // Boğuk: bası ve bulanıklığı azalt, tizi aç
        3 -> when (k) {
            0 -> doubleArrayOf(1.0, 150.0, -2.5, 0.7)
            1 -> doubleArrayOf(0.0, 280.0, -2.0, 1.0)
            else -> doubleArrayOf(2.0, 8000.0, 2.0, 0.7)
        }
        else -> doubleArrayOf(0.0, 1000.0, 0.0, 1.0)
    }

    private fun designBass(fs: Double) {
        lowpass(0, 110.0, fs)
        highpass(1, 140.0, fs)
        lowpass(2, 320.0, fs)
    }

    private fun lowpass(i: Int, f: Double, fs: Double) {
        val w = 2.0 * PI * f / fs
        val c = cos(w)
        val al = sin(w) / (2.0 * 0.7071)
        val a0 = 1.0 + al
        sb0[i] = (1.0 - c) / 2.0 / a0
        sb1[i] = (1.0 - c) / a0
        sb2[i] = (1.0 - c) / 2.0 / a0
        sa1[i] = -2.0 * c / a0
        sa2[i] = (1.0 - al) / a0
    }

    private fun highpass(i: Int, f: Double, fs: Double) {
        val w = 2.0 * PI * f / fs
        val c = cos(w)
        val al = sin(w) / (2.0 * 0.7071)
        val a0 = 1.0 + al
        sb0[i] = (1.0 + c) / 2.0 / a0
        sb1[i] = -(1.0 + c) / a0
        sb2[i] = (1.0 + c) / 2.0 / a0
        sa1[i] = -2.0 * c / a0
        sa2[i] = (1.0 - al) / a0
    }

    // Gerekirse ekolayzır katsayılarını yeniden hesaplar (ses iş parçacığında)
    private fun refresh() {
        val g = pendingGains
        val corr = corrType
        if (g === appliedGains && corr == appliedCorr) return
        appliedGains = g
        appliedCorr = corr
        val fs = sampleRate.toDouble()
        activeCount = 0
        for (i in 0 until SLOTS) {
            // kind: 0 tepe, 1 alçak raf, 2 yüksek raf
            val kind: Int
            val f: Double
            val gdb: Double
            val q: Double
            if (i < BANDS) {
                kind = 0; f = FREQS[i].toDouble(); gdb = g[i]; q = Q
            } else {
                val e = extraStage(corr, i - BANDS)
                kind = e[0].toInt(); f = e[1]; gdb = e[2]; q = e[3]
            }
            if (abs(gdb) < 0.05 || f >= 0.45 * fs) {
                wasActive[i] = false
                continue
            }
            if (!wasActive[i]) {
                for (c in 0 until MAX_CH) {
                    z1[i * MAX_CH + c] = 0.0
                    z2[i * MAX_CH + c] = 0.0
                }
                wasActive[i] = true
            }
            val a = 10.0.pow(gdb / 40.0)
            val w = 2.0 * PI * f / fs
            val c = cos(w)
            if (kind == 0) {
                val alpha = sin(w) / (2.0 * q)
                val a0 = 1.0 + alpha / a
                b0[i] = (1.0 + alpha * a) / a0
                b1[i] = (-2.0 * c) / a0
                b2[i] = (1.0 - alpha * a) / a0
                a1[i] = (-2.0 * c) / a0
                a2[i] = (1.0 - alpha / a) / a0
            } else {
                val t = 2.0 * Math.sqrt(a) * (sin(w) / 2.0 * Math.sqrt(2.0))
                if (kind == 1) {
                    val a0 = (a + 1) + (a - 1) * c + t
                    b0[i] = a * ((a + 1) - (a - 1) * c + t) / a0
                    b1[i] = 2 * a * ((a - 1) - (a + 1) * c) / a0
                    b2[i] = a * ((a + 1) - (a - 1) * c - t) / a0
                    a1[i] = -2 * ((a - 1) + (a + 1) * c) / a0
                    a2[i] = ((a + 1) + (a - 1) * c - t) / a0
                } else {
                    val a0 = (a + 1) - (a - 1) * c + t
                    b0[i] = a * ((a + 1) + (a - 1) * c + t) / a0
                    b1[i] = -2 * a * ((a - 1) + (a + 1) * c) / a0
                    b2[i] = a * ((a + 1) + (a - 1) * c - t) / a0
                    a1[i] = 2 * ((a - 1) - (a + 1) * c) / a0
                    a2[i] = ((a + 1) - (a - 1) * c - t) / a0
                }
            }
            activeIdx[activeCount++] = i
        }
        // En yüksek artışı bul, ona göre sesi biraz kıs
        var peakDb = 0.0
        if (activeCount > 0) {
            val top = minOf(20000.0, 0.45 * fs)
            val steps = 160
            for (s in 0 until steps) {
                val fr = 20.0 * (top / 20.0).pow(s.toDouble() / (steps - 1))
                val w = 2.0 * PI * fr / fs
                val cw = cos(w)
                val sw = sin(w)
                val c2 = cos(2.0 * w)
                val s2 = sin(2.0 * w)
                var m2 = 1.0
                for (k in 0 until activeCount) {
                    val i = activeIdx[k]
                    val nr = b0[i] + b1[i] * cw + b2[i] * c2
                    val ni = -(b1[i] * sw + b2[i] * s2)
                    val dr = 1.0 + a1[i] * cw + a2[i] * c2
                    val di = -(a1[i] * sw + a2[i] * s2)
                    m2 *= (nr * nr + ni * ni) / (dr * dr + di * di)
                }
                val db = 10.0 * log10(m2)
                if (db > peakDb) peakDb = db
            }
        }
        preamp = 10.0.pow(-PREAMP_FACTOR * peakDb / 20.0)
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val remaining = inputBuffer.remaining()
        if (remaining == 0) return
        val out = replaceOutputBuffer(remaining)
        refresh()
        val auto = autoOn
        measure(inputBuffer, inputBuffer.position(), inputBuffer.limit())
        if (!on && !auto) {
            out.put(inputBuffer)
            out.flip()
            return
        }

        val ch = channels
        val bytesPer = if (isFloat) 4 else 2
        val frameBytes = bytesPer * ch
        var pos = inputBuffer.position()
        val limit = inputBuffer.limit()
        val frames = remaining / frameBytes

        val fx = on
        val eqCount = if (fx) activeCount else 0
        val fxGain = if (fx) preamp * 10.0.pow(loudDb / 20.0) else 1.0
        var gTarget = fxGain * (if (auto) autoCur else 1.0)
        val bTarget = if (fx) bassAmt.toDouble() else 0.0
        val wTarget = if (fx) widthAmt.toDouble() * 0.8 else 0.0
        if (!primed) {
            gainCur = gTarget
            bassCur = bTarget
            widthCur = wTarget
            primed = true
        }
        val sm = smoothCoef
        val n = lookN

        for (f in 0 until frames) {
            var fp = 0.0
            // Ayarları yumuşakça hedefe götür
            if (auto && ++autoTick >= 2048) {
                autoTick = 0
                // Yeterince ses ölçüldüyse (yaklaşık 2 sn) kazancı hesapla
                if (meterCount > 2L * sampleRate && meterMs > 1e-9) {
                    val db = AUTO_TARGET_DB - 10.0 * log10(meterMs)
                    autoGoal = 10.0.pow(db.coerceIn(AUTO_MIN_DB, AUTO_MAX_DB) / 20.0)
                }
            }
            if (auto) {
                autoCur += (autoGoal - autoCur) * (if (autoGoal < autoCur) autoDown else autoUp)
                gTarget = fxGain * autoCur
            }
            gainCur += (gTarget - gainCur) * sm
            bassCur += (bTarget - bassCur) * sm
            widthCur += (wTarget - widthCur) * sm

            for (c in 0 until ch) {
                var x: Double
                if (isFloat) {
                    x = inputBuffer.getFloat(pos).toDouble()
                    pos += 4
                } else {
                    x = inputBuffer.getShort(pos).toDouble() / 32768.0
                    pos += 2
                }
                fp += x * x
                // Ekolayzır
                for (k in 0 until eqCount) {
                    val i = activeIdx[k]
                    val zi = i * MAX_CH + c
                    val y = b0[i] * x + z1[zi]
                    z1[zi] = b1[i] * x - a1[i] * y + z2[zi]
                    z2[zi] = b2[i] * x - a2[i] * y
                    x = y
                }
                // Harmonik bas: düşük frekansların üst harmoniklerini üretir,
                // küçük hoparlör ve kulaklıkta bas "hissedilir"
                if (bassCur > 0.0005) {
                    var h = x
                    for (s in 0 until 3) {
                        if (s == 1) h = tanh(BASS_DRIVE * h)
                        val si = s * MAX_CH + c
                        val y = sb0[s] * h + sz1[si]
                        sz1[si] = sb1[s] * h - sa1[s] * y + sz2[si]
                        sz2[si] = sb2[s] * h - sa2[s] * y
                        h = y
                    }
                    x += BASS_MIX * bassCur * h
                }
                xs[c] = x
                // Düşük bandı ayır (iki kademeli alçak geçiren filtre)
                var lo = x
                for (st in 0 until 2) {
                    val zi = (st * MAX_CH + c) * 2
                    val y = lb0 * lo + lz[zi]
                    lz[zi] = lb1 * lo - la1 * y + lz[zi + 1]
                    lz[zi + 1] = lb2 * lo - la2 * y
                    lo = y
                }
                lowv[c] = lo
            }

            // Bas koruyucu: sadece düşük bant çok yükselirse onu kısar, tizlere dokunmaz
            var lpk = 0.0
            for (c in 0 until ch) {
                val a = abs(lowv[c])
                if (a > lpk) lpk = a
            }
            val blT = if (lpk > BLIM_T) BLIM_T / lpk else 1.0
            blGain += (blT - blGain) * (if (blT < blGain) blAtk else blRel)
            if (blGain < 0.999) {
                val cut = 1.0 - blGain
                for (c in 0 until ch) xs[c] -= cut * lowv[c]
            }

            // Seviye ölçümü (çok sessiz anlar sayılmaz, böylece sessizlik sesi şişirmez)
            if (auto) {
                val ms = fp / ch
                if (ms > 1e-6) {
                    meterCount++
                    val k = if (meterCount * meterCoef < 1.0) 1.0 / meterCount else meterCoef
                    meterMs += (ms - meterMs) * k
                }
            }

            // Stereo genişlik (sadece 2 kanalda)
            if (ch == 2 && widthCur > 0.0005) {
                val m = (xs[0] + xs[1]) * 0.5
                val s = (xs[0] - xs[1]) * 0.5 * (1.0 + widthCur)
                xs[0] = m + s
                xs[1] = m - s
            }

            // Ses seviyesi
            var pk = 0.0
            for (c in 0 until ch) {
                xs[c] *= gainCur
                val a = abs(xs[c])
                if (a > pk) pk = a
            }

            // İleriye bakan sınırlayıcı: tepe gelmeden önce sesi yumuşakça kısar
            val gr = if (pk > CEIL) CEIL / pk else 1.0
            var wmin = gr
            for (i in 0 until n) {
                if (grBuf[i] < wmin) wmin = grBuf[i]
            }
            grBuf[limPos] = gr
            env += (wmin - env) * (if (wmin < env) atk else rel)
            val base = limPos * ch
            var rg = 1.0
            if (rampLeft > 0) {
                rg = 1.0 - rampLeft.toDouble() / rampTotal
                rampLeft--
            }
            for (c in 0 until ch) {
                var o = dly[base + c] * env * rg
                dly[base + c] = xs[c]
                if (o > 1.0) o = 1.0 else if (o < -1.0) o = -1.0
                if (isFloat) {
                    out.putFloat(o.toFloat())
                } else {
                    out.putShort((o * 32768.0).roundToInt().coerceIn(-32768, 32767).toShort())
                }
            }
            limPos++
            if (limPos >= n) limPos = 0
        }
        // Tam kare olmayan artık baytlar varsa olduğu gibi geçir
        if (pos < limit) {
            inputBuffer.position(pos)
            out.put(inputBuffer)
        }
        inputBuffer.position(limit)
        out.flip()
    }
}
