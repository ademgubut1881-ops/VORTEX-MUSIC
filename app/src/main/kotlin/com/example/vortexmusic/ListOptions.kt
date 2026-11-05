package io.github.vortexmusic.player
import com.example.vortexmusic.R

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.ViewGroup
import android.view.Window
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlin.random.Random

// Liste başına saklanan sıralama ve görünüm ayarları
object ListPrefs {

    const val S_DEFAULT = 0
    const val S_TITLE = 1
    const val S_ARTIST = 2
    const val S_ALBUM = 3
    const val S_TRACK = 4
    const val S_FILE = 5
    const val S_PATH = 6
    const val S_YEAR = 7
    const val S_ADDED = 8
    const val S_MODIFIED = 9
    const val S_DURATION = 10
    const val S_LASTPLAYED = 11
    const val S_PLAYS = 12
    const val S_SHUFFLE = 13
    const val S_MANUAL = 14

    val sortLabels = arrayOf(
        "Varsayılan sıra",
        "Başlığa göre",
        "Sanatçıya göre",
        "Albüme göre",
        "Parça numarasına göre",
        "Dosya ismine göre",
        "Yola göre",
        "Yıla göre",
        "Kitaplığa ekleme tarihine göre",
        "Değiştirme tarihine göre",
        "Süreye göre",
        "Son çalma zamanına göre",
        "Çalma sayısına göre",
        "Karışık sıraya göre",
        "Elle sıralama (benim sıram)"
    )

    const val L_LIST = 0
    const val L_COMPACT = 1
    const val L_SMALL = 2
    const val L_NOIMG = 3
    const val L_GRID3 = 4
    const val L_GRID2 = 5

    val layoutLabels = arrayOf(
        "Liste",
        "Liste - kompakt",
        "Liste - küçük",
        "Liste - resim yok",
        "Izgara - küçük",
        "Izgara"
    )

    class Opts(var sort: Int, var reverse: Boolean, var titlesOnly: Boolean, var layout: Int)

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("vortex_list", Context.MODE_PRIVATE)

    fun load(ctx: Context, key: String): Opts {
        val sp = prefs(ctx)
        return Opts(
            sp.getInt("s_$key", 0).coerceIn(0, sortLabels.size - 1),
            sp.getBoolean("r_$key", false),
            sp.getBoolean("t_$key", false),
            sp.getInt("l_$key", 0).coerceIn(0, layoutLabels.size - 1)
        )
    }

    fun save(ctx: Context, key: String, o: Opts) {
        prefs(ctx).edit()
            .putInt("s_$key", o.sort)
            .putBoolean("r_$key", o.reverse)
            .putBoolean("t_$key", o.titlesOnly)
            .putInt("l_$key", o.layout)
            .apply()
    }

    fun newSeed(ctx: Context, key: String) {
        prefs(ctx).edit().putLong("seed_$key", System.nanoTime()).apply()
    }

    fun manualOrder(ctx: Context, key: String): List<Long> {
        val s = prefs(ctx).getString("o_$key", "") ?: ""
        return if (s.isEmpty()) emptyList() else s.split(",").mapNotNull { it.toLongOrNull() }
    }

    private fun saveManual(ctx: Context, key: String, ids: List<Long>) {
        prefs(ctx).edit().putString("o_$key", ids.joinToString(",")).apply()
    }

    // Görünen sıraya göre elle sıralamayı kaydeder
    fun move(ctx: Context, key: String, current: List<Song>, index: Int, delta: Int) {
        val ids = current.map { it.id }.toMutableList()
        val j = index + delta
        if (index !in ids.indices || j !in ids.indices) return
        val id = ids.removeAt(index)
        ids.add(j, id)
        saveManual(ctx, key, ids)
    }

    fun moveToEdge(ctx: Context, key: String, current: List<Song>, index: Int, top: Boolean) {
        val ids = current.map { it.id }.toMutableList()
        if (index !in ids.indices) return
        val id = ids.removeAt(index)
        if (top) ids.add(0, id) else ids.add(id)
        saveManual(ctx, key, ids)
    }

    private fun statMaps(ctx: Context): Pair<Map<Long, Long>, Map<Long, Int>> {
        val sp = ctx.getSharedPreferences("vortex_stats", Context.MODE_PRIVATE)
        val last = HashMap<Long, Long>()
        val cnt = HashMap<Long, Int>()
        for ((k, v) in sp.all) {
            if (k.startsWith("l_")) {
                val id = k.substring(2).toLongOrNull() ?: continue
                val t = v as? Long ?: continue
                last[id] = t
            } else if (k.startsWith("c_")) {
                val id = k.substring(2).toLongOrNull() ?: continue
                val n = v as? Int ?: continue
                cnt[id] = n
            }
        }
        return Pair(last, cnt)
    }

    private fun norm(s: String): String = SongSearch.normalize(s)

    fun ordered(ctx: Context, key: String, list: List<Song>, o: Opts): List<Song> {
        var out: List<Song> = when (o.sort) {
            S_TITLE -> list.sortedBy { norm(it.title) }
            S_ARTIST -> list.sortedWith(compareBy<Song>({ norm(it.artist) }, { norm(it.title) }))
            S_ALBUM -> list.sortedWith(
                compareBy<Song>({ norm(it.album) }, { if (it.track == 0) Int.MAX_VALUE else it.track }, { norm(it.title) })
            )
            S_TRACK -> list.sortedWith(
                compareBy<Song>({ if (it.track == 0) Int.MAX_VALUE else it.track }, { norm(it.title) })
            )
            S_FILE -> list.sortedBy { norm(it.fileName) }
            S_PATH -> list.sortedWith(compareBy<Song>({ norm(it.path) }, { norm(it.fileName) }))
            S_YEAR -> list.sortedWith(compareBy<Song>({ it.year }, { norm(it.title) }))
            S_ADDED -> list.sortedByDescending { it.added }
            S_MODIFIED -> list.sortedByDescending { it.modified }
            S_DURATION -> list.sortedBy { it.duration }
            S_LASTPLAYED -> {
                val last = statMaps(ctx).first
                list.sortedByDescending { last[it.id] ?: 0L }
            }
            S_PLAYS -> {
                val cnt = statMaps(ctx).second
                list.sortedByDescending { cnt[it.id] ?: 0 }
            }
            S_SHUFFLE -> {
                val seed = prefs(ctx).getLong("seed_$key", 1L)
                list.shuffled(Random(seed))
            }
            S_MANUAL -> {
                val order = manualOrder(ctx, key)
                if (order.isEmpty()) {
                    list
                } else {
                    val idx = HashMap<Long, Int>()
                    order.forEachIndexed { i, id -> idx[id] = i }
                    list.sortedBy { idx[it.id] ?: Int.MAX_VALUE }
                }
            }
            else -> list
        }
        if (o.reverse && o.sort != S_MANUAL && o.sort != S_DEFAULT) out = out.reversed()
        return out
    }
}

// "Liste seçenekleri" penceresi (alttan açılır)
object ListOptionsDialog {

    fun show(ui: Ui, key: String, title: String, onChanged: () -> Unit) {
        val activity = ui.activity
        val opts = ListPrefs.load(activity, key)
        val accent = ui.color(R.color.vortex_blue)
        val textColor = ui.color(R.color.vortex_text)
        val secondary = ui.color(R.color.vortex_text_secondary)
        val refreshers = ArrayList<() -> Unit>()

        val dialog = Dialog(activity)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)

        val sheet = LinearLayout(activity)
        sheet.orientation = LinearLayout.VERTICAL
        sheet.setPadding(ui.dp(20), ui.dp(18), ui.dp(20), ui.dp(14))
        val r = ui.dp(24).toFloat()
        sheet.background = GradientDrawable().apply {
            setColor(ui.color(R.color.vortex_surface))
            cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
        }

        val heading = TextView(activity).apply {
            text = "Liste seçenekleri: $title"
            textSize = 18f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(textColor)
            maxLines = 1
        }
        sheet.addView(heading)

        val scroll = ScrollView(activity)
        scroll.isVerticalScrollBarEnabled = false
        scroll.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            (activity.resources.displayMetrics.heightPixels * 0.6f).toInt()
        ).apply { topMargin = ui.dp(8) }
        val box = LinearLayout(activity)
        box.orientation = LinearLayout.VERTICAL
        scroll.addView(box)
        sheet.addView(scroll)

        fun section(label: String) {
            box.addView(TextView(activity).apply {
                text = label
                textSize = 13f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(secondary)
                setPadding(0, ui.dp(16), 0, ui.dp(2))
            })
        }

        fun addRow(label: String, checkbox: Boolean, selected: () -> Boolean, onClick: () -> Unit) {
            val tv = TextView(activity)
            tv.textSize = 15f
            tv.setPadding(ui.dp(4), ui.dp(11), ui.dp(4), ui.dp(11))
            tv.setOnClickListener {
                onClick()
                for (f in refreshers) f()
            }
            refreshers.add {
                val on = selected()
                val mark = if (checkbox) (if (on) "☑  " else "☐  ") else (if (on) "◉  " else "○  ")
                tv.text = mark + label
                tv.setTextColor(if (on) accent else textColor)
            }
            box.addView(tv)
        }

        fun persist() {
            ListPrefs.save(activity, key, opts)
            onChanged()
        }

        section("Sırala")
        for (i in ListPrefs.sortLabels.indices) {
            addRow(ListPrefs.sortLabels[i], false, { opts.sort == i }) {
                opts.sort = i
                if (i == ListPrefs.S_SHUFFLE) ListPrefs.newSeed(activity, key)
                persist()
            }
        }
        addRow("Ters", true, { opts.reverse }) {
            opts.reverse = !opts.reverse
            persist()
        }

        section("Düzen")
        addRow("Sadece başlıkları göster", true, { opts.titlesOnly }) {
            opts.titlesOnly = !opts.titlesOnly
            persist()
        }

        section("Görünüm")
        for (i in ListPrefs.layoutLabels.indices) {
            addRow(ListPrefs.layoutLabels[i], false, { opts.layout == i }) {
                opts.layout = i
                persist()
            }
        }

        box.addView(TextView(activity).apply {
            text = "Elle sıralamada satırlarda ↑ ↓ düğmeleri çıkar, uzun basınca \"En üste / En alta taşı\" seçilir. " +
                "Sıralama sadece uygulamada saklanır, dosyalarına dokunulmaz."
            textSize = 12f
            setTextColor(secondary)
            setPadding(0, ui.dp(14), 0, ui.dp(4))
        })

        for (f in refreshers) f()

        sheet.addView(TextView(activity).apply {
            text = "Kapat"
            textSize = 14f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            background = ui.neonGradient(false, 24)
            setPadding(ui.dp(24), ui.dp(10), ui.dp(24), ui.dp(10))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = ui.dp(12)
                gravity = Gravity.END
            }
            setOnClickListener { dialog.dismiss() }
        })

        dialog.setContentView(sheet)
        dialog.show()
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setGravity(Gravity.BOTTOM)
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
    }
}