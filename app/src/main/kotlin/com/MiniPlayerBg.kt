package io.github.vortexmusic.player

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable

// Mini oynatıcı kartının zemini: yuvarlak kart + şarkının ilerlemesi kadar dolan yumuşak mor şerit.
class MiniPlayerBg(
    private val fillColor: Int,
    accent: Int,
    private val strokeColor: Int,
    private val radius: Float,
    private val strokePx: Float
) : Drawable() {

    private val barColor = Color.argb(70, Color.red(accent), Color.green(accent), Color.blue(accent))
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private val path = Path()
    private var progress = 0f

    fun setProgress(p: Float) {
        val v = p.coerceIn(0f, 1f)
        if (kotlin.math.abs(v - progress) < 0.002f) return
        progress = v
        invalidateSelf()
    }

    override fun draw(canvas: Canvas) {
        val b = bounds
        if (b.width() <= 0 || b.height() <= 0) return
        rect.set(b)
        rect.inset(strokePx / 2f, strokePx / 2f)
        path.reset()
        path.addRoundRect(rect, radius, radius, Path.Direction.CW)
        canvas.save()
        canvas.clipPath(path)
        paint.style = Paint.Style.FILL
        paint.color = fillColor
        canvas.drawRect(rect, paint)
        if (progress > 0f) {
            paint.color = barColor
            canvas.drawRect(rect.left, rect.top, rect.left + rect.width() * progress, rect.bottom, paint)
        }
        canvas.restore()
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = strokePx
        paint.color = strokeColor
        canvas.drawRoundRect(rect, radius, radius, paint)
    }

    override fun setAlpha(alpha: Int) {}

    override fun setColorFilter(colorFilter: ColorFilter?) {}

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
