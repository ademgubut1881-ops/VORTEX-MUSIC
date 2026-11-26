package io.github.vortexmusic.player

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint as GPaint
import android.graphics.RectF
import android.graphics.drawable.BitmapDrawable
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.view.Gravity
import android.view.MotionEvent
import android.view.ViewConfiguration
import kotlin.math.abs
import kotlin.math.min
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.Timeline

class Palette(
    val purple: Int,
    val blue: Int,
    val text: Int,
    val secondary: Int,
    val surface: Int,
    val surfaceLight: Int
)

// ---------- Kuyruk ikonu ----------

class QueueIconDrawable(private val tint: Int, private val sizePx: Int) : Drawable() {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
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
        canvas.drawLine(4f, 7f, 20f, 7f, paint)
        canvas.drawLine(4f, 12f, 20f, 12f, paint)
        canvas.drawLine(4f, 17f, 13f, 17f, paint)
        canvas.restore()
    }

    override fun setAlpha(alpha: Int) {}

    override fun setColorFilter(colorFilter: ColorFilter?) {}

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}

// ---------- Yumuşak ses girişi ----------

class Fader(private val player: Player) {

    private val handler = Handler(Looper.getMainLooper())

    // Eskiden burada ses 0'a düşürülüp yükseltiliyordu. Ama "şarkı değişti" olayı sesin
    // zaten başlamasından SONRA gelir: sesi 0'a çekmek şarkı başını kesiyordu.
    // Yumuşak başlangıç artık ses motorunda (VortexEq, örnek hassasiyetinde) yapılıyor.
    fun fadeIn() {
        handler.removeCallbacksAndMessages(null)
        player.volume = 1f
    }

    fun cancel() {
        handler.removeCallbacksAndMessages(null)
        player.volume = 1f
    }
}

// ---------- Kuyruk ekranı ----------

class QueueScreen(
    private val activity: Activity,
    private val palette: Palette,
    bg: Drawable,
    private val coverFor: (ImageView, Long) -> Unit
) {

    private val density = activity.resources.displayMetrics.density
    private var player: Player? = null
    private val adapter = QueueAdapter()
    private lateinit var countText: TextView
    private lateinit var list: ListView
    @Volatile private var dragFrom = -1

    val view: LinearLayout = LinearLayout(activity)

    val isOpen: Boolean
        get() = view.visibility == View.VISIBLE

    private val listener = object : Player.Listener {
        override fun onTimelineChanged(timeline: Timeline, reason: Int) {
            refresh()
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            refresh()
        }
    }

    private fun dp(v: Int): Int = (v * density).toInt()

    init {
        view.orientation = LinearLayout.VERTICAL
        view.visibility = View.GONE
        view.isClickable = true
        view.isFocusable = true
        view.background = bg
        view.layoutParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
        view.setOnApplyWindowInsetsListener { v, insets ->
            v.setPadding(
                dp(16),
                insets.systemWindowInsetTop + dp(12),
                dp(16),
                insets.systemWindowInsetBottom + dp(12)
            )
            insets
        }

        val header = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val close = ImageView(activity).apply {
            setPadding(dp(11), dp(11), dp(11), dp(11))
            isClickable = true
            isFocusable = true
            setImageDrawable(IconDrawable(IconDrawable.CHEVRON, palette.purple, dp(24)))
            layoutParams = LinearLayout.LayoutParams(dp(46), dp(46))
            setOnClickListener { hide() }
        }
        val title = TextView(activity).apply {
            text = tx("Kuyruk", "Queue")
            textSize = 24f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(palette.text)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = dp(4) }
        }
        header.addView(title)

        // Üstte tutamaç çizgisi: dokun ya da aşağı kaydır, kuyruk kapanır
        val handleBox = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            isClickable = true
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(36))
            addView(View(activity).apply {
                background = android.graphics.drawable.GradientDrawable().apply {
                    setColor((palette.secondary and 0x00FFFFFF) or 0x99000000.toInt())
                    cornerRadius = dp(2).toFloat()
                }
                layoutParams = LinearLayout.LayoutParams(dp(40), dp(4))
            })
            var downY = 0f
            setOnTouchListener { _, e ->
                when (e.actionMasked) {
                    android.view.MotionEvent.ACTION_DOWN -> downY = e.rawY
                    android.view.MotionEvent.ACTION_UP -> {
                        val dy = e.rawY - downY
                        if (dy > dp(24) || Math.abs(dy) < dp(8)) hide()
                    }
                }
                true
            }
        }
        view.addView(handleBox)

        countText = TextView(activity).apply {
            textSize = 13f
            setTextColor(palette.secondary)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(4) }
        }
        val hint = TextView(activity).apply {
            text = tx("Dokun: çal · basılı tut ve sürükle: sırala · yana kaydır: kaldır", "Tap: play · hold and drag: reorder · swipe: remove")
            textSize = 12f
            setTextColor(palette.secondary)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(2) }
        }

        list = ListView(activity).apply {
            divider = null
            dividerHeight = 0
            setSelector(android.R.color.transparent)
            isVerticalScrollBarEnabled = false
            adapter = this@QueueScreen.adapter
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
            ).apply { topMargin = dp(10) }
            setOnItemClickListener { _, _, position, _ -> playAt(position) }
        }

        installSwipeToRemove()

        view.addView(header)
        view.addView(countText)
        view.addView(hint)
        view.addView(list)
    }

    // Dokunma hareketleri: yana kaydır = kaldır, basılı tut + sürükle = sırayı değiştir
    private fun installSwipeToRemove() {
        val slop = ViewConfiguration.get(activity).scaledTouchSlop
        val handler = Handler(Looper.getMainLooper())
        var downX = 0f
        var downY = 0f
        var fingerY = 0f
        var swiping = false
        var dragging = false
        var swipePos = -1
        var hover = -1
        var grab = 0f
        var ghost: BitmapDrawable? = null
        var ghostH = 0
        var swipeView: View? = null

        fun cancelListTouch(v: View) {
            val now = android.os.SystemClock.uptimeMillis()
            val c = MotionEvent.obtain(now, now, MotionEvent.ACTION_CANCEL, downX, downY, 0)
            v.onTouchEvent(c)
            c.recycle()
            v.parent?.requestDisallowInterceptTouchEvent(true)
        }

        fun resetRows() {
            for (k in 0 until list.childCount) {
                val c = list.getChildAt(k)
                c.animate().translationY(0f).translationX(0f).alpha(1f).setDuration(120).start()
            }
        }

        // Hayalet satırı parmağa taşır, aradaki satırları kaydırıp yer açar
        fun updateDrag() {
            val g = ghost ?: return
            val top = (fingerY - grab).toInt().coerceIn(-ghostH / 2, list.height - ghostH / 2)
            g.setBounds(0, top, list.width, top + ghostH)
            val count = player?.mediaItemCount ?: 0
            val h = list.pointToPosition(list.width / 2, fingerY.toInt().coerceIn(0, list.height - 1))
            hover = when {
                h >= 0 -> h
                fingerY < list.height / 2f -> 0
                else -> maxOf(count - 1, 0)
            }
            for (k in 0 until list.childCount) {
                val c = list.getChildAt(k)
                val p = list.firstVisiblePosition + k
                val t = when {
                    p == swipePos -> 0f
                    p > swipePos && p <= hover -> -ghostH.toFloat()
                    p < swipePos && p >= hover -> ghostH.toFloat()
                    else -> 0f
                }
                if (p == swipePos) c.alpha = 0.15f
                if (c.translationY != t) c.animate().translationY(t).setDuration(100).start()
            }
            list.invalidate()
        }

        val edge = dp(80)
        val scroller = object : Runnable {
            override fun run() {
                if (!dragging) return
                val h = list.height
                val dy = when {
                    fingerY < edge -> -((edge - fingerY) / edge * dp(16))
                    fingerY > h - edge -> ((fingerY - (h - edge)) / edge * dp(16))
                    else -> 0f
                }
                if (dy != 0f) {
                    list.scrollListBy(dy.toInt())
                    updateDrag()
                }
                handler.postDelayed(this, 16)
            }
        }

        fun endDrag(apply: Boolean) {
            if (!dragging) return
            dragging = false
            handler.removeCallbacks(scroller)
            val g = ghost
            if (g != null) list.overlay.remove(g)
            ghost = null
            val from = swipePos
            val to = hover
            dragFrom = -1
            val pl = player
            resetRows()
            if (apply && pl != null && to >= 0 && to != from &&
                from in 0 until pl.mediaItemCount && to in 0 until pl.mediaItemCount
            ) {
                pl.moveMediaItem(from, to)
            } else {
                adapter.notifyDataSetChanged()
            }
        }

        val longPress = Runnable {
            val sv = swipeView
            if (!swiping && !dragging && sv != null && swipePos >= 0 && sv.width > 0 && sv.height > 0) {
                dragging = true
                hover = swipePos
                dragFrom = swipePos
                grab = downY - sv.top
                ghostH = sv.height
                val bm = Bitmap.createBitmap(sv.width, sv.height, Bitmap.Config.ARGB_8888)
                val cv = Canvas(bm)
                val paint = GPaint(GPaint.ANTI_ALIAS_FLAG).apply { color = palette.surfaceLight }
                cv.drawRoundRect(RectF(0f, 0f, sv.width.toFloat(), sv.height.toFloat()), dp(20).toFloat(), dp(20).toFloat(), paint)
                sv.draw(cv)
                val g = BitmapDrawable(activity.resources, bm)
                g.alpha = 235
                ghost = g
                list.overlay.add(g)
                fingerY = downY
                cancelListTouch(list)
                list.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
                updateDrag()
                handler.post(scroller)
            }
        }

        list.setOnTouchListener { v, e ->
            var consumed = false
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.x
                    downY = e.y
                    fingerY = e.y
                    swiping = false
                    if (dragging) endDrag(false)
                    swipePos = list.pointToPosition(e.x.toInt(), e.y.toInt())
                    swipeView = if (swipePos >= 0) list.getChildAt(swipePos - list.firstVisiblePosition) else null
                    handler.removeCallbacks(longPress)
                    if (swipeView != null) handler.postDelayed(longPress, ViewConfiguration.getLongPressTimeout().toLong())
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.x - downX
                    val dy = e.y - downY
                    val sv = swipeView
                    fingerY = e.y
                    if (dragging) {
                        updateDrag()
                        consumed = true
                    } else {
                        if (abs(dx) > slop || abs(dy) > slop) handler.removeCallbacks(longPress)
                        if (!swiping && sv != null && abs(dx) > slop * 2 && abs(dx) > abs(dy) * 1.5f) {
                            swiping = true
                            cancelListTouch(v)
                        }
                        if (swiping && sv != null) {
                            sv.translationX = dx
                            sv.alpha = 1f - min(1f, abs(dx) / (v.width * 0.6f))
                            consumed = true
                        }
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    handler.removeCallbacks(longPress)
                    val sv = swipeView
                    if (dragging) {
                        endDrag(e.actionMasked == MotionEvent.ACTION_UP)
                        consumed = true
                    } else if (swiping && sv != null) {
                        val dx = e.x - downX
                        val pos = swipePos
                        swiping = false
                        if (e.actionMasked == MotionEvent.ACTION_UP && abs(dx) > v.width * 0.35f) {
                            val target = if (dx > 0) v.width.toFloat() else -v.width.toFloat()
                            sv.animate().translationX(target).alpha(0f).setDuration(160).withEndAction {
                                val pl = player
                                if (pl != null && pos in 0 until pl.mediaItemCount) pl.removeMediaItem(pos)
                            }.start()
                        } else {
                            sv.animate().translationX(0f).alpha(1f).setDuration(160).start()
                        }
                        consumed = true
                    }
                }
            }
            consumed
        }
    }

    private var closing = false

    fun show(p: Player) {
        player?.removeListener(listener)
        player = p
        p.addListener(listener)
        refresh()
        val cur = p.currentMediaItemIndex
        if (cur >= 0) list.setSelection(maxOf(cur - 2, 0))
        view.animate().cancel()
        closing = false
        // Aşağıdan yumuşakça süzülerek açılır
        val h = activity.resources.displayMetrics.heightPixels.toFloat()
        view.translationY = h * 0.35f
        view.alpha = 0f
        view.visibility = View.VISIBLE
        view.animate()
            .translationY(0f)
            .alpha(1f)
            .setDuration(380)
            .setInterpolator(android.view.animation.PathInterpolator(0.05f, 0.7f, 0.1f, 1f))
            .start()
    }

    fun hide() {
        player?.removeListener(listener)
        player = null
        if (view.visibility != View.VISIBLE || closing) return
        closing = true
        val h = activity.resources.displayMetrics.heightPixels.toFloat()
        view.animate().cancel()
        view.animate()
            .translationY(h * 0.35f)
            .alpha(0f)
            .setDuration(240)
            .setInterpolator(android.view.animation.AccelerateInterpolator(1.4f))
            .withEndAction {
                view.visibility = View.GONE
                view.translationY = 0f
                view.alpha = 1f
                closing = false
            }
            .start()
    }

    private fun refresh() {
        adapter.notifyDataSetChanged()
        val n = player?.mediaItemCount ?: 0
        countText.text = if (n == 0) tx("Kuyruk boş", "Queue is empty") else tx("$n şarkı", if (n == 1) "1 song" else "$n songs")
    }

    private fun playAt(position: Int) {
        val p = player ?: return
        if (position !in 0 until p.mediaItemCount) return
        p.seekToDefaultPosition(position)
        if (p.playbackState == Player.STATE_IDLE) p.prepare()
        p.play()
    }

    private inner class QueueAdapter : BaseAdapter() {

        override fun getCount(): Int = player?.mediaItemCount ?: 0

        override fun getItem(position: Int): Any = position

        override fun getItemId(position: Int): Long = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val p = player
            if (p == null || position !in 0 until p.mediaItemCount) return View(activity)
            val item = p.getMediaItemAt(position)
            val isCurrent = p.currentMediaItemIndex == position

            val row = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(8), dp(8), dp(12), dp(8))
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
                if (isCurrent) {
                    background = GradientDrawable().apply {
                        setColor(palette.surfaceLight)
                        cornerRadius = dp(20).toFloat()
                    }
                }
            }

            val cover = ImageView(activity).apply {
                layoutParams = LinearLayout.LayoutParams(dp(48), dp(48))
            }
            coverFor(cover, item.mediaId.toLongOrNull() ?: -1L)

            val column = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    .apply { marginStart = dp(12) }
            }
            val title = TextView(activity).apply {
                text = item.mediaMetadata.title?.toString() ?: tx("Bilinmeyen", "Unknown")
                textSize = 15f
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                setTextColor(if (isCurrent) palette.purple else palette.text)
            }
            val artist = TextView(activity).apply {
                text = item.mediaMetadata.artist?.toString() ?: ""
                textSize = 13f
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                setTextColor(palette.secondary)
            }
            column.addView(title)
            column.addView(artist)

            row.addView(cover)
            row.addView(column)
            if (position == dragFrom) row.alpha = 0.15f
            return row
        }
    }
}