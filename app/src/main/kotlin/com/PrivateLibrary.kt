package io.github.vortexmusic.player

import android.app.Activity
import android.app.AlertDialog
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.widget.EditText
import android.widget.Toast

// Vortex klasörü: kullanıcının seçtiği, içinde .nomedia olan kendi klasörü.
// Buradaki müzikler galeriye ve diğer müzik uygulamalarına düşmez.
// Klasör Dosyalarım'da görünür; uygulama silinse de müzikler kalır.
object PrivateLibrary {

    private const val REQ_TREE = 301
    private const val REQ_ADD = 302
    private const val PREFS = "vortex_private"
    private val main = Handler(Looper.getMainLooper())

    var onChanged: () -> Unit = {}

    @Volatile private var scanning = false
    @Volatile private var listFailed = false
    @Volatile private var mem: List<Song>? = null

    private class Doc(val id: String, val name: String, val mime: String, val modified: Long, val size: Long)

    private class Entry(
        val docId: String, val name: String, val title: String, val artist: String,
        val album: String, val dur: Long, val year: Int, val track: Int, val mod: Long, val size: Long
    )

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // Klasörün görünen adı: kullanıcı değiştirebilir, boş bırakırsa varsayılan ad döner
    fun folderName(ctx: Context): String {
        val n = prefs(ctx).getString("name", "") ?: ""
        return if (n.isNotBlank()) n else tx("Vortex klasörü", "Vortex folder")
    }

    private fun askName(host: ToolHost) {
        val a = host.activity
        val input = EditText(a).apply {
            setText(folderName(a))
            setSelection(text.length)
            setSingleLine(true)
            hint = tx("Klasör adı", "Folder name")
        }
        AlertDialog.Builder(a, DialogStyle.theme())
            .setTitle(tx("Klasörün adı", "Folder name"))
            .setView(input)
            .setMessage(tx("Boş bırakırsan varsayılan ad döner.", "Leave empty to use the default name."))
            .setPositiveButton(tx("Tamam", "OK")) { _, _ ->
                prefs(a).edit().putString("name", input.text.toString().trim().take(30)).apply()
                onChanged()
            }
            .setNegativeButton(tx("Vazgeç", "Cancel"), null)
            .create().also { it.show(); DialogStyle.apply(it, host.ui) }
    }

    fun treeUri(ctx: Context): Uri? = prefs(ctx).getString("tree", null)?.let { Uri.parse(it) }

    // Otomatik tarama: uygulama açılınca ve geri dönünce Vortex klasörünü kendiliğinden tarar
    fun autoScan(ctx: Context): Boolean = prefs(ctx).getBoolean("auto", true)

    fun onlyPrivate(ctx: Context): Boolean = prefs(ctx).getBoolean("only", false) && treeUri(ctx) != null

    fun install(host: ToolHost) {
        host.resultHandlers.add { req, res, data -> handle(host, req, res, data) }
    }

    fun entries(host: ToolHost): List<ToolEntry> {
        val on = treeUri(host.activity) != null
        return listOf(
            ToolEntry(
                tx("Kitaplık taraması", "Library scan"),
                if (autoScan(host.activity)) tx("Otomatik · elle de yenileyebilirsin", "Automatic · you can also refresh by hand")
                else tx("Elle · yeni şarkı için \"Şimdi tara\" de", "Manual · tap \"Scan now\" for new songs"),
                IconDrawable.FOLDER
            ) { scanMenu(host) },
            ToolEntry(
                folderName(host.activity),
                if (on) tx("${songs(host.activity).size} şarkı · galeriye düşmeyen kendi klasörün", "${songs(host.activity).size} songs · your own folder, kept out of the gallery")
                else tx("Müziklerin galeriye düşmeden duracağı kendi klasörün", "Your own folder for music, kept out of the gallery"),
                IconDrawable.FOLDER
            ) { folderMenu(host) }
        )
    }

    // Kitaplık taraması: elle (Şimdi tara) ve otomatik (aç/kapat). Ayarlar ve hızlı ayarlardan açılır.
    fun scanMenu(host: ToolHost) {
        val a = host.activity
        val auto = autoScan(a)
        val acts = ArrayList<Pair<String, () -> Unit>>()
        acts.add(Pair(tx("Şimdi tara (elle)", "Scan now (manual)")) {
            toast(a, tx("Kitaplık yenileniyor…", "Refreshing library…"))
            if (treeUri(a) != null) rescan(a, true) else onChanged()
        })
        acts.add(Pair(
            tx("Otomatik tarama: ", "Automatic scan: ") + if (auto) tx("Açık", "On") else tx("Kapalı", "Off")
        ) {
            prefs(a).edit().putBoolean("auto", !auto).apply()
            toast(
                a,
                if (!auto) tx("Otomatik tarama açıldı", "Automatic scan is on")
                else tx("Otomatik tarama kapandı, yeni şarkılar için elle tara", "Automatic scan is off, scan by hand for new songs")
            )
        })
        SheetDialog.Builder(a, host.ui)
            .setTitle(tx("Kitaplık taraması", "Library scan"))
            .setItems(Array<CharSequence>(acts.size) { acts[it].first }) { _, i -> acts[i].second() }
            .create().show()
    }

    fun folderMenu(host: ToolHost) {
        val a = host.activity
        val acts = ArrayList<Pair<String, () -> Unit>>()
        if (treeUri(a) == null) {
            acts.add(Pair(tx("Klasör seç", "Choose folder")) { pickTree(a) })
        } else {
            acts.add(Pair(tx("Şarkı ekle (kopyalar)", "Add songs (copies)")) { pickFiles(a) })
            if (!hasAccess(a)) {
                acts.add(Pair(tx("İzni yenile (aynı klasörü seç)", "Renew access (pick the same folder)")) { pickTree(a) })
            }
            acts.add(Pair(tx("Klasörü tara", "Scan folder")) {
                toast(a, tx("Taranıyor…", "Scanning…"))
                rescan(a, true)
            })
            val only = prefs(a).getBoolean("only", false)
            acts.add(Pair(
                tx("Sadece Vortex klasörü: ", "Only Vortex folder: ") + if (only) tx("Açık", "On") else tx("Kapalı", "Off")
            ) {
                prefs(a).edit().putBoolean("only", !only).apply()
                onChanged()
            })
            acts.add(Pair(tx("Adı değiştir", "Rename")) { askName(host) })
            acts.add(Pair(tx("Klasörü değiştir", "Change folder")) { pickTree(a) })
        }
        SheetDialog.Builder(a, host.ui)
            .setTitle(folderName(a))
            .setItems(Array<CharSequence>(acts.size) { acts[it].first }) { _, i -> acts[i].second() }
            .create().show()
    }

    @Suppress("DEPRECATION")
    private fun pickTree(a: Activity) {
        toast(a, tx("Bir klasör aç ya da yeni klasör yap, sonra 'Bu klasörü kullan' de", "Open or create a folder, then tap 'Use this folder'"))
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
            .addFlags(
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                    Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION
            )
        a.startActivityForResult(intent, REQ_TREE)
    }

    @Suppress("DEPRECATION")
    private fun pickFiles(a: Activity) {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType("audio/*")
            .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        a.startActivityForResult(intent, REQ_ADD)
    }

    private fun handle(host: ToolHost, req: Int, res: Int, data: Intent?): Boolean {
        val a = host.activity
        if (req == REQ_TREE) {
            val uri = data?.data
            if (res == Activity.RESULT_OK && uri != null) {
                try {
                    a.contentResolver.takePersistableUriPermission(
                        uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    )
                } catch (e: Exception) {
                    toast(a, tx("Bu klasöre izin alınamadı, başka klasör seç", "Could not get access to this folder, pick another"))
                    return true
                }
                prefs(a).edit().putString("tree", uri.toString()).apply()
                mem = null
                Thread {
                    makeNoMedia(a, uri)
                    main.post {
                        toast(a, tx("Klasör hazır. Şarkıları Dosyalarım'dan buraya atabilir ya da 'Şarkı ekle' kullanabilirsin", "Folder ready. Drop songs here with your file manager or use 'Add songs'"))
                    }
                    rescan(a, true)
                }.start()
            }
            return true
        }
        if (req == REQ_ADD) {
            if (res == Activity.RESULT_OK && data != null) {
                val list = ArrayList<Uri>()
                val clip = data.clipData
                if (clip != null) {
                    for (i in 0 until clip.itemCount) list.add(clip.getItemAt(i).uri)
                } else data.data?.let { list.add(it) }
                if (list.isNotEmpty()) copyIn(a, list)
            }
            return true
        }
        return false
    }

    // Klasör izni sistemde hâlâ kayıtlı mı? Uygulama silinip yüklenince izin düşer, ayar kalır.
    fun hasAccess(ctx: Context): Boolean {
        val tree = treeUri(ctx) ?: return false
        return try {
            ctx.contentResolver.persistedUriPermissions.any { it.uri == tree && it.isReadPermission }
        } catch (e: Exception) {
            false
        }
    }

    private fun toast(a: Context, t: String) {
        main.post { Toast.makeText(a, t, Toast.LENGTH_LONG).show() }
    }

    private fun rootDoc(tree: Uri): Uri =
        DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))

    private fun makeNoMedia(a: Context, tree: Uri) {
        try {
            val cr = a.contentResolver
            val has = children(cr, tree, DocumentsContract.getTreeDocumentId(tree)).any { it.name == ".nomedia" }
            if (!has) DocumentsContract.createDocument(cr, rootDoc(tree), "application/octet-stream", ".nomedia")
        } catch (e: Exception) {
        }
    }

    private fun copyIn(a: Activity, uris: List<Uri>) {
        val tree = treeUri(a) ?: return
        val app = a.applicationContext
        toast(a, tx("${uris.size} dosya kopyalanıyor…", "Copying ${uris.size} files…"))
        Thread {
            val cr = app.contentResolver
            val existing = children(cr, tree, DocumentsContract.getTreeDocumentId(tree)).map { it.name }.toMutableSet()
            var added = 0
            var skipped = 0
            var failed = 0
            for (u in uris) {
                try {
                    var name = "song"
                    cr.query(u, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                        if (c.moveToFirst()) name = c.getString(0) ?: name
                    }
                    if (existing.contains(name)) {
                        skipped++
                        continue
                    }
                    val mime = cr.getType(u) ?: "audio/*"
                    val dest = DocumentsContract.createDocument(cr, rootDoc(tree), mime, name)
                    if (dest == null) {
                        failed++
                        continue
                    }
                    cr.openInputStream(u)?.use { input ->
                        cr.openOutputStream(dest)?.use { out -> input.copyTo(out, 64 * 1024) }
                    }
                    existing.add(name)
                    added++
                } catch (e: Exception) {
                    failed++
                }
            }
            toast(
                app,
                tx(
                    "$added şarkı eklendi" + (if (skipped > 0) ", $skipped zaten vardı" else "") + (if (failed > 0) ", $failed eklenemedi" else ""),
                    "$added added" + (if (skipped > 0) ", $skipped already there" else "") + (if (failed > 0) ", $failed failed" else "")
                )
            )
            rescan(app, true)
        }.start()
    }

    private fun children(cr: ContentResolver, tree: Uri, parentId: String): List<Doc> {
        val out = ArrayList<Doc>()
        val uri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentId)
        val proj = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
            DocumentsContract.Document.COLUMN_SIZE
        )
        try {
            cr.query(uri, proj, null, null, null)?.use { c ->
                while (c.moveToNext()) {
                    out.add(Doc(c.getString(0), c.getString(1) ?: "", c.getString(2) ?: "", c.getLong(3), c.getLong(4)))
                }
            }
        } catch (e: Exception) {
            listFailed = true
        }
        return out
    }

    private fun walk(cr: ContentResolver, tree: Uri, parentId: String, depth: Int, out: MutableList<Doc>) {
        for (d in children(cr, tree, parentId)) {
            if (d.mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                if (depth < 4) walk(cr, tree, d.id, depth + 1, out)
            } else if (isAudio(d)) out.add(d)
        }
    }

    private fun isAudio(d: Doc): Boolean {
        if (d.mime.startsWith("audio/")) return true
        val n = d.name.lowercase()
        return n.endsWith(".mp3") || n.endsWith(".m4a") || n.endsWith(".flac") || n.endsWith(".ogg") ||
            n.endsWith(".opus") || n.endsWith(".wav") || n.endsWith(".aac") || n.endsWith(".mka")
    }

    private fun clean(s: String?): String = (s ?: "").replace('\t', ' ').replace('\n', ' ').replace('\r', ' ').trim()

    private fun encode(e: Entry): String = listOf(
        e.docId, e.name, e.title, e.artist, e.album, e.dur, e.year, e.track, e.mod, e.size
    ).joinToString("\t") { clean(it.toString()) }

    private fun decode(line: String): Entry? {
        val p = line.split('\t')
        if (p.size < 10) return null
        return Entry(
            p[0], p[1], p[2], p[3], p[4], p[5].toLongOrNull() ?: 0L, p[6].toIntOrNull() ?: 0,
            p[7].toIntOrNull() ?: 0, p[8].toLongOrNull() ?: 0L, p[9].toLongOrNull() ?: 0L
        )
    }

    private fun idFor(docId: String): Long {
        var h = -3750763034362895579L
        for (ch in docId) {
            h = h xor ch.code.toLong()
            h *= 1099511628211L
        }
        return -((h and Long.MAX_VALUE) % 4000000000000000000L) - 1L
    }

    private fun toSong(e: Entry, tree: Uri): Song {
        val doc = DocumentsContract.buildDocumentUriUsingTree(tree, e.docId).toString()
        return Song(
            idFor(e.docId),
            if (e.title.isNotEmpty()) e.title else e.name.substringBeforeLast('.'),
            if (e.artist.isNotEmpty()) e.artist else tx("Bilinmeyen sanatçı", "Unknown artist"),
            if (e.album.isNotEmpty()) e.album else tx("Bilinmeyen albüm", "Unknown album"),
            e.mod, e.dur, e.year, e.track, e.mod, e.name, "Vortex/", doc
        )
    }

    private fun readCache(ctx: Context): List<Entry> {
        val text = prefs(ctx).getString("cache", "") ?: ""
        return text.split('\n').mapNotNull { if (it.isEmpty()) null else decode(it) }
    }

    fun songs(ctx: Context): List<Song> {
        mem?.let { return it }
        val tree = treeUri(ctx) ?: return emptyList()
        val list = readCache(ctx).map { toSong(it, tree) }
        mem = list
        return list
    }

    fun rescan(ctx: Context, force: Boolean = false) {
        val app = ctx.applicationContext
        val tree = treeUri(app) ?: return
        if (scanning) return
        if (!hasAccess(app)) {
            if (force) toast(app, tx("Klasör izni düşmüş. Vortex klasörü menüsünden 'İzni yenile' de, aynı klasörü seç", "Folder access was lost. In the Vortex folder menu tap 'Renew access' and pick the same folder"))
            return
        }
        scanning = true
        Thread {
            try {
                listFailed = false
                val cr = app.contentResolver
                val old = HashMap<String, Entry>()
                for (e in readCache(app)) old[e.docId] = e
                val docs = ArrayList<Doc>()
                walk(cr, tree, DocumentsContract.getTreeDocumentId(tree), 0, docs)
                if (listFailed) {
                    if (force) toast(app, tx("Klasör okunamadı, hiçbir şey silinmedi", "Could not read the folder, nothing was removed"))
                    return@Thread
                }
                val fresh = ArrayList<Entry>()
                for (d in docs) {
                    val o = old[d.id]
                    if (o != null && o.mod == d.modified / 1000 && o.size == d.size) {
                        fresh.add(o)
                        continue
                    }
                    var title = ""
                    var artist = ""
                    var album = ""
                    var dur = 0L
                    var year = 0
                    var track = 0
                    val r = MediaMetadataRetriever()
                    try {
                        r.setDataSource(app, DocumentsContract.buildDocumentUriUsingTree(tree, d.id))
                        title = clean(r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE))
                        artist = clean(r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST))
                        album = clean(r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM))
                        dur = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                        year = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_YEAR)?.take(4)?.toIntOrNull() ?: 0
                        track = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CD_TRACK_NUMBER)
                            ?.substringBefore('/')?.toIntOrNull() ?: 0
                    } catch (e: Exception) {
                    } finally {
                        try { r.release() } catch (e: Exception) {}
                    }
                    fresh.add(Entry(d.id, clean(d.name), title, artist, album, dur, year, track, d.modified / 1000, d.size))
                }
                val text = fresh.joinToString("\n") { encode(it) }
                val changed = text != (prefs(app).getString("cache", "") ?: "")
                prefs(app).edit().putString("cache", text).apply()
                mem = fresh.map { toSong(it, tree) }
                if (changed || force) main.post { onChanged() }
            } finally {
                scanning = false
            }
        }.start()
    }
}
