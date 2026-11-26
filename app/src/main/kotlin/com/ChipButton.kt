package io.github.vortexmusic.player

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView

// Küçük, ince çerçeveli Material 3 tarzı düğme (isteğe bağlı sıralama simgesiyle)
class SortGlyph(private val d: Float, private val tint: Int) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeWidth = 1.8f * d
        color = tint
    }

    override fun getIntrinsicWidth(): Int = (18f * d).toInt()
    override fun getIntrinsicHeight(): Int = (18f * d).toInt()

    override fun draw(canvas: Canvas) {
        val b = bounds
        val x0 = b.left + 2f * d
        val w = b.width() - 4f * d
        val cy = b.exactCenterY()
        val gap = 4.5f * d
        canvas.drawLine(x0, cy - gap, x0 + w, cy - gap, paint)
        canvas.drawLine(x0, cy, x0 + w * 0.68f, cy, paint)
        canvas.drawLine(x0, cy + gap, x0 + w * 0.36f, cy + gap, paint)
    }

    override fun setAlpha(alpha: Int) { paint.alpha = alpha }
    override fun setColorFilter(cf: ColorFilter?) { paint.colorFilter = cf }
    @Suppress("OVERRIDE_DEPRECATION")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}

fun Ui.chipButton(label: String, sortIcon: Boolean, onClick: () -> Unit): TextView = TextView(activity).apply {
    text = label
    textSize = 13f
    typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    gravity = Gravity.CENTER_VERTICAL
    val ink = color(R.color.vortex_text)
    setTextColor(ink)
    background = GradientDrawable().apply {
        setColor(0x00000000)
        cornerRadius = dp(18).toFloat()
        setStroke(dp(1), (ink and 0x00FFFFFF) or 0x55000000)
    }
    minHeight = dp(36)
    setPadding(dp(if (sortIcon) 12 else 14), 0, dp(14), 0)
    if (sortIcon) {
        compoundDrawablePadding = dp(8)
        setCompoundDrawablesRelativeWithIntrinsicBounds(SortGlyph(density, ink), null, null, null)
    }
    layoutParams = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
    ).apply { marginEnd = dp(8) }
    setOnClickListener { onClick() }
}
