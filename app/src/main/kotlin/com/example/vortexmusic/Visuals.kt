package io.github.vortexmusic.player

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable

// ====== NEON ZEMİN ======

class NeonBackgroundDrawable(
    private val topColor: Int,
    private val bottomColor: Int,
    private val lineA: Int,
    private val lineB: Int,
    private val density: Float,
    private val blobA: Int = 0,
    private val blobB: Int = 0,
    private val blobStrength: Int = 0
) : Drawable() {

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val blob1 = Paint(Paint.ANTI_ALIAS_FLAG)
    private val blob2 = Paint(Paint.ANTI_ALIAS_FLAG)
    private val blob3 = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    private fun soft(c: Int, a: Int): Int =
        Color.argb(a.coerceIn(0, 255), Color.red(c), Color.green(c), Color.blue(c))

    // Renk geçişleri boyut değişince bir kez hazırlanır, her karede yeniden üretilmez
    override fun onBoundsChange(b: Rect) {
        super.onBoundsChange(b)
        val w = b.width().toFloat()
        val h = b.height().toFloat()
        if (w <= 0f || h <= 0f) return
        val l = b.left.toFloat()
        val t = b.top.toFloat()
        fill.shader = LinearGradient(
            0f, t, 0f, t + h, topColor, bottomColor, Shader.TileMode.CLAMP
        )
        if (blobStrength > 0) {
            val s = blobStrength
            blob1.shader = RadialGradient(
                l + w * 0.12f, t + h * 0.10f, w * 0.95f,
                soft(blobA, s), soft(blobA, 0), Shader.TileMode.CLAMP
            )
            blob2.shader = RadialGradient(
                l + w * 0.95f, t + h * 0.90f, w * 1.0f,
                soft(blobB, (s * 0.9f).toInt()), soft(blobB, 0), Shader.TileMode.CLAMP
            )
            blob3.shader = RadialGradient(
                l + w * 1.0f, t + h * 0.42f, w * 0.6f,
                soft(blobA, (s * 0.45f).toInt()), soft(blobA, 0), Shader.TileMode.CLAMP
            )
        }
    }

    private fun glowLine(c: Canvas, x1: Float, y1: Float, x2: Float, y2: Float, color: Int) {
        val w = density * 1.6f
        stroke.color = color
        stroke.alpha = 35
        stroke.strokeWidth = w * 6f
        c.drawLine(x1, y1, x2, y2, stroke)
        stroke.alpha = 80
        stroke.strokeWidth = w * 3f
        c.drawLine(x1, y1, x2, y2, stroke)
        stroke.alpha = 230
        stroke.strokeWidth = w
        c.drawLine(x1, y1, x2, y2, stroke)
    }

    override fun draw(canvas: Canvas) {
        val w = bounds.width().toFloat()
        val h = bounds.height().toFloat()
        if (w <= 0f || h <= 0f) return
        val l = bounds.left.toFloat()
        val t = bounds.top.toFloat()
        canvas.drawRect(bounds, fill)
        if (blobStrength > 0) {
            canvas.drawRect(bounds, blob1)
            canvas.drawRect(bounds, blob2)
            canvas.drawRect(bounds, blob3)
        }
        // Çizgiler kapalıysa (renkler zemin renginin aynısı) çizgi çizilmez
        if (lineA == topColor && lineB == bottomColor) return
        glowLine(canvas, l - 20f, t + h * 0.16f, l + w + 20f, t + h * 0.06f, lineA)
        glowLine(canvas, l - 20f, t + h * 0.20f, l + w * 0.7f, t + h * 0.11f, lineA)
        glowLine(canvas, l - 20f, t + h * 0.86f, l + w * 0.55f, t + h + 20f, lineB)
        glowLine(canvas, l - 20f, t + h * 0.93f, l + w * 0.32f, t + h + 20f, lineB)
    }

    override fun setAlpha(alpha: Int) {}

    override fun setColorFilter(colorFilter: ColorFilter?) {}

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.OPAQUE
}

// ====== SİMGELER ======

class IconDrawable(
    private val kind: Int,
    private val tint: Int,
    private val sizePx: Int,
    private val filled: Boolean = false,
    private val glowColor: Int = 0
) : Drawable() {

    companion object {
        const val HOME = 0
        const val SEARCH = 1
        const val NOTE = 2
        const val DOWNLOAD = 3
        const val HEART = 4
        const val PLAY = 5
        const val PAUSE = 6
        const val PREV = 7
        const val NEXT = 8
        const val SHUFFLE = 9
        const val REPEAT = 10
        const val CHEVRON = 11
        const val FOLDER = 12
        const val FOLDER_PLUS = 13
        const val ARTIST = 14
        const val DISC = 15
        const val CLOCK = 16
        const val CHART = 17
        const val PLUS = 18
        const val EQ = 19
    }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val path = Path()
    private val rect = RectF()

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
        val style = if (filled) Paint.Style.FILL_AND_STROKE else Paint.Style.STROKE
        if (glowColor != 0) {
            paint.color = glowColor
            paint.alpha = 90
            paint.strokeWidth = 4.6f
            paint.style = style
            shape(canvas, paint)
        }
        paint.color = tint
        paint.strokeWidth = 1.9f
        paint.style = style
        shape(canvas, paint)
        canvas.restore()
    }

    private fun dot(c: Canvas, p: Paint, x: Float, y: Float, r: Float) {
        val old = p.style
        p.style = Paint.Style.FILL
        c.drawCircle(x, y, r, p)
        p.style = old
    }

    private fun shape(c: Canvas, p: Paint) {
        when (kind) {
            HOME -> {
                path.reset()
                path.moveTo(4f, 11f)
                path.lineTo(12f, 4f)
                path.lineTo(20f, 11f)
                c.drawPath(path, p)
                path.reset()
                path.moveTo(6f, 9.6f)
                path.lineTo(6f, 20f)
                path.lineTo(10f, 20f)
                path.lineTo(10f, 14f)
                path.lineTo(14f, 14f)
                path.lineTo(14f, 20f)
                path.lineTo(18f, 20f)
                path.lineTo(18f, 9.6f)
                c.drawPath(path, p)
            }
            SEARCH -> {
                c.drawCircle(10.5f, 10.5f, 6.5f, p)
                c.drawLine(15.3f, 15.3f, 20f, 20f, p)
            }
            NOTE -> {
                c.drawCircle(7.5f, 17.5f, 2.6f, p)
                c.drawCircle(16.5f, 15.5f, 2.6f, p)
                c.drawLine(10.1f, 17.5f, 10.1f, 6.5f, p)
                c.drawLine(19.1f, 15.5f, 19.1f, 4.5f, p)
                c.drawLine(10.1f, 6.5f, 19.1f, 4.5f, p)
            }
            DOWNLOAD -> {
                c.drawLine(12f, 4f, 12f, 15f, p)
                path.reset()
                path.moveTo(7.5f, 10.5f)
                path.lineTo(12f, 15f)
                path.lineTo(16.5f, 10.5f)
                c.drawPath(path, p)
                c.drawLine(5f, 20f, 19f, 20f, p)
            }
            HEART -> {
                path.reset()
                path.moveTo(12f, 20f)
                path.cubicTo(5f, 15f, 3f, 11f, 3f, 8f)
                path.cubicTo(3f, 5.5f, 5f, 4f, 7.2f, 4f)
                path.cubicTo(9.2f, 4f, 11f, 5.2f, 12f, 7f)
                path.cubicTo(13f, 5.2f, 14.8f, 4f, 16.8f, 4f)
                path.cubicTo(19f, 4f, 21f, 5.5f, 21f, 8f)
                path.cubicTo(21f, 11f, 19f, 15f, 12f, 20f)
                path.close()
                c.drawPath(path, p)
            }
            PLAY -> {
                path.reset()
                path.moveTo(7f, 4.5f)
                path.lineTo(19f, 12f)
                path.lineTo(7f, 19.5f)
                path.close()
                c.drawPath(path, p)
            }
            PAUSE -> {
                rect.set(6.5f, 4.5f, 10f, 19.5f)
                c.drawRoundRect(rect, 1.2f, 1.2f, p)
                rect.set(14f, 4.5f, 17.5f, 19.5f)
                c.drawRoundRect(rect, 1.2f, 1.2f, p)
            }
            PREV -> {
                rect.set(5f, 5f, 7.5f, 19f)
                c.drawRoundRect(rect, 1f, 1f, p)
                path.reset()
                path.moveTo(19f, 5f)
                path.lineTo(8.5f, 12f)
                path.lineTo(19f, 19f)
                path.close()
                c.drawPath(path, p)
            }
            NEXT -> {
                rect.set(16.5f, 5f, 19f, 19f)
                c.drawRoundRect(rect, 1f, 1f, p)
                path.reset()
                path.moveTo(5f, 5f)
                path.lineTo(15.5f, 12f)
                path.lineTo(5f, 19f)
                path.close()
                c.drawPath(path, p)
            }
            SHUFFLE -> {
                path.reset()
                path.moveTo(4f, 7f)
                path.lineTo(7.5f, 7f)
                path.cubicTo(12f, 7f, 12f, 17f, 16.5f, 17f)
                path.lineTo(20f, 17f)
                c.drawPath(path, p)
                path.reset()
                path.moveTo(4f, 17f)
                path.lineTo(7.5f, 17f)
                path.cubicTo(9.5f, 17f, 10.5f, 15.8f, 11.5f, 14.5f)
                c.drawPath(path, p)
                path.reset()
                path.moveTo(13f, 9.5f)
                path.cubicTo(14f, 8f, 15f, 7f, 16.5f, 7f)
                path.lineTo(20f, 7f)
                c.drawPath(path, p)
                path.reset()
                path.moveTo(17.5f, 14.5f)
                path.lineTo(20f, 17f)
                path.lineTo(17.5f, 19.5f)
                c.drawPath(path, p)
                path.reset()
                path.moveTo(17.5f, 4.5f)
                path.lineTo(20f, 7f)
                path.lineTo(17.5f, 9.5f)
                c.drawPath(path, p)
            }
            REPEAT -> {
                path.reset()
                path.moveTo(4.5f, 11.5f)
                path.lineTo(4.5f, 10f)
                path.quadTo(4.5f, 7.5f, 7f, 7.5f)
                path.lineTo(19f, 7.5f)
                c.drawPath(path, p)
                path.reset()
                path.moveTo(16.5f, 5f)
                path.lineTo(19.5f, 7.5f)
                path.lineTo(16.5f, 10f)
                c.drawPath(path, p)
                path.reset()
                path.moveTo(19.5f, 12.5f)
                path.lineTo(19.5f, 14f)
                path.quadTo(19.5f, 16.5f, 17f, 16.5f)
                path.lineTo(5f, 16.5f)
                c.drawPath(path, p)
                path.reset()
                path.moveTo(7.5f, 14f)
                path.lineTo(4.5f, 16.5f)
                path.lineTo(7.5f, 19f)
                c.drawPath(path, p)
            }
            CHEVRON -> {
                path.reset()
                path.moveTo(15f, 5f)
                path.lineTo(8f, 12f)
                path.lineTo(15f, 19f)
                c.drawPath(path, p)
            }
            FOLDER, FOLDER_PLUS -> {
                path.reset()
                path.moveTo(3.5f, 7f)
                path.lineTo(3.5f, 18f)
                path.quadTo(3.5f, 19.5f, 5f, 19.5f)
                path.lineTo(19f, 19.5f)
                path.quadTo(20.5f, 19.5f, 20.5f, 18f)
                path.lineTo(20.5f, 9.5f)
                path.quadTo(20.5f, 8f, 19f, 8f)
                path.lineTo(11.5f, 8f)
                path.lineTo(9.5f, 5.5f)
                path.lineTo(5f, 5.5f)
                path.quadTo(3.5f, 5.5f, 3.5f, 7f)
                path.close()
                c.drawPath(path, p)
                if (kind == FOLDER_PLUS) {
                    c.drawLine(12f, 11.5f, 12f, 16.5f, p)
                    c.drawLine(9.5f, 14f, 14.5f, 14f, p)
                }
            }
            ARTIST -> {
                c.drawCircle(12f, 8f, 3.6f, p)
                path.reset()
                path.moveTo(5f, 20f)
                path.quadTo(5f, 14f, 12f, 14f)
                path.quadTo(19f, 14f, 19f, 20f)
                c.drawPath(path, p)
            }
            DISC -> {
                c.drawCircle(12f, 12f, 8.5f, p)
                c.drawCircle(12f, 12f, 2.8f, p)
            }
            CLOCK -> {
                c.drawCircle(12f, 13.5f, 7.5f, p)
                c.drawLine(10f, 3.5f, 14f, 3.5f, p)
                c.drawLine(12f, 3.5f, 12f, 6f, p)
                c.drawLine(12f, 13.5f, 12f, 9.5f, p)
            }
            CHART -> {
                val old = p.strokeWidth
                p.strokeWidth = 2.6f
                c.drawLine(6f, 19f, 6f, 12f, p)
                c.drawLine(12f, 19f, 12f, 5f, p)
                c.drawLine(18f, 19f, 18f, 9f, p)
                p.strokeWidth = old
            }
            PLUS -> {
                c.drawLine(12f, 5f, 12f, 19f, p)
                c.drawLine(5f, 12f, 19f, 12f, p)
            }
            EQ -> {
                c.drawLine(6f, 4f, 6f, 20f, p)
                c.drawLine(12f, 4f, 12f, 20f, p)
                c.drawLine(18f, 4f, 18f, 20f, p)
                dot(c, p, 6f, 15f, 2.4f)
                dot(c, p, 12f, 8f, 2.4f)
                dot(c, p, 18f, 13f, 2.4f)
            }
        }
    }

    override fun setAlpha(alpha: Int) {}

    override fun setColorFilter(colorFilter: ColorFilter?) {}

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}