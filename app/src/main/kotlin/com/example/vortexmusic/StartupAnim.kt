package io.github.vortexmusic.player

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Shader
import android.graphics.Typeface
import android.view.View
import android.view.ViewGroup
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import kotlin.math.exp
import kotlin.math.min

// Açılış animasyonu: VORTEX harfleri tek tek belirir, sonra harfler V'nin içine
// çekilip siliniyor, ortada sadece V kalır ve bas atışı gibi halkalar yayar.
// Ekrana dokununca atlanır.
object StartupAnim {
    fun play(host: FrameLayout) {
        val v = StartupView(host.context)
        v.layoutParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
        host.addView(v)
        v.start { host.removeView(v) }
    }
}

private fun clamp01(x: Float): Float = if (x < 0f) 0f else if (x > 1f) 1f else x

private fun easeOut(x: Float): Float {
    val t = 1f - clamp01(x)
    return 1f - t * t * t
}

private fun smooth(x: Float): Float {
    val t = clamp01(x)
    return t * t * (3f - 2f * t)
}

private fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t

private fun lerpColor(a: Int, b: Int, t: Float): Int = Color.rgb(
    (Color.red(a) + (Color.red(b) - Color.red(a)) * t).toInt(),
    (Color.green(a) + (Color.green(b) - Color.green(a)) * t).toInt(),
    (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * t).toInt()
)

private class StartupView(ctx: Context) : View(ctx) {

    private val total = 4600L
    private var p = 0f
    private var anim: ValueAnimator? = null
    private var done = false
    private var skipping = false
    private var onDone: (() -> Unit)? = null

    private val bg = Color.parseColor("#0A0A12")
    private val purple = Color.parseColor("#8B5CF6")
    private val cyan = Color.parseColor("#22D3EE")

    private val word = "VORTEX"
    private val n = word.length
    private val widths = FloatArray(n)
    private val centers = FloatArray(n)
    private val shaders = arrayOfNulls<LinearGradient>(n)
    private var textSize = 0f
    private var capH = 0f

    // V'nin yalnız kaldığı andan sonra bas atışı halkaları
    private val beats = floatArrayOf(0.80f, 0.86f)
    private val ringLife = 0.16f

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        textAlign = Paint.Align.CENTER
    }
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val bounds = Rect()

    init {
        isClickable = true
        setOnClickListener { skip() }
    }

    fun start(finished: () -> Unit) {
        onDone = finished
        val a = ValueAnimator.ofFloat(0f, 1f)
        a.duration = total
        a.interpolator = LinearInterpolator()
        a.addUpdateListener {
            p = it.animatedValue as Float
            if (!skipping) alpha = 1f - clamp01((p - 0.92f) / 0.08f)
            invalidate()
        }
        a.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                finish()
            }
        })
        anim = a
        a.start()
    }

    private fun finish() {
        if (done) return
        done = true
        onDone?.invoke()
    }

    private fun skip() {
        if (skipping || done) return
        skipping = true
        anim?.removeAllUpdateListeners()
        animate().alpha(0f).setDuration(180L).withEndAction { anim?.cancel() }.start()
    }

    override fun onDetachedFromWindow() {
        anim?.removeAllUpdateListeners()
        anim?.cancel()
        super.onDetachedFromWindow()
    }

    private var laidOut = 0f

    private fun layoutWord(w: Float) {
        textSize = w * 0.15f
        textPaint.textSize = textSize
        val spacing = textSize * 0.20f
        var total = spacing * (n - 1)
        for (i in 0 until n) {
            widths[i] = textPaint.measureText(word[i].toString())
            total += widths[i]
        }
        var x = (w - total) / 2f
        for (i in 0 until n) {
            centers[i] = x + widths[i] / 2f
            x += widths[i] + spacing
            val c1 = if (i == 0) purple else lerpColor(purple, cyan, i / n.toFloat())
            val c2 = if (i == 0) lerpColor(purple, cyan, 0.45f) else lerpColor(purple, cyan, (i + 1) / n.toFloat())
            shaders[i] = LinearGradient(-widths[i] / 2f, 0f, widths[i] / 2f, 0f, c1, c2, Shader.TileMode.CLAMP)
        }
        textPaint.getTextBounds("V", 0, 1, bounds)
        capH = bounds.height().toFloat()
        laidOut = w
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        if (laidOut != w) layoutWord(w)
        canvas.drawColor(bg)
        val cy = h * 0.46f
        val d = resources.displayMetrics.density

        // V'nin yeri: önce kelimenin başında, sonra ekranın ortasına süzülür
        val move = smooth((p - 0.60f) / 0.22f)
        val vx = lerp(centers[0], w / 2f, move)
        val vScale = lerp(1f, 1.9f, move)

        // Bas atışı halkaları (V yalnız kalınca)
        val maxR = min(w, h) * 0.55f
        for (b in beats) {
            val r = (p - b) / ringLife
            if (r < 0f || r > 1f) continue
            ringPaint.strokeWidth = d * (6f * (1f - r) + 1f)
            ringPaint.color = lerpColor(purple, cyan, r)
            ringPaint.alpha = (190f * (1f - r)).toInt().coerceIn(0, 255)
            canvas.drawCircle(w / 2f, cy, maxR * easeOut(r) + textSize * 0.4f, ringPaint)
        }

        // Atışta V hafifçe sıçrar
        var pulse = 0f
        for (b in beats) {
            if (p >= b) {
                val v = exp(-(p - b) * 18f)
                if (v > pulse) pulse = v
            }
        }

        // V'nin arkasında yumuşak parlama
        val glowA = clamp01((p - 0.10f) / 0.3f) * (0.35f + 0.65f * move) + pulse * 0.4f
        for (k in 3 downTo 1) {
            glowPaint.color = purple
            glowPaint.alpha = (22f * glowA).toInt().coerceIn(0, 255)
            canvas.drawCircle(vx, cy, textSize * (0.55f + 0.38f * k) * vScale, glowPaint)
        }

        val vOff = capH / 2f
        for (i in 0 until n) {
            val tIn = 0.05f + i * 0.05f
            val a = easeOut((p - tIn) / 0.12f)
            if (a <= 0f) continue

            var alpha = a
            var dx = 0f
            var dy = (1f - a) * textSize * 0.35f
            var sc = (0.85f + 0.15f * a)
            var cx = centers[i]

            if (i == 0) {
                cx = vx
                sc *= vScale * (1f + 0.07f * pulse)
            } else {
                // Sağdan sola: harfler V'nin içine çekilerek silinir
                val k = (n - 1) - i
                val o = smooth((p - (0.46f + k * 0.045f)) / 0.15f)
                alpha *= (1f - o)
                dx = (centers[0] - centers[i]) * 0.55f * o
                dy -= o * textSize * 0.45f
                sc *= (1f - 0.55f * o)
            }
            if (alpha <= 0.01f) continue

            textPaint.shader = shaders[i]
            textPaint.alpha = (255f * alpha).toInt().coerceIn(0, 255)
            canvas.save()
            canvas.translate(cx + dx, cy + dy)
            canvas.scale(sc, sc)
            canvas.drawText(word[i].toString(), 0f, vOff, textPaint)
            canvas.restore()
        }
    }
}
