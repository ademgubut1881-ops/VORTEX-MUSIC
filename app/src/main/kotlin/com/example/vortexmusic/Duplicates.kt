package io.github.vortexmusic.player

import android.app.Activity
import android.app.AlertDialog
import android.content.ContentUris
import android.os.Build
import android.provider.MediaStore
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.util.Locale

object DuplicatesTool {

    private const val REQ_DELETE = 401

    private class Item(
        val id: Long,
        val title: String,
        val artist: String,
        val size: Long,
        val duration: Long,
        val place: String
    )

    fun install(host: ToolHost) {
        host.resultHandlers.add { req, res, _ -> handle(host, req, res) }
    }

    fun entries(host: ToolHost): List<ToolEntry> = listOf(
        ToolEntry(
            "Yinelenen dosyalar",
            "Aynı şarkının birden fazla kopyasını bul ve temizle",
            IconDrawable.FOLDER
        ) { open(host) }
    )

    private fun handle(host: ToolHost, req: Int, res: Int): Boolean {
        if (req != REQ_DELETE) return false
        if (res == Activity.RESULT_OK) {
            host.ui.toast("Dosyalar silindi")
            host.activity.recreate()
        }
        return true
    }

    private fun mb(size: Long): String =
        String.format(Locale.US, "%.1f MB", size / 1048576.0)

    // Aynı başlık + sanatçı ve birbirine 3 saniyeden yakın süre = kopya
    private fun scan(host: ToolHost): List<List<Item>> {
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.SIZE,
            MediaStore.Audio.Media.DISPLAY_NAME,
            if (Build.VERSION.SDK_INT >= 29) MediaStore.Audio.Media.RELATIVE_PATH
            else MediaStore.Audio.Media.DATA
        )
        val all = ArrayList<Item>()
        val cursor = host.activity.contentResolver.query(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            projection,
            "${MediaStore.Audio.Media.IS_MUSIC} != 0",
            null,
            null
        )
        cursor?.use {
            while (it.moveToNext()) {
                val name = it.getString(5) ?: ""
                val title = it.getString(1) ?: name
                all.add(
                    Item(
                        it.getLong(0),
                        title,
                        it.getString(2) ?: "",
                        it.getLong(4),
                        it.getLong(3),
                        (it.getString(6) ?: "") + name
                    )
                )
            }
        }
        val byKey = all
            .filter { it.title.isNotBlank() }
            .groupBy {
                SongSearch.normalize(it.title).trim() + "|" + SongSearch.normalize(it.artist).trim()
            }
        val clusters = ArrayList<List<Item>>()
        for ((_, items) in byKey) {
            if (items.size < 2) continue
            val sorted = items.sortedBy { it.duration }
            var current = ArrayList<Item>()
            for (item in sorted) {
                if (current.isNotEmpty() && item.duration - current.last().duration > 3000L) {
                    if (current.size >= 2) clusters.add(current)
                    current = ArrayList()
                }
                current.add(item)
            }
            if (current.size >= 2) clusters.add(current)
        }
        clusters.sortBy { SongSearch.normalize(it[0].title) }
        return clusters
    }

    private fun open(host: ToolHost) {
        val ui = host.ui
        val activity = host.activity
        val clusters = try {
            scan(host)
        } catch (e: Exception) {
            emptyList()
        }
        if (clusters.isEmpty()) {
            ui.toast("Yinelenen dosya bulunamadı")
            return
        }

        val box = LinearLayout(activity)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(ui.dp(20), ui.dp(4), ui.dp(20), 0)
        val checks = ArrayList<CheckBox>()
        val ids = HashMap<CheckBox, Long>()
        val suggested = HashSet<Long>()
        val groups = ArrayList<List<Long>>()

        for (cluster in clusters) {
            val biggest = cluster.maxByOrNull { it.size }
            groups.add(cluster.map { it.id })
            box.addView(TextView(activity).apply {
                text = "${cluster[0].title} · ${cluster[0].artist}"
                textSize = 15f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setPadding(0, ui.dp(14), 0, ui.dp(2))
            })
            for (item in cluster) {
                val cb = CheckBox(activity)
                val tag = if (item === biggest) " · en büyük" else ""
                cb.text = "${mb(item.size)} · ${ui.fmt(item.duration)}$tag\n${item.place}"
                cb.textSize = 12f
                box.addView(cb)
                checks.add(cb)
                ids[cb] = item.id
                if (item !== biggest) suggested.add(item.id)
            }
        }

        val scroll = ScrollView(activity)
        scroll.addView(box)
        val dialog = AlertDialog.Builder(activity)
            .setTitle("Yinelenen dosyalar (${clusters.size} grup)")
            .setView(scroll)
            .setPositiveButton("Seçilenleri sil", null)
            .setNeutralButton("Önerileni seç", null)
            .setNegativeButton("Kapat", null)
            .create()
        dialog.show()

        dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
            for (cb in checks) cb.isChecked = suggested.contains(ids[cb] ?: -1L)
        }
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val chosen = HashSet<Long>()
            for (cb in checks) if (cb.isChecked) ids[cb]?.let { chosen.add(it) }
            if (chosen.isEmpty()) {
                ui.toast("Önce silinecek dosyaları seç")
                return@setOnClickListener
            }
            if (groups.any { g -> g.all { chosen.contains(it) } }) {
                ui.toast("Bir grupta tüm kopyaları seçtin. En az birini bırak.")
                return@setOnClickListener
            }
            if (Build.VERSION.SDK_INT < 30) {
                ui.toast("Dosya silmek için Android 11 ya da üstü gerekir")
                return@setOnClickListener
            }
            try {
                val uris = chosen.map {
                    ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, it)
                }
                val pi = MediaStore.createDeleteRequest(activity.contentResolver, uris)
                activity.startIntentSenderForResult(pi.intentSender, REQ_DELETE, null, 0, 0, 0)
                dialog.dismiss()
            } catch (e: Exception) {
                ui.toast("Silme isteği açılamadı")
            }
        }
    }
}