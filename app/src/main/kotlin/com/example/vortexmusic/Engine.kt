package io.github.vortexmusic.player

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.ln

// ================== DİNLEME SAYACI ==================

class ListenTracker(context: Context, private val player: Player) : Player.Listener {

    private val prefs = context.getSharedPreferences("vortex_stats", Context.MODE_PRIVATE)
    private val handler = Handler(Looper.getMainLooper())
    private var currentId = -1L
    private var startedAt = 0L
    private var sessionMs = 0L
    private var counted = false

    private val tick = object : Runnable {
        override fun run() {
            flush()
            if (player.isPlaying) begin()
            handler.postDelayed(this, 30000L)
        }
    }

    private fun idOf(item: MediaItem?): Long = item?.mediaId?.toLongOrNull() ?: -1L

    private fun begin() {
        startedAt = SystemClock.elapsedRealtime()
    }

    override fun onIsPlayingChanged(isPlaying: Boolean) {
        handler.removeCallbacks(tick)
        if (isPlaying) {
            currentId = idOf(player.currentMediaItem)
            begin()
            handler.postDelayed(tick, 30000L)
        } else {
            flush()
        }
    }

    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
        flush()
        currentId = idOf(mediaItem)
        sessionMs = 0L
        counted = false
        if (player.isPlaying) begin()
    }

    private fun flush() {
        if (startedAt == 0L || currentId < 0) {
            startedAt = 0L
            return
        }
        val delta = SystemClock.elapsedRealtime() - startedAt
        startedAt = 0L
        if (delta <= 0L || delta > 6L * 3600L * 1000L) return
        record(currentId, delta)
    }

    private fun record(id: Long, delta: Long) {
        val day = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date())
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        val e = prefs.edit()
        val songKey = "ds_${day}_$id"
        e.putLong(songKey, prefs.getLong(songKey, 0L) + delta)
        val hourKey = "h_$hour"
        e.putLong(hourKey, prefs.getLong(hourKey, 0L) + delta)
        e.putLong("l_$id", System.currentTimeMillis())
        sessionMs += delta
        if (!counted && sessionMs >= 20000L) {
            counted = true
            val countKey = "c_$id"
            e.putInt(countKey, prefs.getInt(countKey, 0) + 1)
        }
        e.apply()
    }

    fun release() {
        handler.removeCallbacks(tick)
        flush()
    }
}

// ================== SES EFEKTLERİ VE PROFİLLER ==================

class AudioFxController(
    context: Context,
    private val player: ExoPlayer,
    val eqProc: VortexEqProcessor
) : Player.Listener {

    class Preset(
        val name: String,
        val db: FloatArray,
        val bass: Int,
        val virt: Int,
        val loud: Int
    )

    // Profil: frekansa göre saklanır, böylece her telefonda uygulanabilir.
    class Profile(
        var name: String,
        var headphone: String,
        val freqs: IntArray,
        val levels: IntArray,
        val bass: Int,
        val virt: Int,
        val loud: Int
    )

    private class Snapshot(
        val enabled: Boolean,
        val bass: Int,
        val virt: Int,
        val loud: Int,
        val levels: List<Int>
    )

    // 10 bant: 31, 62, 125, 250, 500, 1k, 2k, 4k, 8k, 16k Hz (dB)
    // bass: harmonik bas 0-1000, virt: stereo genişlik 0-1000, loud: ses yükseltme (1/100 dB)
    val presets = listOf(
        Preset("Düz", floatArrayOf(0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f), 0, 0, 0),
        Preset("Doğal", floatArrayOf(0.5f, 0.5f, 0f, 0f, 0f, 0f, 0f, 0.5f, 1f, 1f), 0, 0, 0),
        Preset("Enstrümantal", floatArrayOf(2.5f, 2.5f, 0.5f, -1f, -0.5f, 0.5f, 1.5f, 2f, 1.5f, 1f), 100, 200, 150),
        Preset("Klasik", floatArrayOf(1f, 1f, 0.5f, 0f, 0f, 0f, 0.5f, 1f, 1.5f, 1.5f), 0, 150, 100),
        Preset("Derin Bas", floatArrayOf(6f, 5.5f, 2.5f, 0.5f, 0f, 0f, 0f, 0f, 0f, 0f), 450, 0, 100),
        Preset("Berrak Ses", floatArrayOf(-1.5f, -1f, -0.5f, 0f, 0f, 0.5f, 1.5f, 2.5f, 3f, 2.5f), 0, 100, 100),
        Preset("Vokal", floatArrayOf(-2f, -1.5f, -0.5f, 0.5f, 1.5f, 2.5f, 2.5f, 1.5f, 0f, -0.5f), 0, 0, 100),
        Preset("Gece", floatArrayOf(1f, 1f, 0.5f, 0.5f, 0f, 0f, 0f, -1f, -1.5f, -2f), 0, 0, 0)
    )

    private val prefs = context.getSharedPreferences("vortex_audio", Context.MODE_PRIVATE)

    var enabled: Boolean = prefs.getBoolean("enabled", false)
    // Saf mod: tüm efektleri atlar (ayarlar silinmez). Ses dosyadaki gibi çıkar.
    var pure: Boolean = prefs.getBoolean("pure", false)
    // Otomatik ses dengeleme: şarkılar arası ses farkını kendiliğinden azaltır
    var autoLevel: Boolean = prefs.getBoolean("autolevel", true)
    // Animasyon senkron ince ayarı (milisaniye, -100..+300). Çıkış cihazına göre taban gecikmeye eklenir.
    var syncAdjust: Int = prefs.getInt("syncadj", 0)
    // Animasyon senkronu kapalıysa görseller gecikmesiz (canlı) okunur
    var syncOff: Boolean = prefs.getBoolean("syncoff", false)
    // Kulaklık düzeltme: 0 kapalı, 1 kulak içi, 2 kulak üstü, 3 boğuk
    var correction: Int = prefs.getInt("corr", 0)
    // Son seçilen hazır ayar: üzerinde ince ayar yapınca da seçili görünür
    var lastPreset: String = prefs.getString("lastpreset", "") ?: ""
    var bassStrength: Int = prefs.getInt("bass", 0)
    var virtStrength: Int = prefs.getInt("virt", 0)
    var loudnessMb: Int = prefs.getInt("loud", 0)

    // Vortex'in kendi ekolayzırı: 10 bant, -12 ile +12 dB (değerler 1/100 dB cinsinden)
    var bandCount = VortexEqProcessor.BANDS
    var minLevel = -1200
    var maxLevel = 1200
    val bandFreqs = mutableListOf<Int>()
    val levels = mutableListOf<Int>()

    val profiles = mutableListOf<Profile>()

    // Cihaza göre otomatik EQ
    var autoEnabled: Boolean = prefs.getBoolean("auto", true)
    var currentOutput: String = ""
    var autoProfileName: String = ""
    private var snapshot: Snapshot? = null

    val eqAvailable: Boolean get() = true
    // Bas, ses yükseltme ve genişlik Vortex'in kendi işlemcisinde çalışır, her telefonda vardır
    val bassAvailable: Boolean get() = true
    val loudAvailable: Boolean get() = true
    val virtAvailable: Boolean get() = true

    init {
        for (f in VortexEqProcessor.FREQS) bandFreqs.add(f)
        loadLevels()
        loadProfiles()
        pushEq()
    }

    override fun onAudioSessionIdChanged(audioSessionId: Int) {
        attach(audioSessionId)
    }

    // Eski telefon efektleri kaldırıldı, ses artık kendi işlemcimizden geçer
    fun attach(sessionId: Int) {
        applyAll()
    }

    private fun loadLevels() {
        levels.clear()
        val saved = prefs.getString("levels10", "") ?: ""
        val parsed = if (saved.isEmpty()) emptyList() else saved.split(",").mapNotNull { it.toIntOrNull() }
        for (i in 0 until bandCount) {
            levels.add(parsed.getOrElse(i) { 0 })
        }
    }

    // Kendi ses işlemcimize güncel değerleri gönderir
    private fun pushEq() {
        val g = DoubleArray(VortexEqProcessor.BANDS) { i ->
            levels.getOrElse(i) { 0 }.coerceIn(minLevel, maxLevel) / 100.0
        }
        eqProc.setParams(
            g,
            enabled && !pure,
            bassStrength.coerceIn(0, 1000) / 1000f,
            loudnessMb.coerceIn(0, 1000) / 100f,
            virtStrength.coerceIn(0, 1000) / 1000f,
            autoLevel && !pure,
            correction
        )
    }

    fun applyAll() {
        pushEq()
        applySync()
    }

    fun setBand(index: Int, level: Int) {
        if (index !in levels.indices) return
        levels[index] = level.coerceIn(minLevel, maxLevel)
        pushEq()
    }

    fun presetLevels(p: Preset): List<Int> =
        List(bandCount) { i ->
            Math.round(p.db.getOrElse(i) { 0f } * 100f).coerceIn(minLevel, maxLevel)
        }

    fun setPreset(p: Preset) {
        lastPreset = p.name
        val lv = presetLevels(p)
        for (i in 0 until bandCount) {
            if (i in levels.indices) levels[i] = lv.getOrElse(i) { 0 }
        }
        bassStrength = p.bass
        virtStrength = p.virt
        loudnessMb = p.loud
        enabled = true
        applyAll()
        save()
    }

    fun matches(p: Preset): Boolean {
        val lv = presetLevels(p)
        if (bassStrength != p.bass || virtStrength != p.virt || loudnessMb != p.loud) return false
        for (i in 0 until bandCount) {
            if (levels.getOrElse(i) { 0 } != lv.getOrElse(i) { 0 }) return false
        }
        return true
    }

    fun save() {
        prefs.edit()
            .putBoolean("enabled", enabled)
            .putBoolean("pure", pure)
            .putBoolean("autolevel", autoLevel)
            .putInt("syncadj", syncAdjust)
            .putBoolean("syncoff", syncOff)
            .putInt("corr", correction)
            .putString("lastpreset", lastPreset)
            .putInt("bass", bassStrength)
            .putInt("virt", virtStrength)
            .putInt("loud", loudnessMb)
            .putString("levels10", levels.joinToString(","))
            .apply()
    }

    // ---------- Profiller ----------

    private fun interpolate(freqs: IntArray, lv: IntArray, f: Int): Int {
        if (freqs.isEmpty()) return 0
        if (freqs.size == 1 || f <= freqs[0]) return lv[0]
        if (f >= freqs[freqs.size - 1]) return lv[lv.size - 1]
        for (i in 0 until freqs.size - 1) {
            if (f >= freqs[i] && f <= freqs[i + 1]) {
                val f0 = ln(freqs[i].toDouble())
                val f1 = ln(freqs[i + 1].toDouble())
                if (f1 - f0 < 1e-9) return lv[i]
                val t = (ln(f.toDouble()) - f0) / (f1 - f0)
                return (lv[i] + (lv[i + 1] - lv[i]) * t).toInt()
            }
        }
        return lv[lv.size - 1]
    }

    fun profileLevels(p: Profile): List<Int> =
        List(bandCount) { i ->
            interpolate(p.freqs, p.levels, bandFreqs.getOrElse(i) { 0 }).coerceIn(minLevel, maxLevel)
        }

    private fun applyProfileValues(p: Profile) {
        val lv = profileLevels(p)
        for (i in 0 until bandCount) {
            if (i in levels.indices) levels[i] = lv.getOrElse(i) { 0 }
        }
        bassStrength = p.bass
        virtStrength = p.virt
        loudnessMb = p.loud
        enabled = true
        applyAll()
    }

    fun applyProfile(p: Profile) {
        applyProfileValues(p)
        save()
    }

    fun matchesProfile(p: Profile): Boolean {
        if (bandCount == 0) return false
        if (bassStrength != p.bass || virtStrength != p.virt || loudnessMb != p.loud) return false
        val lv = profileLevels(p)
        for (i in 0 until bandCount) {
            if (levels.getOrElse(i) { 0 } != lv.getOrElse(i) { 0 }) return false
        }
        return true
    }

    fun saveCurrentAsProfile(name: String, headphone: String): Boolean {
        if (bandCount == 0 || bandFreqs.size < bandCount) return false
        val cleanName = name.trim().take(60)
        if (cleanName.isEmpty()) return false
        val fr = IntArray(bandCount) { bandFreqs[it] }
        val lv = IntArray(bandCount) { levels.getOrElse(it) { 0 } }
        val p = Profile(cleanName, headphone.trim().take(60), fr, lv, bassStrength, virtStrength, loudnessMb)
        val idx = profiles.indexOfFirst { it.name == cleanName }
        if (idx >= 0) profiles[idx] = p else profiles.add(p)
        saveProfiles()
        return true
    }

    fun deleteProfile(name: String) {
        profiles.removeAll { it.name == name }
        saveProfiles()
    }

    private fun uniqueName(base: String): String {
        if (profiles.none { it.name == base }) return base
        var n = 2
        while (profiles.any { it.name == "$base ($n)" }) n++
        return "$base ($n)"
    }

    private fun profileToJson(p: Profile): JSONObject {
        val o = JSONObject()
        o.put("name", p.name)
        o.put("headphone", p.headphone)
        o.put("bass", p.bass)
        o.put("virt", p.virt)
        o.put("loud", p.loud)
        val pts = JSONArray()
        for (i in p.freqs.indices) {
            val pair = JSONArray()
            pair.put(p.freqs[i])
            pair.put(p.levels[i])
            pts.put(pair)
        }
        o.put("points", pts)
        return o
    }

    private fun profileFromJson(o: JSONObject): Profile? {
        val name = o.optString("name", "").trim().take(60)
        if (name.isEmpty()) return null
        val headphone = o.optString("headphone", "").trim().take(60)
        val arr = o.optJSONArray("points") ?: return null
        if (arr.length() == 0 || arr.length() > 64) return null
        val list = ArrayList<IntArray>()
        for (i in 0 until arr.length()) {
            val pair = arr.optJSONArray(i) ?: return null
            if (pair.length() != 2) return null
            val f = pair.optInt(0, -1)
            val l = pair.optInt(1, 0)
            if (f < 10 || f > 40000) return null
            list.add(intArrayOf(f, l.coerceIn(-2000, 2000)))
        }
        list.sortBy { it[0] }
        return Profile(
            name,
            headphone,
            IntArray(list.size) { list[it][0] },
            IntArray(list.size) { list[it][1] },
            o.optInt("bass", 0).coerceIn(0, 1000),
            o.optInt("virt", 0).coerceIn(0, 1000),
            o.optInt("loud", 0).coerceIn(0, 1000)
        )
    }

    private fun saveProfiles() {
        val arr = JSONArray()
        for (p in profiles) arr.put(profileToJson(p))
        prefs.edit().putString("profiles", arr.toString()).apply()
    }

    private fun loadProfiles() {
        profiles.clear()
        val text = prefs.getString("profiles", "") ?: ""
        if (text.isEmpty()) return
        try {
            val arr = JSONArray(text)
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val p = profileFromJson(o) ?: continue
                profiles.add(p)
            }
        } catch (ex: Exception) {
        }
    }

    fun exportJson(): String {
        val root = JSONObject()
        root.put("app", "Vortex")
        root.put("version", 1)
        val arr = JSONArray()
        for (p in profiles) arr.put(profileToJson(p))
        root.put("profiles", arr)
        return root.toString(2)
    }

    // Başarılı ise içe aktarılan profil sayısını, hatalıysa -1 döndürür.
    fun importJson(text: String): Int {
        return try {
            val root = JSONObject(text)
            val arr = root.optJSONArray("profiles") ?: return -1
            if (arr.length() > 100) return -1
            var count = 0
            for (i in 0 until arr.length()) {
                if (profiles.size >= 200) break
                val o = arr.optJSONObject(i) ?: continue
                val p = profileFromJson(o) ?: continue
                p.name = uniqueName(p.name)
                profiles.add(p)
                count++
            }
            if (count > 0) saveProfiles()
            count
        } catch (ex: Exception) {
            -1
        }
    }

    // ---------- Cihaza göre otomatik EQ ----------

    private fun deviceMatches(profileHeadphone: String, deviceName: String): Boolean {
        val a = SongSearch.normalize(profileHeadphone).trim()
        val b = SongSearch.normalize(deviceName).trim()
        if (a.isEmpty() || b.isEmpty()) return false
        return b.contains(a) || a.contains(b)
    }

    private fun restoreSnapshot() {
        val s = snapshot
        if (s != null) {
            enabled = s.enabled
            bassStrength = s.bass
            virtStrength = s.virt
            loudnessMb = s.loud
            for (i in 0 until bandCount) {
                if (i in levels.indices) levels[i] = s.levels.getOrElse(i) { 0 }
            }
            applyAll()
        }
        snapshot = null
        autoProfileName = ""
    }

    // Çıkış cihazı değişince çağrılır. Cihaz yoksa name boştur.
    // Tahmini taban gecikme: hoparlör, kablolu, Bluetooth. Üstüne kullanıcı ince ayarı eklenir.
    fun applySync() {
        val base = when {
            currentOutput.isEmpty() -> 110L
            currentOutput == WIRED_LABEL -> 90L
            else -> 280L
        }
        eqProc.syncDelayMs = if (syncOff) 0L else (base + syncAdjust).coerceIn(0L, 600L)
    }

    fun onOutputChanged(name: String) {
        currentOutput = name
        // Bluetooth çıkışta sabit ses payı uygulanır (kablolu ve hoparlörde uygulanmaz)
        Headroom.bluetooth = name.isNotEmpty() && name != WIRED_LABEL
        applySync()
        if (!autoEnabled) return
        val match = if (name.isEmpty()) null else profiles.firstOrNull { deviceMatches(it.headphone, name) }
        if (match != null) {
            if (autoProfileName == match.name) return
            if (snapshot == null) {
                snapshot = Snapshot(enabled, bassStrength, virtStrength, loudnessMb, levels.toList())
            }
            applyProfileValues(match)
            autoProfileName = match.name
        } else if (autoProfileName.isNotEmpty()) {
            restoreSnapshot()
        }
    }

    fun setAuto(on: Boolean) {
        autoEnabled = on
        prefs.edit().putBoolean("auto", on).apply()
        if (on) {
            onOutputChanged(currentOutput)
        } else if (autoProfileName.isNotEmpty()) {
            restoreSnapshot()
        }
    }

    fun release() {
    }

    companion object {
        const val WIRED_LABEL = "Kablolu kulaklık"
    }
}

// ================== ÇIKIŞ CİHAZI İZLEYİCİ ==================

class OutputWatcher(context: Context, private val fx: AudioFxController) {

    private val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val handler = Handler(Looper.getMainLooper())
    private var started = false

    private val callback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) {
            evaluate()
        }

        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) {
            evaluate()
        }
    }

    fun start() {
        if (started) return
        started = true
        am.registerAudioDeviceCallback(callback, handler)
        evaluate()
    }

    fun stop() {
        if (!started) return
        started = false
        try {
            am.unregisterAudioDeviceCallback(callback)
        } catch (e: Exception) {
        }
    }

    fun evaluate() {
        try {
            fx.onOutputChanged(currentName())
        } catch (e: Exception) {
        }
    }

    private fun nameOf(d: AudioDeviceInfo): String {
        val n = d.productName?.toString()?.trim() ?: ""
        return if (n.isEmpty()) "Bluetooth" else n
    }

    private fun currentName(): String {
        val devices = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        var wired = false
        for (d in devices) {
            val t = d.type
            if (t == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP) return nameOf(d)
            if (Build.VERSION.SDK_INT >= 31 && t == AudioDeviceInfo.TYPE_BLE_HEADSET) return nameOf(d)
            if (t == AudioDeviceInfo.TYPE_WIRED_HEADPHONES ||
                t == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
                t == AudioDeviceInfo.TYPE_USB_HEADSET
            ) {
                wired = true
            }
        }
        return if (wired) AudioFxController.WIRED_LABEL else ""
    }
}

// ================== ARKA PLAN ÇALMA SERVİSİ ==================

class PlaybackService : MediaSessionService() {

    private var session: MediaSession? = null
    private var exoPlayer: ExoPlayer? = null
    private var tracker: ListenTracker? = null
    private var fx: AudioFxController? = null
    private var watcher: OutputWatcher? = null
    private var eqProcessor: VortexEqProcessor? = null

    inner class LocalBinder : Binder() {
        fun getPlayer(): ExoPlayer? = exoPlayer
        fun getFx(): AudioFxController? = fx
    }

    override fun onCreate() {
        super.onCreate()
        val eqProc = VortexEqProcessor()
        eqProcessor = eqProc
        // TEST: Araçlar > Ses çıkışı testi anahtarı açıksa işlemci hiç hatta takılmaz
        val noChain = getSharedPreferences("vortex_audio", Context.MODE_PRIVATE)
            .getBoolean(NOCHAIN_KEY, false)
        noChainActive = noChain
        Headroom.load(this)
        val testGain = HeadroomProcessor()
        // Ses hattına kendi ekolayzırımızı ekleyen ses çıkışı
        val renderers = object : DefaultRenderersFactory(this) {
            override fun buildAudioSink(
                context: Context,
                enableFloatOutput: Boolean,
                enableAudioTrackPlaybackParams: Boolean
            ): AudioSink? {
                return DefaultAudioSink.Builder(context)
                    .setEnableFloatOutput(enableFloatOutput)
                    .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
                    .setAudioProcessors(
                        if (noChain) arrayOf<AudioProcessor>(testGain)
                        else arrayOf<AudioProcessor>(eqProc, testGain)
                    )
                    .build()
            }
        }
        val exo = ExoPlayer.Builder(this, renderers)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                true
            )
            .setHandleAudioBecomingNoisy(true)
            .build()
        exoPlayer = exo
        // Ses çıkışı testi ekranı için çalan dosyanın biçimi
        formatInfo = {
            val f = exo.audioFormat
            if (f == null) {
                ""
            } else {
                val rate = if (f.sampleRate > 0) "${f.sampleRate} Hz" else "? Hz"
                val kbps = if (f.averageBitrate > 0) " · ${f.averageBitrate / 1000} kbps" else ""
                "${f.sampleMimeType ?: "?"} · $rate · ${f.channelCount} ch$kbps"
            }
        }
        val t = ListenTracker(this, exo)
        tracker = t
        exo.addListener(t)
        val controller = AudioFxController(this, exo, eqProc)
        fx = controller
        exo.addListener(controller)
        controller.attach(exo.audioSessionId)

        val w = OutputWatcher(this, controller)
        watcher = w
        w.start()
        exo.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (isPlaying) w.evaluate()
            }
        })

        // Bildirime (ve kilit ekranı kartına) dokununca uygulamayı aç
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val newSession = MediaSession.Builder(this, exo)
            .setSessionActivity(openApp)
            .build()
        session = newSession
        addSession(newSession)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onBind(intent: Intent?): IBinder? {
        return if (intent?.action == ACTION_LOCAL) LocalBinder() else super.onBind(intent)
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        val p = exoPlayer
        if (p == null || !p.playWhenReady || p.mediaItemCount == 0) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        watcher?.stop()
        watcher = null
        tracker?.let {
            exoPlayer?.removeListener(it)
            it.release()
        }
        tracker = null
        fx?.let {
            exoPlayer?.removeListener(it)
            it.release()
        }
        fx = null
        formatInfo = null
        eqProcessor = null
        session?.release()
        exoPlayer?.release()
        session = null
        exoPlayer = null
        super.onDestroy()
    }

    companion object {
        const val ACTION_LOCAL = "com.example.vortexmusic.LOCAL"
        const val NOCHAIN_KEY = "nochain"

        // Test ekranı için: çalan dosyanın biçimi ve işlemcinin hatta olup olmadığı
        @Volatile var formatInfo: (() -> String)? = null
        @Volatile var noChainActive: Boolean = false
    }
}