package io.github.vortexmusic.player

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import java.util.Calendar

private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
private const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

// Sayfaların ana ekranla konuştuğu köprü
interface PageHost {
    val ui: Ui
    val loaded: Boolean
    val permissionOk: Boolean
    fun songs(): List<Song>
    fun songsFor(kind: Int): List<Song>
    fun likedSongs(): List<Song>
    fun playList(list: List<Song>, position: Int)
    fun openLibrary(view: Int, smart: Int)
    fun openGroup(view: Int, name: String)
    fun setCover(view: ImageView, song: Song?, sizePx: Int, radiusDp: Int, iconDp: Int)
    fun askPermission()
    fun fillSearch(text: String)
}

// ---------- Son aramalar ----------

object RecentSearches {

    private const val KEY = "recent"

    fun load(ctx: Context): List<String> {
        val s = ctx.getSharedPreferences("vortex", Context.MODE_PRIVATE).getString(KEY, "") ?: ""
        return if (s.isEmpty()) emptyList() else s.split('\n').filter { it.isNotBlank() }
    }

    private fun save(ctx: Context, list: List<String>) {
        ctx.getSharedPreferences("vortex", Context.MODE_PRIVATE).edit()
            .putString(KEY, list.joinToString("\n"))
            .apply()
    }

    fun add(ctx: Context, query: String) {
        val q = query.trim().replace('\n', ' ')
        if (q.length < 2) return
        val list = ArrayList(load(ctx))
        list.removeAll { it.equals(q, ignoreCase = true) }
        list.add(0, q)
        save(ctx, list.take(8))
    }

    fun remove(ctx: Context, query: String) {
        save(ctx, load(ctx).filter { it != query })
    }

    fun clear(ctx: Context) {
        save(ctx, emptyList())
    }
}

// ---------- Liste sayfalarının başlığı (kapak, başlık, oynat, karıştır) ----------

class DetailHeader(private val ui: Ui) {

    val view = LinearLayout(ui.activity)
    val cover = ImageView(ui.activity)
    private val titleText = TextView(ui.activity)
    private val subText = TextView(ui.activity)

    var onBack: () -> Unit = {}
    var onPlay: () -> Unit = {}
    var onShuffle: () -> Unit = {}

    init {
        view.orientation = LinearLayout.VERTICAL
        view.visibility = View.GONE
        view.layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = ui.dp(4) }

        val back = ImageView(ui.activity).apply {
            setPadding(ui.dp(11), ui.dp(11), ui.dp(11), ui.dp(11))
            isClickable = true
            isFocusable = true
            setImageDrawable(ui.icon(IconDrawable.CHEVRON, ui.color(R.color.vortex_purple), 24))
            layoutParams = LinearLayout.LayoutParams(ui.dp(46), ui.dp(46))
            setOnClickListener { onBack() }
        }

        val row = LinearLayout(ui.activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, ui.dp(4), 0, ui.dp(10))
        }
        cover.layoutParams = LinearLayout.LayoutParams(ui.dp(84), ui.dp(84))

        val col = LinearLayout(ui.activity).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f).apply {
                marginStart = ui.dp(14)
                marginEnd = ui.dp(8)
            }
        }
        titleText.textSize = 22f
        titleText.setTypeface(titleText.typeface, Typeface.BOLD)
        titleText.setTextColor(ui.color(R.color.vortex_text))
        titleText.maxLines = 2
        titleText.ellipsize = TextUtils.TruncateAt.END
        subText.textSize = 13f
        subText.setTextColor(ui.color(R.color.vortex_text_secondary))
        subText.maxLines = 2
        col.addView(titleText)
        col.addView(subText)

        val shuffle = ImageView(ui.activity).apply {
            setPadding(ui.dp(11), ui.dp(11), ui.dp(11), ui.dp(11))
            background = ui.circleBackground(false)
            isClickable = true
            setImageDrawable(ui.icon(IconDrawable.SHUFFLE, ui.color(R.color.vortex_text), 22))
            layoutParams = LinearLayout.LayoutParams(ui.dp(46), ui.dp(46))
            setOnClickListener { onShuffle() }
        }
        val play = ImageView(ui.activity).apply {
            setPadding(ui.dp(17), ui.dp(17), ui.dp(17), ui.dp(17))
            background = ui.neonGradient(true)
            isClickable = true
            setImageDrawable(ui.icon(IconDrawable.PLAY, Color.WHITE, 22, filled = true))
            layoutParams = LinearLayout.LayoutParams(ui.dp(56), ui.dp(56)).apply {
                marginStart = ui.dp(10)
            }
            setOnClickListener { onPlay() }
        }

        row.addView(cover)
        row.addView(col)
        row.addView(shuffle)
        row.addView(play)
        view.addView(back)
        view.addView(row)
    }

    fun bind(title: String, subtitle: String) {
        titleText.text = title
        subText.text = subtitle
    }
}

// ---------- Ana Sayfa, Ara ve İndirilenler sayfaları ----------

class Pages(private val host: PageHost, private val content: LinearLayout) {

    private val ui: Ui get() = host.ui
    private var homeFilter = 0
    private val homeChips = arrayOf(
        tx("Tümü", "All"), tx("Beğenilenler", "Liked"), tx("Yeniler", "New"), tx("Unutulanlar", "Forgotten")
    )
    private val homeKinds = intArrayOf(-1, -2, SmartLists.RECENTLY_ADDED, SmartLists.FORGOTTEN)

    private class Quick(val title: String, val kind: Int, val song: Song?, val action: () -> Unit)
    private class Browse(val title: String, val kind: Int, val view: Int, val smart: Int)

    // Material 3 tarzı: tonlu yüzey (zemin + vurgu karışımı) ve orta kalınlık yazı
    private fun tonal(amount: Float): Int {
        val base = ui.color(R.color.vortex_surface)
        val acc = ui.color(R.color.vortex_purple)
        val a = amount
        return Color.rgb(
            (Color.red(base) * (1 - a) + Color.red(acc) * a).toInt(),
            (Color.green(base) * (1 - a) + Color.green(acc) * a).toInt(),
            (Color.blue(base) * (1 - a) + Color.blue(acc) * a).toInt()
        )
    }

    private fun medium(): Typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)

    private fun sectionHeader(t: String): TextView = TextView(ui.activity).apply {
        text = t
        textSize = 19f
        typeface = medium()
        setTextColor(ui.color(R.color.vortex_text))
        layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply {
            topMargin = ui.dp(28)
            bottomMargin = ui.dp(10)
        }
    }

    private fun label(t: String, size: Float, colorId: Int, bold: Boolean = false): TextView =
        TextView(ui.activity).apply {
            text = t
            textSize = size
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setTextColor(ui.color(colorId))
            if (bold) setTypeface(typeface, Typeface.BOLD)
        }

    private fun chipRow(labels: Array<String>, selected: Int, onSelect: (Int) -> Unit): View {
        val scroll = HorizontalScrollView(ui.activity)
        scroll.isHorizontalScrollBarEnabled = false
        scroll.layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply {
            topMargin = ui.dp(12)
            bottomMargin = ui.dp(4)
        }
        val row = LinearLayout(ui.activity)
        row.orientation = LinearLayout.HORIZONTAL
        labels.forEachIndexed { i, l ->
            row.addView(TextView(ui.activity).apply {
                text = l
                textSize = 14f
                typeface = medium()
                gravity = Gravity.CENTER
                setPadding(ui.dp(16), 0, ui.dp(16), 0)
                setTextColor(ui.color(R.color.vortex_text))
                background = GradientDrawable().apply {
                    cornerRadius = ui.dp(10).toFloat()
                    if (i == selected) {
                        setColor(tonal(0.32f))
                    } else {
                        setColor(Color.TRANSPARENT)
                        setStroke(ui.dp(1), (ui.color(R.color.vortex_text_secondary) and 0x00FFFFFF) or 0x66000000)
                    }
                }
                layoutParams = LinearLayout.LayoutParams(WRAP, ui.dp(36)).apply { marginEnd = ui.dp(8) }
                setOnClickListener { onSelect(i) }
            })
        }
        scroll.addView(row)
        return scroll
    }

    private fun permissionCard(): View {
        val card = LinearLayout(ui.activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(ui.dp(20), ui.dp(20), ui.dp(20), ui.dp(20))
            background = GradientDrawable().apply {
                setColor(ui.color(R.color.vortex_surface))
                cornerRadius = ui.dp(22).toFloat()
            }
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = ui.dp(12) }
        }
        card.addView(label(tx("Müzik izni gerekli", "Music permission needed"), 18f, R.color.vortex_text, true))
        card.addView(ui.noteText(
            tx(
                "Şarkılarını listeleyebilmem için müzik ve ses dosyalarına erişim izni ver.",
                "Allow access to music and audio files so I can list your songs."
            )
        ))
        card.addView(TextView(ui.activity).apply {
            text = tx("İzin ver", "Allow")
            textSize = 15f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            background = ui.neonGradient(false, 24)
            setPadding(ui.dp(16), ui.dp(12), ui.dp(16), ui.dp(12))
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = ui.dp(16) }
            setOnClickListener { host.askPermission() }
        })
        return card
    }

    // ----- Ana Sayfa -----

    private fun greeting(): View {
        val h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        val hello = when (h) {
            in 5..11 -> tx("Günaydın", "Good morning")
            in 12..17 -> tx("İyi günler", "Good afternoon")
            in 18..22 -> tx("İyi akşamlar", "Good evening")
            else -> tx("İyi geceler", "Good night")
        }
        return TextView(ui.activity).apply {
            text = hello
            textSize = 24f
            typeface = medium()
            setTextColor(ui.color(R.color.vortex_text))
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = ui.dp(4) }
        }
    }

    private fun quickTile(q: Quick): View {
        val tile = LinearLayout(ui.activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply {
                setColor(ui.color(R.color.vortex_surface))
                cornerRadius = ui.dp(16).toFloat()
            }
            isClickable = true
            setOnClickListener { q.action() }
        }
        val img = ImageView(ui.activity)
        img.layoutParams = LinearLayout.LayoutParams(ui.dp(56), ui.dp(56))
        val song = q.song
        if (song != null) {
            host.setCover(img, song, ui.dp(56), 16, 24)
        } else {
            img.background = GradientDrawable().apply {
                setColor(tonal(0.38f))
                cornerRadius = ui.dp(16).toFloat()
            }
            img.setPadding(ui.dp(16), ui.dp(16), ui.dp(16), ui.dp(16))
            img.setImageDrawable(ui.icon(q.kind, ui.color(R.color.vortex_text), 24))
        }
        val t = TextView(ui.activity).apply {
            text = q.title
            textSize = 13f
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            typeface = medium()
            setTextColor(ui.color(R.color.vortex_text))
            setPadding(ui.dp(10), 0, ui.dp(8), 0)
            layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f)
        }
        tile.addView(img)
        tile.addView(t)
        return tile
    }

    private fun addQuickGrid(items: List<Quick>) {
        var i = 0
        while (i < items.size) {
            val row = LinearLayout(ui.activity).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = ui.dp(8) }
            }
            for (j in 0..1) {
                val lp = LinearLayout.LayoutParams(0, ui.dp(56), 1f).apply {
                    if (j == 0) marginEnd = ui.dp(4) else marginStart = ui.dp(4)
                }
                if (i + j < items.size) row.addView(quickTile(items[i + j]), lp)
                else row.addView(View(ui.activity), lp)
            }
            content.addView(row)
            i += 2
        }
    }

    private fun card(coverSong: Song?, title: String, subtitle: String, onClick: () -> Unit): View {
        val w = ui.dp(132)
        val box = LinearLayout(ui.activity).apply {
            orientation = LinearLayout.VERTICAL
            isClickable = true
            layoutParams = LinearLayout.LayoutParams(w, WRAP).apply { marginEnd = ui.dp(12) }
            setOnClickListener { onClick() }
        }
        val cover = ImageView(ui.activity)
        cover.layoutParams = LinearLayout.LayoutParams(w, w)
        host.setCover(cover, coverSong, w, 16, 40)
        val t = label(title, 14f, R.color.vortex_text)
        t.typeface = medium()
        t.layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = ui.dp(8) }
        box.addView(cover)
        box.addView(t)
        box.addView(label(subtitle, 12f, R.color.vortex_text_secondary))
        return box
    }

    private fun cardRow(cards: List<View>): View {
        val scroll = HorizontalScrollView(ui.activity)
        scroll.isHorizontalScrollBarEnabled = false
        val row = LinearLayout(ui.activity)
        row.orientation = LinearLayout.HORIZONTAL
        for (c in cards) row.addView(c)
        scroll.addView(row)
        return scroll
    }

    private fun songCards(list: List<Song>): View =
        cardRow(list.mapIndexed { i, s -> card(s, s.title, s.artist) { host.playList(list, i) } })

    private fun songRow(song: Song, onClick: () -> Unit): View {
        val row = LinearLayout(ui.activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(ui.dp(2), ui.dp(6), ui.dp(2), ui.dp(6))
            isClickable = true
            setOnClickListener { onClick() }
        }
        val cover = ImageView(ui.activity)
        cover.layoutParams = LinearLayout.LayoutParams(ui.dp(52), ui.dp(52))
        host.setCover(cover, song, ui.dp(52), 14, 24)
        val col = LinearLayout(ui.activity).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f).apply { marginStart = ui.dp(12) }
        }
        col.addView(label(song.title, 16f, R.color.vortex_text))
        col.addView(label(song.artist, 13f, R.color.vortex_text_secondary))
        row.addView(cover)
        row.addView(col)
        return row
    }

    fun renderHome() {
        content.removeAllViews()
        if (!host.permissionOk) {
            content.addView(permissionCard())
            return
        }
        if (!host.loaded) {
            content.addView(ui.noteText(tx("Şarkılar yükleniyor...", "Loading songs...")))
            return
        }
        val songs = host.songs()
        if (songs.isEmpty()) {
            content.addView(ui.noteText(
                tx(
                    "Telefonda müzik bulunamadı. Müzik ekleyince burada görünür.",
                    "No music found on this phone. It will show up here once you add some."
                )
            ))
            return
        }
        content.addView(greeting())
        content.addView(chipRow(homeChips, homeFilter) { i ->
            homeFilter = i
            renderHome()
        })

        if (homeFilter != 0) {
            val list = if (homeFilter == 1) host.likedSongs() else host.songsFor(homeKinds[homeFilter])
            if (list.isEmpty()) {
                val hint = if (homeFilter == 1) tx(
                    "Henüz beğenilen şarkı yok. Tam ekran çalarda kalbe dokunarak ekleyebilirsin.",
                    "No liked songs yet. Tap the heart in the full-screen player to add some."
                )
                else SmartLists.hints[homeKinds[homeFilter]]
                content.addView(ui.noteText(hint))
            } else {
                list.take(80).forEachIndexed { i, s ->
                    content.addView(songRow(s) { host.playList(list, i) })
                }
            }
            return
        }

        // Hızlı erişim: 2 özel kutu + son çalınan (yoksa son eklenen) 4 şarkı
        val quick = ArrayList<Quick>()
        quick.add(Quick(tx("Beğenilenler", "Liked songs"), IconDrawable.HEART, null) { host.openLibrary(1, -1) })
        quick.add(Quick(tx("Tüm şarkılar", "All songs"), IconDrawable.NOTE, null) { host.openLibrary(10, -1) })
        var recent = host.songsFor(SmartLists.RECENT).take(4)
        if (recent.isEmpty()) recent = host.songsFor(SmartLists.RECENTLY_ADDED).take(4)
        recent.forEachIndexed { i, s -> quick.add(Quick(s.title, IconDrawable.NOTE, s) { host.playList(recent, i) }) }
        addQuickGrid(quick)

        val order = intArrayOf(
            SmartLists.RECENT,
            SmartLists.MOST_PLAYED,
            SmartLists.FORGOTTEN,
            SmartLists.RECENTLY_ADDED
        )
        for (kind in order) {
            val list = host.songsFor(kind).take(12)
            if (list.isEmpty()) continue
            content.addView(sectionHeader(SmartLists.titles[kind]))
            content.addView(songCards(list))
        }
        val albums = songs.groupBy { it.album }.toList().sortedBy { it.first.lowercase() }.take(12)
        if (albums.isNotEmpty()) {
            content.addView(sectionHeader(tx("Albümlerin", "Your albums")))
            content.addView(cardRow(albums.map { (name, list) ->
                card(list.first(), name, list.first().artist) { host.openGroup(5, name) }
            }))
        }
        content.addView(TextView(ui.activity).apply {
            text = tx("Tüm şarkıları gör", "See all songs")
            textSize = 14f
            typeface = medium()
            gravity = Gravity.CENTER
            setTextColor(ui.color(R.color.vortex_text))
            background = GradientDrawable().apply {
                setColor(tonal(0.22f))
                cornerRadius = ui.dp(24).toFloat()
            }
            layoutParams = LinearLayout.LayoutParams(MATCH, ui.dp(48)).apply {
                topMargin = ui.dp(28)
                bottomMargin = ui.dp(8)
            }
            setOnClickListener { host.openLibrary(10, -1) }
        })
    }

    // ----- Ara (arama kutusu boşken) -----

    // Gözat kartları: "tek renk" seçiliyken koyu gri tonlar (her temada beyaz yazı okunur),
    // renkli vurguda vurgu renginden türeyen degradeler
    private fun browseColors(i: Int): IntArray {
        if (ThemeStore.mono()) {
            return when (i % 4) {
                0 -> intArrayOf(Color.parseColor("#3A3A44"), Color.parseColor("#22222A"))
                1 -> intArrayOf(Color.parseColor("#2C2C34"), Color.parseColor("#1B1B21"))
                2 -> intArrayOf(Color.parseColor("#34343E"), Color.parseColor("#20202A"))
                else -> intArrayOf(Color.parseColor("#2A2A32"), Color.parseColor("#17171C"))
            }
        }
        val bg = Color.parseColor("#0B0F1E")
        val p = ui.color(R.color.vortex_purple)
        val b = ui.color(R.color.vortex_blue)
        val pb = ui.mix(p, b, 0.5f)
        return when (i % 4) {
            0 -> intArrayOf(ui.mix(bg, p, 0.75f), ui.mix(bg, b, 0.55f))
            1 -> intArrayOf(ui.mix(bg, b, 0.7f), ui.mix(bg, p, 0.55f))
            2 -> intArrayOf(ui.mix(bg, p, 0.7f), ui.mix(bg, pb, 0.5f))
            else -> intArrayOf(ui.mix(bg, pb, 0.7f), ui.mix(bg, b, 0.6f))
        }
    }

    private fun browseCard(item: Browse, index: Int): View {
        val c = LinearLayout(ui.activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(ui.dp(14), ui.dp(12), ui.dp(14), ui.dp(12))
            background = GradientDrawable().apply {
                setColor(tonal(if (index % 2 == 0) 0.20f else 0.14f))
                cornerRadius = ui.dp(16).toFloat()
            }
            isClickable = true
            setOnClickListener { host.openLibrary(item.view, item.smart) }
        }
        val ic = ImageView(ui.activity).apply {
            setImageDrawable(ui.icon(item.kind, ui.color(R.color.vortex_text), 22))
            layoutParams = LinearLayout.LayoutParams(ui.dp(22), ui.dp(22))
        }
        val t = label(item.title, 15f, R.color.vortex_text)
        t.typeface = medium()
        c.addView(ic)
        c.addView(View(ui.activity), LinearLayout.LayoutParams(1, 0, 1f))
        c.addView(t)
        return c
    }

    private fun recentRow(q: String): View {
        val row = LinearLayout(ui.activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(ui.dp(2), ui.dp(4), ui.dp(2), ui.dp(4))
            isClickable = true
            setOnClickListener { host.fillSearch(q) }
        }
        val ic = ImageView(ui.activity).apply {
            setImageDrawable(ui.icon(IconDrawable.CLOCK, ui.color(R.color.vortex_text_secondary), 22))
            layoutParams = LinearLayout.LayoutParams(ui.dp(30), ui.dp(30)).apply { marginEnd = ui.dp(12) }
        }
        val t = label(q, 16f, R.color.vortex_text)
        t.layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f)
        val x = TextView(ui.activity).apply {
            text = "✕"
            textSize = 16f
            gravity = Gravity.CENTER
            setTextColor(ui.color(R.color.vortex_text_secondary))
            layoutParams = LinearLayout.LayoutParams(ui.dp(44), ui.dp(44))
            setOnClickListener {
                RecentSearches.remove(ui.activity, q)
                renderSearch()
            }
        }
        row.addView(ic)
        row.addView(t)
        row.addView(x)
        return row
    }

    fun renderSearch() {
        content.removeAllViews()
        if (!host.permissionOk) {
            content.addView(permissionCard())
            return
        }
        val recents = RecentSearches.load(ui.activity)
        if (recents.isNotEmpty()) {
            val title = sectionHeader(tx("Son aramalar", "Recent searches"))
            (title.layoutParams as LinearLayout.LayoutParams).topMargin = ui.dp(8)
            content.addView(title)
            for (q in recents) content.addView(recentRow(q))
            content.addView(TextView(ui.activity).apply {
                text = tx("Son aramaları temizle", "Clear recent searches")
                textSize = 13f
                gravity = Gravity.CENTER
                setTextColor(ui.color(R.color.vortex_text))
                background = ui.pillBackground(false)
                setPadding(ui.dp(16), ui.dp(10), ui.dp(16), ui.dp(10))
                layoutParams = LinearLayout.LayoutParams(WRAP, WRAP).apply {
                    topMargin = ui.dp(10)
                    gravity = Gravity.CENTER_HORIZONTAL
                }
                setOnClickListener {
                    RecentSearches.clear(ui.activity)
                    renderSearch()
                }
            })
        }
        content.addView(sectionHeader(tx("Gözat", "Browse")))
        val items = listOf(
            Browse(tx("Tüm şarkılar", "All songs"), IconDrawable.NOTE, 10, -1),
            Browse(tx("Beğenilenler", "Liked songs"), IconDrawable.HEART, 1, -1),
            Browse(tx("Son eklenenler", "Recently added"), IconDrawable.PLUS, 0, SmartLists.RECENTLY_ADDED),
            Browse(tx("En çok dinlenen", "Most played"), IconDrawable.CHART, 0, SmartLists.MOST_PLAYED),
            Browse(tx("Unutulanlar", "Forgotten"), IconDrawable.REPEAT, 0, SmartLists.FORGOTTEN),
            Browse(tx("Klasörler", "Folders"), IconDrawable.FOLDER, 6, -1),
            Browse(tx("Sanatçılar", "Artists"), IconDrawable.ARTIST, 2, -1),
            Browse(tx("Albümler", "Albums"), IconDrawable.DISC, 4, -1)
        )
        var i = 0
        while (i < items.size) {
            val row = LinearLayout(ui.activity).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = ui.dp(10) }
            }
            for (j in 0..1) {
                val idx = i + j
                val lp = LinearLayout.LayoutParams(0, ui.dp(92), 1f).apply {
                    if (j == 0) marginEnd = ui.dp(5) else marginStart = ui.dp(5)
                }
                if (idx < items.size) row.addView(browseCard(items[idx], idx), lp)
                else row.addView(View(ui.activity), lp)
            }
            content.addView(row)
            i += 2
        }
    }

    // ----- İndirilenler -----

    fun renderDownloads() {
        content.removeAllViews()
        val c = LinearLayout(ui.activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(ui.dp(20), ui.dp(20), ui.dp(20), ui.dp(20))
            background = GradientDrawable().apply {
                setColor(ui.color(R.color.vortex_surface))
                cornerRadius = ui.dp(22).toFloat()
            }
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = ui.dp(8) }
        }
        val ic = ImageView(ui.activity).apply {
            setPadding(ui.dp(14), ui.dp(14), ui.dp(14), ui.dp(14))
            background = ui.neonGradient(false, 16)
            setImageDrawable(ui.icon(IconDrawable.DOWNLOAD, Color.WHITE, 28))
            layoutParams = LinearLayout.LayoutParams(ui.dp(56), ui.dp(56))
        }
        val title = label(tx("İndirilenler", "Downloads"), 20f, R.color.vortex_text, true)
        title.layoutParams = LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = ui.dp(14) }
        val body = TextView(ui.activity).apply {
            text = tx(
                "Çevrimdışı dinlemek için indirdiğin şarkılar burada görünecek. " +
                    "İndirme özelliği Beta 3'te açılacak. Şimdilik telefonundaki müzikler Kütüphane'de.",
                "Songs you download for offline listening will appear here. " +
                    "Downloads will arrive in Beta 3. For now, the music on your phone is in the Library."
            )
            textSize = 14f
            setTextColor(ui.color(R.color.vortex_text_secondary))
            layoutParams = LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = ui.dp(6) }
        }
        c.addView(ic)
        c.addView(title)
        c.addView(body)
        content.addView(c)
    }
}