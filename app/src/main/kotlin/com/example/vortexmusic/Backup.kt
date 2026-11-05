package io.github.vortexmusic.player

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object BackupTool {

    private const val REQ_EXPORT = 201
    private const val REQ_IMPORT = 202
    private const val MAX_FILE = 30000000
    private const val MAX_ENTRIES = 50000
    private const val MAX_ART_BYTES = 12000000L

    private val names = arrayOf(
        "vortex", "vortex_audio", "vortex_stats", "vortex_theme",
        "vortex_list", "vortex_session", "vortex_art"
    )

    private class Entry(val key: String, val type: String, val value: Any)

    private class Parsed(
        val prefs: Map<String, List<Entry>>,
        val hasLyrics: Boolean,
        val lyrics: Map<String, String>,
        val hasArt: Boolean,
        val art: Map<String, ByteArray>
    )

    fun install(host: ToolHost) {
        host.resultHandlers.add { req, res, data -> handle(host, req, res, data) }
    }

    fun entries(host: ToolHost): List<ToolEntry> = listOf(
        ToolEntry(
            "Yedekle",
            "Beğeniler, klasörler, sözler, kapaklar, sıralamalar, ses ve görünüm ayarları",
            IconDrawable.DOWNLOAD
        ) { export(host) },
        ToolEntry(
            "Geri yükle",
            "Bir Vortex yedek dosyasından verilerini geri getir (aynı telefonda)",
            IconDrawable.REPEAT
        ) { import(host) }
    )

    @Suppress("DEPRECATION")
    private fun export(host: ToolHost) {
        val stamp = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date())
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType("application/json")
            .putExtra(Intent.EXTRA_TITLE, "vortex-yedek-$stamp.json")
        host.activity.startActivityForResult(intent, REQ_EXPORT)
    }

    @Suppress("DEPRECATION")
    private fun import(host: ToolHost) {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType("*/*")
        host.activity.startActivityForResult(intent, REQ_IMPORT)
    }

    private fun handle(host: ToolHost, req: Int, res: Int, data: Intent?): Boolean {
        if (req != REQ_EXPORT && req != REQ_IMPORT) return false
        if (res != Activity.RESULT_OK) return true
        val uri = data?.data ?: return true
        if (req == REQ_EXPORT) writeBackup(host, uri) else readBackup(host, uri)
        return true
    }

    private fun collectLyrics(ctx: Context): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        val files = File(ctx.filesDir, "lyrics").listFiles() ?: return out
        for (f in files) {
            if (!f.name.endsWith(".txt")) continue
            val id = f.name.removeSuffix(".txt")
            if (id.toLongOrNull() == null) continue
            try {
                out[id] = f.readText(Charsets.UTF_8)
            } catch (e: Exception) {
            }
        }
        return out
    }

    // Kapaklar çok yer tutarsa null döner (yedeğe dahil edilmez)
    private fun collectArt(ctx: Context): Map<String, ByteArray>? {
        val sp = ctx.getSharedPreferences("vortex_art", Context.MODE_PRIVATE)
        val dir = File(ctx.filesDir, "albumart")
        val out = LinkedHashMap<String, ByteArray>()
        var total = 0L
        for ((_, v) in sp.all) {
            val name = v as? String ?: continue
            val f = File(dir, name)
            if (!f.exists()) continue
            val b = f.readBytes()
            total += b.size
            if (total > MAX_ART_BYTES) return null
            out[name] = b
        }
        return out
    }

    private fun build(ctx: Context, art: Map<String, ByteArray>?): String {
        val root = JSONObject()
        root.put("app", "Vortex")
        root.put("kind", "backup")
        root.put("version", 2)
        root.put("created", System.currentTimeMillis())
        val prefsObj = JSONObject()
        for (name in names) {
            if (name == "vortex_art" && art == null) continue
            val sp = ctx.getSharedPreferences(name, Context.MODE_PRIVATE)
            val o = JSONObject()
            for ((k, v) in sp.all) {
                val e = JSONObject()
                when (v) {
                    is Boolean -> {
                        e.put("t", "b")
                        e.put("v", v)
                    }
                    is Int -> {
                        e.put("t", "i")
                        e.put("v", v)
                    }
                    is Long -> {
                        e.put("t", "l")
                        e.put("v", v)
                    }
                    is Float -> {
                        e.put("t", "f")
                        e.put("v", v.toDouble())
                    }
                    is String -> {
                        e.put("t", "s")
                        e.put("v", v)
                    }
                    is Set<*> -> {
                        val a = JSONArray()
                        for (x in v) if (x is String) a.put(x)
                        e.put("t", "ss")
                        e.put("v", a)
                    }
                    else -> continue
                }
                o.put(k, e)
            }
            prefsObj.put(name, o)
        }
        root.put("prefs", prefsObj)

        val lyricsObj = JSONObject()
        for ((id, text) in collectLyrics(ctx)) lyricsObj.put(id, text)
        root.put("lyrics", lyricsObj)

        if (art != null) {
            val artObj = JSONObject()
            for ((name, bytes) in art) artObj.put(name, Base64.encodeToString(bytes, Base64.NO_WRAP))
            root.put("art", artObj)
        }
        return root.toString()
    }

    private fun writeBackup(host: ToolHost, uri: Uri) {
        try {
            val art = collectArt(host.activity)
            val json = build(host.activity, art)
            host.activity.contentResolver.openOutputStream(uri)?.use {
                it.write(json.toByteArray(Charsets.UTF_8))
            }
            if (art == null) {
                host.ui.toast("Yedek kaydedildi (kapaklar çok yer tuttuğu için dahil edilmedi)")
            } else {
                host.ui.toast("Yedek kaydedildi")
            }
        } catch (e: Exception) {
            host.ui.toast("Yedek yazılamadı")
        }
    }

    private fun readLimited(host: ToolHost, uri: Uri): String? {
        return try {
            host.activity.contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use { r ->
                val sb = StringBuilder()
                val buf = CharArray(16384)
                while (true) {
                    val n = r.read(buf)
                    if (n < 0) break
                    sb.append(buf, 0, n)
                    if (sb.length > MAX_FILE) return@use null
                }
                sb.toString()
            }
        } catch (e: Exception) {
            null
        }
    }

    // Önce tamamını doğrular. Geçersizse hiçbir şey yazılmaz.
    private fun parse(text: String): Parsed? {
        try {
            val root = JSONObject(text)
            if (root.optString("app") != "Vortex" || root.optString("kind") != "backup") return null
            val prefs = root.optJSONObject("prefs") ?: return null
            val out = HashMap<String, List<Entry>>()
            var total = 0
            for (name in names) {
                val o = prefs.optJSONObject(name) ?: continue
                val list = ArrayList<Entry>()
                val keys = o.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    if (k.length > 300) return null
                    val e = o.optJSONObject(k) ?: return null
                    total++
                    if (total > MAX_ENTRIES) return null
                    val type = e.optString("t")
                    val value: Any = when (type) {
                        "b" -> e.getBoolean("v")
                        "i" -> e.getInt("v")
                        "l" -> e.getLong("v")
                        "f" -> e.getDouble("v").toFloat()
                        "s" -> {
                            val s = e.getString("v")
                            if (s.length > 400000) return null
                            s
                        }
                        "ss" -> {
                            val a = e.getJSONArray("v")
                            if (a.length() > MAX_ENTRIES) return null
                            val set = HashSet<String>()
                            for (i in 0 until a.length()) {
                                val s = a.getString(i)
                                if (s.length > 1000) return null
                                set.add(s)
                            }
                            set
                        }
                        else -> return null
                    }
                    list.add(Entry(k, type, value))
                }
                out[name] = list
            }

            val lyrics = LinkedHashMap<String, String>()
            val lyricsObj = root.optJSONObject("lyrics")
            if (lyricsObj != null) {
                if (lyricsObj.length() > 5000) return null
                val lk = lyricsObj.keys()
                while (lk.hasNext()) {
                    val id = lk.next()
                    if (id.toLongOrNull() == null) return null
                    val s = lyricsObj.getString(id)
                    if (s.length > 200000) return null
                    lyrics[id] = s
                }
            }

            val art = LinkedHashMap<String, ByteArray>()
            val artObj = root.optJSONObject("art")
            if (artObj != null) {
                if (artObj.length() > 400) return null
                val ak = artObj.keys()
                val pattern = Regex("^[0-9a-f]+_[0-9]+\\.jpg$")
                while (ak.hasNext()) {
                    val name = ak.next()
                    if (!pattern.matches(name)) return null
                    val bytes = Base64.decode(artObj.getString(name), Base64.DEFAULT)
                    if (bytes.size > 3000000) return null
                    art[name] = bytes
                }
            }

            if (out.isEmpty() && lyrics.isEmpty()) return null
            return Parsed(out, lyricsObj != null, lyrics, artObj != null, art)
        } catch (ex: Exception) {
            return null
        }
    }

    private fun apply(ctx: Context, data: Parsed) {
        for ((name, list) in data.prefs) {
            val e = ctx.getSharedPreferences(name, Context.MODE_PRIVATE).edit()
            e.clear()
            for (en in list) {
                when (en.type) {
                    "b" -> e.putBoolean(en.key, en.value as Boolean)
                    "i" -> e.putInt(en.key, en.value as Int)
                    "l" -> e.putLong(en.key, en.value as Long)
                    "f" -> e.putFloat(en.key, en.value as Float)
                    "s" -> e.putString(en.key, en.value as String)
                    else -> e.putStringSet(
                        en.key,
                        (en.value as Set<*>).filterIsInstance<String>().toSet()
                    )
                }
            }
            e.commit()
        }
        if (data.hasLyrics) {
            val dir = File(ctx.filesDir, "lyrics")
            dir.mkdirs()
            dir.listFiles()?.forEach { it.delete() }
            for ((id, text) in data.lyrics) {
                try {
                    File(dir, "$id.txt").writeText(text, Charsets.UTF_8)
                } catch (e: Exception) {
                }
            }
        }
        if (data.hasArt) {
            val dir = File(ctx.filesDir, "albumart")
            dir.mkdirs()
            dir.listFiles()?.forEach { it.delete() }
            for ((name, bytes) in data.art) {
                try {
                    File(dir, name).writeBytes(bytes)
                } catch (e: Exception) {
                }
            }
        }
    }

    private fun readBackup(host: ToolHost, uri: Uri) {
        val text = readLimited(host, uri)
        if (text == null) {
            host.ui.toast("Dosya okunamadı ya da çok büyük")
            return
        }
        val data = parse(text)
        if (data == null) {
            host.ui.toast("Geçerli bir Vortex yedeği değil")
            return
        }
        AlertDialog.Builder(host.activity)
            .setTitle("Yedek geri yüklensin mi?")
            .setMessage(
                "Mevcut beğeniler, klasörler, sözler, kapaklar, sıralamalar, ses ve görünüm ayarları " +
                    "yedektekilerle değiştirilecek. Çalan müzik durur ve uygulama yenilenir."
            )
            .setPositiveButton("Geri yükle") { _, _ ->
                apply(host.activity, data)
                host.restartService()
                host.ui.toast("Yedek geri yüklendi")
                host.activity.recreate()
            }
            .setNegativeButton("Vazgeç", null)
            .show()
    }
}