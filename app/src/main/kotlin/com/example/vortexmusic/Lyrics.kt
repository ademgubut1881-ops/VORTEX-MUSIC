package io.github.vortexmusic.player

import android.app.Activity
import android.app.AlertDialog
import android.app.Dialog
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.media3.common.Player
import java.io.File

// ---------- Depolama ----------

object LyricsStore {

    private const val MAX = 200000

    private fun file(ctx: Context, id: Long): File =
        File(File(ctx.filesDir, "lyrics"), "$id.txt")

    fun load(ctx: Context, id: Long): String? {
        val f = file(ctx, id)
        if (!f.exists()) return null
        return try {
            f.readText(Charsets.UTF_8)
        } catch (e: Exception) {
            null
        }
    }

    fun save(ctx: Context, id: Long, text: String): Boolean {
        return try {
            val f = file(ctx, id)
            f.parentFile?.mkdirs()
            f.writeText(text.take(MAX), Charsets.UTF_8)
            true
        } catch (e: Exception) {
            false
        }
    }

    fun delete(ctx: Context, id: Long) {
        try {
            file(ctx, id).delete()
        } catch (e: Exception) {
        }
    }
}

// ---------- .lrc çözümleyici ----------

class LyricLine(val timeMs: Long, val text: String)

class ParsedLyrics(val synced: Boolean, val lines: List<LyricLine>, val plain: String)

object Lrc {

    private val lineRegex = Regex("^((?:\\[\\d{1,3}:\\d{1,2}(?:[.:]\\d{1,3})?\\])+)(.*)$")
    private val stampRegex = Regex("\\[(\\d{1,3}):(\\d{1,2})(?:[.:](\\d{1,3}))?\\]")
    private val offsetRegex = Regex("^\\[offset:\\s*([+-]?\\d+)\\]", RegexOption.IGNORE_CASE)
    private val metaRegex = Regex("^\\[[A-Za-z]+:[^\\]]*\\]$")

    fun parse(text: String): ParsedLyrics {
        val out = ArrayList<LyricLine>()
        val plain = StringBuilder()
        var offset = 0L
        for (rawLine in text.split('\n')) {
            val line = rawLine.trimEnd('\r').trim()
            val off = offsetRegex.find(line)
            if (off != null) {
                offset = off.groupValues[1].toLongOrNull() ?: 0L
                continue
            }
            val m = lineRegex.find(line)
            if (m != null) {
                val body = m.groupValues[2].trim()
                for (s in stampRegex.findAll(m.groupValues[1])) {
                    val min = s.groupValues[1].toLongOrNull() ?: continue
                    val sec = s.groupValues[2].toLongOrNull() ?: continue
                    val frac = s.groupValues[3]
                    val ms = when (frac.length) {
                        0 -> 0L
                        1 -> (frac.toLongOrNull() ?: 0L) * 100L
                        2 -> (frac.toLongOrNull() ?: 0L) * 10L
                        else -> frac.take(3).toLongOrNull() ?: 0L
                    }
                    val t = (min * 60000L + sec * 1000L + ms - offset).coerceAtLeast(0L)
                    out.add(LyricLine(t, body))
                }
                plain.append(body).append('\n')
                continue
            }
            if (metaRegex.matches(line)) continue
            plain.append(line).append('\n')
        }
        out.sortBy { it.timeMs }
        val synced = out.size >= 2
        return ParsedLyrics(synced, if (synced) out else emptyList(), plain.toString())
    }
}

// ---------- Simge ----------

class LyricsIconDrawable(private val tint: Int, private val sizePx: Int) : Drawable() {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = tint
    }

    override fun getIntrinsicWidth(): Int = sizePx
    override fun getIntrinsicHeight(): Int = sizePx

    override fun draw(canvas: Canvas) {
        val w = bounds.width().toFloat()
        val h = bounds.height().toFloat()
        if (w <= 0f || h <= 0f) return
        val s = minOf(w, h) / 24f
        canvas.save()
        canvas.translate(bounds.left + (w - 24f * s) / 2f, bounds.top + (h - 24f * s) / 2f)
        canvas.scale(s, s)
        paint.strokeWidth = 1.9f
        canvas.drawRoundRect(RectF(3f, 4f, 21f, 17f), 3f, 3f, paint)
        val tail = Path()
        tail.moveTo(8f, 17f)
        tail.lineTo(8f, 21f)
        tail.lineTo(12.5f, 17f)
        canvas.drawPath(tail, paint)
        canvas.drawLine(7f, 9f, 17f, 9f, paint)
        canvas.drawLine(7f, 12.5f, 14f, 12.5f, paint)
        canvas.restore()
    }

    override fun setAlpha(alpha: Int) {}

    override fun setColorFilter(colorFilter: ColorFilter?) {}

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}

// ---------- Araç ----------

object LyricsTool {

    private const val REQ_IMPORT = 501

    private var pendingId = -1L
    private var refreshHook: (() -> Unit)? = null
    private val handler = Handler(Looper.getMainLooper())

    fun install(host: ToolHost) {
        host.resultHandlers.add { req, res, data -> handle(host, req, res, data) }
        // Ekran hazır olunca tam ekran çalara söz düğmesi eklenir
        host.activity.window.decorView.post { injectButton(host) }
    }

    fun entries(host: ToolHost): List<ToolEntry> = listOf(
        ToolEntry(
            "Şarkı sözleri",
            "Çalan şarkının sözlerini göster, içe aktar ya da yapıştır",
            IconDrawable.NOTE
        ) { open(host) }
    )

    private fun injectButton(host: ToolHost) {
        val screen = host.activity.findViewById<LinearLayout>(R.id.nowPlayingScreen) ?: return
        val top = screen.getChildAt(0) as? LinearLayout ?: return
        if (top.tag == "lyrics-added") return
        val ui = host.ui
        val btn = ImageView(host.activity).apply {
            setPadding(ui.dp(11), ui.dp(11), ui.dp(11), ui.dp(11))
            isClickable = true
            isFocusable = true
            layoutParams = LinearLayout.LayoutParams(ui.dp(46), ui.dp(46))
            setImageDrawable(LyricsIconDrawable(ui.color(R.color.vortex_purple), ui.dp(24)))
            setOnClickListener { open(host) }
        }
        top.addView(btn, maxOf(top.childCount - 1, 0))
        top.tag = "lyrics-added"
    }

    private fun safeUnbind(activity: Activity, conn: ServiceConnection) {
        try {
            activity.unbindService(conn)
        } catch (e: Exception) {
        }
    }

    private fun open(host: ToolHost) {
        val activity = host.activity
        val conn = object : ServiceConnection {
            private var shown = false

            override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                if (shown) return
                val p = (service as? PlaybackService.LocalBinder)?.getPlayer()
                if (p == null) {
                    host.ui.toast("Çalar henüz hazır değil")
                    safeUnbind(activity, this)
                    return
                }
                shown = true
                showDialog(host, p, this)
            }

            override fun onServiceDisconnected(name: ComponentName?) {}
        }
        val ok = activity.bindService(
            Intent(activity, PlaybackService::class.java).setAction(PlaybackService.ACTION_LOCAL),
            conn,
            Context.BIND_AUTO_CREATE
        )
        if (!ok) host.ui.toast("Çalara bağlanılamadı")
    }

    private fun showDialog(host: ToolHost, player: Player, conn: ServiceConnection) {
        val ui = host.ui
        val activity = host.activity
        val textColor = ui.color(R.color.vortex_text)
        val secondary = ui.color(R.color.vortex_text_secondary)
        val accent = ui.color(R.color.vortex_blue)

        val dialog = Dialog(activity, android.R.style.Theme_Material_NoActionBar)
        dialog.window?.setBackgroundDrawable(ColorDrawable(ui.color(R.color.vortex_background)))
        val root = ui.overlay()
        root.visibility = View.VISIBLE

        val songTitle = TextView(activity).apply {
            textSize = 20f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(textColor)
            maxLines = 1
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = ui.dp(8) }
        }
        val songArtist = TextView(activity).apply {
            textSize = 14f
            setTextColor(secondary)
            maxLines = 1
        }
        val scroll = ScrollView(activity).apply {
            isVerticalScrollBarEnabled = false
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
            ).apply { topMargin = ui.dp(12) }
        }
        val box = LinearLayout(activity)
        box.orientation = LinearLayout.VERTICAL
        scroll.addView(box)

        var shownId = Long.MIN_VALUE
        var lines: List<LyricLine> = emptyList()
        val views = ArrayList<TextView>()
        var active = -1

        fun currentId(): Long = player.currentMediaItem?.mediaId?.toLongOrNull() ?: -1L

        fun render(id: Long) {
            shownId = id
            box.removeAllViews()
            views.clear()
            lines = emptyList()
            active = -1
            val meta = player.currentMediaItem?.mediaMetadata
            songTitle.text = meta?.title?.toString() ?: "Çalan şarkı yok"
            songArtist.text = meta?.artist?.toString() ?: ""
            if (id < 0) {
                box.addView(ui.noteText("Önce bir şarkı çal."))
                return
            }
            val text = LyricsStore.load(activity, id)
            if (text == null) {
                box.addView(
                    ui.noteText(
                        "Bu şarkı için söz yok. Aşağıdan bir .lrc ya da .txt dosyası içe aktar " +
                            "veya sözleri yapıştır. .lrc dosyalarında satırlar müzikle birlikte parlar."
                    )
                )
                return
            }
            val parsed = Lrc.parse(text)
            if (parsed.synced) {
                lines = parsed.lines
                for (l in parsed.lines) {
                    val tv = TextView(activity).apply {
                        this.text = if (l.text.isEmpty()) "♪" else l.text
                        textSize = 20f
                        setTextColor(secondary)
                        setPadding(0, ui.dp(8), 0, ui.dp(8))
                        setOnClickListener { player.seekTo(l.timeMs) }
                    }
                    views.add(tv)
                    box.addView(tv)
                }
            } else {
                box.addView(TextView(activity).apply {
                    this.text = parsed.plain.trim()
                    textSize = 18f
                    setTextColor(textColor)
                    setLineSpacing(0f, 1.15f)
                })
            }
        }

        fun setActive(idx: Int) {
            if (active in views.indices) {
                val old = views[active]
                old.setTextColor(secondary)
                old.textSize = 20f
                old.setTypeface(null, Typeface.NORMAL)
            }
            active = idx
            if (idx in views.indices) {
                val v = views[idx]
                v.setTextColor(accent)
                v.textSize = 24f
                v.setTypeface(null, Typeface.BOLD)
                scroll.post {
                    scroll.smoothScrollTo(0, (v.top - scroll.height / 2 + v.height / 2).coerceAtLeast(0))
                }
            }
        }

        val tick = object : Runnable {
            override fun run() {
                val id = currentId()
                if (id != shownId) render(id)
                if (lines.isNotEmpty()) {
                    val pos = player.currentPosition
                    var idx = -1
                    for (i in lines.indices) {
                        if (lines[i].timeMs <= pos) idx = i else break
                    }
                    if (idx != active) setActive(idx)
                }
                handler.postDelayed(this, 250L)
            }
        }

        fun askPaste() {
            if (shownId < 0) {
                ui.toast("Önce bir şarkı çal")
                return
            }
            val input = EditText(activity).apply {
                hint = "Sözleri yapıştır (düz metin ya da .lrc)"
                minLines = 8
                gravity = Gravity.TOP or Gravity.START
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            }
            AlertDialog.Builder(activity)
                .setTitle("Sözleri yapıştır")
                .setView(input)
                .setPositiveButton("Kaydet") { _, _ ->
                    val t = input.text.toString()
                    if (t.isBlank()) {
                        ui.toast("Metin boş")
                    } else if (LyricsStore.save(activity, shownId, t)) {
                        render(shownId)
                    } else {
                        ui.toast("Kaydedilemedi")
                    }
                }
                .setNegativeButton("Vazgeç", null)
                .show()
        }

        fun askDelete() {
            if (shownId < 0 || LyricsStore.load(activity, shownId) == null) {
                ui.toast("Silinecek söz yok")
                return
            }
            AlertDialog.Builder(activity)
                .setTitle("Bu şarkının sözleri silinsin mi?")
                .setPositiveButton("Sil") { _, _ ->
                    LyricsStore.delete(activity, shownId)
                    render(shownId)
                }
                .setNegativeButton("Vazgeç", null)
                .show()
        }

        @Suppress("DEPRECATION")
        fun askImport() {
            if (shownId < 0) {
                ui.toast("Önce bir şarkı çal")
                return
            }
            pendingId = shownId
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType("*/*")
            activity.startActivityForResult(intent, REQ_IMPORT)
        }

        val actions = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = ui.dp(10) }
        }
        actions.addView(ui.actionPill("İçe aktar") { askImport() })
        actions.addView(ui.actionPill("Yapıştır") { askPaste() })
        actions.addView(ui.actionPill("Sil") { askDelete() })

        root.addView(ui.header("Şarkı sözleri") { dialog.dismiss() })
        root.addView(songTitle)
        root.addView(songArtist)
        root.addView(scroll)
        root.addView(actions)
        dialog.setContentView(root)

        refreshHook = { render(currentId()) }
        dialog.setOnDismissListener {
            handler.removeCallbacks(tick)
            refreshHook = null
            safeUnbind(activity, conn)
        }
        dialog.show()
        handler.post(tick)
    }

    private fun readLimited(host: ToolHost, uri: Uri): String? {
        return try {
            host.activity.contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use { r ->
                val sb = StringBuilder()
                val buf = CharArray(4096)
                while (true) {
                    val n = r.read(buf)
                    if (n < 0) break
                    sb.append(buf, 0, n)
                    if (sb.length > 200000) return@use null
                }
                sb.toString()
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun handle(host: ToolHost, req: Int, res: Int, data: Intent?): Boolean {
        if (req != REQ_IMPORT) return false
        if (res != Activity.RESULT_OK) return true
        val uri = data?.data ?: return true
        val id = pendingId
        if (id < 0) return true
        val text = readLimited(host, uri)
        if (text == null || text.isBlank() || text.indexOf('\u0000') >= 0) {
            host.ui.toast("Geçerli bir metin ya da .lrc dosyası değil")
            return true
        }
        if (LyricsStore.save(host.activity, id, text)) {
            host.ui.toast("Sözler kaydedildi")
            refreshHook?.invoke()
        } else {
            host.ui.toast("Sözler kaydedilemedi")
        }
        return true
    }
}