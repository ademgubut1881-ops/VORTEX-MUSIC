package io.github.vortexmusic.player

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Outline
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.MediaStore
import android.provider.Settings
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.Window
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import kotlin.math.abs
import kotlin.random.Random

class MainActivity : Activity() {

    // iconStyle: 0 neon gradyanlı kare, 1 koyu kare, 2 koyu daire
    private class Row(
        val kind: Int,
        val iconStyle: Int,
        val title: String,
        val subtitle: String,
        val enabled: Boolean,
        val key: String,
        val movable: Boolean = false,
        val coverSong: Song? = null,
        val onClick: () -> Unit
    )

    private val songs = mutableListOf<Song>()
    private val byId = HashMap<Long, Song>()
    private val shown = mutableListOf<Song>()
    private val libRows = mutableListOf<Row>()
    private val folders = linkedMapOf<String, MutableSet<Long>>()
    private var loaded = false
    private var mode = 0 // 0 ana sayfa, 1 ara, 2 kütüphane, 3 indirilenler
    // Kütüphane: 0 ana, 1 beğenilenler, 2 sanatçılar, 3 sanatçı şarkıları,
    // 4 albümler, 5 albüm şarkıları, 6 klasörler, 7 klasör şarkıları,
    // 8 akıllı listeler, 9 akıllı liste şarkıları, 10 tüm şarkılar, 11 araçlar
    private var libView = 0
    private var selectedGroup = ""
    private var searchScope = SongSearch.SCOPE_ALL
    private var searchSort = SongSearch.SORT_BEST
    private var npArtSize = 0
    private var pitchLink = false
    private var player: ExoPlayer? = null
    private var fx: AudioFxController? = null
    private var fader: Fader? = null
    private val adapter = SongAdapter()
    private val libAdapter = RowAdapter()

    // Albümler
    private var albumByArtist = false
    private var albumSort = 0
    private var albumManual = ArrayList<String>()
    private var albumViewByTag = false
    private var pendingArtKey: String? = null

    // Liste seçenekleri
    private val defaultOpts = ListPrefs.Opts(0, false, false, 0)
    private var curOpts = defaultOpts
    private var listKey = ""
    private var curDetailTitle = ""

    // Oturum ve oynatıcı rengi
    private var sessionRestored = false
    private var tickCount = 0
    private var accentColor = 0

    private val favorites = mutableSetOf<Long>()
    private val speeds = floatArrayOf(0.75f, 0.9f, 1.0f, 1.1f, 1.25f, 1.5f)
    private val chipLabels = arrayOf(
        "Tümü", "Beğenilenler", "Klasörler", "Akıllı", "Sanatçılar", "Albümler"
    )
    private val chipTargets = intArrayOf(0, 1, 6, 8, 2, 4)
    private val smartIcons = intArrayOf(
        IconDrawable.CHART,
        IconDrawable.PLUS,
        IconDrawable.NOTE,
        IconDrawable.REPEAT,
        IconDrawable.CLOCK
    )
    private val tabKinds = intArrayOf(
        IconDrawable.HOME,
        IconDrawable.SEARCH,
        IconDrawable.NOTE,
        IconDrawable.DOWNLOAD
    )

    private lateinit var ui: Ui
    private lateinit var pages: Pages
    private lateinit var detailHeader: DetailHeader
    private lateinit var detailOptionsRow: LinearLayout
    private lateinit var coverAddBtn: TextView
    private lateinit var coverRemoveBtn: TextView
    private lateinit var statsScreen: StatsScreen
    private lateinit var audioScreen: AudioScreen
    private lateinit var queueScreen: QueueScreen
    private lateinit var toolHost: ToolHost

    private lateinit var statusText: TextView
    private lateinit var searchBox: EditText
    private lateinit var statsButton: ImageView
    private lateinit var audioButton: ImageView
    private lateinit var queueButton: ImageView
    private lateinit var chipScroll: HorizontalScrollView
    private lateinit var chipContainer: LinearLayout
    private lateinit var searchChipScroll: HorizontalScrollView
    private lateinit var searchChips: LinearLayout
    private lateinit var pageScroll: ScrollView
    private lateinit var pageContent: LinearLayout
    private lateinit var nowTitle: TextView
    private lateinit var nowArtist: TextView
    private lateinit var miniCover: ImageView
    private lateinit var songList: ListView
    private lateinit var playPauseButton: ImageView
    private lateinit var tabIcons: Array<ImageView>

    private lateinit var nowPlayingScreen: LinearLayout
    private lateinit var npArt: ImageView
    private lateinit var npTitle: TextView
    private lateinit var npArtist: TextView
    private lateinit var npSeek: SeekBar
    private lateinit var npPosition: TextView
    private lateinit var npDuration: TextView
    private lateinit var npPlayPause: ImageView
    private lateinit var npLikeIcon: ImageView
    private lateinit var npLikeLabel: TextView
    private lateinit var npFolderIcon: ImageView
    private lateinit var npFolderLabel: TextView
    private lateinit var npShuffleIcon: ImageView
    private lateinit var npShuffleLabel: TextView
    private lateinit var npRepeatIcon: ImageView
    private lateinit var npRepeatLabel: TextView
    private lateinit var npSpeedIcon: ImageView
    private lateinit var npSpeedLabel: TextView

    private val handler = Handler(Looper.getMainLooper())
    private var dragging = false
    private var backCallback: Any? = null

    private val playerListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            updatePlayIcons(isPlaying)
            if (isPlaying) fader?.fadeIn() else saveSession()
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            updateNowPlayingUi()
            if (player?.isPlaying == true) fader?.fadeIn()
            saveSession()
        }

        override fun onRepeatModeChanged(repeatMode: Int) {
            updatePills()
        }

        override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
            updatePills()
        }
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as? PlaybackService.LocalBinder ?: return
            val p = binder.getPlayer() ?: return
            fx = binder.getFx()
            attachPlayer(p)
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            player?.removeListener(playerListener)
            player = null
            fx = null
            fader = null
        }
    }

    private val ticker = object : Runnable {
        override fun run() {
            val p = player
            if (p != null && nowPlayingScreen.visibility == View.VISIBLE && !dragging) {
                val dur = p.duration
                if (dur > 0) {
                    npSeek.max = dur.toInt()
                    npSeek.progress = p.currentPosition.toInt()
                    npDuration.text = fmt(dur)
                }
                npPosition.text = fmt(p.currentPosition)
            }
            tickCount++
            if (tickCount % 20 == 0 && p != null && p.isPlaying) saveSession()
            handler.postDelayed(this, 500)
        }
    }

    // Sayfaların (Pages) ana ekranla konuştuğu köprü
    private inner class HostImpl : PageHost {
        override val ui: Ui get() = this@MainActivity.ui
        override val loaded: Boolean get() = this@MainActivity.loaded
        override val permissionOk: Boolean get() = this@MainActivity.permissionOk()
        override fun songs(): List<Song> = this@MainActivity.songs
        override fun songsFor(kind: Int): List<Song> = this@MainActivity.songsFor(kind)
        override fun likedSongs(): List<Song> =
            this@MainActivity.songs.filter { favorites.contains(it.id) }

        override fun playList(list: List<Song>, position: Int) =
            this@MainActivity.playList(list, position)

        override fun openLibrary(view: Int, smart: Int) = this@MainActivity.openLibrary(view, smart)
        override fun openGroup(view: Int, name: String) = this@MainActivity.openGroup(view, name)
        override fun setCover(view: ImageView, song: Song?, sizePx: Int, radiusDp: Int, iconDp: Int) =
            this@MainActivity.setCover(view, song, sizePx, radiusDp, iconDp)

        override fun askPermission() = this@MainActivity.askAudioPermission()
        override fun fillSearch(text: String) {
            searchBox.setText(text)
            searchBox.setSelection(text.length)
        }
    }

    // ---------- Yardımcılar (Ui sınıfına yönlendirir) ----------

    private fun audioPermission(): String =
        if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO
        else Manifest.permission.READ_EXTERNAL_STORAGE

    private fun permissionOk(): Boolean =
        checkSelfPermission(audioPermission()) == PackageManager.PERMISSION_GRANTED

    private fun dp(v: Int): Int = ui.dp(v)
    private fun color(id: Int): Int = ui.color(id)
    private fun toast(msg: String) = ui.toast(msg)
    private fun fmt(ms: Long): String = ui.fmt(ms)
    private fun icon(
        kind: Int,
        tint: Int,
        sizeDp: Int,
        filled: Boolean = false,
        glow: Boolean = false
    ): IconDrawable = ui.icon(kind, tint, sizeDp, filled, glow)

    private fun neonGradient(oval: Boolean, radiusDp: Int = 16): GradientDrawable =
        ui.neonGradient(oval, radiusDp)

    private fun circleBackground(active: Boolean): GradientDrawable = ui.circleBackground(active)
    private fun pillBackground(active: Boolean): GradientDrawable = ui.pillBackground(active)
    private fun neonBackground(): NeonBackgroundDrawable = ui.neonBackground()

    private fun setStatus(t: String?) {
        if (t.isNullOrEmpty()) {
            statusText.visibility = View.GONE
        } else {
            statusText.text = t
            statusText.visibility = View.VISIBLE
        }
    }

    // ---------- Albüm kapağı ----------

    private fun showBitmap(view: ImageView, bmp: Bitmap) {
        view.setPadding(0, 0, 0, 0)
        view.scaleType = ImageView.ScaleType.CENTER_CROP
        view.setImageBitmap(bmp)
    }

    private fun loadEmbedded(view: ImageView, song: Song, sizePx: Int, onBitmap: ((Bitmap?) -> Unit)?) {
        val key = "${song.id}:$sizePx"
        view.tag = key
        Covers.load(this, song.id, song.uri, sizePx) { bmp ->
            if (view.tag == key) {
                if (bmp != null) showBitmap(view, bmp)
                onBitmap?.invoke(bmp)
            }
        }
    }

    // Kapak: kullanıcının eklediği albüm resmi, yoksa şarkının kendi kapağı, yoksa neon nota kutusu
    private fun setCover(
        view: ImageView,
        song: Song?,
        sizePx: Int,
        radiusDp: Int,
        iconDp: Int,
        onBitmap: ((Bitmap?) -> Unit)? = null,
        forceKey: String? = null
    ) {
        val r = dp(radiusDp).toFloat()
        view.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(v: View, outline: Outline) {
                outline.setRoundRect(0, 0, v.width, v.height, r)
            }
        }
        view.clipToOutline = true
        view.background = neonGradient(false, radiusDp)
        val pad = ((sizePx - dp(iconDp)) / 2).coerceAtLeast(0)
        view.setPadding(pad, pad, pad, pad)
        view.scaleType = ImageView.ScaleType.FIT_CENTER
        view.setImageDrawable(icon(IconDrawable.NOTE, Color.WHITE, iconDp))
        if (song == null && forceKey == null) {
            view.tag = null
            onBitmap?.invoke(null)
            return
        }
        val artKey: String? = if (forceKey != null && AlbumArt.has(this, forceKey)) {
            forceKey
        } else if (song != null) {
            AlbumArt.keyFor(this, song)
        } else {
            null
        }
        if (artKey != null) {
            val tag = "$artKey:$sizePx"
            view.tag = tag
            AlbumArt.load(this, artKey, sizePx) { bmp ->
                if (view.tag == tag) {
                    if (bmp != null) {
                        showBitmap(view, bmp)
                        onBitmap?.invoke(bmp)
                    } else if (song != null) {
                        loadEmbedded(view, song, sizePx, onBitmap)
                    } else {
                        onBitmap?.invoke(null)
                    }
                }
            }
            return
        }
        if (song != null) loadEmbedded(view, song, sizePx, onBitmap) else onBitmap?.invoke(null)
    }

    private fun currentArtKey(): String? {
        if (mode != 2 || libView != 5) return null
        val byArtist = albumByArtist && !albumViewByTag
        return if (byArtist) AlbumArt.artistKey(selectedGroup) else AlbumArt.albumKey(selectedGroup)
    }

    @Suppress("DEPRECATION")
    private fun pickAlbumCover() {
        val key = currentArtKey() ?: return
        pendingArtKey = key
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType("image/*")
        startActivityForResult(intent, REQ_ALBUM_ART)
    }

    private fun removeAlbumCover() {
        val key = currentArtKey() ?: return
        if (AlbumArt.has(this, key)) {
            AlbumArt.remove(this, key)
            toast("Özel kapak kaldırıldı")
            refreshList()
            updateNowPlayingUi()
        } else {
            toast("Bu albümde özel kapak yok")
        }
    }

    // ---------- Oynatıcı rengi (kapağın baskın rengi) ----------

    private fun dominantColor(src: Bitmap): Int {
        try {
            val soft = if (src.config == Bitmap.Config.HARDWARE) {
                src.copy(Bitmap.Config.ARGB_8888, false)
            } else {
                src
            }
            if (soft == null) return 0
            val s = Bitmap.createScaledBitmap(soft, 24, 24, true)
            val hsv = FloatArray(3)
            val weight = FloatArray(12)
            val rs = FloatArray(12)
            val gs = FloatArray(12)
            val bs = FloatArray(12)
            for (y in 0 until 24) {
                for (x in 0 until 24) {
                    val c = s.getPixel(x, y)
                    Color.colorToHSV(c, hsv)
                    if (hsv[1] < 0.28f || hsv[2] < 0.25f) continue
                    val b = (hsv[0] / 30f).toInt().coerceIn(0, 11)
                    val w = hsv[1] * hsv[2]
                    weight[b] += w
                    rs[b] += Color.red(c) * w
                    gs[b] += Color.green(c) * w
                    bs[b] += Color.blue(c) * w
                }
            }
            var best = -1
            var bw = 0f
            for (i in 0 until 12) {
                if (weight[i] > bw) {
                    bw = weight[i]
                    best = i
                }
            }
            if (best < 0 || bw < 0.5f) return 0
            val c = Color.rgb(
                (rs[best] / weight[best]).toInt(),
                (gs[best] / weight[best]).toInt(),
                (bs[best] / weight[best]).toInt()
            )
            Color.colorToHSV(c, hsv)
            hsv[1] = hsv[1].coerceIn(0.45f, 0.95f)
            hsv[2] = hsv[2].coerceIn(0.70f, 0.95f)
            return Color.HSVToColor(hsv)
        } catch (e: Exception) {
            return 0
        }
    }

    private fun applyAccent(bmp: Bitmap?) {
        accentColor = if (bmp != null) dominantColor(bmp) else 0
        if (accentColor != 0) {
            val base = color(R.color.vortex_background)
            val top = ui.mix(base, accentColor, 0.42f)
            val mid = ui.mix(base, accentColor, 0.14f)
            nowPlayingScreen.background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(top, mid, base)
            )
            val dark = ui.mix(accentColor, Color.BLACK, 0.25f)
            npPlayPause.background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(accentColor, dark)
            ).apply { shape = GradientDrawable.OVAL }
            npSeek.progressTintList = ColorStateList.valueOf(accentColor)
            npSeek.thumbTintList = ColorStateList.valueOf(accentColor)
        } else {
            nowPlayingScreen.background = neonBackground()
            npPlayPause.background = neonGradient(true)
            npSeek.progressTintList = ColorStateList.valueOf(color(R.color.vortex_purple))
            npSeek.thumbTintList = ColorStateList.valueOf(color(R.color.vortex_blue))
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Mor üst çubuğu temadan bağımsız olarak kapatır
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        ui = Ui(this)
        setContentView(R.layout.activity_main)
        actionBar?.hide()

        val vp = getSharedPreferences("vortex", MODE_PRIVATE)
        pitchLink = vp.getBoolean("pitchlink", false)
        albumByArtist = vp.getBoolean("album_by_artist", false)
        albumSort = vp.getInt("album_sort", 0)
        albumManual = ArrayList((vp.getString("album_manual", "") ?: "").split("\n").filter { it.isNotEmpty() })
        loadFavorites()
        loadFolders()

        val root = findViewById<LinearLayout>(R.id.root)
        val headerRow = findViewById<LinearLayout>(R.id.headerRow)
        statusText = findViewById(R.id.statusText)
        searchBox = findViewById(R.id.searchBox)
        pageScroll = findViewById(R.id.pageScroll)
        pageContent = findViewById(R.id.pageContent)
        nowTitle = findViewById(R.id.nowTitle)
        nowArtist = findViewById(R.id.nowArtist)
        miniCover = findViewById(R.id.miniCover)
        songList = findViewById(R.id.songList)
        playPauseButton = findViewById(R.id.playPauseButton)
        val miniPlayer = findViewById<LinearLayout>(R.id.miniPlayer)
        val navBar = findViewById<LinearLayout>(R.id.navBar)
        setStatus(null)

        pages = Pages(HostImpl(), pageContent)
        detailHeader = DetailHeader(ui)
        detailHeader.onBack = { handleBack() }
        detailHeader.onPlay = { playList(shown, 0) }
        detailHeader.onShuffle = { shuffleAll() }
        detailOptionsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(4) }
        }
        detailOptionsRow.addView(ui.actionPill("Sırala ve görünüm") { openListOptions() })
        coverAddBtn = ui.actionPill("Kapak ekle") { pickAlbumCover() }
        coverRemoveBtn = ui.actionPill("Kapağı kaldır") { removeAlbumCover() }
        detailOptionsRow.addView(coverAddBtn)
        detailOptionsRow.addView(coverRemoveBtn)
        detailHeader.view.addView(detailOptionsRow)
        root.addView(detailHeader.view, root.indexOfChild(pageScroll))

        // İstatistik, ses ve kuyruk ekranları
        statsScreen = StatsScreen(ui) { songs }
        audioScreen = AudioScreen(ui) { fx }
        queueScreen = QueueScreen(
            this,
            Palette(
                color(R.color.vortex_purple),
                color(R.color.vortex_blue),
                color(R.color.vortex_text),
                color(R.color.vortex_text_secondary),
                color(R.color.vortex_surface),
                color(R.color.vortex_surface_light)
            ),
            neonBackground()
        )
        val frame = root.parent as FrameLayout
        frame.addView(statsScreen.view)
        frame.addView(audioScreen.view)
        frame.addView(queueScreen.view)

        // Araçlar (yedekleme, görünüm, sözler, yinelenen dosyalar)
        toolHost = ToolHost(this, ui, { songs }, { reloadAfterRestore() }, { restartService() })
        Tools.install(toolHost)

        audioButton = ImageView(this).apply {
            setPadding(dp(11), dp(11), dp(11), dp(11))
            isClickable = true
            isFocusable = true
            layoutParams = LinearLayout.LayoutParams(dp(46), dp(46))
            setImageDrawable(icon(IconDrawable.EQ, color(R.color.vortex_text_secondary), 24))
            setOnClickListener { audioScreen.show() }
        }
        statsButton = ImageView(this).apply {
            setPadding(dp(11), dp(11), dp(11), dp(11))
            isClickable = true
            isFocusable = true
            layoutParams = LinearLayout.LayoutParams(dp(46), dp(46))
            setImageDrawable(icon(IconDrawable.CHART, color(R.color.vortex_text_secondary), 24))
            setOnClickListener { statsScreen.show() }
        }
        headerRow.addView(audioButton)
        headerRow.addView(statsButton)

        // Kütüphane süzgeçleri ve arama süzgeçleri, arama kutusunun altına eklenir
        chipScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(10) }
        }
        chipContainer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        chipScroll.addView(chipContainer)
        root.addView(chipScroll, root.indexOfChild(searchBox) + 1)

        searchChipScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(10) }
        }
        searchChips = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        searchChipScroll.addView(searchChips)
        root.addView(searchChipScroll, root.indexOfChild(searchBox) + 1)

        tabIcons = arrayOf<ImageView>(
            findViewById(R.id.iconHome),
            findViewById(R.id.iconSearch),
            findViewById(R.id.iconLibrary),
            findViewById(R.id.iconDownloads)
        )
        findViewById<View>(R.id.tabHome).setOnClickListener { setMode(0) }
        findViewById<View>(R.id.tabSearch).setOnClickListener { setMode(1) }
        findViewById<View>(R.id.tabLibrary).setOnClickListener { setMode(2) }
        findViewById<View>(R.id.tabDownloads).setOnClickListener { setMode(3) }

        nowPlayingScreen = findViewById(R.id.nowPlayingScreen)
        npArt = findViewById(R.id.npArt)
        npTitle = findViewById(R.id.npTitle)
        npArtist = findViewById(R.id.npArtist)
        npSeek = findViewById(R.id.npSeek)
        npPosition = findViewById(R.id.npPosition)
        npDuration = findViewById(R.id.npDuration)
        npPlayPause = findViewById(R.id.npPlayPause)
        npLikeIcon = findViewById(R.id.npLikeIcon)
        npLikeLabel = findViewById(R.id.npLikeLabel)
        npFolderIcon = findViewById(R.id.npFolderIcon)
        npFolderLabel = findViewById(R.id.npFolderLabel)
        npShuffleIcon = findViewById(R.id.npShuffleIcon)
        npShuffleLabel = findViewById(R.id.npShuffleLabel)
        npRepeatIcon = findViewById(R.id.npRepeatIcon)
        npRepeatLabel = findViewById(R.id.npRepeatLabel)
        npSpeedIcon = findViewById(R.id.npSpeedIcon)
        npSpeedLabel = findViewById(R.id.npSpeedLabel)
        val npClose = findViewById<ImageView>(R.id.npClose)
        val npPrev = findViewById<ImageView>(R.id.npPrev)
        val npNext = findViewById<ImageView>(R.id.npNext)

        // Tam ekran çaların üst satırı: geri, (boşluk), ekolayzır, kuyruk
        queueButton = ImageView(this).apply {
            setPadding(dp(11), dp(11), dp(11), dp(11))
            isClickable = true
            isFocusable = true
            layoutParams = LinearLayout.LayoutParams(dp(46), dp(46))
            setImageDrawable(QueueIconDrawable(color(R.color.vortex_purple), dp(24)))
            setOnClickListener { showQueue() }
        }
        val npEqButton = ImageView(this).apply {
            setPadding(dp(11), dp(11), dp(11), dp(11))
            isClickable = true
            isFocusable = true
            layoutParams = LinearLayout.LayoutParams(dp(46), dp(46))
            setImageDrawable(icon(IconDrawable.EQ, color(R.color.vortex_purple), 24))
            setOnClickListener { audioScreen.show() }
        }
        val closeIndex = nowPlayingScreen.indexOfChild(npClose)
        nowPlayingScreen.removeView(npClose)
        val topRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        npClose.layoutParams = LinearLayout.LayoutParams(dp(46), dp(46))
        topRow.addView(npClose)
        topRow.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        topRow.addView(npEqButton)
        topRow.addView(queueButton)
        nowPlayingScreen.addView(topRow, closeIndex)

        // Büyük albüm kapağı: ekrana göre boyutlanır
        val dm = resources.displayMetrics
        npArtSize = minOf(dm.widthPixels - dp(56), (dm.heightPixels * 0.36f).toInt())
        npArt.layoutParams = LinearLayout.LayoutParams(npArtSize, npArtSize).apply {
            topMargin = dp(8)
        }

        root.background = neonBackground()
        nowPlayingScreen.background = neonBackground()

        root.setOnApplyWindowInsetsListener { v, insets ->
            v.setPadding(
                dp(16),
                insets.systemWindowInsetTop + dp(12),
                dp(16),
                insets.systemWindowInsetBottom + dp(12)
            )
            insets
        }
        nowPlayingScreen.setOnApplyWindowInsetsListener { v, insets ->
            v.setPadding(
                dp(24),
                insets.systemWindowInsetTop + dp(12),
                dp(24),
                insets.systemWindowInsetBottom + dp(20)
            )
            insets
        }

        // Yüzen mini oynatıcı kartı
        miniPlayer.background = GradientDrawable().apply {
            setColor(color(R.color.vortex_surface))
            cornerRadius = dp(22).toFloat()
            setStroke(dp(1), color(R.color.vortex_purple))
        }
        navBar.background = GradientDrawable().apply {
            setColor(color(R.color.vortex_surface))
            cornerRadius = dp(26).toFloat()
            setStroke(dp(1), color(R.color.vortex_surface_light))
        }
        searchBox.background = GradientDrawable().apply {
            setColor(color(R.color.vortex_surface_light))
            cornerRadius = dp(24).toFloat()
        }
        playPauseButton.background = neonGradient(true)

        npClose.setImageDrawable(icon(IconDrawable.CHEVRON, color(R.color.vortex_purple), 24))
        npPlayPause.background = neonGradient(true)
        npPrev.setImageDrawable(icon(IconDrawable.PREV, Color.WHITE, 24, filled = true))
        npNext.setImageDrawable(icon(IconDrawable.NEXT, Color.WHITE, 24, filled = true))
        npSeek.progressTintList = ColorStateList.valueOf(color(R.color.vortex_purple))
        npSeek.thumbTintList = ColorStateList.valueOf(color(R.color.vortex_blue))
        npSeek.progressBackgroundTintList = ColorStateList.valueOf(color(R.color.vortex_surface_light))

        playPauseButton.setOnClickListener { togglePlay() }
        npPlayPause.setOnClickListener { togglePlay() }

        npPrev.setOnClickListener { player?.seekToPrevious() }
        npNext.setOnClickListener { player?.seekToNext() }

        findViewById<View>(R.id.cellLike).setOnClickListener { toggleFavoriteId(currentId()) }
        findViewById<View>(R.id.cellFolder).setOnClickListener {
            if (currentId() >= 0) addToFolderDialog(currentId())
        }
        findViewById<View>(R.id.cellShuffle).setOnClickListener {
            val p = player ?: return@setOnClickListener
            p.shuffleModeEnabled = !p.shuffleModeEnabled
            updatePills()
        }
        findViewById<View>(R.id.cellRepeat).setOnClickListener {
            val p = player ?: return@setOnClickListener
            p.repeatMode = when (p.repeatMode) {
                Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
                Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
                else -> Player.REPEAT_MODE_OFF
            }
            updatePills()
        }
        val cellSpeed = findViewById<View>(R.id.cellSpeed)
        cellSpeed.setOnClickListener {
            if (player == null) return@setOnClickListener
            applySpeed(speeds[(currentSpeedIndex() + 1) % speeds.size])
            updatePills()
        }
        cellSpeed.setOnLongClickListener {
            pitchLink = !pitchLink
            getSharedPreferences("vortex", MODE_PRIVATE).edit()
                .putBoolean("pitchlink", pitchLink)
                .apply()
            applySpeed(speeds[currentSpeedIndex()])
            toast(
                if (pitchLink) "Perde hızla birlikte değişecek (plak gibi)"
                else "Perde sabit kalacak"
            )
            updatePills()
            true
        }

        miniPlayer.setOnClickListener { showNowPlaying() }
        npClose.setOnClickListener { hideNowPlaying() }

        npSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) npPosition.text = fmt(progress.toLong())
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {
                dragging = true
            }

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                dragging = false
                player?.seekTo((seekBar?.progress ?: 0).toLong())
            }
        })

        searchBox.imeOptions = EditorInfo.IME_ACTION_SEARCH
        searchBox.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                RecentSearches.add(this, searchBox.text.toString())
                hideKeyboard()
                true
            } else {
                false
            }
        }
        searchBox.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                refreshList()
            }
        })

        songList.adapter = adapter
        songList.setOnItemClickListener { _, _, position, _ ->
            if (songList.adapter === libAdapter) {
                libRows.getOrNull(position)?.onClick?.invoke()
            } else if (!adapter.isGrid()) {
                playFromShown(position)
            }
        }
        songList.setOnItemLongClickListener { _, _, position, _ ->
            var handled = false
            if (songList.adapter === libAdapter) {
                val row = libRows.getOrNull(position)
                if (libView == 6 && row != null && row.key.isNotEmpty()) {
                    folderOptions(row.key)
                    handled = true
                }
            } else if (!adapter.isGrid()) {
                val song = shown.getOrNull(position)
                if (song != null) {
                    songOptions(song)
                    handled = true
                }
            }
            handled
        }

        updateTabs()
        updatePlayIcons(false)
        updateNowPlayingUi()
        handler.post(ticker)

        // Geri tuşu (Android 13 ve üstü)
        if (Build.VERSION.SDK_INT >= 33) {
            val cb = android.window.OnBackInvokedCallback { handleBack() }
            backCallback = cb
            onBackInvokedDispatcher.registerOnBackInvokedCallback(
                android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT, cb
            )
        }

        // İzinler (Android sürümüne göre doğru müzik izni istenir)
        val needed = mutableListOf<String>()
        if (!permissionOk()) needed.add(audioPermission())
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            needed.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (permissionOk()) loadSongs() else refreshList()
        if (needed.isNotEmpty()) {
            getSharedPreferences("vortex", MODE_PRIVATE).edit()
                .putBoolean("perm_asked", true)
                .apply()
            requestPermissions(needed.toTypedArray(), 1)
        }

        // Arka plan servisini başlat ve ona bağlan
        startService(Intent(this, PlaybackService::class.java))
        bindService(
            Intent(this, PlaybackService::class.java).setAction(PlaybackService.ACTION_LOCAL),
            connection,
            Context.BIND_AUTO_CREATE
        )
    }

    override fun onResume() {
        super.onResume()
        // Ayarlardan izin verip dönünce şarkıları yükle
        if (!loaded && permissionOk()) loadSongs()
    }

    override fun onStop() {
        super.onStop()
        saveSession()
    }

    // ---------- İzin ----------

    private fun askAudioPermission() {
        val prefs = getSharedPreferences("vortex", MODE_PRIVATE)
        val asked = prefs.getBoolean("perm_asked", false)
        if (asked && !shouldShowRequestPermissionRationale(audioPermission())) {
            // Sistem artık sormuyor, ayarlara götür
            try {
                startActivity(
                    Intent(
                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:$packageName")
                    )
                )
                toast("İzinler → Müzik ve ses → İzin ver")
            } catch (e: Exception) {
                toast("Ayarlar açılamadı")
            }
        } else {
            prefs.edit().putBoolean("perm_asked", true).apply()
            requestPermissions(arrayOf(audioPermission()), 1)
        }
    }

    // ---------- Servis ve yedek geri yükleme ----------

    private fun reloadAfterRestore() {
        loadFavorites()
        loadFolders()
        refreshList()
        updatePills()
    }

    private fun restartService() {
        player?.removeListener(playerListener)
        player = null
        fx = null
        fader?.cancel()
        fader = null
        sessionRestored = false
        try {
            unbindService(connection)
        } catch (e: Exception) {
        }
        stopService(Intent(this, PlaybackService::class.java))
        startService(Intent(this, PlaybackService::class.java))
        bindService(
            Intent(this, PlaybackService::class.java).setAction(PlaybackService.ACTION_LOCAL),
            connection,
            Context.BIND_AUTO_CREATE
        )
        updatePlayIcons(false)
        updateNowPlayingUi()
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_ALBUM_ART) {
            val uri = data?.data
            val key = pendingArtKey
            if (resultCode == RESULT_OK && uri != null && key != null) {
                if (AlbumArt.save(this, key, uri)) {
                    toast("Kapak kaydedildi")
                    refreshList()
                    updateNowPlayingUi()
                } else {
                    toast("Resim okunamadı")
                }
            }
            return
        }
        if (audioScreen.handleResult(requestCode, resultCode, data)) return
        for (h in toolHost.resultHandlers) {
            if (h(requestCode, resultCode, data)) return
        }
    }

    // ---------- Çalar ----------

    private fun attachPlayer(p: ExoPlayer) {
        player?.removeListener(playerListener)
        fader?.cancel()
        player = p
        fader = Fader(p)
        p.addListener(playerListener)
        updatePlayIcons(p.isPlaying)
        updateNowPlayingUi()
        restoreSession()
    }

    // Son kuyruğu ve konumu kaydeder
    private fun saveSession() {
        val p = player ?: return
        val n = p.mediaItemCount
        if (n == 0) return
        val limit = minOf(n, 3000)
        val sb = StringBuilder()
        for (i in 0 until limit) {
            if (i > 0) sb.append(',')
            sb.append(p.getMediaItemAt(i).mediaId)
        }
        getSharedPreferences("vortex_session", MODE_PRIVATE).edit()
            .putString("ids", sb.toString())
            .putInt("index", p.currentMediaItemIndex.coerceAtMost(limit - 1))
            .putLong("pos", p.currentPosition.coerceAtLeast(0L))
            .apply()
    }

    // Uygulama yeniden açılınca son kuyruğu duraklatılmış olarak geri yükler
    private fun restoreSession() {
        val p = player ?: return
        if (!loaded || sessionRestored) return
        sessionRestored = true
        if (p.mediaItemCount > 0) return
        val sp = getSharedPreferences("vortex_session", MODE_PRIVATE)
        val ids = (sp.getString("ids", "") ?: "").split(",").mapNotNull { it.toLongOrNull() }
        val list = ids.mapNotNull { byId[it] }
        if (list.isEmpty()) return
        val currentSavedId = ids.getOrNull(sp.getInt("index", 0))
        var idx = list.indexOfFirst { it.id == currentSavedId }
        if (idx < 0) idx = 0
        p.setMediaItems(list.map { toMediaItem(it) }, idx, sp.getLong("pos", 0L))
        p.prepare()
    }

    private fun currentId(): Long =
        player?.currentMediaItem?.mediaId?.toLongOrNull() ?: -1L

    private fun toMediaItem(s: Song): MediaItem = MediaItem.Builder()
        .setMediaId(s.id.toString())
        .setUri(s.uri)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(s.title)
                .setArtist(s.artist)
                .build()
        )
        .build()

    private fun playList(list: List<Song>, position: Int) {
        val p = player ?: return
        if (position !in list.indices) return
        p.setMediaItems(list.map { toMediaItem(it) }, position, 0L)
        p.prepare()
        p.play()
    }

    private fun playFromShown(position: Int) {
        if (mode == 1) RecentSearches.add(this, searchBox.text.toString())
        playList(shown, position)
    }

    private fun shuffleAll() {
        val p = player ?: return
        if (shown.isEmpty()) return
        p.shuffleModeEnabled = true
        playList(shown, Random.nextInt(shown.size))
    }

    private fun playNext(song: Song) {
        val p = player ?: return
        if (p.mediaItemCount == 0) {
            p.setMediaItems(listOf(toMediaItem(song)))
            p.prepare()
            p.play()
        } else {
            p.addMediaItem(p.currentMediaItemIndex + 1, toMediaItem(song))
            toast("Sıradaki olarak eklendi")
        }
    }

    private fun addToQueue(song: Song) {
        val p = player ?: return
        if (p.mediaItemCount == 0) {
            p.setMediaItems(listOf(toMediaItem(song)))
            p.prepare()
            p.play()
        } else {
            p.addMediaItem(toMediaItem(song))
            toast("Kuyruğa eklendi")
        }
    }

    private fun showQueue() {
        val p = player ?: return
        queueScreen.show(p)
    }

    private fun togglePlay() {
        val p = player ?: return
        if (p.mediaItemCount == 0) {
            // Kuyruk boşsa son oturumu dene, yoksa tüm şarkıları çal
            sessionRestored = false
            restoreSession()
            if (p.mediaItemCount > 0) {
                p.play()
            } else if (songs.isNotEmpty()) {
                playList(songs, 0)
            } else {
                toast("Önce bir şarkı seç")
            }
            return
        }
        if (p.isPlaying) {
            p.pause()
        } else {
            if (p.playbackState == Player.STATE_ENDED) p.seekToDefaultPosition(0)
            if (p.playbackState == Player.STATE_IDLE) p.prepare()
            p.play()
        }
    }

    private fun applySpeed(speed: Float) {
        val p = player ?: return
        p.playbackParameters = PlaybackParameters(speed, if (pitchLink) speed else 1f)
    }

    private fun currentSpeedIndex(): Int {
        val cur = player?.playbackParameters?.speed ?: 1f
        val idx = speeds.indexOfFirst { abs(it - cur) < 0.01f }
        return if (idx >= 0) idx else 2
    }

    // ---------- Gezinme ----------

    private fun hideKeyboard() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(searchBox.windowToken, 0)
    }

    private fun setMode(m: Int) {
        mode = m
        searchBox.visibility = if (m == 1) View.VISIBLE else View.GONE
        if (m != 1) hideKeyboard()
        pageScroll.scrollTo(0, 0)
        updateTabs()
        refreshList()
    }

    private fun setLibView(v: Int) {
        libView = v
        refreshList()
    }

    private fun openLibrary(view: Int, smart: Int) {
        mode = 2
        searchBox.visibility = View.GONE
        hideKeyboard()
        if (smart >= 0) {
            selectedGroup = smart.toString()
            libView = 9
        } else {
            libView = view
        }
        pageScroll.scrollTo(0, 0)
        updateTabs()
        refreshList()
    }

    private fun openGroup(view: Int, name: String) {
        mode = 2
        searchBox.visibility = View.GONE
        hideKeyboard()
        selectedGroup = name
        libView = view
        albumViewByTag = view == 5
        pageScroll.scrollTo(0, 0)
        updateTabs()
        refreshList()
    }

    private fun handleBack() {
        if (queueScreen.isOpen) {
            queueScreen.hide()
        } else if (audioScreen.isOpen) {
            audioScreen.hide()
        } else if (statsScreen.isOpen) {
            statsScreen.hide()
        } else if (nowPlayingScreen.visibility == View.VISIBLE) {
            hideNowPlaying()
        } else if (mode == 2 && libView != 0) {
            setLibView(
                when (libView) {
                    3 -> 2
                    5 -> 4
                    7 -> 6
                    9 -> 8
                    else -> 0
                }
            )
        } else if (mode != 0) {
            setMode(0)
        } else {
            finish()
        }
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        handleBack()
    }

    private fun updateTabs() {
        val secondary = color(R.color.vortex_text_secondary)
        for (i in 0..3) {
            val selected = i == mode
            tabIcons[i].setImageDrawable(
                icon(tabKinds[i], if (selected) Color.WHITE else secondary, 24, glow = selected)
            )
        }
    }

    private fun chipIndexFor(view: Int): Int = when (view) {
        0, 10, 11 -> 0
        1 -> 1
        6, 7 -> 2
        8, 9 -> 3
        2, 3 -> 4
        else -> 5
    }

    private fun updateChips() {
        chipContainer.removeAllViews()
        val sel = chipIndexFor(libView)
        chipLabels.forEachIndexed { i, label ->
            val chip = TextView(this).apply {
                text = label
                textSize = 14f
                setPadding(dp(16), dp(8), dp(16), dp(8))
                setTextColor(if (i == sel) Color.WHITE else color(R.color.vortex_text))
                background = pillBackground(i == sel)
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { marginEnd = dp(8) }
                setOnClickListener { setLibView(chipTargets[i]) }
            }
            chipContainer.addView(chip)
        }
    }

    // ---------- Arama süzgeçleri ----------

    private fun updateSearchChips() {
        searchChips.removeAllViews()
        fun addChip(label: String, active: Boolean, onClick: () -> Unit) {
            searchChips.addView(TextView(this).apply {
                text = label
                textSize = 13f
                setPadding(dp(14), dp(7), dp(14), dp(7))
                setTextColor(if (active) Color.WHITE else color(R.color.vortex_text))
                background = pillBackground(active)
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { marginEnd = dp(8) }
                setOnClickListener { onClick() }
            })
        }
        SongSearch.scopeLabels.forEachIndexed { i, l ->
            addChip(l, searchScope == i) {
                searchScope = i
                refreshList()
            }
        }
        searchChips.addView(View(this).apply {
            setBackgroundColor(color(R.color.vortex_surface_light))
            layoutParams = LinearLayout.LayoutParams(dp(1), dp(22)).apply { marginEnd = dp(8) }
        })
        SongSearch.sortLabels.forEachIndexed { i, l ->
            addChip(l, searchSort == i) {
                searchSort = i
                refreshList()
            }
        }
    }

    // ---------- Akıllı listeler ----------

    private fun smartIds(kind: Int): List<Long> =
        SmartLists.build(
            this,
            kind,
            songs.map { it.id },
            songs.associate { it.id to it.added }
        )

    private fun songsFor(kind: Int): List<Song> =
        smartIds(kind).mapNotNull { byId[it] }

    // ---------- Albümler ----------

    private fun albumGroups(): List<Pair<String, List<Song>>> {
        val groups = songs.groupBy { if (albumByArtist) it.artist else it.album }.toList()
        return when (albumSort) {
            1 -> groups.sortedWith(
                compareBy<Pair<String, List<Song>>>(
                    { SongSearch.normalize(it.second.first().artist) },
                    { SongSearch.normalize(it.first) }
                )
            )
            2 -> groups.sortedWith(
                compareByDescending<Pair<String, List<Song>>> { it.second.size }
                    .thenBy { SongSearch.normalize(it.first) }
            )
            3 -> {
                val idx = HashMap<String, Int>()
                albumManual.forEachIndexed { i, n -> idx[n] = i }
                groups.sortedWith(
                    compareBy<Pair<String, List<Song>>>(
                        { idx[it.first] ?: Int.MAX_VALUE },
                        { SongSearch.normalize(it.first) }
                    )
                )
            }
            else -> groups.sortedBy { SongSearch.normalize(it.first) }
        }
    }

    private fun saveAlbumPrefs() {
        getSharedPreferences("vortex", MODE_PRIVATE).edit()
            .putBoolean("album_by_artist", albumByArtist)
            .putInt("album_sort", albumSort)
            .putString("album_manual", albumManual.joinToString("\n"))
            .apply()
    }

    private fun albumModeLabel(): String {
        val s = arrayOf("A-Z", "Sanatçıya göre", "Şarkı sayısına göre", "Elle sıralı")[albumSort.coerceIn(0, 3)]
        val src = if (albumByArtist) "sanatçıya göre albüm" else "dosyadaki albümler"
        return "$s · $src"
    }

    private fun albumOptionsDialog() {
        val items = arrayOf(
            "A-Z",
            "Sanatçıya göre",
            "Şarkı sayısına göre",
            "Elle sırala (↑ ↓ ile)",
            if (albumByArtist) "Kaynak: Dosyadaki albüm adlarını kullan"
            else "Kaynak: Sanatçıya göre (her sanatçının tüm şarkıları tek albümde)"
        )
        AlertDialog.Builder(this)
            .setTitle("Albüm seçenekleri")
            .setItems(items) { _, which ->
                if (which < 4) {
                    albumSort = which
                    if (which == 3 && albumManual.isEmpty()) {
                        albumManual = ArrayList(albumGroups().map { it.first })
                    }
                } else {
                    albumByArtist = !albumByArtist
                    albumManual = ArrayList()
                }
                saveAlbumPrefs()
                refreshList()
            }
            .show()
    }

    private fun moveAlbum(name: String, delta: Int) {
        val names = albumGroups().map { it.first }.toMutableList()
        val i = names.indexOf(name)
        val j = i + delta
        if (i < 0 || j < 0 || j >= names.size) return
        names.removeAt(i)
        names.add(j, name)
        albumManual = ArrayList(names)
        saveAlbumPrefs()
        refreshList()
    }

    // ---------- Liste seçenekleri ve elle sıralama ----------

    private fun openListOptions() {
        if (listKey.isEmpty()) return
        ListOptionsDialog.show(ui, listKey, curDetailTitle) { refreshList() }
    }

    private fun moveSong(position: Int, delta: Int) {
        if (listKey.isEmpty()) return
        ListPrefs.move(this, listKey, shown, position, delta)
        refreshList()
    }

    // ---------- Kütüphane ----------

    private fun buildLibraryRows() {
        libRows.clear()
        when (libView) {
            0 -> {
                if (!permissionOk()) {
                    libRows.add(
                        Row(IconDrawable.PLUS, 0, "Müzik iznini ver", "Şarkılar için gerekli", true, "") {
                            askAudioPermission()
                        }
                    )
                }
                val likedCount = songs.count { favorites.contains(it.id) }
                val artistCount = songs.map { it.artist }.distinct().size
                val albumCount = songs.map { it.album }.distinct().size
                libRows.add(
                    Row(IconDrawable.HEART, 0, "Beğenilen Şarkılar", "$likedCount şarkı", true, "") {
                        setLibView(1)
                    }
                )
                libRows.add(
                    Row(IconDrawable.NOTE, 1, "Tüm şarkılar", "${songs.size} şarkı", true, "") {
                        setLibView(10)
                    }
                )
                libRows.add(
                    Row(
                        IconDrawable.CLOCK, 1, "Akıllı listeler",
                        "En çok dinlenen, unutulanlar ve daha fazlası", true, ""
                    ) {
                        setLibView(8)
                    }
                )
                libRows.add(
                    Row(IconDrawable.FOLDER, 1, "Klasörler", "${folders.size} klasör", true, "") {
                        setLibView(6)
                    }
                )
                libRows.add(
                    Row(IconDrawable.ARTIST, 1, "Sanatçılar", "$artistCount sanatçı", true, "") {
                        setLibView(2)
                    }
                )
                libRows.add(
                    Row(IconDrawable.DISC, 1, "Albümler", "$albumCount albüm", true, "") {
                        albumViewByTag = false
                        setLibView(4)
                    }
                )
                libRows.add(
                    Row(IconDrawable.EQ, 1, "Araçlar", "Görünüm, sözler, yedekleme ve daha fazlası", true, "") {
                        setLibView(11)
                    }
                )
            }
            2 -> {
                songs.groupBy { it.artist }
                    .toList()
                    .sortedBy { it.first.lowercase() }
                    .forEach { (name, list) ->
                        libRows.add(
                            Row(IconDrawable.ARTIST, 2, name, "${list.size} şarkı", true, "") {
                                selectedGroup = name
                                setLibView(3)
                            }
                        )
                    }
            }
            4 -> {
                libRows.add(
                    Row(IconDrawable.EQ, 0, "Albüm seçenekleri", albumModeLabel(), true, "") {
                        albumOptionsDialog()
                    }
                )
                for ((name, list) in albumGroups()) {
                    val sub = if (albumByArtist) "${list.size} şarkı"
                    else "${list.first().artist} · ${list.size} şarkı"
                    libRows.add(
                        Row(IconDrawable.DISC, 1, name, sub, true, name, albumSort == 3, list.first()) {
                            selectedGroup = name
                            albumViewByTag = false
                            setLibView(5)
                        }
                    )
                }
            }
            6 -> {
                libRows.add(
                    Row(IconDrawable.FOLDER_PLUS, 0, "Yeni klasör", "Şarkılarını düzenle", true, "") {
                        askNewFolder()
                    }
                )
                val existing = songs.map { it.id }.toSet()
                folders.keys.sortedBy { it.lowercase() }.forEach { name ->
                    val count = folders[name]?.count { existing.contains(it) } ?: 0
                    libRows.add(
                        Row(IconDrawable.FOLDER, 1, name, "$count şarkı", true, name) {
                            selectedGroup = name
                            setLibView(7)
                        }
                    )
                }
            }
            8 -> {
                for (k in SmartLists.titles.indices) {
                    val count = smartIds(k).size
                    libRows.add(
                        Row(smartIcons[k], 1, SmartLists.titles[k], "$count şarkı", true, "") {
                            selectedGroup = k.toString()
                            setLibView(9)
                        }
                    )
                }
            }
            11 -> {
                for (t in Tools.entries(toolHost)) {
                    libRows.add(Row(t.iconKind, 1, t.title, t.subtitle, true, "") { t.action() })
                }
            }
        }
    }

    private fun bindDetail(title: String) {
        val k = (selectedGroup.toIntOrNull() ?: 0).coerceIn(0, SmartLists.titles.size - 1)
        var sub = if (shown.isEmpty()) {
            when (libView) {
                1 -> "Henüz beğenilen şarkı yok"
                7 -> "Boş. Bir şarkıya uzun basıp klasöre ekleyebilirsin"
                9 -> SmartLists.hints[k]
                else -> "Şarkı yok"
            }
        } else {
            "${shown.size} şarkı"
        }
        if (curOpts.sort != ListPrefs.S_DEFAULT && shown.isNotEmpty()) {
            sub += " · " + ListPrefs.sortLabels[curOpts.sort]
            if (curOpts.reverse && curOpts.sort != ListPrefs.S_MANUAL) sub += " (ters)"
        }
        detailHeader.bind(title, sub)
        val artKey = currentArtKey()
        setCover(detailHeader.cover, shown.firstOrNull(), dp(84), 18, 32, null, artKey)
        coverAddBtn.visibility = if (artKey != null) View.VISIBLE else View.GONE
        coverRemoveBtn.visibility =
            if (artKey != null && AlbumArt.has(this, artKey)) View.VISIBLE else View.GONE
    }

    private fun refreshList() {
        shown.clear()
        var rowsMode = false
        var detail = false
        var detailTitle = ""
        var key = ""
        val q = searchBox.text.toString().trim()
        val usePage = mode == 0 || mode == 3 || (mode == 1 && q.isEmpty())
        when (mode) {
            1 -> {
                if (q.isNotEmpty()) {
                    shown.addAll(SongSearch.search(songs, q, searchScope, searchSort))
                }
            }
            2 -> {
                val byArtist = albumByArtist && !albumViewByTag
                when (libView) {
                    0, 2, 4, 6, 8, 11 -> {
                        rowsMode = true
                        buildLibraryRows()
                    }
                    1 -> {
                        shown.addAll(songs.filter { favorites.contains(it.id) })
                        detail = true
                        detailTitle = "Beğenilen Şarkılar"
                        key = "liked"
                    }
                    3 -> {
                        shown.addAll(songs.filter { it.artist == selectedGroup })
                        detail = true
                        detailTitle = selectedGroup
                        key = "artist:$selectedGroup"
                    }
                    5 -> {
                        shown.addAll(
                            songs.filter {
                                if (byArtist) it.artist == selectedGroup else it.album == selectedGroup
                            }
                        )
                        detail = true
                        detailTitle = selectedGroup
                        key = "album:${if (byArtist) "A" else "B"}:$selectedGroup"
                    }
                    9 -> {
                        shown.addAll(songsFor(selectedGroup.toIntOrNull() ?: 0))
                        detail = true
                        val k = (selectedGroup.toIntOrNull() ?: 0).coerceIn(0, SmartLists.titles.size - 1)
                        detailTitle = SmartLists.titles[k]
                        key = "smart:$selectedGroup"
                    }
                    10 -> {
                        shown.addAll(songs)
                        detail = true
                        detailTitle = "Tüm şarkılar"
                        key = "all"
                    }
                    else -> {
                        val ids = folders[selectedGroup] ?: emptySet<Long>()
                        shown.addAll(songs.filter { ids.contains(it.id) })
                        detail = true
                        detailTitle = selectedGroup
                        key = "folder:$selectedGroup"
                    }
                }
                updateChips()
            }
            else -> {}
        }
        if (detail) {
            listKey = key
            curDetailTitle = detailTitle
            curOpts = ListPrefs.load(this, key)
            if (libView == 5 && curOpts.sort == ListPrefs.S_DEFAULT) {
                shown.sortWith(
                    compareBy<Song>(
                        { if (it.track == 0) Int.MAX_VALUE else it.track },
                        { SongSearch.normalize(it.title) }
                    )
                )
            }
            val ordered = ArrayList(ListPrefs.ordered(this, key, ArrayList(shown), curOpts))
            shown.clear()
            shown.addAll(ordered)
        } else {
            listKey = ""
            curOpts = defaultOpts
        }
        chipScroll.visibility = if (mode == 2 && !detail) View.VISIBLE else View.GONE
        val showSearchChips = mode == 1 && q.isNotEmpty()
        searchChipScroll.visibility = if (showSearchChips) View.VISIBLE else View.GONE
        if (showSearchChips) updateSearchChips()
        detailHeader.view.visibility = if (detail) View.VISIBLE else View.GONE
        if (detail) bindDetail(detailTitle)
        pageScroll.visibility = if (usePage) View.VISIBLE else View.GONE
        songList.visibility = if (usePage) View.GONE else View.VISIBLE
        if (usePage) {
            when (mode) {
                0 -> pages.renderHome()
                1 -> pages.renderSearch()
                3 -> pages.renderDownloads()
            }
        }
        val target: BaseAdapter = if (rowsMode) libAdapter else adapter
        if (songList.adapter !== target) songList.adapter = target
        adapter.notifyDataSetChanged()
        libAdapter.notifyDataSetChanged()
        setStatus(
            when {
                !permissionOk() -> if (usePage) null else "Müzik izni gerekli"
                !loaded -> null
                mode == 1 -> if (q.isEmpty()) null else "${shown.size} sonuç"
                mode == 2 -> when (libView) {
                    0 -> "Kütüphane"
                    2 -> "${libRows.size} sanatçı"
                    4 -> "${(libRows.size - 1).coerceAtLeast(0)} albüm"
                    6 -> "${folders.size} klasör · uzun basarak düzenle"
                    8 -> "Akıllı listeler"
                    11 -> "Araçlar"
                    else -> null
                }
                else -> null
            }
        )
    }

    // ---------- Beğenilenler (telefonda kalıcı saklanır) ----------

    private fun loadFavorites() {
        val saved = getSharedPreferences("vortex", MODE_PRIVATE)
            .getStringSet("favs", emptySet()) ?: emptySet()
        favorites.clear()
        favorites.addAll(saved.mapNotNull { it.toLongOrNull() })
    }

    private fun saveFavorites() {
        getSharedPreferences("vortex", MODE_PRIVATE).edit()
            .putStringSet("favs", favorites.map { it.toString() }.toSet())
            .apply()
    }

    private fun toggleFavoriteId(id: Long) {
        if (id < 0) return
        if (favorites.contains(id)) favorites.remove(id) else favorites.add(id)
        saveFavorites()
        updatePills()
        refreshList()
    }

    // ---------- Klasörler (telefonda kalıcı saklanır) ----------

    private fun loadFolders() {
        val prefs = getSharedPreferences("vortex", MODE_PRIVATE)
        folders.clear()
        val names = prefs.getStringSet("folders", emptySet()) ?: emptySet()
        names.sorted().forEach { n ->
            val ids = prefs.getStringSet("f_$n", emptySet()) ?: emptySet()
            folders[n] = ids.mapNotNull { it.toLongOrNull() }.toMutableSet()
        }
    }

    private fun saveFolders(removed: List<String> = emptyList()) {
        val e = getSharedPreferences("vortex", MODE_PRIVATE).edit()
        e.putStringSet("folders", folders.keys.toSet())
        folders.forEach { (n, ids) ->
            e.putStringSet("f_$n", ids.map { it.toString() }.toSet())
        }
        removed.forEach { e.remove("f_$it") }
        e.apply()
    }

    private fun createFolder(name: String): Boolean {
        if (folders.containsKey(name)) {
            toast("Bu isimde bir klasör zaten var")
            return false
        }
        folders[name] = mutableSetOf()
        saveFolders()
        return true
    }

    private fun askFolderName(title: String, initial: String, onOk: (String) -> Unit) {
        val input = EditText(this).apply {
            setText(initial)
            setSelection(text.length)
            setSingleLine(true)
            hint = "Klasör adı"
        }
        AlertDialog.Builder(this)
            .setTitle(title)
            .setView(input)
            .setPositiveButton("Tamam") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isNotEmpty()) onOk(name)
            }
            .setNegativeButton("Vazgeç", null)
            .show()
    }

    private fun askNewFolder() {
        askFolderName("Yeni klasör", "") { n ->
            if (createFolder(n)) refreshList()
        }
    }

    private fun renameFolder(old: String) {
        askFolderName("Klasörü yeniden adlandır", old) { n ->
            if (n == old) return@askFolderName
            if (folders.containsKey(n)) {
                toast("Bu isimde bir klasör zaten var")
                return@askFolderName
            }
            val ids = folders.remove(old) ?: return@askFolderName
            folders[n] = ids
            saveFolders(listOf(old))
            refreshList()
        }
    }

    private fun deleteFolder(name: String) {
        AlertDialog.Builder(this)
            .setTitle("\"$name\" silinsin mi?")
            .setMessage("Şarkılar telefondan silinmez, sadece klasör kalkar.")
            .setPositiveButton("Sil") { _, _ ->
                folders.remove(name)
                saveFolders(listOf(name))
                refreshList()
            }
            .setNegativeButton("Vazgeç", null)
            .show()
    }

    private fun folderOptions(name: String) {
        val items = arrayOf("Yeniden adlandır", "Sil")
        AlertDialog.Builder(this)
            .setTitle(name)
            .setItems(items) { _, which ->
                if (which == 0) renameFolder(name) else deleteFolder(name)
            }
            .show()
    }

    private fun addToFolderDialog(songId: Long) {
        if (songId < 0) return
        val names = folders.keys.sortedBy { it.lowercase() }
        val items = (listOf("+ Yeni klasör") + names).toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Klasöre ekle")
            .setItems(items) { _, which ->
                if (which == 0) {
                    askFolderName("Yeni klasör", "") { n ->
                        if (createFolder(n)) {
                            folders[n]?.add(songId)
                            saveFolders()
                            toast("\"$n\" klasörüne eklendi")
                            refreshList()
                        }
                    }
                } else {
                    val n = names[which - 1]
                    folders[n]?.add(songId)
                    saveFolders()
                    toast("\"$n\" klasörüne eklendi")
                    refreshList()
                }
            }
            .show()
    }

    private fun songOptions(song: Song) {
        val idx = shown.indexOf(song)
        val manual = listKey.isNotEmpty() && curOpts.sort == ListPrefs.S_MANUAL && idx >= 0
        val items = mutableListOf(
            "Sıradaki olarak çal",
            "Kuyruğa ekle",
            "Klasöre ekle",
            if (favorites.contains(song.id)) "Beğenmeyi kaldır" else "Beğen"
        )
        if (mode == 2 && libView == 7) items.add("Klasörden çıkar")
        if (manual) {
            items.add("En üste taşı")
            items.add("En alta taşı")
        }
        AlertDialog.Builder(this)
            .setTitle(song.title)
            .setItems(items.toTypedArray()) { _, which ->
                when (items[which]) {
                    "Sıradaki olarak çal" -> playNext(song)
                    "Kuyruğa ekle" -> addToQueue(song)
                    "Klasöre ekle" -> addToFolderDialog(song.id)
                    "Klasörden çıkar" -> {
                        folders[selectedGroup]?.remove(song.id)
                        saveFolders()
                        refreshList()
                    }
                    "En üste taşı" -> {
                        ListPrefs.moveToEdge(this, listKey, shown, idx, true)
                        refreshList()
                    }
                    "En alta taşı" -> {
                        ListPrefs.moveToEdge(this, listKey, shown, idx, false)
                        refreshList()
                    }
                    else -> toggleFavoriteId(song.id)
                }
            }
            .show()
    }

    // ---------- Arayüz güncellemeleri ----------

    private fun updateNowPlayingUi() {
        val meta = player?.currentMediaItem?.mediaMetadata
        val t = meta?.title?.toString()
        val a = meta?.artist?.toString()
        nowTitle.text = t ?: "Çalan şarkı yok"
        nowArtist.text = a ?: "Bir şarkıya dokun"
        npTitle.text = t ?: "Çalan şarkı yok"
        npArtist.text = a ?: ""
        val cur = byId[currentId()]
        setCover(miniCover, cur, dp(44), 12, 22)
        setCover(npArt, cur, npArtSize, 28, 80, { bmp -> applyAccent(bmp) })
        updatePills()
        adapter.notifyDataSetChanged()
    }

    private fun styleCell(
        iconView: ImageView,
        label: TextView,
        kind: Int,
        active: Boolean,
        text: String,
        fillIcon: Boolean = false
    ) {
        iconView.background = circleBackground(active)
        iconView.setImageDrawable(
            icon(kind, if (active) Color.WHITE else color(R.color.vortex_text), 22, filled = fillIcon && active)
        )
        label.text = text
        label.setTextColor(
            if (active) color(R.color.vortex_blue) else color(R.color.vortex_text_secondary)
        )
    }

    private fun updatePills() {
        val p = player
        val liked = favorites.contains(currentId())
        styleCell(
            npLikeIcon, npLikeLabel, IconDrawable.HEART, liked,
            if (liked) "Beğenildi" else "Beğen", true
        )
        styleCell(npFolderIcon, npFolderLabel, IconDrawable.FOLDER_PLUS, false, "Klasör")

        val shuffleOn = p?.shuffleModeEnabled == true
        styleCell(npShuffleIcon, npShuffleLabel, IconDrawable.SHUFFLE, shuffleOn, "Karıştır")

        val repeatMode = p?.repeatMode ?: Player.REPEAT_MODE_OFF
        styleCell(
            npRepeatIcon, npRepeatLabel, IconDrawable.REPEAT,
            repeatMode != Player.REPEAT_MODE_OFF,
            when (repeatMode) {
                Player.REPEAT_MODE_ALL -> "Hepsi"
                Player.REPEAT_MODE_ONE -> "Tek şarkı"
                else -> "Döngü"
            }
        )

        val speed = speeds[currentSpeedIndex()]
        styleCell(
            npSpeedIcon, npSpeedLabel, IconDrawable.CLOCK, speed != 1.0f,
            if (pitchLink && speed != 1.0f) "${speed}x ♪" else "${speed}x"
        )
    }

    private fun updatePlayIcons(isPlaying: Boolean) {
        val kind = if (isPlaying) IconDrawable.PAUSE else IconDrawable.PLAY
        playPauseButton.setImageDrawable(icon(kind, Color.WHITE, 24, filled = true))
        npPlayPause.setImageDrawable(icon(kind, Color.WHITE, 32, filled = true))
    }

    private fun showNowPlaying() {
        if (nowPlayingScreen.visibility == View.VISIBLE) return
        updateNowPlayingUi()
        nowPlayingScreen.visibility = View.VISIBLE
    }

    private fun hideNowPlaying() {
        nowPlayingScreen.visibility = View.GONE
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (permissionOk()) {
            if (!loaded) loadSongs()
        } else {
            refreshList()
        }
    }

    private fun loadSongs() {
        songs.clear()
        val pathColumn = if (Build.VERSION.SDK_INT >= 29) {
            MediaStore.Audio.Media.RELATIVE_PATH
        } else {
            @Suppress("DEPRECATION")
            MediaStore.Audio.Media.DATA
        }
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.DATE_ADDED,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.YEAR,
            MediaStore.Audio.Media.TRACK,
            MediaStore.Audio.Media.DATE_MODIFIED,
            MediaStore.Audio.Media.DISPLAY_NAME,
            pathColumn
        )
        val cursor = contentResolver.query(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            projection,
            "${MediaStore.Audio.Media.IS_MUSIC} != 0",
            null,
            "${MediaStore.Audio.Media.TITLE} COLLATE NOCASE ASC"
        )
        cursor?.use {
            while (it.moveToNext()) {
                songs.add(
                    Song(
                        it.getLong(0),
                        it.getString(1) ?: "Bilinmeyen",
                        it.getString(2) ?: "Bilinmeyen sanatçı",
                        it.getString(3) ?: "Bilinmeyen albüm",
                        it.getLong(4),
                        it.getLong(5),
                        it.getInt(6),
                        it.getInt(7),
                        it.getLong(8),
                        it.getString(9) ?: "",
                        it.getString(10) ?: ""
                    )
                )
            }
        }
        byId.clear()
        for (s in songs) byId[s.id] = s
        loaded = true
        refreshList()
        updateNowPlayingUi()
        restoreSession()
    }

    // ---------- Liste satırları ----------

    private fun arrow(symbol: String, onClick: () -> Unit): TextView = TextView(this).apply {
        text = symbol
        textSize = 18f
        gravity = Gravity.CENTER
        setTextColor(color(R.color.vortex_purple))
        layoutParams = LinearLayout.LayoutParams(dp(40), dp(44))
        setOnClickListener { onClick() }
    }

    private fun buildRowView(row: Row): View {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(8), dp(10), dp(8))
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        val iconView = ImageView(this)
        iconView.layoutParams = LinearLayout.LayoutParams(dp(56), dp(56))
        if (row.coverSong != null) {
            setCover(iconView, row.coverSong, dp(56), 14, 26)
        } else {
            val iconBg: GradientDrawable = when (row.iconStyle) {
                0 -> neonGradient(false, 14)
                2 -> GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(color(R.color.vortex_surface_light))
                }
                else -> GradientDrawable().apply {
                    setColor(color(R.color.vortex_surface_light))
                    cornerRadius = dp(14).toFloat()
                }
            }
            iconView.setPadding(dp(15), dp(15), dp(15), dp(15))
            iconView.background = iconBg
            iconView.setImageDrawable(
                icon(row.kind, if (row.iconStyle == 0) Color.WHITE else color(R.color.vortex_purple), 26)
            )
        }

        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                .apply { marginStart = dp(14) }
        }

        val title = TextView(this).apply {
            text = row.title
            textSize = 16f
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setTextColor(
                if (row.enabled) color(R.color.vortex_text) else color(R.color.vortex_text_secondary)
            )
        }

        val subtitle = TextView(this).apply {
            text = row.subtitle
            textSize = 13f
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setTextColor(color(R.color.vortex_text_secondary))
        }

        column.addView(title)
        column.addView(subtitle)
        box.addView(iconView)
        box.addView(column)
        if (row.movable) {
            box.addView(arrow("↑") { moveAlbum(row.key, -1) })
            box.addView(arrow("↓") { moveAlbum(row.key, 1) })
        }
        return box
    }

    private inner class RowAdapter : BaseAdapter() {
        override fun getCount() = libRows.size
        override fun getItem(position: Int) = libRows[position]
        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            return buildRowView(libRows[position])
        }
    }

    private inner class SongAdapter : BaseAdapter() {

        private fun cols(): Int = when (curOpts.layout) {
            ListPrefs.L_GRID3 -> 3
            ListPrefs.L_GRID2 -> 2
            else -> 1
        }

        fun isGrid(): Boolean = cols() > 1

        override fun getCount(): Int {
            val c = cols()
            return (shown.size + c - 1) / c
        }

        override fun getItem(position: Int): Any = position
        override fun getItemId(position: Int): Long = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val c = cols()
            return if (c == 1) listRow(position) else gridRow(position, c)
        }

        private fun listRow(position: Int): View {
            val song = shown[position]
            val isCurrent = song.id == currentId()
            val layout = curOpts.layout
            val coverDp = when (layout) {
                ListPrefs.L_COMPACT -> 36
                ListPrefs.L_SMALL -> 44
                ListPrefs.L_NOIMG -> 0
                else -> 52
            }
            val titleSize = when (layout) {
                ListPrefs.L_COMPACT -> 14f
                ListPrefs.L_SMALL -> 15f
                else -> 16f
            }
            val artistSize = when (layout) {
                ListPrefs.L_COMPACT -> 11f
                ListPrefs.L_SMALL -> 12f
                else -> 13f
            }
            val vPad = when (layout) {
                ListPrefs.L_COMPACT -> 3
                ListPrefs.L_SMALL -> 6
                else -> 8
            }

            val row = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(8), dp(vPad), dp(10), dp(vPad))
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
                if (isCurrent) {
                    background = GradientDrawable().apply {
                        setColor(color(R.color.vortex_surface_light))
                        cornerRadius = dp(18).toFloat()
                    }
                }
            }

            if (coverDp > 0) {
                val cover = ImageView(this@MainActivity)
                cover.layoutParams = LinearLayout.LayoutParams(dp(coverDp), dp(coverDp))
                setCover(cover, song, dp(coverDp), 12, coverDp / 2)
                row.addView(cover)
            }

            val column = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    .apply { marginStart = if (coverDp > 0) dp(12) else dp(6) }
            }
            column.addView(TextView(this@MainActivity).apply {
                text = song.title
                textSize = titleSize
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                setTextColor(if (isCurrent) color(R.color.vortex_blue) else color(R.color.vortex_text))
            })
            if (!curOpts.titlesOnly) {
                column.addView(TextView(this@MainActivity).apply {
                    text = song.artist
                    textSize = artistSize
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                    setTextColor(color(R.color.vortex_text_secondary))
                })
            }
            row.addView(column)

            if (favorites.contains(song.id)) {
                row.addView(ImageView(this@MainActivity).apply {
                    setImageDrawable(icon(IconDrawable.HEART, color(R.color.vortex_purple), 18, filled = true))
                    layoutParams = LinearLayout.LayoutParams(dp(22), dp(22)).apply {
                        marginStart = dp(8)
                    }
                })
            }
            if (listKey.isNotEmpty() && curOpts.sort == ListPrefs.S_MANUAL) {
                row.addView(arrow("↑") { moveSong(position, -1) })
                row.addView(arrow("↓") { moveSong(position, 1) })
            }
            return row
        }

        private fun gridRow(position: Int, cols: Int): View {
            val row = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(dp(0), dp(6), dp(0), dp(6))
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            }
            val screenW = resources.displayMetrics.widthPixels
            val cellPx = ((screenW - dp(32)) / cols - dp(8)).coerceAtLeast(dp(60))
            for (j in 0 until cols) {
                val idx = position * cols + j
                val lp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    .apply {
                        marginStart = dp(4)
                        marginEnd = dp(4)
                    }
                if (idx < shown.size) row.addView(gridCell(idx, cellPx), lp)
                else row.addView(View(this@MainActivity), lp)
            }
            return row
        }

        private fun gridCell(idx: Int, cellPx: Int): View {
            val song = shown[idx]
            val isCurrent = song.id == currentId()
            val cell = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                isClickable = true
                setOnClickListener { playFromShown(idx) }
                setOnLongClickListener {
                    songOptions(song)
                    true
                }
            }
            val cover = ImageView(this@MainActivity)
            cover.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, cellPx)
            setCover(cover, song, cellPx, 14, 36)
            cell.addView(cover)
            cell.addView(TextView(this@MainActivity).apply {
                text = song.title
                textSize = 13f
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                setTextColor(if (isCurrent) color(R.color.vortex_blue) else color(R.color.vortex_text))
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(6) }
            })
            if (!curOpts.titlesOnly) {
                cell.addView(TextView(this@MainActivity).apply {
                    text = song.artist
                    textSize = 11f
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                    setTextColor(color(R.color.vortex_text_secondary))
                })
            }
            return cell
        }
    }

    override fun onDestroy() {
        handler.removeCallbacks(ticker)
        saveSession()
        queueScreen.hide()
        fader?.cancel()
        fader = null
        player?.removeListener(playerListener)
        player = null
        fx = null
        if (Build.VERSION.SDK_INT >= 33) {
            (backCallback as? android.window.OnBackInvokedCallback)?.let {
                onBackInvokedDispatcher.unregisterOnBackInvokedCallback(it)
            }
            backCallback = null
        }
        try {
            unbindService(connection)
        } catch (e: Exception) {
        }
        super.onDestroy()
    }

    companion object {
        private const val REQ_ALBUM_ART = 601
    }
}