package io.github.vortexmusic.player

import android.app.AlertDialog
import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.widget.ScrollView
import android.widget.TextView
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import java.util.Locale
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.roundToInt

// Tepe ölçer (test): sinyalin ses payından ÖNCEKİ en yüksek seviyesini ve tam ölçeğe yakın örnek sayısını tutar.
// Şarkı değişince ya da sarılınca sıfırlanır.
object PeakMeter {
    @Volatile var peak: Double = 0.0
    @Volatile var near: Long = 0L
    @Volatile var total: Long = 0L

    fun reset() {
        peak = 0.0
        near = 0L
        total = 0L
    }
}

// Ses payı (headroom): Bluetooth çıkışta sinyali sabit -5 dB kısar. Bagetler gibi ince detaylar
// ağır basın altında ezilmesin diye (kulakla seçildi). Kablolu kulaklık ve hoparlörde 0 dB.
// Saf moddan bağımsızdır. 0 dB'de ses hiç değişmez (ham kopya).
object Headroom {
    const val BLUETOOTH_DB = -2

    // Çıkış cihazı değişince ses motoru günceller
    @Volatile var bluetooth: Boolean = false

    // Sadece test için: Ses çıkışı testi ekranından kapatılabilir
    @Volatile var off: Boolean = false

    val db: Int get() = if (bluetooth && !off) BLUETOOTH_DB else 0
    val gain: Double get() = if (db == 0) 1.0 else 10.0.pow(db / 20.0)

    fun load(context: Context) {
        off = context.getSharedPreferences("vortex_audio", Context.MODE_PRIVATE)
            .getBoolean("headroom_off", false)
    }

    fun setOff(context: Context, v: Boolean) {
        off = v
        context.getSharedPreferences("vortex_audio", Context.MODE_PRIVATE)
            .edit().putBoolean("headroom_off", v).apply()
    }
}

// TEST ARACI (ses çıkışı testi): çalan dosyanın biçimini ve cihaz çıkışını gösterir.
// Anahtar: Vortex ses işlemcisini ses hattından tamamen çıkarır (sadece test içindir).
object AudioTestTool {

    fun install(host: ToolHost) {
    }

    // Geliştirici aracı: gizli. Ayarlar başlığına 7 kez dokununca açılır.
    private const val DEV_KEY = "devtools"

    fun unlocked(context: Context): Boolean =
        context.getSharedPreferences("vortex_audio", Context.MODE_PRIVATE).getBoolean(DEV_KEY, false)

    fun unlock(context: Context) {
        context.getSharedPreferences("vortex_audio", Context.MODE_PRIVATE).edit().putBoolean(DEV_KEY, true).apply()
    }

    fun entries(host: ToolHost): List<ToolEntry> = if (!unlocked(host.activity)) emptyList() else listOf(
        ToolEntry(
            tx("Ses çıkışı testi", "Audio output test"),
            tx(
                "Çalan dosyanın biçimi, cihaz çıkışı ve test anahtarı",
                "Playing file format, device output and a test switch"
            ),
            IconDrawable.EQ
        ) { open(host) }
    )

    private fun typeName(type: Int): String = when (type) {
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "Bluetooth (A2DP)"
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "Bluetooth (SCO)"
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> tx("Kablolu kulaklık", "Wired headphones")
        AudioDeviceInfo.TYPE_WIRED_HEADSET -> tx("Kablolu kulaklık", "Wired headset")
        AudioDeviceInfo.TYPE_USB_DEVICE -> "USB"
        AudioDeviceInfo.TYPE_USB_HEADSET -> "USB"
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> tx("Hoparlör", "Speaker")
        else -> tx("Diğer", "Other") + " ($type)"
    }

    private fun onOff(v: Boolean): String = if (v) tx("AÇIK", "ON") else tx("KAPALI", "OFF")

    private fun report(context: Context): String {
        val prefs = context.getSharedPreferences("vortex_audio", Context.MODE_PRIVATE)
        val sb = StringBuilder()

        val fmt = PlaybackService.formatInfo?.invoke().orEmpty()
        sb.append(tx("Çalan dosya:", "Playing file:")).append('\n')
        sb.append(
            if (fmt.isEmpty()) tx("(şarkı çalmıyor, önce bir şarkı çal)", "(nothing playing, play a song first)")
            else fmt
        ).append("\n\n")

        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val rate = am.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE) ?: "?"
        val frames = am.getProperty(AudioManager.PROPERTY_OUTPUT_FRAMES_PER_BUFFER) ?: "?"
        sb.append(tx("Cihazın ana çıkış hızı:", "Device main output rate:"))
            .append(" $rate Hz\n")
        sb.append(tx("Cihaz tampon boyu:", "Device buffer size:"))
            .append(" $frames ").append(tx("kare", "frames")).append("\n\n")

        sb.append(tx("Bağlı çıkışlar:", "Connected outputs:")).append('\n')
        var any = false
        try {
            for (d in am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) {
                if (d.type == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE) continue
                any = true
                sb.append("• ").append(typeName(d.type))
                val name = d.productName?.toString().orEmpty()
                if (name.isNotEmpty()) sb.append(" – ").append(name)
                sb.append('\n')
            }
        } catch (e: Exception) {
        }
        if (!any) sb.append("• ?\n")
        sb.append('\n')

        sb.append(tx("Saf mod:", "Pure mode:")).append(' ')
            .append(onOff(prefs.getBoolean("pure", false))).append('\n')
        sb.append(tx("Efektler:", "Effects:")).append(' ')
            .append(onOff(prefs.getBoolean("enabled", false))).append('\n')
        sb.append(tx("Otomatik seviye:", "Auto level:")).append(' ')
            .append(onOff(prefs.getBoolean("autolevel", true))).append('\n')
        sb.append(tx("Ses payı:", "Headroom:")).append(' ')
            .append(
                if (Headroom.db == 0) {
                    if (Headroom.off) tx("Kapalı (test)", "Off (test)") else tx("0 dB (Bluetooth değil)", "0 dB (not Bluetooth)")
                } else "${Headroom.db} dB (Bluetooth)"
            ).append("\n\n")

        val pk = PeakMeter.peak
        val tot = PeakMeter.total
        sb.append(tx("Tepe seviyesi:", "Peak level:")).append(' ')
            .append(
                if (pk <= 0.0) "—"
                else String.format(Locale.US, "%.1f dBFS", 20.0 * log10(pk))
            ).append('\n')
        sb.append(tx("Tam ölçeğe yakın örnek (≥ -0,1 dB):", "Near full-scale samples (>= -0.1 dB):")).append(' ')
            .append(
                if (tot <= 0L) "—"
                else String.format(Locale.US, "%d (%.3f%%)", PeakMeter.near, 100.0 * PeakMeter.near / tot)
            ).append('\n')
        sb.append(
            tx(
                "Ölçüm, ses payından önce alınır. Şarkı değişince ya da ileri-geri sarınca sıfırlanır.",
                "Measured before the headroom. Resets when the song changes or you seek."
            )
        ).append("\n\n")

        sb.append(tx("İşlemci ses hattında:", "Processor in the audio path:")).append(' ')
            .append(if (PlaybackService.noChainActive) tx("HAYIR (test)", "NO (test)") else tx("EVET", "YES"))
            .append("\n\n")
        sb.append(
            tx(
                "Dosya hızı ile cihaz hızı farklıysa Android yeniden örnekler. İşlemci hattan çıkarılınca EQ, efektler ve spektrum çalışmaz; sadece test içindir.",
                "If the file rate differs from the device rate, Android resamples. With the processor removed, EQ, effects and the spectrum do not work; for testing only."
            )
        )
        return sb.toString()
    }

    private fun open(host: ToolHost) {
        val activity = host.activity
        val prefs = activity.getSharedPreferences("vortex_audio", Context.MODE_PRIVATE)
        val chainOff = prefs.getBoolean(PlaybackService.NOCHAIN_KEY, false)

        val pad = (18 * activity.resources.displayMetrics.density).toInt()
        val text = TextView(activity)
        text.text = report(activity)
        text.textSize = 14f
        text.setPadding(pad, pad, pad, pad)
        text.setTextIsSelectable(true)
        val scroll = ScrollView(activity)
        scroll.addView(text)

        val toggleLabel = if (chainOff) {
            tx("İşlemciyi hatta geri tak", "Put the processor back")
        } else {
            tx("İşlemciyi hattan çıkar (test)", "Remove processor (test)")
        }

        val handler = Handler(Looper.getMainLooper())
        val refresher = object : Runnable {
            override fun run() {
                text.text = report(activity)
                handler.postDelayed(this, 700)
            }
        }

        val dialog = AlertDialog.Builder(activity)
            .setTitle(tx("Ses çıkışı testi", "Audio output test"))
            .setView(scroll)
            .setPositiveButton(toggleLabel) { _, _ ->
                prefs.edit().putBoolean(PlaybackService.NOCHAIN_KEY, !chainOff).apply()
                host.restartService()
                host.ui.toast(
                    tx(
                        "Ses servisi yeniden başlatıldı. Şarkıyı tekrar çal.",
                        "Audio service restarted. Play the song again."
                    )
                )
            }
            .setNeutralButton(
                if (Headroom.off) tx("Ses payını aç", "Turn headroom on") else tx("Ses payını kapat (test)", "Turn headroom off (test)")
            ) { _, _ ->
                Headroom.setOff(activity, !Headroom.off)
                host.ui.toast(
                    if (Headroom.off) tx("Ses payı kapatıldı (test)", "Headroom turned off (test)")
                    else tx("Ses payı açıldı", "Headroom turned on")
                )
            }
            .setNegativeButton(tx("Kapat", "Close"), null)
            .show()
        dialog.setOnDismissListener { handler.removeCallbacks(refresher) }
        handler.postDelayed(refresher, 700)
    }
}

// Ses payı uygulayıcısı: sadece 0 dB dışında bir değer seçilince sesi değiştirir (0 dB'de ham kopya).
@UnstableApi
class HeadroomProcessor : BaseAudioProcessor() {

    private var isFloat = false

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        val enc = inputAudioFormat.encoding
        if (enc != C.ENCODING_PCM_16BIT && enc != C.ENCODING_PCM_FLOAT) {
            return AudioProcessor.AudioFormat.NOT_SET
        }
        isFloat = enc == C.ENCODING_PCM_FLOAT
        return inputAudioFormat
    }

    override fun onFlush() {
        PeakMeter.reset()
    }

    // Tepe ve tam ölçeğe yakın örnek sayısı (sadece okur, sesi değiştirmez)
    private fun measure(inputBuffer: ByteBuffer) {
        var p = inputBuffer.position()
        val lim = inputBuffer.limit()
        var pk = PeakMeter.peak
        var near = PeakMeter.near
        var n = 0L
        if (isFloat) {
            while (p + 4 <= lim) {
                val a = abs(inputBuffer.getFloat(p).toDouble())
                if (a > pk) pk = a
                if (a >= 0.9886) near++
                n++
                p += 4
            }
        } else {
            while (p + 2 <= lim) {
                val a = abs(inputBuffer.getShort(p).toInt()) / 32768.0
                if (a > pk) pk = a
                if (a >= 0.9886) near++
                n++
                p += 2
            }
        }
        PeakMeter.peak = pk
        PeakMeter.near = near
        PeakMeter.total = PeakMeter.total + n
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val remaining = inputBuffer.remaining()
        if (remaining == 0) return
        measure(inputBuffer)
        val out = replaceOutputBuffer(remaining)
        val g = Headroom.gain
        if (g == 1.0) {
            out.put(inputBuffer)
        } else {
            var pos = inputBuffer.position()
            val limit = inputBuffer.limit()
            if (isFloat) {
                while (pos + 4 <= limit) {
                    out.putFloat((inputBuffer.getFloat(pos) * g).toFloat())
                    pos += 4
                }
            } else {
                while (pos + 2 <= limit) {
                    val v = (inputBuffer.getShort(pos) * g).roundToInt().coerceIn(-32768, 32767)
                    out.putShort(v.toShort())
                    pos += 2
                }
            }
            inputBuffer.position(limit)
        }
        out.flip()
    }
}
