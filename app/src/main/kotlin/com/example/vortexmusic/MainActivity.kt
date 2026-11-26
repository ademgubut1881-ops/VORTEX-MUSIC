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
import android.graphics.Typeface
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
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.animation.OvershootInterpolator
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
 private class Row(
  val kind: Int,
  val iconStyle: Int,
  val title: String,
  val subtitle: String,
  val enabled: Boolean,
  val key: String,
  val movable: Boolean = false,
  val coverSong: Song? = null,
  val coverKey: String? = null,
  val onClick: () -> Unit
 )
 private val songs = mutableListOf<Song>()
 private val libSongs get() = songs.filter { it.docUri == null }
 private val vortexSongs get() = songs.filter { it.docUri != null }
 private val byId = HashMap<Long, Song>()
 private val shown = mutableListOf<Song>()
 private val libRows = mutableListOf<Row>()
 private val folders = linkedMapOf<String, MutableSet<Long>>()
 private var loaded = false
 private var mode = 0 // 0 ana sayfa, 1 ara, 2 kütüphane, 3 indirilenler
 private var libView = 0
 private var selectedGroup = ""
 private var artistsViewShown = false
 private var searchScope = SongSearch.SCOPE_ALL
 private var searchSort = SongSearch.SORT_BEST
 private var npArtSize = 0
 private var topIconRefresh: ((Int) -> Unit)? = null
 private var ringView: VortexRingView? = null
 private var pitchLink = false
 private var player: ExoPlayer? = null
 private var fx: AudioFxController? = null
 private var fader: Fader? = null
 private val adapter = SongAdapter()
 private val libAdapter = RowAdapter()
 private var albumByArtist = false
 private var albumSort = 0
 private var albumManual = ArrayList<String>()
 private var albumViewByTag = false
 private var pendingArtKey: String? = null
 private val defaultOpts = ListPrefs.Opts(0, false, false, 0)
 private var curOpts = defaultOpts
 private var listKey = ""
 private var curDetailTitle = ""
 private var sessionRestored = false
 private var tickCount = 0
 private var accentColor = 0
 private val favorites = mutableSetOf<Long>()
 private val speeds = floatArrayOf(0.75f, 0.9f, 1.0f, 1.1f, 1.25f, 1.5f)
 private val chipLabels: Array<String>
  get() = arrayOf(
  tx("Tümü", "All"), tx("Beğenilenler", "Liked"), tx("Klasörler", "Folders"),
  tx("Akıllı", "Smart"), tx("Sanatçılar", "Artists"), tx("Albümler", "Albums")
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
 private lateinit var vortexScanBtn: TextView
 private lateinit var statsScreen: StatsScreen
 private lateinit var audioScreen: AudioScreen
 private lateinit var queueScreen: QueueScreen
 private lateinit var settingsScreen: SettingsScreen
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
 private var navLabels: NavLabels? = null
 private var miniBg: MiniPlayerBg? = null
 private var waveBars: WaveBarsDrawable? = null
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
   if (p != null) {
    val d0 = p.duration
    if (d0 > 0) miniBg?.setProgress(p.currentPosition.toFloat() / d0.toFloat())
   }
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
 private fun audioPermission(): String =
  if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO
  else Manifest.permission.READ_EXTERNAL_STORAGE
 private fun permissionOk(): Boolean =
  checkSelfPermission(audioPermission()) == PackageManager.PERMISSION_GRANTED
 private fun dp(v: Int): Int = ui.dp(v)
 private fun color(id: Int): Int = ui.color(id)
 private fun toast(msg: String) = ui.toast(msg)
 private fun nSongs(n: Int) = tx("$n şarkı", if (n == 1) "1 song" else "$n songs")
 private fun nArtists(n: Int) = tx("$n sanatçı", if (n == 1) "1 artist" else "$n artists")
 private fun nAlbums(n: Int) = tx("$n albüm", if (n == 1) "1 album" else "$n albums")
 private fun nFolders(n: Int) = tx("$n klasör", if (n == 1) "1 folder" else "$n folders")
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
  if (mode == 2 && libView == 3) return AlbumArt.artistKey(selectedGroup)
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
   toast(tx("Özel kapak kaldırıldı", "Custom cover removed"))
   refreshList()
   updateNowPlayingUi()
  } else {
   toast(tx("Bu albümde özel kapak yok", "This album has no custom cover"))
  }
 }
 private fun dominantColor(src: Bitmap): Int = ColorUtil.dominant(src)
 private fun applyAccent(bmp: Bitmap?) {
  accentColor = if (bmp != null) dominantColor(bmp) else 0
  updateRingColors()
  if (accentColor != 0) {
   val base = color(R.color.vortex_background)
   val top = ui.mix(base, accentColor, 0.42f)
   val mid = ui.mix(base, accentColor, 0.14f)
   nowPlayingScreen.background = GradientDrawable(
    GradientDrawable.Orientation.TOP_BOTTOM,
    intArrayOf(top, mid, base)
   )
   topIconRefresh?.invoke(color(R.color.vortex_purple))
   npPlayPause.background = neonGradient(false, 40)
   npSeek.progressTintList = ColorStateList.valueOf(color(R.color.vortex_purple))
   npSeek.thumbTintList = ColorStateList.valueOf(color(R.color.vortex_purple))
  } else {
   nowPlayingScreen.background = neonBackground()
   topIconRefresh?.invoke(color(R.color.vortex_purple))
   npPlayPause.background = neonGradient(false, 40)
   npSeek.progressTintList = ColorStateList.valueOf(color(R.color.vortex_purple))
   npSeek.thumbTintList = ColorStateList.valueOf(color(R.color.vortex_blue))
  }
 }
 override fun onCreate(savedInstanceState: Bundle?) {
  super.onCreate(savedInstanceState)
  I18n.load(this)
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
   gravity = Gravity.END
   layoutParams = LinearLayout.LayoutParams(
    ViewGroup.LayoutParams.MATCH_PARENT,
    ViewGroup.LayoutParams.WRAP_CONTENT
   ).apply { bottomMargin = dp(4) }
  }
  vortexScanBtn = ui.chipButton(tx("Tara", "Scan"), false) { PrivateLibrary.folderMenu(toolHost) }
  vortexScanBtn.visibility = View.GONE
  detailOptionsRow.addView(vortexScanBtn)
  detailOptionsRow.addView(ui.chipButton(tx("Sırala", "Sort"), true) { openListOptions() })
  coverAddBtn = ui.chipButton(tx("Kapak ekle", "Add cover"), false) { pickAlbumCover() }
  coverRemoveBtn = ui.chipButton(tx("Kapağı kaldır", "Remove cover"), false) { removeAlbumCover() }
  detailOptionsRow.addView(coverAddBtn)
  detailOptionsRow.addView(coverRemoveBtn)
  detailHeader.view.addView(detailOptionsRow)
  root.addView(detailHeader.view, root.indexOfChild(pageScroll))
  statsScreen = StatsScreen(ui) { songs }
  audioScreen = AudioScreen(ui) { fx }
  val pal = Palette(
   color(R.color.vortex_purple),
   color(R.color.vortex_blue),
   color(R.color.vortex_text),
   color(R.color.vortex_text_secondary),
   color(R.color.vortex_surface),
   color(R.color.vortex_surface_light)
  )
  queueScreen = QueueScreen(this, pal, neonBackground()) { iv, id -> setCover(iv, byId[id], dp(48), 12, 22) }
  settingsScreen = SettingsScreen(
   this, pal, neonBackground(),
   SettingsActions(
    { audioScreen.show(); audioScreen.view.bringToFront() }, { statsScreen.show(); statsScreen.view.bringToFront() },
    { ringDialog() }, { ringLabel(RingPrefs.load(this)) },
    { ringColorDialog() },
    { when (RingPrefs.loadColor(this)) { 0 -> tx("Albüm rengi", "Album color"); 1 -> tx("Vortex rengi (sabit)", "Vortex color (fixed)"); else -> tx("Gökkuşağı", "Rainbow") } },
    { separatorDialog() }, { refreshList() }, { Tools.entries(toolHost) }
   )
  )
  val frame = root.parent as FrameLayout
  frame.addView(statsScreen.view)
  frame.addView(audioScreen.view)
  frame.addView(queueScreen.view)
  frame.addView(settingsScreen.view)
  toolHost = ToolHost(this, ui, { songs }, { reloadAfterRestore() }, { restartService() })
  Tools.install(toolHost)
  ArtistGroups.load(this)
  SmartSort.load(this)
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
  val settingsButton = ImageView(this).apply {
   setPadding(dp(11), dp(11), dp(11), dp(11))
   isClickable = true
   isFocusable = true
   layoutParams = LinearLayout.LayoutParams(dp(46), dp(46))
   contentDescription = tx("Ayarlar", "Settings")
   setImageDrawable(icon(IconDrawable.GEAR, color(R.color.vortex_text_secondary), 24))
   setOnClickListener { settingsScreen.show() }
  }
  headerRow.addView(settingsButton)
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
  findViewById<View>(R.id.tabDownloads).visibility = View.GONE
  navLabels = NavLabels(
   this,
   intArrayOf(R.id.tabHome, R.id.tabSearch, R.id.tabLibrary),
   arrayOf(tx("Ana sayfa", "Home"), tx("Ara", "Search"), tx("Kütüphane", "Library"))
  )
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
  statusText.text = tx("Şarkılar yükleniyor...", "Loading songs...")
  searchBox.hint = tx("Şarkı ya da sanatçı ara", "Search songs or artists")
  nowTitle.text = tx("Çalan şarkı yok", "Nothing playing")
  nowArtist.text = tx("Bir şarkıya dokun", "Tap a song")
  npTitle.text = tx("Çalan şarkı yok", "Nothing playing")
  findViewById<View>(R.id.tabHome).contentDescription = tx("Ana Sayfa", "Home")
  findViewById<View>(R.id.tabSearch).contentDescription = tx("Ara", "Search")
  findViewById<View>(R.id.tabLibrary).contentDescription = tx("Kütüphane", "Library")
  findViewById<View>(R.id.tabDownloads).contentDescription = tx("İndirilenler", "Downloads")
  val npClose = findViewById<ImageView>(R.id.npClose)
  val npPrev = findViewById<ImageView>(R.id.npPrev)
  val npNext = findViewById<ImageView>(R.id.npNext)
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
  val npGearButton = ImageView(this).apply {
   setPadding(dp(11), dp(11), dp(11), dp(11))
   isClickable = true
   isFocusable = true
   layoutParams = LinearLayout.LayoutParams(dp(46), dp(46))
   contentDescription = tx("Ayarlar", "Settings")
   setImageDrawable(icon(IconDrawable.GEAR, color(R.color.vortex_purple), 24))
   setOnClickListener { quickSettings() }
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
  npClose.visibility = View.GONE
  topRow.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
  topRow.addView(npGearButton)
  topRow.addView(npEqButton)
  nowPlayingScreen.addView(topRow, closeIndex)
  val queueHandle = LinearLayout(this).apply {
   orientation = LinearLayout.VERTICAL
   gravity = Gravity.CENTER
   isClickable = true
   layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(64))
   var startY = 0f
   setOnTouchListener { v, e ->
    when (e.actionMasked) {
     android.view.MotionEvent.ACTION_DOWN -> {
      startY = e.rawY
      true
     }
     android.view.MotionEvent.ACTION_UP -> {
      val dy = startY - e.rawY
      if (dy > dp(24) || Math.abs(dy) < dp(8)) showQueue()
      true
     }
     else -> true
    }
   }
  }
  queueHandle.addView(View(this).apply {
   background = GradientDrawable().apply {
    setColor((color(R.color.vortex_text_secondary) and 0x00FFFFFF) or 0x99000000.toInt())
    cornerRadius = dp(2).toFloat()
   }
   layoutParams = LinearLayout.LayoutParams(dp(40), dp(4))
  })
  queueHandle.addView(TextView(this).apply {
   text = tx("Sıradaki", "Up next")
   textSize = 12f
   typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
   setTextColor(color(R.color.vortex_text_secondary))
   layoutParams = LinearLayout.LayoutParams(
    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
   ).apply { topMargin = dp(6) }
  })
  nowPlayingScreen.addView(queueHandle)
  val dm = resources.displayMetrics
  val frameSize = minOf(dm.widthPixels - dp(44), (dm.heightPixels * 0.40f).toInt())
  val ringPad = dp(14)
  npArtSize = frameSize - 2 * ringPad
  val artFrame = FrameLayout(this)
  artFrame.layoutParams = LinearLayout.LayoutParams(frameSize, frameSize).apply { topMargin = dp(8) }
  val ring = VortexRingView(this, ringPad.toFloat(), dp(28).toFloat(), color(R.color.vortex_purple), color(R.color.vortex_blue))
  ring.applyStyle(RingPrefs.load(this))
  ringView = ring
  ring.levelSource = { fx?.eqProc?.level ?: 0f }
  ring.beatSource = { fx?.eqProc?.beatCount ?: 0 }
  ring.spectrumSource = { fx?.eqProc?.spectrum }
  artFrame.addView(ring, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
  val artParent = npArt.parent as ViewGroup
  val artIndex = artParent.indexOfChild(npArt)
  artParent.removeView(npArt)
  npArt.layoutParams = FrameLayout.LayoutParams(npArtSize, npArtSize).apply { gravity = Gravity.CENTER }
  artFrame.addView(npArt)
  artParent.addView(artFrame, artIndex)
  val gestures = CoverGestures(this, nowPlayingScreen, { player }, { hideNowPlaying() })
  gestures.attachCover(artFrame)
  gestures.attachScreen()
  val artAt = nowPlayingScreen.indexOfChild(artFrame)
  val titleAt = nowPlayingScreen.indexOfChild(findViewById<View>(R.id.npTitleRow))
  if (artAt >= 0 && titleAt > artAt) {
   nowPlayingScreen.addView(View(this), titleAt, LinearLayout.LayoutParams(1, 0, 1f))
   nowPlayingScreen.addView(View(this), artAt, LinearLayout.LayoutParams(1, 0, 1f))
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
  val miniDrawable = MiniPlayerBg(
   color(R.color.vortex_surface), color(R.color.vortex_purple),
   color(R.color.vortex_purple), dp(22).toFloat(), dp(1).toFloat()
  )
  miniBg = miniDrawable
  miniPlayer.background = miniDrawable
  navBar.background = GradientDrawable().apply {
   setColor(ui.mix(color(R.color.vortex_surface), color(R.color.vortex_purple), 0.22f))
   cornerRadius = dp(32).toFloat()
   setStroke(dp(1), (color(R.color.vortex_purple) and 0x00FFFFFF) or 0x66000000)
  }
  navBar.elevation = dp(8).toFloat()
  navBar.setPadding(dp(6), dp(4), dp(6), dp(4))
  searchBox.background = GradientDrawable().apply {
   setColor(color(R.color.vortex_surface_light))
   cornerRadius = dp(24).toFloat()
  }
  playPauseButton.background = neonGradient(true)
  topIconRefresh = { c ->
   npClose.setImageDrawable(icon(IconDrawable.CHEVRON, c, 24))
   npGearButton.setImageDrawable(icon(IconDrawable.GEAR, c, 24))
   npEqButton.setImageDrawable(icon(IconDrawable.EQ, c, 24))
  }
  topIconRefresh?.invoke(color(R.color.vortex_purple))
  npPlayPause.background = neonGradient(false, 40)
  npPrev.setImageDrawable(icon(IconDrawable.PREV, color(R.color.vortex_text), 24, filled = true))
  npNext.setImageDrawable(icon(IconDrawable.NEXT, color(R.color.vortex_text), 24, filled = true))
  val wave = WaveBarsDrawable(
   color(R.color.vortex_purple), color(R.color.vortex_surface_light),
   dp(3).toFloat(), dp(2).toFloat(), dp(44)
  )
  waveBars = wave
  npSeek.progressDrawable = wave
  npSeek.progressTintList = ColorStateList.valueOf(color(R.color.vortex_purple))
  npSeek.thumbTintList = ColorStateList.valueOf(color(R.color.vortex_blue))
  playPauseButton.setOnClickListener { togglePlay() }
  npPlayPause.setOnClickListener { togglePlay() }
  npPrev.setOnClickListener { player?.seekToPrevious() }
  npNext.setOnClickListener { skipNext() }
  findViewById<View>(R.id.cellLike).setOnClickListener { toggleFavoriteId(currentId()) }
  findViewById<View>(R.id.cellFolder).setOnClickListener {
   if (byId.containsKey(currentId())) addToFolderDialog(currentId())
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
    if (pitchLink) tx("Perde hızla birlikte değişecek (plak gibi)", "Pitch will change with speed (like a record)")
    else tx("Perde sabit kalacak", "Pitch will stay constant")
   )
   updatePills()
   true
  }
  miniPlayer.setOnClickListener { showNowPlaying() }
  CoverGestures(this, nowPlayingScreen, { player }, { hideNowPlaying() })
   .attachMini(miniPlayer) { showNowPlaying() }
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
  if (Build.VERSION.SDK_INT >= 33) {
   val cb = android.window.OnBackInvokedCallback { handleBack() }
   backCallback = cb
   onBackInvokedDispatcher.registerOnBackInvokedCallback(
    android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT, cb
   )
  }
  val needed = mutableListOf<String>()
  if (!permissionOk()) needed.add(audioPermission())
  if (Build.VERSION.SDK_INT >= 33 &&
   checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
   PackageManager.PERMISSION_GRANTED
  ) {
   needed.add(Manifest.permission.POST_NOTIFICATIONS)
  }
  PrivateLibrary.onChanged = { runOnUiThread { loadSongs() } }
  if (permissionOk() || PrivateLibrary.treeUri(this) != null) loadSongs() else refreshList()
  if (PrivateLibrary.autoScan(this)) PrivateLibrary.rescan(this)
  if (needed.isNotEmpty()) {
   getSharedPreferences("vortex", MODE_PRIVATE).edit()
    .putBoolean("perm_asked", true)
    .apply()
   requestPermissions(needed.toTypedArray(), 1)
  }
  if (savedInstanceState == null) StartupAnim.play(frame)
  startService(Intent(this, PlaybackService::class.java))
  bindService(
   Intent(this, PlaybackService::class.java).setAction(PlaybackService.ACTION_LOCAL),
   connection,
   Context.BIND_AUTO_CREATE
  )
 }
 override fun onResume() {
  super.onResume()
  if (!loaded && (permissionOk() || PrivateLibrary.treeUri(this) != null)) loadSongs()
  else if (PrivateLibrary.autoScan(this)) PrivateLibrary.rescan(this)
 }
 override fun onStop() {
  super.onStop()
  saveSession()
 }
 private fun askAudioPermission() {
  val prefs = getSharedPreferences("vortex", MODE_PRIVATE)
  val asked = prefs.getBoolean("perm_asked", false)
  if (asked && !shouldShowRequestPermissionRationale(audioPermission())) {
   try {
    startActivity(
     Intent(
      Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
      Uri.parse("package:$packageName")
     )
    )
    toast(tx("İzinler → Müzik ve ses → İzin ver", "Permissions → Music and audio → Allow"))
   } catch (e: Exception) {
    toast(tx("Ayarlar açılamadı", "Could not open settings"))
   }
  } else {
   prefs.edit().putBoolean("perm_asked", true).apply()
   requestPermissions(arrayOf(audioPermission()), 1)
  }
 }
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
     toast(tx("Kapak kaydedildi", "Cover saved"))
     refreshList()
     updateNowPlayingUi()
    } else {
     toast(tx("Resim okunamadı", "Could not read the image"))
    }
   }
   return
  }
  if (audioScreen.handleResult(requestCode, resultCode, data)) return
  for (h in toolHost.resultHandlers) {
   if (h(requestCode, resultCode, data)) return
  }
 }
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
 private fun skipNext() {
  val p = player ?: return
  if (p.hasNextMediaItem()) {
   p.seekToNext()
   return
  }
  val all = songs.toList()
  if (all.isEmpty()) return
  val i = all.indexOfFirst { it.id == currentId() }
  val start = if (i in 0 until all.lastIndex) i + 1 else 0
  playList(all, start)
 }
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
   toast(tx("Sıradaki olarak eklendi", "Added to play next"))
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
   toast(tx("Kuyruğa eklendi", "Added to queue"))
  }
 }
 private fun showQueue() {
  val p = player ?: return
  queueScreen.show(p)
 }
 private fun togglePlay() {
  val p = player ?: return
  if (p.mediaItemCount == 0) {
   sessionRestored = false
   restoreSession()
   if (p.mediaItemCount > 0) {
    p.play()
   } else if (songs.isNotEmpty()) {
    playList(songs, 0)
   } else {
    toast(tx("Önce bir şarkı seç", "Pick a song first"))
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
  if (audioScreen.isOpen) {
   audioScreen.hide()
  } else if (statsScreen.isOpen) {
   statsScreen.hide()
  } else if (queueScreen.isOpen) {
   queueScreen.hide()
  } else if (settingsScreen.isOpen) {
   settingsScreen.hide()
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
  val text = color(R.color.vortex_text)
  val idle = ui.mix(secondary, text, 0.55f)
  val pill = ui.mix(color(R.color.vortex_surface), color(R.color.vortex_purple), 0.55f)
  for (i in 0..3) {
   val selected = i == mode
   tabIcons[i].layoutParams = LinearLayout.LayoutParams(dp(64), dp(34))
   tabIcons[i].setPadding(dp(5), dp(5), dp(5), dp(5))
   tabIcons[i].background = if (selected) GradientDrawable().apply {
    setColor(pill)
    cornerRadius = dp(17).toFloat()
   } else null
   tabIcons[i].setImageDrawable(icon(tabKinds[i], if (selected) text else idle, 24))
  }
  navLabels?.update(mode, text, idle)
 }
 private fun chipIndexFor(view: Int): Int = when (view) {
  0, 10, 11, 12 -> 0
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
 private fun smartIds(kind: Int): List<Long> =
  SmartLists.build(
   this,
   kind,
   songs.map { it.id },
   songs.associate { it.id to it.added }
  )
 private fun songsFor(kind: Int): List<Song> =
  smartIds(kind).mapNotNull { byId[it] }
 private fun albumGroups(): List<Pair<String, List<Song>>> {
  val groups = libSongs.groupBy { if (albumByArtist) it.artist else it.album }.toList()
  return when (albumSort) {
   1 -> groups.sortedWith(
    compareBy<Pair<String, List<Song>>>(
     { SmartSort.key(it.second.first().artist) },
     { SmartSort.key(it.first) }
    )
   )
   2 -> groups.sortedWith(
    compareByDescending<Pair<String, List<Song>>> { it.second.size }
     .thenBy { SmartSort.key(it.first) }
   )
   3 -> {
    val idx = HashMap<String, Int>()
    albumManual.forEachIndexed { i, n -> idx[n] = i }
    groups.sortedWith(
     compareBy<Pair<String, List<Song>>>(
      { idx[it.first] ?: Int.MAX_VALUE },
      { SmartSort.key(it.first) }
     )
    )
   }
   else -> groups.sortedBy { SmartSort.key(it.first) }
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
  val s = arrayOf(
   "A-Z", tx("Sanatçıya göre", "By artist"),
   tx("Şarkı sayısına göre", "By song count"), tx("Elle sıralı", "Manual order")
  )[albumSort.coerceIn(0, 3)]
  val src = if (albumByArtist) tx("sanatçıya göre albüm", "albums by artist")
  else tx("dosyadaki albümler", "albums from files")
  return "$s · $src"
 }
 private fun albumOptionsDialog() {
  val items = arrayOf(
   "A-Z",
   tx("Sanatçıya göre", "By artist"),
   tx("Şarkı sayısına göre", "By song count"),
   tx("Elle sırala (↑ ↓ ile)", "Sort manually (with ↑ ↓)"),
   if (albumByArtist) tx("Kaynak: Dosyadaki albüm adlarını kullan", "Source: use the album names in the files")
   else tx(
    "Kaynak: Sanatçıya göre (her sanatçının tüm şarkıları tek albümde)",
    "Source: by artist (all songs of each artist in one album)"
   )
  )
  SheetDialog.Builder(this, ui)
   .setTitle(tx("Albüm seçenekleri", "Album options"))
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
   .create().also { it.show(); DialogStyle.apply(it, ui) }
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
 private fun openListOptions() {
  if (listKey.isEmpty()) return
  ListOptionsDialog.show(ui, listKey, curDetailTitle) { refreshList() }
 }
 private fun moveSong(position: Int, delta: Int) {
  if (listKey.isEmpty()) return
  ListPrefs.move(this, listKey, shown, position, delta)
  refreshList()
 }
 private fun buildLibraryRows() {
  libRows.clear()
  when (libView) {
   0 -> {
    if (!permissionOk()) {
     libRows.add(
      Row(IconDrawable.PLUS, 0, tx("Müzik iznini ver", "Grant music permission"), tx("Şarkılar için gerekli", "Required for your songs"), true, "") {
       askAudioPermission()
      }
     )
    }
    val likedCount = songs.count { favorites.contains(it.id) }
    val artistCount = ArtistGroups.count(libSongs.map { it.artist })
    val albumCount = libSongs.map { it.album }.distinct().size
    libRows.add(
     Row(IconDrawable.HEART, 0, tx("Beğenilen Şarkılar", "Liked Songs"), nSongs(likedCount), true, "") {
      setLibView(1)
     }
    )
    libRows.add(
     Row(IconDrawable.NOTE, 1, tx("Tüm şarkılar", "All songs"), nSongs(libSongs.size), true, "") {
      setLibView(10)
     }
    )
    libRows.add(
     Row(
      IconDrawable.CLOCK, 1, tx("Akıllı listeler", "Smart lists"),
      tx("En çok dinlenen, unutulanlar ve daha fazlası", "Most played, forgotten and more"), true, ""
     ) {
      setLibView(8)
     }
    )
    libRows.add(
     Row(IconDrawable.FOLDER, 1, tx("Klasörler", "Folders"), nFolders(folders.size), true, "") {
      setLibView(6)
     }
    )
    libRows.add(
     Row(IconDrawable.ARTIST, 1, tx("Sanatçılar", "Artists"), nArtists(artistCount), true, "") {
      setLibView(2)
     }
    )
    libRows.add(
     Row(IconDrawable.DISC, 1, tx("Albümler", "Albums"), nAlbums(albumCount), true, "") {
      albumViewByTag = false
      setLibView(4)
     }
    )
    if (PrivateLibrary.treeUri(this) != null) {
     libRows.add(
      Row(IconDrawable.FOLDER, 1, PrivateLibrary.folderName(this), nSongs(vortexSongs.size), true, "") {
       setLibView(12)
      }
     )
    }
   }
   2 -> {
    val aq = ArtistGroups.key(searchBox.text.toString())
    ArtistGroups.groups(libSongs) { it.artist }
     .filter { aq.isEmpty() || ArtistGroups.key(it.first).contains(aq) }
     .forEach { (name, list) ->
      libRows.add(
       Row(IconDrawable.ARTIST, 2, name, nSongs(list.size), true, "", false, null, AlbumArt.artistKey(name)) {
        selectedGroup = name
        setLibView(3)
       }
      )
     }
   }
   4 -> {
    libRows.add(
     Row(IconDrawable.EQ, 0, tx("Albüm seçenekleri", "Album options"), albumModeLabel(), true, "") {
      albumOptionsDialog()
     }
    )
    for ((name, list) in albumGroups()) {
     val sub = if (albumByArtist) nSongs(list.size)
     else "${list.first().artist} · ${nSongs(list.size)}"
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
     Row(IconDrawable.FOLDER_PLUS, 0, tx("Yeni klasör", "New folder"), tx("Şarkılarını düzenle", "Organize your songs"), true, "") {
      askNewFolder()
     }
    )
    val existing = songs.map { it.id }.toSet()
    folders.keys.sortedBy { it.lowercase() }.forEach { name ->
     val count = folders[name]?.count { existing.contains(it) } ?: 0
     libRows.add(
      Row(IconDrawable.FOLDER, 1, name, nSongs(count), true, name) {
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
      Row(smartIcons[k], 1, SmartLists.titles[k], nSongs(count), true, "") {
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
    1 -> tx("Henüz beğenilen şarkı yok", "No liked songs yet")
    7 -> tx("Boş. Bir şarkıya uzun basıp klasöre ekleyebilirsin", "Empty. Long-press a song to add it to this folder")
    9 -> SmartLists.hints[k]
    else -> tx("Şarkı yok", "No songs")
   }
  } else {
   nSongs(shown.size)
  }
  if (curOpts.sort != ListPrefs.S_DEFAULT && shown.isNotEmpty()) {
   sub += " · " + ListPrefs.sortLabels[curOpts.sort]
   if (curOpts.reverse && curOpts.sort != ListPrefs.S_MANUAL) sub += tx(" (ters)", " (reversed)")
  }
  detailHeader.bind(title, sub)
  val artKey = currentArtKey()
  setCover(detailHeader.cover, shown.firstOrNull(), dp(84), 18, 32, null, artKey)
  val isArtist = mode == 2 && libView == 3
  coverAddBtn.text = if (isArtist) tx("Fotoğraf ekle", "Add photo") else tx("Kapak ekle", "Add cover")
  coverRemoveBtn.text = if (isArtist) tx("Fotoğrafı kaldır", "Remove photo") else tx("Kapağı kaldır", "Remove cover")
  coverAddBtn.visibility = if (artKey != null) View.VISIBLE else View.GONE
  vortexScanBtn.visibility = if (libView == 12) View.VISIBLE else View.GONE
  coverRemoveBtn.visibility =
   if (artKey != null && AlbumArt.has(this, artKey)) View.VISIBLE else View.GONE
 }
 private fun updateRingColors() {
  val mode = RingPrefs.loadColor(this)
  ringView?.setRainbow(mode == 2)
  if (mode == 2) {
  } else if (accentColor != 0 && mode == 0) {
   ringView?.setColors(ui.mix(accentColor, Color.WHITE, 0.25f), ui.mix(accentColor, Color.WHITE, 0.6f))
  } else {
   ringView?.setColors(color(R.color.vortex_purple), color(R.color.vortex_blue))
  }
 }
 private fun ringColorDialog() {
  val items = arrayOf(
   tx("Albüm rengi", "Album color"),
   tx("Vortex rengi (sabit)", "Vortex color (fixed)"),
   tx("Gökkuşağı", "Rainbow")
  )
  SheetDialog.Builder(this, ui)
   .setTitle(tx("Animasyon rengi", "Animation color"))
   .setSingleChoiceItems(items, RingPrefs.loadColor(this)) { dlg, which ->
    RingPrefs.saveColor(this, which)
    updateRingColors()
    dlg.dismiss()
    refreshList()
   }
   .setNegativeButton(tx("Kapat", "Close"), null)
   .create().also { it.show(); DialogStyle.apply(it, ui) }
 }
 private fun ringLabel(s: Int): String = when (s) {
  0 -> tx("Kapalı", "Off")
  1 -> tx("Halka", "Ring")
  2 -> tx("Girdap", "Vortex")
  3 -> tx("Nabız", "Pulse")
  4 -> tx("Yörünge", "Orbit")
  5 -> tx("Nefes", "Breath")
  6 -> tx("Ritim", "Rhythm")
  8 -> tx("Damla", "Drop")
  9 -> tx("Vortex", "Vortex")
  else -> tx("Spektrum", "Spectrum")
 }
 private fun ringDialog() {
  val visible = intArrayOf(0, 1, 9, 2, 5, 8)
  val items = Array(visible.size) { ringLabel(visible[it]) }
  SheetDialog.Builder(this, ui)
   .setTitle(tx("Girdap animasyonu", "Vortex animation"))
   .setSingleChoiceItems(items, visible.indexOf(RingPrefs.load(this)).coerceAtLeast(0)) { dlg, which ->
    RingPrefs.save(this, visible[which])
    ringView?.applyStyle(visible[which])
    dlg.dismiss()
    refreshList()
   }
   .setNegativeButton(tx("Kapat", "Close"), null)
   .create().also { it.show(); DialogStyle.apply(it, ui) }
 }
 private fun speedSheet() {
  if (player == null) return
  val labels = Array<CharSequence>(speeds.size + 1) { i ->
   if (i < speeds.size) "${speeds[i]}x" else tx("Perde hızla değişsin: ", "Pitch follows speed: ") + (if (pitchLink) tx("Açık", "On") else tx("Kapalı", "Off"))
  }
  SheetDialog.Builder(this, ui)
   .setTitle(tx("Oynatma hızı", "Playback speed"))
   .setSingleChoiceItems(labels, currentSpeedIndex()) { dlg, i ->
    if (i < speeds.size) {
     applySpeed(speeds[i])
    } else {
     pitchLink = !pitchLink
     getSharedPreferences("vortex", MODE_PRIVATE).edit().putBoolean("pitchlink", pitchLink).apply()
     applySpeed(speeds[currentSpeedIndex()])
    }
    updatePills()
    dlg.dismiss()
   }
   .create().show()
 }
 private fun quickSettings() {
  val acts = ArrayList<Pair<String, () -> Unit>>()
  acts.add(Pair(tx("Oynatma hızı: ", "Playback speed: ") + speeds[currentSpeedIndex()] + "x") { speedSheet() })
  acts.add(Pair(tx("Şarkıyı klasöre ekle", "Add song to a folder")) { if (byId.containsKey(currentId())) addToFolderDialog(currentId()) })
  acts.add(Pair(tx("Kitaplık taraması (elle / otomatik)", "Library scan (manual / automatic)")) { PrivateLibrary.scanMenu(toolHost) })
  acts.add(Pair(tx("Ekolayzır ve ses efektleri", "Equalizer and sound effects")) { audioScreen.show(); audioScreen.view.bringToFront() })
  acts.add(Pair(tx("Girdap animasyonu", "Vortex animation")) { ringDialog() })
  acts.add(Pair(tx("Animasyon rengi", "Animation color")) { ringColorDialog() })
  acts.add(Pair(tx("Tüm ayarlar", "All settings")) { settingsScreen.show(); settingsScreen.view.bringToFront() })
  SheetDialog.Builder(this, ui)
   .setTitle(tx("Hızlı ayarlar", "Quick settings"))
   .setItems(Array<CharSequence>(acts.size) { acts[it].first }) { _, i -> acts[i].second() }
   .create().show()
 }
 private fun separatorDialog() {
  val labels = arrayOf(
   tx("Virgül ( , )", "Comma ( , )"),
   tx("Noktalı virgül ( ; )", "Semicolon ( ; )"),
   tx("Ve işareti ( & )", "Ampersand ( & )"),
   tx("Eğik çizgi ( / )", "Slash ( / )"),
   tx("feat. / ft.", "feat. / ft.")
  )
  val m = ArtistGroups.mask()
  val checked = BooleanArray(5) { (m shr it) and 1 != 0 }
  SheetDialog.Builder(this, ui)
   .setTitle(tx("Sanatçı ayırıcıları", "Artist separators"))
   .setMultiChoiceItems(labels, checked, null)
   .setPositiveButton(tx("Kaydet", "Save")) { _, _ ->
    var nm = 0
    for (i in 0 until 5) if (checked[i]) nm = nm or (1 shl i)
    ArtistGroups.save(this, nm)
    toast(tx("Kaydedildi", "Saved"))
    refreshList()
   }
   .setNegativeButton(tx("Vazgeç", "Cancel"), null)
   .create().also { it.show(); DialogStyle.apply(it, ui) }
 }
 private fun refreshList() {
  shown.clear()
  var rowsMode = false
  var detail = false
  var detailTitle = ""
  var key = ""
  val artistsView = mode == 2 && libView == 2
  val allView = mode == 2 && libView == 10
  val boxView = artistsView || allView
  if (boxView != artistsViewShown) {
   artistsViewShown = boxView
   if (searchBox.text.isNotEmpty()) {
    searchBox.setText("")
    return
   }
  }
  if (boxView) {
   searchBox.visibility = View.VISIBLE
   searchBox.hint = if (artistsView) tx("Sanatçı ara", "Search artists") else tx("Şarkı ara", "Search songs")
  } else {
   searchBox.hint = tx("Şarkı ya da sanatçı ara", "Search songs or artists")
   if (mode != 1) searchBox.visibility = View.GONE
  }
  val q = if (artistsView) "" else searchBox.text.toString().trim()
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
      detailTitle = tx("Beğenilen Şarkılar", "Liked Songs")
      key = "liked"
     }
     3 -> {
      shown.addAll(libSongs.filter { ArtistGroups.has(it, { s -> s.artist }, selectedGroup) })
      detail = true
      detailTitle = selectedGroup
      key = "artist:$selectedGroup"
     }
     5 -> {
      shown.addAll(
       libSongs.filter {
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
     12 -> {
      shown.addAll(vortexSongs)
      detail = true
      detailTitle = PrivateLibrary.folderName(this)
      key = "vortex"
     }
     10 -> {
      if (q.isEmpty()) {
       shown.addAll(libSongs)
      } else {
       val hit = SongSearch.search(libSongs, q, searchScope, searchSort).map { it.id }.toHashSet()
       shown.addAll(libSongs.filter { hit.contains(it.id) })
      }
      detail = true
      detailTitle = tx("Tüm şarkılar", "All songs")
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
    !permissionOk() -> if (usePage) null else tx("Müzik izni gerekli", "Music permission needed")
    !loaded -> null
    mode == 1 -> if (q.isEmpty()) null else tx("${shown.size} sonuç", if (shown.size == 1) "1 result" else "${shown.size} results")
    mode == 2 -> when (libView) {
     0 -> tx("Kütüphane", "Library")
     2 -> nArtists(libRows.size)
     4 -> nAlbums((libRows.size - 1).coerceAtLeast(0))
     6 -> nFolders(folders.size) + tx(" · uzun basarak düzenle", " · long-press to edit")
     8 -> tx("Akıllı listeler", "Smart lists")
     11 -> tx("Araçlar", "Tools")
     else -> null
    }
    else -> null
   }
  )
 }
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
  if (!byId.containsKey(id)) return
  if (favorites.contains(id)) favorites.remove(id) else favorites.add(id)
  saveFavorites()
  updatePills()
  refreshList()
 }
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
   toast(tx("Bu isimde bir klasör zaten var", "A folder with this name already exists"))
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
   hint = tx("Klasör adı", "Folder name")
  }
  AlertDialog.Builder(this, DialogStyle.theme())
   .setTitle(title)
   .setView(input)
   .setPositiveButton(tx("Tamam", "OK")) { _, _ ->
    val name = input.text.toString().trim()
    if (name.isNotEmpty()) onOk(name)
   }
   .setNegativeButton(tx("Vazgeç", "Cancel"), null)
   .create().also { it.show(); DialogStyle.apply(it, ui) }
 }
 private fun askNewFolder() {
  askFolderName(tx("Yeni klasör", "New folder"), "") { n ->
   if (createFolder(n)) refreshList()
  }
 }
 private fun renameFolder(old: String) {
  askFolderName(tx("Klasörü yeniden adlandır", "Rename folder"), old) { n ->
   if (n == old) return@askFolderName
   if (folders.containsKey(n)) {
    toast(tx("Bu isimde bir klasör zaten var", "A folder with this name already exists"))
    return@askFolderName
   }
   val ids = folders.remove(old) ?: return@askFolderName
   folders[n] = ids
   saveFolders(listOf(old))
   refreshList()
  }
 }
 private fun deleteFolder(name: String) {
  AlertDialog.Builder(this, DialogStyle.theme())
   .setTitle(tx("\"$name\" silinsin mi?", "Delete \"$name\"?"))
   .setMessage(tx("Şarkılar telefondan silinmez, sadece klasör kalkar.", "Songs are not deleted from the phone, only the folder is removed."))
   .setPositiveButton(tx("Sil", "Delete")) { _, _ ->
    folders.remove(name)
    saveFolders(listOf(name))
    refreshList()
   }
   .setNegativeButton(tx("Vazgeç", "Cancel"), null)
   .create().also { it.show(); DialogStyle.apply(it, ui) }
 }
 private fun folderOptions(name: String) {
  val items = arrayOf(tx("Yeniden adlandır", "Rename"), tx("Sil", "Delete"))
  SheetDialog.Builder(this, ui)
   .setTitle(name)
   .setItems(items) { _, which ->
    if (which == 0) renameFolder(name) else deleteFolder(name)
   }
   .create().also { it.show(); DialogStyle.apply(it, ui) }
 }
 private fun addToFolderDialog(songId: Long) {
  if (songId < 0) return
  val names = folders.keys.sortedBy { it.lowercase() }
  val items = (listOf(tx("+ Yeni klasör", "+ New folder")) + names).toTypedArray()
  SheetDialog.Builder(this, ui)
   .setTitle(tx("Klasöre ekle", "Add to folder"))
   .setItems(items) { _, which ->
    if (which == 0) {
     askFolderName(tx("Yeni klasör", "New folder"), "") { n ->
      if (createFolder(n)) {
       folders[n]?.add(songId)
       saveFolders()
       toast(tx("\"$n\" klasörüne eklendi", "Added to \"$n\""))
       refreshList()
      }
     }
    } else {
     val n = names[which - 1]
     folders[n]?.add(songId)
     saveFolders()
     toast(tx("\"$n\" klasörüne eklendi", "Added to \"$n\""))
     refreshList()
    }
   }
   .create().also { it.show(); DialogStyle.apply(it, ui) }
 }
 private fun songOptions(song: Song) {
  val idx = shown.indexOf(song)
  val manual = listKey.isNotEmpty() && curOpts.sort == ListPrefs.S_MANUAL && idx >= 0
  val oPlayNext = tx("Sıradaki olarak çal", "Play next")
  val oQueue = tx("Kuyruğa ekle", "Add to queue")
  val oFolder = tx("Klasöre ekle", "Add to folder")
  val oRemoveFromFolder = tx("Klasörden çıkar", "Remove from folder")
  val oTop = tx("En üste taşı", "Move to top")
  val oBottom = tx("En alta taşı", "Move to bottom")
  val items = mutableListOf(
   oPlayNext,
   oQueue,
   oFolder,
   if (favorites.contains(song.id)) tx("Beğenmeyi kaldır", "Unlike") else tx("Beğen", "Like")
  )
  if (mode == 2 && libView == 7) items.add(oRemoveFromFolder)
  if (manual) {
   items.add(oTop)
   items.add(oBottom)
  }
  SheetDialog.Builder(this, ui)
   .setTitle(song.title)
   .setItems(items.toTypedArray()) { _, which ->
    when (items[which]) {
     oPlayNext -> playNext(song)
     oQueue -> addToQueue(song)
     oFolder -> addToFolderDialog(song.id)
     oRemoveFromFolder -> {
      folders[selectedGroup]?.remove(song.id)
      saveFolders()
      refreshList()
     }
     oTop -> {
      ListPrefs.moveToEdge(this, listKey, shown, idx, true)
      refreshList()
     }
     oBottom -> {
      ListPrefs.moveToEdge(this, listKey, shown, idx, false)
      refreshList()
     }
     else -> toggleFavoriteId(song.id)
    }
   }
   .create().also { it.show(); DialogStyle.apply(it, ui) }
 }
 private fun updateNowPlayingUi() {
  val meta = player?.currentMediaItem?.mediaMetadata
  val t = meta?.title?.toString()
  val a = meta?.artist?.toString()
  nowTitle.text = t ?: tx("Çalan şarkı yok", "Nothing playing")
  nowArtist.text = a ?: tx("Bir şarkıya dokun", "Tap a song")
  npTitle.text = t ?: tx("Çalan şarkı yok", "Nothing playing")
  npArtist.text = a ?: ""
  val cur = byId[currentId()]
  waveBars?.seed = currentId().hashCode()
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
  fillIcon: Boolean = false,
  plain: Boolean = false
 ) {
  if (plain) {
   iconView.background = null
   iconView.setImageDrawable(
    icon(kind, if (active) color(R.color.vortex_purple) else color(R.color.vortex_text), 26, filled = fillIcon && active)
   )
  } else {
   iconView.background = if (active) neonGradient(false, 23) else GradientDrawable().apply {
    setColor(color(R.color.vortex_surface_light))
    cornerRadius = dp(23).toFloat()
   }
   iconView.setImageDrawable(
    icon(kind, if (active) Color.WHITE else color(R.color.vortex_text), 22, filled = fillIcon && active)
   )
  }
  label.text = text
  label.setTextColor(
   if (active) color(R.color.vortex_text) else color(R.color.vortex_text_secondary)
  )
 }
 private fun updatePills() {
  val p = player
  val liked = favorites.contains(currentId())
  styleCell(
   npLikeIcon, npLikeLabel, IconDrawable.HEART, liked,
   if (liked) tx("Beğenildi", "Liked") else tx("Beğen", "Like"), true, true
  )
  styleCell(npFolderIcon, npFolderLabel, IconDrawable.FOLDER_PLUS, false, tx("Klasör", "Folder"))
  val shuffleOn = p?.shuffleModeEnabled == true
  styleCell(npShuffleIcon, npShuffleLabel, IconDrawable.SHUFFLE, shuffleOn, tx("Karıştır", "Shuffle"))
  val repeatMode = p?.repeatMode ?: Player.REPEAT_MODE_OFF
  styleCell(
   npRepeatIcon, npRepeatLabel, IconDrawable.REPEAT,
   repeatMode != Player.REPEAT_MODE_OFF,
   when (repeatMode) {
    Player.REPEAT_MODE_ALL -> tx("Hepsi", "All")
    Player.REPEAT_MODE_ONE -> tx("Tek şarkı", "One song")
    else -> tx("Döngü", "Repeat")
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
  ringView?.setPlaying(isPlaying)
 }
 private fun showNowPlaying() {
  if (nowPlayingScreen.visibility == View.VISIBLE) return
  updateNowPlayingUi()
  nowPlayingScreen.translationY = resources.displayMetrics.heightPixels * 0.25f
  nowPlayingScreen.alpha = 0f
  nowPlayingScreen.visibility = View.VISIBLE
  nowPlayingScreen.animate().translationY(0f).alpha(1f).setDuration(220).start()
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
   loadSongs()
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
  val cursor = if (permissionOk() && !PrivateLibrary.onlyPrivate(this)) contentResolver.query(
   MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
   projection,
   "${MediaStore.Audio.Media.IS_MUSIC} != 0",
   null,
   "${MediaStore.Audio.Media.TITLE} COLLATE NOCASE ASC"
  ) else null
  cursor?.use {
   while (it.moveToNext()) {
    songs.add(
     Song(
      it.getLong(0),
      it.getString(1) ?: tx("Bilinmeyen", "Unknown"),
      it.getString(2) ?: tx("Bilinmeyen sanatçı", "Unknown artist"),
      it.getString(3) ?: tx("Bilinmeyen albüm", "Unknown album"),
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
  songs.addAll(PrivateLibrary.songs(this))
  byId.clear()
  for (s in songs) byId[s.id] = s
  loaded = true
  refreshList()
  updateNowPlayingUi()
  restoreSession()
 }
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
  if (row.coverKey != null && AlbumArt.has(this, row.coverKey)) {
   setCover(iconView, null, dp(56), 28, 26, null, row.coverKey)
  } else if (row.coverSong != null) {
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
    // Kalbe dokununca beğeni kalkar (Beğenilen listesinde şarkı da listeden çıkar)
    row.addView(ImageView(this@MainActivity).apply {
     setImageDrawable(icon(IconDrawable.HEART, color(R.color.vortex_purple), 18, filled = true))
     setPadding(dp(11), dp(11), dp(11), dp(11))
     isClickable = true
     isFocusable = true
     contentDescription = tx("Beğenmeyi kaldır", "Unlike")
     layoutParams = LinearLayout.LayoutParams(dp(44), dp(44)).apply {
      marginStart = dp(4)
     }
     setOnClickListener { toggleFavoriteId(song.id) }
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
