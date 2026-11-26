package io.github.vortexmusic.player

import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable
import kotlin.math.abs
import kotlin.math.sin

// Oynatıcı ilerleme çubuğu: dalga gibi dizilmiş çubuklar, çalınan kısım renkli dolar.
// Not: çubukların yüksekliği şarkıya özgü süs desenidir (gerçek ses dalgası değildir),
// şarkı numarasından üretilir, bu yüzden her şarkının kendi deseni olur.
class WaveBarsDrawable(
    private var activeColor: Int,
    private val idleColor: Int,
    private val barPx: Float,
    private val gapPx: Float,
    private val heightPx: Int
) : Drawable() {

    var seed: Int = 1
        set(v) {
            if (field != v) {
                field = v
                heights = null
                invalidateSelf()
            }
        }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val rect = RectF()
    private var heights: FloatArray? = null

    override fun getIntrinsicHeight(): Int = heightPx

    // SeekBar vurgu rengini (progressTintList) buraya verir: çalınan kısım o renkte olur
    override fun setTintList(tint: ColorStateList?) {
        if (tint != null) {
            activeColor = tint.defaultColor
            invalidateSelf()
        }
    }

    override fun onLevelChange(level: Int): Boolean {
        invalidateSelf()
        return true
    }

    private fun build(n: Int): FloatArray {
        val out = FloatArray(n)
        var x = (seed.toLong() * 2654435761L + 12345L) and 0x7fffffffL
        var smooth = 0.5f
        val phase = (seed and 255) / 40f
        for (i in 0 until n) {
            x = (x * 1103515245L + 12345L) and 0x7fffffffL
            val r = (x % 1000L) / 1000f
            smooth = smooth * 0.45f + r * 0.55f
            val env = 0.62f + 0.38f * abs(sin(i / (n / 5f) + phase))
            out[i] = (0.18f + 0.82f * smooth * env).coerceIn(0.14f, 1f)
        }
        return out
    }

    override fun draw(canvas: Canvas) {
        val b = bounds
        val w = b.width().toFloat()
        val h = b.height().toFloat()
        if (w <= 0f || h <= 0f) return
        val step = barPx + gapPx
        val n = ((w + gapPx) / step).toInt().coerceAtLeast(1)
        var hs = heights
        if (hs == null || hs.size != n) {
            hs = build(n)
            heights = hs
        }
        val frac = level / 10000f
        val cy = b.top + h / 2f
        for (i in 0 until n) {
            val bh = (h * 0.9f * hs[i]).coerceAtLeast(barPx)
            val left = b.left + i * step
            rect.set(left, cy - bh / 2f, left + barPx, cy + bh / 2f)
            paint.color = if ((i + 0.5f) / n <= frac) activeColor else idleColor
            canvas.drawRoundRect(rect, barPx / 2f, barPx / 2f, paint)
        }
    }

    override fun setAlpha(alpha: Int) {}

    override fun setColorFilter(colorFilter: ColorFilter?) {}

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
