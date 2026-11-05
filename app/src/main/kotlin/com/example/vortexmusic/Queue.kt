package io.github.vortexmusic.player

import android.app.Activity
import android.graphics.Canvas
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
    private val steps = 8
    private val stepMs = 60L
    private var step = 0

    private val tick = object : Runnable {
        override fun run() {
            step++
            player.volume = (step.toFloat() / steps).coerceAtMost(1f)
            if (step < steps) handler.postDelayed(this, stepMs)
        }
    }

    fun fadeIn() {
        handler.removeCallbacks(tick)
        step = 0
        player.volume = 0f
        handler.postDelayed(tick, stepMs)
    }

    fun cancel() {
        handler.removeCallbacks(tick)
        player.volume = 1f
    }
}

// ---------- Kuyruk ekranı ----------

class QueueScreen(
    private val activity: Activity,
    private val palette: Palette,
    bg: Drawable
) {

    private val density = activity.resources.displayMetrics.density
    private var player: Player? = null
    private val adapter = QueueAdapter()
    private lateinit var countText: TextView
    private lateinit var list: ListView

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
            text = "Kuyruk"
            textSize = 24f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(palette.text)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = dp(4) }
        }
        header.addView(close)
        header.addView(title)

        countText = TextView(activity).apply {
            textSize = 13f
            setTextColor(palette.secondary)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(4) }
        }
        val hint = TextView(activity).apply {
            text = "Şarkıya dokun: çal · ↑ ↓ sırala · ✕ kaldır"
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

        view.addView(header)
        view.addView(countText)
        view.addView(hint)
        view.addView(list)
    }

    fun show(p: Player) {
        player?.removeListener(listener)
        player = p
        p.addListener(listener)
        view.visibility = View.VISIBLE
        refresh()
        val cur = p.currentMediaItemIndex
        if (cur >= 0) list.setSelection(maxOf(cur - 2, 0))
    }

    fun hide() {
        player?.removeListener(listener)
        player = null
        view.visibility = View.GONE
    }

    private fun refresh() {
        adapter.notifyDataSetChanged()
        val n = player?.mediaItemCount ?: 0
        countText.text = if (n == 0) "Kuyruk boş" else "$n şarkı"
    }

    private fun playAt(position: Int) {
        val p = player ?: return
        if (position !in 0 until p.mediaItemCount) return
        p.seekToDefaultPosition(position)
        if (p.playbackState == Player.STATE_IDLE) p.prepare()
        p.play()
    }

    private fun smallButton(label: String, onClick: () -> Unit): TextView = TextView(activity).apply {
        text = label
        textSize = 18f
        gravity = Gravity.CENTER
        setTextColor(palette.purple)
        layoutParams = LinearLayout.LayoutParams(dp(40), dp(44))
        setOnClickListener { onClick() }
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
                setPadding(dp(8), dp(6), dp(4), dp(6))
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
                if (isCurrent) {
                    background = GradientDrawable().apply {
                        setColor(palette.surfaceLight)
                        cornerRadius = dp(16).toFloat()
                    }
                }
            }

            val number = TextView(activity).apply {
                text = "${position + 1}"
                textSize = 13f
                gravity = Gravity.CENTER
                setTextColor(if (isCurrent) palette.blue else palette.secondary)
                layoutParams = LinearLayout.LayoutParams(dp(30), ViewGroup.LayoutParams.WRAP_CONTENT)
            }

            val column = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    .apply { marginStart = dp(6) }
            }
            val title = TextView(activity).apply {
                text = item.mediaMetadata.title?.toString() ?: "Bilinmeyen"
                textSize = 15f
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                setTextColor(if (isCurrent) palette.blue else palette.text)
            }
            val artist = TextView(activity).apply {
                text = item.mediaMetadata.artist?.toString() ?: ""
                textSize = 12f
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                setTextColor(palette.secondary)
            }
            column.addView(title)
            column.addView(artist)

            row.addView(number)
            row.addView(column)
            row.addView(smallButton("↑") {
                val pl = player
                if (pl != null && position > 0 && position < pl.mediaItemCount) {
                    pl.moveMediaItem(position, position - 1)
                }
            })
            row.addView(smallButton("↓") {
                val pl = player
                if (pl != null && position >= 0 && position < pl.mediaItemCount - 1) {
                    pl.moveMediaItem(position, position + 1)
                }
            })
            row.addView(smallButton("✕") {
                val pl = player
                if (pl != null && position >= 0 && position < pl.mediaItemCount) {
                    pl.removeMediaItem(position)
                }
            })
            return row
        }
    }
}