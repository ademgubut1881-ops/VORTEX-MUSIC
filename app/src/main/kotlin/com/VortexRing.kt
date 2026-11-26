package io.github.vortexmusic.player

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import android.view.animation.LinearInterpolator

// Girdap animasyonu ayarı: 0 Kapalı, 1 Halka, 2 Girdap, 3 Nabız, 4 Yörünge, 5 Nefes, 6 Ritim, 7 Spektrum
object RingPrefs {
    private const val FILE = "vortex_ring"
    // Renk kaynağı: 0 albüm rengi, 1 Vortex rengi (hep mor-mavi), 2 gökkuşağı
    fun loadColor(ctx: Context): Int =
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).getInt("color", 0).coerceIn(0, 2)

    fun saveColor(ctx: Context, m: Int) {
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putInt("color", m).apply()
    }

    fun load(ctx: Context): Int =
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).getInt("style", 1).coerceIn(0, 9).let {
            // Kaldırılan stiller (Nabız, Yörünge, Ritim, Spektrum) Halka'ya döner
            if (it == 3 || it == 4 || it == 6 || it == 7) 1 else it
        }

    fun save(ctx: Context, style: Int) {
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putInt("style", style).apply()
    }
}

// Albüm kapağının çevresinde dönen/atan ışık. Sadece müzik çalarken ve ekran görünürken çalışır.
class VortexRingView(
    ctx: Context,
    private val pad: Float,
    private val corner: Float,
    private var c1: Int,
    private var c2: Int
) : View(ctx) {

    private var style = 1
    // Çalan sesin seviyesini veren kaynak (0-1)
    var levelSource: () -> Float = { 0f }
    // Bas vuruşu sayacı kaynağı
    var beatSource: () -> Int = { 0 }
    private var lastBeat = 0
    // 32 bantlık spektrum kaynağı (0-1)
    var spectrumSource: () -> FloatArray? = { null }
    private val specSm = FloatArray(32)
    private var kick = 0f
    // Damla: her vuruşta kapaktan dışarı yayılan su halkaları (ilerleme 0-1, eksi = gecikmeli)
    private val ripples = FloatArray(6) { 2f }
    private var dropAcc = 0f
    // Spiral girdap: dönüş fazı müziğin gücüyle ilerler
    private var swirl = 0f
    private val spX = FloatArray(4 * 12)
    private val spY = FloatArray(4 * 12)
    private var lvl = 0f
    private val path = android.graphics.Path()
    private val pm = android.graphics.PathMeasure()
    private val pos = FloatArray(2)
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.style = Paint.Style.FILL }
    private var playing = false
    private var t = 0f
    private val d = ctx.resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val rect = RectF()
    private val anim = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 6000L
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener {
            t = it.animatedValue as Float
            invalidate()
        }
    }

    // Gökkuşağı: renkler animasyonla birlikte tüm renk tekerleğini dolaşır
    private var rainbow = false

    fun setRainbow(on: Boolean) {
        rainbow = on
        invalidate()
    }

    // Albümün baskın rengine göre renkleri güncelle
    fun setColors(a: Int, b: Int) {
        c1 = a
        c2 = b
        invalidate()
    }

    fun applyStyle(s: Int) {
        style = s.coerceIn(0, 9)
        update()
        invalidate()
    }

    fun setPlaying(p: Boolean) {
        playing = p
        update()
    }

    private fun update() {
        val run = style != 0 && playing && isShown
        if (run && !anim.isRunning) anim.start()
        if (!run && anim.isRunning) anim.cancel()
        if (!run) invalidate()
    }

    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        update()
    }

    override fun onDetachedFromWindow() {
        anim.cancel()
        super.onDetachedFromWindow()
    }

    private fun drawRing(c: Canvas, inset: Float, color: Int, alpha: Int, width: Float, onFrac: Float, phaseTurns: Float) {
        rect.set(inset, inset, this.width - inset, this.height - inset)
        val r = corner + (pad - inset)
        val w = rect.width()
        val h = rect.height()
        val per = 2f * (w + h) - (8f - 2f * Math.PI.toFloat()) * r
        paint.color = color
        paint.alpha = (alpha + 70 * kick).toInt().coerceAtMost(255)
        paint.strokeWidth = width * (1f + 0.9f * kick)
        paint.strokeCap = Paint.Cap.ROUND
        if (onFrac >= 1f) {
            paint.pathEffect = null
        } else {
            val on = per * onFrac
            paint.pathEffect = DashPathEffect(floatArrayOf(on, per - on), -phaseTurns * per)
        }
        c.drawRoundRect(rect, r, r, paint)
    }

    private fun lerp(a: Int, b: Int, f: Float): Int {
        val ar = android.graphics.Color.red(a); val ag = android.graphics.Color.green(a); val ab = android.graphics.Color.blue(a)
        val br = android.graphics.Color.red(b); val bg = android.graphics.Color.green(b); val bb = android.graphics.Color.blue(b)
        return android.graphics.Color.rgb((ar + (br - ar) * f).toInt(), (ag + (bg - ag) * f).toInt(), (ab + (bb - ab) * f).toInt())
    }

    override fun onDraw(canvas: Canvas) {
        if (style == 0 || !playing) return
        if (rainbow) {
            val hue = (t * 360f) % 360f
            c1 = android.graphics.Color.HSVToColor(floatArrayOf(hue, 0.75f, 1f))
            c2 = android.graphics.Color.HSVToColor(floatArrayOf((hue + 140f) % 360f, 0.75f, 1f))
        }
        // Vuruş geldiğinde tüm animasyonlar bir anda "coşar", sonra yavaşça söner
        val bc = beatSource()
        if (bc != lastBeat) {
            lastBeat = bc
            kick = 1f
        } else {
            kick *= 0.88f
        }
        when (style) {
            1 -> { // Halka: soluk bir çizgi ve üzerinde dönen parlak yay
                drawRing(canvas, pad * 0.5f, c1, 45, 2f * d, 1f, 0f)
                drawRing(canvas, pad * 0.5f, c2, 235, 3f * d, 0.22f, t)
            }
            4 -> { // Yörünge: kapağın kenarında dolaşan üç ışık, kuyruklu
                val inset = pad * 0.5f
                rect.set(inset, inset, this.width - inset, this.height - inset)
                val r = corner + (pad - inset)
                path.reset()
                path.addRoundRect(rect, r, r, android.graphics.Path.Direction.CW)
                pm.setPath(path, true)
                val len = pm.length
                for (k in 0 until 3) {
                    for (tail in 0 until 7) {
                        val f = (((t + k / 3f - tail * 0.012f) % 1f) + 1f) % 1f
                        pm.getPosTan(f * len, pos, null)
                        dot.color = if (k % 2 == 0) c1 else c2
                        dot.alpha = (240 * (1f - tail / 7f)).toInt()
                        canvas.drawCircle(pos[0], pos[1], (4.2f - tail * 0.45f) * d, dot)
                    }
                }
            }
            7 -> { // Spektrum: frekansa göre ayrı çubuklar (bas baştan, tiz karşı uçta)
                val sp = spectrumSource()
                val inset = pad * 0.28f
                drawRing(canvas, pad * 0.2f, c1, 35, 4f * d, 1f, 0f)
                rect.set(inset, inset, this.width - inset, this.height - inset)
                val r = corner + (pad - inset)
                path.reset()
                path.addRoundRect(rect, r, r, android.graphics.Path.Direction.CW)
                pm.setPath(path, true)
                val len = pm.length
                val tan = FloatArray(2)
                val bars = 64
                paint.pathEffect = null
                paint.strokeCap = Paint.Cap.ROUND
                paint.strokeWidth = 3.4f * d
                for (b in 0 until 32) {
                    val tv = if (sp != null && b < sp.size) sp[b] else 0f
                    specSm[b] += (tv - specSm[b]) * (if (tv > specSm[b]) 0.55f else 0.14f)
                }
                for (i in 0 until bars) {
                    val band = if (i < bars / 2) i else bars - 1 - i
                    val v = specSm[band.coerceAtMost(31)]
                    pm.getPosTan(i * len / bars, pos, tan)
                    val h = (pad * 0.78f) * v + 2f * d
                    val k = band / 31f
                    paint.color = lerp(c1, c2, k)
                    paint.alpha = (120 + 135 * v).toInt().coerceAtMost(255)
                    canvas.drawLine(pos[0], pos[1], pos[0] + tan[1] * h, pos[1] - tan[0] * h, paint)
                }
            }
            6 -> { // Ritim: sesin gücüne göre çubuklar fırlar (albüm rengine uyumlu)
                val raw = (levelSource() * 3.6f).coerceIn(0f, 1f)
                lvl += (raw - lvl) * (if (raw > lvl) 0.65f else 0.10f)
                val inset = pad * 0.28f
                // Arkada yumuşak ışıma
                drawRing(canvas, pad * 0.2f, c1, (25 + 70 * lvl).toInt(), (5f + 5f * lvl) * d, 1f, 0f)
                rect.set(inset, inset, this.width - inset, this.height - inset)
                val r = corner + (pad - inset)
                path.reset()
                path.addRoundRect(rect, r, r, android.graphics.Path.Direction.CW)
                pm.setPath(path, true)
                val len = pm.length
                val tan = FloatArray(2)
                val bars = 64
                paint.pathEffect = null
                paint.strokeCap = Paint.Cap.ROUND
                paint.strokeWidth = 3.4f * d
                for (i in 0 until bars) {
                    pm.getPosTan(i * len / bars, pos, tan)
                    val wob = 0.30f + 0.70f * Math.abs(Math.sin((i * 1.9 + t * 2.0 * Math.PI * 7.0))).toFloat()
                    val h = (pad * 0.72f) * (0.55f * lvl + 0.75f * kick).coerceAtMost(1f) * wob + 2.5f * d
                    val nx = tan[1]
                    val ny = -tan[0]
                    // Çubuk dizisi boyunca c1 -> c2 -> c1 geçişi
                    val k = Math.abs(i - bars / 2f) / (bars / 2f)
                    paint.color = lerp(c2, c1, k)
                    paint.alpha = (140 + 115 * lvl).toInt().coerceAtMost(255)
                    canvas.drawLine(pos[0], pos[1], pos[0] + nx * h, pos[1] + ny * h, paint)
                }
            }
            5 -> { // Nefes: kapağın çevresinde yumuşakça parlayıp sönen ışıma
                val breath = 0.5f + 0.5f * Math.sin(2.0 * Math.PI * t).toFloat()
                for (i in 0 until 4) {
                    drawRing(canvas, pad * (0.75f - i * 0.15f), if (i % 2 == 0) c1 else c2,
                        ((30 + 70 * breath) * (1f - i * 0.18f)).toInt(), (3f + i * 3f) * d, 1f, 0f)
                }
            }
            2, 9 -> { // Girdap:  kapağın çevresinde merkeze doğru kıvrılan spiral kollar, müzikle hızlanır
                val raw = (levelSource() * 3.6f).coerceIn(0f, 1f)
                lvl += (raw - lvl) * (if (raw > lvl) 0.65f else 0.10f)
                val lv = maxOf(lvl, 0.12f) // ses ölçümü gelmese bile çalarken hafifçe canlı kalır
                if (lv > 0.02f) swirl = (swirl + 0.0016f + 0.011f * lv + 0.010f * kick) % 1f
                val arms = 4
                val steps = 12
                for (j in 0 until steps) {
                    val u = j / (steps - 1f)
                    val inset = pad * (0.95f - 0.9f * u)
                    rect.set(inset, inset, this.width - inset, this.height - inset)
                    val r = corner + (pad - inset)
                    path.reset()
                    path.addRoundRect(rect, r, r, android.graphics.Path.Direction.CW)
                    pm.setPath(path, true)
                    val len = pm.length
                    for (k in 0 until arms) {
                        val f = (((swirl + k / arms.toFloat() + 0.28f * u) % 1f) + 1f) % 1f
                        pm.getPosTan(f * len, pos, null)
                        spX[k * steps + j] = pos[0]
                        spY[k * steps + j] = pos[1]
                    }
                }
                paint.pathEffect = null
                paint.strokeCap = Paint.Cap.ROUND
                for (k in 0 until arms) {
                    for (j in 0 until steps - 1) {
                        val u = j / (steps - 1f)
                        paint.color = lerp(c1, c2, u)
                        paint.alpha = ((40 + 150 * u) * (0.55f + 0.45f * lv) + 60 * kick).toInt().coerceIn(0, 255)
                        paint.strokeWidth = (1.2f + 2.8f * u) * d * (1f + 0.6f * kick)
                        canvas.drawLine(spX[k * steps + j], spY[k * steps + j], spX[k * steps + j + 1], spY[k * steps + j + 1], paint)
                    }
                }
            }
            8 -> { // Damla: müziğin vuruşuyla suya düşen damla gibi yayılan halkalar
                val raw = (levelSource() * 3.6f).coerceIn(0f, 1f)
                lvl += (raw - lvl) * (if (raw > lvl) 0.65f else 0.10f)
                val lv = maxOf(lvl, 0.12f) // ses ölçümü gelmese bile çalarken hafifçe canlı kalır
                // Vuruş olmasa da ses varsa düzenli damlalar düşer (ses güçlendikçe sıklaşır)
                if (lv > 0.02f) dropAcc += 0.016f + 0.06f * lv
                val fire = kick > 0.97f || dropAcc >= 1f
                if (dropAcc >= 1f) dropAcc = 0f
                if (fire) {
                    // Yeni damla: ana halka + biraz geriden gelen ikinci halka
                    var placed = 0
                    for (i in ripples.indices) {
                        if (ripples[i] >= 1f) {
                            ripples[i] = if (placed == 0) 0f else -0.16f
                            placed++
                            if (placed == 2) break
                        }
                    }
                }
                // Kapağın hemen çevresinde sese göre parlayan ince halka
                drawRing(canvas, pad * 0.92f, c1, (50 + 150 * lv).toInt(), 2.2f * d, 1f, 0f)
                for (i in ripples.indices) {
                    val f = ripples[i]
                    if (f >= 1f) continue
                    ripples[i] = f + 0.022f
                    if (f < 0f) continue
                    val inset = pad * (0.92f - 0.88f * f)
                    val a = ((1f - f) * (1f - f) * 230f).toInt()
                    drawRing(canvas, inset, if (i % 2 == 0) c2 else c1, a, (2.8f - 1.6f * f) * d, 1f, 0f)
                }
            }
            else -> { // Nabız: kapaktan dışarı yayılan üç dalga
                for (i in 0 until 3) {
                    val f = (t * 3f + i / 3f) % 1f
                    val inset = pad * (0.9f - 0.8f * f)
                    drawRing(canvas, inset, if (i % 2 == 0) c1 else c2, ((1f - f) * 200f).toInt(), 2.5f * d, 1f, 0f)
                }
            }
        }
    }
}

