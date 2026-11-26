package io.github.vortexmusic.player

import android.Manifest
import android.app.Activity
import android.animation.ValueAnimator
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Shader
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ln

private fun alphaOf(c: Int, a: Int): Int =
    Color.argb(a, Color.red(c), Color.green(c), Color.blue(c))

// Seçilen zemin rengine göre okunur yazı rengi
private fun onColor(c: Int): Int {
    val l = (0.299f * Color.red(c) + 0.587f * Color.green(c) + 0.114f * Color.blue(c)) / 255f
    return if (l > 0.62f) Color.parseColor("#0B0B0F") else Color.WHITE
}

private fun shortFreq(f: Int): String =
    if (f >= 1000) {
        if (f % 1000 == 0) "${f / 1000}k" else String.format(Locale.US, "%.1fk", f / 1000f)
    } else {
        "$f"
    }

private fun presetLabel(name: String): String = when (name) {
    "Düz" -> tx("Düz", "Flat")
    "Doğal" -> tx("Doğal", "Natural")
    "Enstrümantal" -> tx("Enstrümantal", "Instrumental")
    "Klasik" -> tx("Klasik", "Classical")
    "Derin Bas" -> tx("Derin Bas", "Deep Bass")
    "Berrak Ses" -> tx("Berrak Ses", "Clear Sound")
    "Vokal" -> tx("Vokal", "Vocal")
    "Gece" -> tx("Gece", "Night")
    else -> name
}

private fun outputLabel(name: String): String =
    if (name == AudioFxController.WIRED_LABEL) tx("Kablolu kulaklık", "Wired headphones") else name

private fun dbInt(level: Int): String = if (level > 0) "+${level / 100}" else "${level / 100}"

// ---------- Eğri grafik: noktaları sürükleyerek ayar ----------

private class EqGraphView(
    ctx: Context,
    private val fx: AudioFxController,
    private val d: Float
) : View(ctx) {

    var accent = Color.WHITE
    var accent2 = Color.WHITE
    var textColor = Color.WHITE
    var secondary = Color.GRAY
    // Canlı spektrum (müzik çalarken eğrinin arkasında dans eder)
    var spectrum: () -> FloatArray? = { null }
    private val specSm = FloatArray(32)
    private val specPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private var specShader: LinearGradient? = null
    private var shaderKey = 0f
    var surface = Color.BLACK
    var selected = 0

    var onSelect: (Int) -> Unit = {}
    var onBeforeChange: () -> Unit = {}
    var onChange: (Int, Int) -> Unit = { _, _ -> }
    var onDone: () -> Unit = {}

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val guidePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private val fillPath = Path()
    private val rect = RectF()
    private val slop = ViewConfiguration.get(ctx).scaledTouchSlop

    private var startY = 0f
    private var startLevel = 0
    private var moved = false
    private var lastTapTime = 0L
    private var lastTapIdx = -1

    private fun plotLeft(): Float = d * 34f
    private fun plotRight(): Float = width - d * 14f
    private fun plotTop(): Float = d * 22f
    private fun plotBottom(): Float = height - d * 26f

    private fun count(): Int = minOf(fx.bandCount, fx.bandFreqs.size)

    private fun lo(): Double = ln(maxOf(20.0, fx.bandFreqs[0] / 2.2))
    private fun hi(): Double = ln(fx.bandFreqs[count() - 1] * 1.7)

    private fun xFor(i: Int): Float {
        val f = fx.bandFreqs[i].toDouble().coerceAtLeast(1.0)
        val t = (ln(f) - lo()) / (hi() - lo())
        return plotLeft() + ((plotRight() - plotLeft()) * t).toFloat()
    }

    private fun yFor(level: Int): Float {
        val span = (fx.maxLevel - fx.minLevel).coerceAtLeast(1)
        val t = (fx.maxLevel - level).toFloat() / span.toFloat()
        return plotTop() + (plotBottom() - plotTop()) * t
    }

    private fun levelAt(i: Int): Int =
        fx.levels.getOrElse(i) { 0 }.coerceIn(fx.minLevel, fx.maxLevel)

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val n = count()
        if (n == 0 || width <= 0 || height <= 0) return
        val left = plotLeft()
        val right = plotRight()
        val top = plotTop()
        val bottom = plotBottom()
        val zeroY = yFor(0)
        val sel = selected.coerceIn(0, n - 1)

        // Kılavuz çizgileri
        guidePaint.strokeWidth = d
        guidePaint.color = alphaOf(secondary, 60)
        canvas.drawLine(left, top, right, top, guidePaint)
        canvas.drawLine(left, bottom, right, bottom, guidePaint)
        guidePaint.color = alphaOf(secondary, 110)
        canvas.drawLine(left, zeroY, right, zeroY, guidePaint)

        // dB yazıları
        textPaint.color = secondary
        textPaint.textSize = d * 10f
        textPaint.textAlign = Paint.Align.RIGHT
        textPaint.typeface = Typeface.DEFAULT
        canvas.drawText(dbInt(fx.maxLevel), left - d * 6f, top + d * 4f, textPaint)
        canvas.drawText("0", left - d * 6f, zeroY + d * 4f, textPaint)
        canvas.drawText(dbInt(fx.minLevel), left - d * 6f, bottom + d * 4f, textPaint)

        // Canlı spektrum: frekansa göre çubuklar, eğrinin arkasında
        drawSpectrum(canvas, left, right, top, bottom)

        // Seçili bandın sütunu
        val sx = xFor(sel)
        val prevX = if (sel > 0) (xFor(sel - 1) + sx) / 2f else left
        val nextX = if (sel < n - 1) (xFor(sel + 1) + sx) / 2f else right
        fillPaint.color = alphaOf(accent, 28)
        canvas.drawRect(prevX, top, nextX, bottom, fillPaint)

        // Eğri: noktalardan geçen yumuşak bir çizgi (uçlar sabit düz uzar)
        val m = n + 2
        val px = FloatArray(m)
        val py = FloatArray(m)
        px[0] = left
        py[0] = yFor(levelAt(0))
        for (i in 0 until n) {
            px[i + 1] = xFor(i)
            py[i + 1] = yFor(levelAt(i))
        }
        px[m - 1] = right
        py[m - 1] = yFor(levelAt(n - 1))

        path.reset()
        path.moveTo(px[0], py[0])
        for (i in 0 until m - 1) {
            val a = maxOf(i - 1, 0)
            val b = minOf(i + 2, m - 1)
            val c1x = px[i] + (px[i + 1] - px[a]) / 6f
            val c1y = py[i] + (py[i + 1] - py[a]) / 6f
            val c2x = px[i + 1] - (px[b] - px[i]) / 6f
            val c2y = py[i + 1] - (py[b] - py[i]) / 6f
            path.cubicTo(c1x, c1y, c2x, c2y, px[i + 1], py[i + 1])
        }

        canvas.save()
        canvas.clipRect(left, top - d * 6f, right, bottom + d * 6f)
        fillPath.set(path)
        fillPath.lineTo(right, zeroY)
        fillPath.lineTo(left, zeroY)
        fillPath.close()
        fillPaint.color = alphaOf(accent, 30)
        canvas.drawPath(fillPath, fillPaint)
        // Neon parlama: geniş silik çizgiler + ince parlak çizgi
        linePaint.color = accent
        linePaint.alpha = 255
        linePaint.strokeWidth = d * 3f
        canvas.drawPath(path, linePaint)
        canvas.restore()

        // Noktalar
        for (i in 0 until n) {
            val cx = xFor(i)
            val cy = yFor(levelAt(i))
            val isSel = i == sel
            val many = n > 7
            val r = if (isSel) d * (if (many) 12f else 13f) else d * (if (many) 8.5f else 10.5f)
            fillPaint.color = if (isSel) accent else surface
            canvas.drawCircle(cx, cy, r, fillPaint)
            linePaint.color = accent
            linePaint.strokeWidth = d * 1.8f
            canvas.drawCircle(cx, cy, r, linePaint)
            textPaint.textAlign = Paint.Align.CENTER
            textPaint.textSize = d * (if (many) (if (isSel) 11f else 9.5f) else (if (isSel) 12f else 11f))
            textPaint.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            textPaint.color = if (isSel) onColor(accent) else textColor
            canvas.drawText(
                "${i + 1}", cx,
                cy - (textPaint.descent() + textPaint.ascent()) / 2f, textPaint
            )
        }

        // Frekans yazıları
        textPaint.textAlign = Paint.Align.CENTER
        textPaint.textSize = d * 10f
        for (i in 0 until n) {
            val isSel = i == sel
            textPaint.typeface = if (isSel) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            textPaint.color = if (isSel) textColor else secondary
            canvas.drawText(shortFreq(fx.bandFreqs[i]), xFor(i), bottom + d * 18f, textPaint)
        }

        // Seçili noktanın üstünde değer etiketi
        val label = String.format(Locale.US, "%+.1f dB", levelAt(sel) / 100f)
        textPaint.textAlign = Paint.Align.CENTER
        textPaint.textSize = d * 11f
        textPaint.typeface = Typeface.DEFAULT_BOLD
        val tw = textPaint.measureText(label)
        val bx = sx.coerceIn(left + tw / 2f + d * 8f, right - tw / 2f - d * 8f)
        val ny = yFor(levelAt(sel))
        var by = ny - d * 30f
        if (by - d * 13f < 0f) by = ny + d * 42f
        rect.set(bx - tw / 2f - d * 8f, by - d * 13f, bx + tw / 2f + d * 8f, by + d * 7f)
        fillPaint.color = accent
        canvas.drawRoundRect(rect, d * 10f, d * 10f, fillPaint)
        textPaint.color = onColor(accent)
        canvas.drawText(
            label, bx,
            (by - d * 3f) - (textPaint.descent() + textPaint.ascent()) / 2f, textPaint
        )
    }

    private fun drawSpectrum(canvas: Canvas, left: Float, right: Float, top: Float, bottom: Float) {
        val sp = spectrum()
        var alive = false
        val key = top + bottom * 3f + right
        if (specShader == null || shaderKey != key) {
            specShader = LinearGradient(
                0f, top, 0f, bottom,
                intArrayOf(alphaOf(accent2, 150), alphaOf(accent, 60), alphaOf(accent, 8)),
                floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP
            )
            shaderKey = key
        }
        specPaint.shader = specShader
        val h = (bottom - top) * 0.92f
        val l0 = lo()
        val span = hi() - l0
        for (b in 0 until 32) {
            val tv = if (sp != null && b < sp.size) sp[b] else 0f
            specSm[b] += (tv - specSm[b]) * (if (tv > specSm[b]) 0.5f else 0.12f)
            if (specSm[b] > 0.01f) alive = true
            val f0 = 40.0 * Math.pow(400.0, b.toDouble() / 32.0)
            val f1 = 40.0 * Math.pow(400.0, (b + 1).toDouble() / 32.0)
            val x0 = left + ((ln(f0) - l0) / span * (right - left)).toFloat()
            val x1 = left + ((ln(f1) - l0) / span * (right - left)).toFloat()
            val gap = d * 1.2f
            val bh = specSm[b] * h
            if (bh > d) {
                rect.set(maxOf(left, x0 + gap), bottom - bh, minOf(right, x1 - gap), bottom)
                canvas.drawRoundRect(rect, d * 2f, d * 2f, specPaint)
            }
        }
        specPaint.shader = null
        // Müzik çalıyorsa akıcı yenile, sessizse durup pil harcama
        if (isShown) {
            if (alive || (sp != null && sp.any { it > 0.01f })) postInvalidateOnAnimation()
            else postInvalidateDelayed(300L)
        }
    }

    private fun nearest(x: Float): Int {
        var best = 0
        var bd = Float.MAX_VALUE
        for (i in 0 until count()) {
            val dd = abs(x - xFor(i))
            if (dd < bd) {
                bd = dd
                best = i
            }
        }
        return best
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (count() == 0) return false
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                selected = nearest(e.x)
                startY = e.y
                startLevel = levelAt(selected)
                moved = false
                onSelect(selected)
                invalidate()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val dy = e.y - startY
                if (!moved && abs(dy) > slop) {
                    moved = true
                    onBeforeChange()
                }
                if (moved) {
                    val span = (fx.maxLevel - fx.minLevel).toFloat()
                    val h = (plotBottom() - plotTop()).coerceAtLeast(1f)
                    val raw = startLevel - dy / h * span
                    val snapped = (Math.round(raw / 10f) * 10).coerceIn(fx.minLevel, fx.maxLevel)
                    if (snapped != levelAt(selected)) onChange(selected, snapped)
                    invalidate()
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                if (moved) {
                    onDone()
                } else {
                    val now = System.currentTimeMillis()
                    if (lastTapIdx == selected && now - lastTapTime < 350L) {
                        lastTapTime = 0L
                        lastTapIdx = -1
                        onBeforeChange()
                        onChange(selected, 0)
                        onDone()
                    } else {
                        lastTapTime = now
                        lastTapIdx = selected
                    }
                    invalidate()
                }
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                if (moved) onDone()
                return true
            }
        }
        return super.onTouchEvent(e)
    }
}

// ---------- Ses ekranı ----------

// Kilitliyken içindeki hiçbir düğme ya da kaydırıcı dokunuş almaz; liste yine de kaydırılır
private class LockLayout(context: Context) : LinearLayout(context) {
    var locked = false

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean = locked

    override fun onTouchEvent(event: MotionEvent): Boolean =
        if (locked) false else super.onTouchEvent(event)
}

class AudioScreen(private val ui: Ui, private val fxProvider: () -> AudioFxController?) {

    private val mp = ViewGroup.LayoutParams.MATCH_PARENT
    private val wc = ViewGroup.LayoutParams.WRAP_CONTENT

    private val content = LockLayout(ui.activity)
    private val audioPrefs = ui.activity.getSharedPreferences("vortex_audio", Context.MODE_PRIVATE)
    private val lockBar = TextView(ui.activity)
    private val lockBtn = LockButtonView(ui.activity, ui.density)
    private val lockRow = LinearLayout(ui.activity)
    val view: LinearLayout = ui.overlay()

    private var selectedBand = 0
    private var onOffTile: EffectTileView? = null
    private var toggleHint: TextView? = null
    private var graph: EqGraphView? = null
    private var bandTitle: TextView? = null
    private var bandFreq: TextView? = null
    private var bandGain: TextView? = null
    private var presetBox: LinearLayout? = null
    private var profileBox: LinearLayout? = null

    val isOpen: Boolean
        get() = view.visibility == View.VISIBLE

    init {
        content.orientation = LinearLayout.VERTICAL
        val scroll = ScrollView(ui.activity)
        scroll.isVerticalScrollBarEnabled = false
        scroll.layoutParams = LinearLayout.LayoutParams(mp, 0, 1f).apply { topMargin = ui.dp(8) }
        scroll.addView(content)
        lockBar.textSize = 14f
        lockBar.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        lockBar.layoutParams = LinearLayout.LayoutParams(0, wc, 1f)
        lockBtn.layoutParams = LinearLayout.LayoutParams(ui.dp(48), ui.dp(48))
        lockBtn.accent = ui.color(R.color.vortex_purple)
        lockRow.orientation = LinearLayout.HORIZONTAL
        lockRow.gravity = Gravity.CENTER_VERTICAL
        lockRow.setPadding(ui.dp(18), ui.dp(6), ui.dp(8), ui.dp(6))
        lockRow.layoutParams = LinearLayout.LayoutParams(mp, wc).apply {
            topMargin = ui.dp(8)
            marginStart = ui.dp(16)
            marginEnd = ui.dp(16)
        }
        lockRow.addView(lockBar)
        lockRow.addView(lockBtn)
        lockRow.setOnClickListener { applyLock(!content.locked, true) }
        applyLock(audioPrefs.getBoolean("eqlock", false), false)
        view.addView(ui.header(tx("Ses", "Audio")) { hide() })
        view.addView(lockRow)
        view.addView(scroll)
    }

    fun show() {
        render()
        view.visibility = View.VISIBLE
    }

    fun hide() {
        view.visibility = View.GONE
    }

    // Material 3 hap: etkin = düz mor, pasif = hafif tonlu yüzey
    private fun m3Pill(active: Boolean): GradientDrawable = GradientDrawable().apply {
        val accent = ui.color(R.color.vortex_purple)
        setColor(if (active) accent else ui.mix(ui.color(R.color.vortex_surface), accent, 0.16f))
        cornerRadius = ui.dp(24).toFloat()
    }

    private fun chip(label: String, active: Boolean): TextView = TextView(ui.activity).apply {
        text = label
        textSize = 14f
        setPadding(ui.dp(16), ui.dp(8), ui.dp(16), ui.dp(8))
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        setTextColor(if (active) Color.WHITE else ui.color(R.color.vortex_text))
        background = m3Pill(active)
        layoutParams = LinearLayout.LayoutParams(wc, wc).apply { marginEnd = ui.dp(8) }
    }

    // Tam genişlikte düğme
    private fun wide(label: String, active: Boolean, onClick: () -> Unit): TextView =
        TextView(ui.activity).apply {
            text = label
            textSize = 15f
            gravity = Gravity.CENTER
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(if (active) Color.WHITE else ui.color(R.color.vortex_text))
            background = m3Pill(active)
            setPadding(ui.dp(16), ui.dp(12), ui.dp(16), ui.dp(12))
            layoutParams = LinearLayout.LayoutParams(mp, wc).apply { topMargin = ui.dp(8) }
            setOnClickListener { onClick() }
        }

    private fun needsBluetoothPermission(): Boolean =
        Build.VERSION.SDK_INT >= 31 &&
            ui.activity.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) !=
            PackageManager.PERMISSION_GRANTED

    private fun toggleLabel(f: AudioFxController): String =
        if (f.enabled) tx("Ses efektleri: Açık", "Audio effects: On") else tx("Ses efektleri: Kapalı", "Audio effects: Off")

    private fun refreshToggle(f: AudioFxController) {
        onOffTile?.let {
            it.on = f.enabled
            it.valueText = if (f.enabled) tx("Açık", "On") else tx("Kapalı", "Off")
        }
        toggleHint?.text = toggleHintText(f)
    }

    // ---------- Küçük açıklama yazıları (rahatsız etmeyecek şekilde sönük ve kısa) ----------

    private fun hintText(t: String): TextView = TextView(ui.activity).apply {
        text = t
        textSize = 12f
        setTextColor(ui.color(R.color.vortex_text_secondary))
        alpha = 0.85f
        setPadding(ui.dp(2), ui.dp(2), ui.dp(2), ui.dp(6))
    }

    private fun toggleHintText(f: AudioFxController): String =
        if (f.enabled) {
            tx(
                "Açık: ekolayzır ve efektler müziğe uygulanıyor.",
                "On: the equalizer and effects are applied to your music."
            )
        } else {
            tx(
                "Kapalı: müzik hiçbir değişiklik olmadan çalıyor. Bir ayara dokunursan kendiliğinden açılır.",
                "Off: music plays unchanged. It turns on by itself when you touch a setting."
            )
        }

    private fun bassHint(p: Int): String = when {
        p == 0 -> tx("Kapalı: bas olduğu gibi duyulur.", "Off: the bass is left as it is.")
        p < 350 -> tx("Hafif: bas biraz daha belirgin olur.", "Light: the bass becomes a little more noticeable.")
        p < 700 -> tx("Orta: bas daha dolgun ve hissedilir olur.", "Medium: the bass gets fuller and easier to feel.")
        else -> tx(
            "Güçlü: çok dolgun bas, küçük hoparlörde boğuk gelebilir.",
            "Strong: very full bass, may sound muddy on small speakers."
        )
    }

    private fun loudHint(p: Int): String = when {
        p == 0 -> tx("Kapalı: ses seviyesi değişmez.", "Off: the volume level is unchanged.")
        p < 400 -> tx("Hafif: biraz daha yüksek ve dolgun duyulur.", "Light: a little louder and fuller.")
        p < 700 -> tx(
            "Orta: belirgin şekilde daha yüksek, sınırlayıcı bozulmayı önler.",
            "Medium: clearly louder, the limiter prevents distortion."
        )
        else -> tx(
            "Yüksek: çok yüksek, sesin canlılığı (yumuşak-sert farkı) azalır.",
            "High: very loud, the dynamics (soft vs. loud parts) are reduced."
        )
    }

    private fun widthHint(p: Int): String = when {
        p == 0 -> tx("Kapalı: stereo olduğu gibi çalar.", "Off: stereo plays as it is.")
        p < 350 -> tx("Hafif: ses sahnesi biraz genişler.", "Light: the sound stage widens a little.")
        p < 700 -> tx(
            "Orta: enstrümanlar birbirinden daha ayrı duyulur.",
            "Medium: instruments are heard more separately."
        )
        else -> tx(
            "Geniş: çok geniş sahne, vokal biraz geride kalabilir.",
            "Wide: a very wide stage, vocals may sit further back."
        )
    }

    private fun card(): LinearLayout = LinearLayout(ui.activity).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(ui.dp(16), ui.dp(16), ui.dp(16), ui.dp(16))
        background = GradientDrawable().apply {
            setColor(ui.mix(ui.color(R.color.vortex_surface), ui.color(R.color.vortex_purple), 0.07f))
            cornerRadius = ui.dp(28).toFloat()
        }
        layoutParams = LinearLayout.LayoutParams(mp, wc)
    }

    private fun knobColumn(control: View, label: String, value: TextView?): LinearLayout {
        val col = LinearLayout(ui.activity)
        col.orientation = LinearLayout.VERTICAL
        col.gravity = Gravity.CENTER_HORIZONTAL
        col.layoutParams = LinearLayout.LayoutParams(0, wc, 1f)
        control.layoutParams = LinearLayout.LayoutParams(ui.dp(84), ui.dp(84))
        col.addView(control)
        col.addView(TextView(ui.activity).apply {
            text = label
            textSize = 13f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(ui.color(R.color.vortex_text))
            setPadding(0, ui.dp(8), 0, 0)
        })
        if (value != null) col.addView(value)
        return col
    }

    private fun smallValue(t: String): TextView = TextView(ui.activity).apply {
        text = t
        textSize = 12f
        gravity = Gravity.CENTER
        setTextColor(ui.color(R.color.vortex_text_secondary))
    }

    private fun enableIfOff(f: AudioFxController) {
        if (!f.enabled) {
            f.enabled = true
            refreshToggle(f)
        }
    }

    // Kaydırıcı + altında değere göre değişen küçük açıklama
    private fun effectSlider(
        label: String,
        start: Int,
        format: (Int) -> String,
        hint: (Int) -> String,
        onChange: (Int) -> Unit,
        onDone: () -> Unit
    ): View {
        val box = LinearLayout(ui.activity)
        box.orientation = LinearLayout.VERTICAL
        val h = hintText(hint(start))
        box.addView(ui.slider(label, 1000, start, format, { p ->
            h.text = hint(p)
            onChange(p)
        }, onDone))
        box.addView(h)
        return box
    }

    private fun updateBandInfo(f: AudioFxController) {
        val i = selectedBand
        bandTitle?.text = tx("Bant ${i + 1}", "Band ${i + 1}")
        bandFreq?.text = ui.fmtFreq(f.bandFreqs.getOrElse(i) { 0 })
        val lv = f.levels.getOrElse(i) { 0 }
        bandGain?.text = String.format(Locale.US, "%+.1f dB", lv / 100f)
        graph?.invalidate()
    }

    // Küçük düğmelerle ince ayar
    private fun nudge(f: AudioFxController, delta: Int, reset: Boolean) {
        val i = selectedBand
        if (i !in 0 until f.bandCount) return
        if (!f.enabled) {
            f.enabled = true
            f.applyAll()
        }
        val cur = f.levels.getOrElse(i) { 0 }
        val target = if (reset) 0 else cur + delta
        f.setBand(i, target.coerceIn(f.minLevel, f.maxLevel))
        f.save()
        graph?.alpha = 1f
        refreshToggle(f)
        updateBandInfo(f)
        refreshChips(f)
    }

    private fun fillPresets(f: AudioFxController) {
        val box = presetBox ?: return
        box.removeAllViews()
        val scroll = HorizontalScrollView(ui.activity)
        scroll.isHorizontalScrollBarEnabled = false
        val row = LinearLayout(ui.activity)
        row.orientation = LinearLayout.HORIZONTAL
        for (preset in f.presets) {
            val c = chip(presetLabel(preset.name), f.enabled && (f.matches(preset) || f.lastPreset == preset.name))
            c.setOnClickListener {
                f.setPreset(preset)
                render()
            }
            row.addView(c)
        }
        scroll.addView(row)
        box.addView(scroll)
    }

    private fun fillProfiles(f: AudioFxController) {
        val box = profileBox ?: return
        box.removeAllViews()
        if (f.profiles.isEmpty()) {
            box.addView(
                ui.noteText(
                    tx(
                        "Henüz profil yok. Ayarlarını yapıp \"Profil olarak kaydet\" de.",
                        "No profiles yet. Adjust your settings and tap \"Save as profile\"."
                    )
                )
            )
            return
        }
        val scroll = HorizontalScrollView(ui.activity)
        scroll.isHorizontalScrollBarEnabled = false
        val row = LinearLayout(ui.activity)
        row.orientation = LinearLayout.HORIZONTAL
        for (prof in f.profiles.toList()) {
            val label = if (prof.headphone.isEmpty()) prof.name else "${prof.name} · ${outputLabel(prof.headphone)}"
            val c = chip(label, f.enabled && f.matchesProfile(prof))
            c.setOnClickListener {
                f.applyProfile(prof)
                render()
            }
            c.setOnLongClickListener {
                profileOptions(prof.name)
                true
            }
            row.addView(c)
        }
        scroll.addView(row)
        box.addView(scroll)
        box.addView(ui.noteText(tx("Silmek için profile uzun bas.", "Long-press a profile to delete it.")))
    }

    private fun refreshChips(f: AudioFxController) {
        fillPresets(f)
        fillProfiles(f)
    }

    fun render() {
        content.removeAllViews()
        toggleHint = null
        graph = null
        bandTitle = null
        bandFreq = null
        bandGain = null
        presetBox = null
        profileBox = null

        val f = fxProvider()
        if (f == null) {
            content.addView(ui.noteText(tx("Ses motoru henüz hazır değil. Birkaç saniye sonra tekrar aç.", "The audio engine is not ready yet. Open this again in a few seconds.")))
            return
        }

        // Motor adı
        val cap = LinearLayout(ui.activity)
        cap.orientation = LinearLayout.HORIZONTAL
        cap.gravity = Gravity.CENTER_VERTICAL
        cap.layoutParams = LinearLayout.LayoutParams(mp, wc).apply { topMargin = ui.dp(2) }
        cap.addView(TextView(ui.activity).apply {
            text = "VORTEX AUDIO ENGINE"
            textSize = 12f
            letterSpacing = 0.12f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(ui.color(R.color.vortex_purple))
        })
        cap.addView(TextView(ui.activity).apply {
            text = tx("  ·  10 bant · sınırlayıcı", "  ·  10 bands · limiter")
            textSize = 12f
            setTextColor(ui.color(R.color.vortex_text_secondary))
        })
        content.addView(cap)

        // Saf mod: tüm efektleri atlar, ses dosyadaki gibi çıkar (ayarlar silinmez)
        fun pureLabel() = if (f.pure) tx("Saf mod: AÇIK (efektler atlanıyor)", "Pure mode: ON (effects bypassed)")
        else tx("Saf mod: Kapalı", "Pure mode: Off")
        fun pureHint() = if (f.pure) tx(
            "Ekolayzır, bas, genişlik, bas koruyucu ve otomatik denge devre dışı. Ses dosyadaki gibi çıkar. Ölçüm ve karşılaştırma için.",
            "Equalizer, bass, width, bass protector and auto level are bypassed. Sound is output as in the file. For measuring and comparing."
        ) else tx(
            "Açınca tüm efektler tek dokunuşla atlanır. Ayarların silinmez, kapatınca geri gelir.",
            "Turn on to bypass every effect in one tap. Your settings are kept and come back when you turn it off."
        )
        val pureHintView = hintText(pureHint())
        val pureBtn = PureCardView(ui.activity, ui.density).apply {
            accent = ui.color(R.color.vortex_purple)
            surface = ui.color(R.color.vortex_surface)
            textColor = ui.color(R.color.vortex_text)
            subColor = ui.color(R.color.vortex_text_secondary)
            title = tx("Saf mod", "Pure mode")
            offText = tx("Efektler çalışıyor", "Effects are active")
            onText = tx("Ses dosyadaki gibi, efektsiz", "Sound as in the file, no effects")
            setOn(f.pure, false)
        }
        pureBtn.setOnClickListener {
            f.pure = !f.pure
            f.applyAll()
            f.save()
            pureBtn.setOn(f.pure, true)
            pureHintView.text = pureHint()
            dimForPure(f)
        }
        pureBtn.layoutParams = LinearLayout.LayoutParams(mp, ui.dp(76)).apply { topMargin = ui.dp(10) }
        content.addView(pureBtn)
        content.addView(pureHintView)

        // ---------- Ekolayzır: eğri grafik ----------
        content.addView(ui.sectionTitle(tx("Ekolayzır", "Equalizer")))
        if (!f.eqAvailable || f.bandCount == 0) {
            content.addView(ui.noteText(tx("Bu telefon ekolayzırı desteklemiyor.", "This phone does not support the equalizer.")))
        } else {
            if (selectedBand !in 0 until f.bandCount) selectedBand = 0

            val card = LinearLayout(ui.activity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(ui.dp(10), ui.dp(12), ui.dp(10), ui.dp(14))
                background = GradientDrawable().apply {
                    setColor(ui.color(R.color.vortex_surface))
                    cornerRadius = ui.dp(28).toFloat()
                }
                layoutParams = LinearLayout.LayoutParams(mp, wc)
            }

            val g = EqGraphView(ui.activity, f, ui.density)
            g.accent = ui.color(R.color.vortex_purple)
            g.accent2 = if (ThemeStore.mono()) g.accent else ui.color(R.color.vortex_blue)
            g.spectrum = { f.eqProc.spectrum }
            g.textColor = ui.color(R.color.vortex_text)
            g.secondary = ui.color(R.color.vortex_text_secondary)
            g.surface = ui.color(R.color.vortex_surface)
            g.selected = selectedBand
            g.alpha = if (f.enabled) 1f else 0.5f
            g.layoutParams = LinearLayout.LayoutParams(mp, ui.dp(220))
            g.onSelect = { i ->
                selectedBand = i
                updateBandInfo(f)
            }
            g.onBeforeChange = {
                if (!f.enabled) {
                    f.enabled = true
                    f.applyAll()
                    g.alpha = 1f
                    refreshToggle(f)
                }
            }
            g.onChange = { i, lv ->
                f.setBand(i, lv)
                updateBandInfo(f)
            }
            g.onDone = {
                f.save()
                updateBandInfo(f)
                refreshChips(f)
            }
            graph = g
            card.addView(g)

            // Seçili bandın bilgisi
            val info = LinearLayout(ui.activity)
            info.orientation = LinearLayout.HORIZONTAL
            info.gravity = Gravity.CENTER_VERTICAL
            info.setPadding(ui.dp(6), ui.dp(8), ui.dp(6), 0)

            val title = TextView(ui.activity).apply {
                textSize = 16f
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                setTextColor(ui.color(R.color.vortex_text))
                layoutParams = LinearLayout.LayoutParams(0, wc, 1f)
            }
            val freq = TextView(ui.activity).apply {
                textSize = 14f
                setTextColor(ui.color(R.color.vortex_text_secondary))
                setPadding(ui.dp(8), 0, ui.dp(12), 0)
            }
            val gain = TextView(ui.activity).apply {
                textSize = 16f
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                setTextColor(ui.color(R.color.vortex_text))
            }
            bandTitle = title
            bandFreq = freq
            bandGain = gain
            info.addView(title)
            info.addView(freq)
            info.addView(gain)
            card.addView(info)

            val adj = LinearLayout(ui.activity)
            adj.orientation = LinearLayout.HORIZONTAL
            adj.layoutParams = LinearLayout.LayoutParams(mp, wc).apply { topMargin = ui.dp(10) }
            adj.addView(ui.actionPill("− 0.5 dB") { nudge(f, -50, false) })
            adj.addView(ui.actionPill(tx("Sıfırla", "Reset")) { nudge(f, 0, true) })
            adj.addView(ui.actionPill("+ 0.5 dB") { nudge(f, 50, false) })
            card.addView(adj)

            card.addView(
                ui.noteText(
                    tx(
                        "Bir noktayı yukarı ya da aşağı sürükle, çift dokununca sıfırlanır. " +
                            "Vortex Audio Engine'in 10 bantlı ekolayzırı. Yüksek artışlarda bozulmayı önlemek için ses otomatik biraz kısılır.",
                        "Drag a point up or down; double-tap to reset it. " +
                            "This is the 10-band equalizer of Vortex Audio Engine. With big boosts the volume is lowered a little automatically to avoid distortion."
                    )
                )
            )
            content.addView(card)
            updateBandInfo(f)
        }

        // ---------- Hazır ayarlar ----------
        content.addView(ui.sectionTitle(tx("Hazır ayarlar", "Presets")))
        val pb = LinearLayout(ui.activity)
        pb.orientation = LinearLayout.VERTICAL
        presetBox = pb
        content.addView(pb)
        fillPresets(f)

        // ---------- Kulaklık düzeltme ----------
        content.addView(ui.sectionTitle(tx("Kulaklık düzeltme", "Headphone correction")))
        val corrNames = listOf(
            tx("Kapalı", "Off"), tx("Kulak içi", "Earbuds"),
            tx("Kulak üstü", "Over-ear"), tx("Boğuk ses", "Muffled")
        )
        val corrHints = listOf(
            tx("Kapalı: kulaklığın sesi olduğu gibi duyulur.", "Off: your headphones sound as they are."),
            tx("Kulak içi kulaklıklarda bası ve tizi biraz destekler, sert tonları yumuşatır.",
                "Gives earbuds a little more bass and treble and softens harsh tones."),
            tx("Kulak üstü kulaklıklarda hafif bas desteği verir, tizi yumuşatır.",
                "Adds a light bass lift to over-ear headphones and softens the treble."),
            tx("Bulanık ve fazla bas veren kulaklıklarda bası azaltır, tizi açar.",
                "Reduces bass and muddiness on boomy headphones and opens up the treble.")
        )
        val corrHint = hintText(corrHints[f.correction.coerceIn(0, 3)])
        val corrRow = LinearLayout(ui.activity)
        corrRow.orientation = LinearLayout.HORIZONTAL
        val corrScroll = HorizontalScrollView(ui.activity)
        corrScroll.isHorizontalScrollBarEnabled = false
        val corrChips = ArrayList<TextView>()
        for (idx in corrNames.indices) {
            val c = chip(corrNames[idx], f.correction == idx)
            c.setOnClickListener {
                f.correction = idx
                enableIfOff(f)
                f.applyAll()
                f.save()
                for (j in corrChips.indices) {
                    corrChips[j].setTextColor(if (j == idx) Color.WHITE else ui.color(R.color.vortex_text))
                    corrChips[j].background = ui.pillBackground(j == idx)
                }
                corrHint.text = corrHints[idx]
            }
            corrChips.add(c)
            corrRow.addView(c)
        }
        corrScroll.addView(corrRow)
        content.addView(corrScroll)
        corrHint.setPadding(ui.dp(4), ui.dp(8), ui.dp(4), 0)
        content.addView(corrHint)

        // ---------- Efektler (doldurma kutuları) ----------
        content.addView(ui.sectionTitle(tx("Efektler", "Effects")))
        val accent = ui.color(R.color.vortex_purple)
        val track = ui.color(R.color.vortex_surface)
        val th = hintText(toggleHintText(f))
        toggleHint = th

        fun fxTile(title: String, start: Int, fmt: (Int) -> String, hint: (Int) -> String,
                   set: (Int) -> Unit): EffectTileView {
            val t = EffectTileView(ui.activity, ui.density)
            t.title = title
            t.accent = accent
            t.track = track
            t.textColor = ui.color(R.color.vortex_text)
            t.subColor = ui.color(R.color.vortex_text_secondary)
            t.value = start
            t.valueText = fmt(start)
            t.onChange = { p ->
                enableIfOff(f)
                set(p)
                f.applyAll()
                t.valueText = fmt(p)
                th.text = hint(p)
            }
            t.onDone = {
                f.save()
                refreshChips(f)
            }
            return t
        }

        val bassTile = fxTile(tx("Bas güçlendirici", "Bass enhancer"), f.bassStrength,
            { "${it / 10}%" }, { bassHint(it) }) { f.bassStrength = it }
        val loudTile = fxTile(tx("Ses yükseltme", "Loudness boost"), f.loudnessMb,
            { String.format(Locale.US, "+%.1f dB", it / 100f) }, { loudHint(it) }) { f.loudnessMb = it }
        val widthTile = fxTile(tx("Stereo genişlik", "Stereo width"), f.virtStrength,
            { "${it / 10}%" }, { widthHint(it) }) { f.virtStrength = it }

        val onOff = EffectTileView(ui.activity, ui.density)
        onOffTile = onOff
        onOff.title = tx("Ses efektleri", "Audio effects")
        onOff.accent = accent
        onOff.track = track
        onOff.textColor = ui.color(R.color.vortex_text)
        onOff.subColor = ui.color(R.color.vortex_text_secondary)
        onOff.isToggle = true
        onOff.on = f.enabled
        onOff.valueText = if (f.enabled) tx("Açık", "On") else tx("Kapalı", "Off")
        onOff.onTap = {
            f.enabled = !f.enabled
            f.applyAll()
            f.save()
            graph?.alpha = if (f.enabled) 1f else 0.5f
            onOff.on = f.enabled
            onOff.valueText = if (f.enabled) tx("Açık", "On") else tx("Kapalı", "Off")
            th.text = toggleHintText(f)
            refreshChips(f)
        }

        fun tileRow(a: View, b: View, top: Int): LinearLayout {
            val r = LinearLayout(ui.activity)
            r.orientation = LinearLayout.HORIZONTAL
            r.layoutParams = LinearLayout.LayoutParams(mp, wc).apply { topMargin = ui.dp(top) }
            a.layoutParams = LinearLayout.LayoutParams(0, ui.dp(84), 1f).apply { marginEnd = ui.dp(5) }
            b.layoutParams = LinearLayout.LayoutParams(0, ui.dp(84), 1f).apply { marginStart = ui.dp(5) }
            r.addView(a)
            r.addView(b)
            return r
        }
        content.addView(tileRow(bassTile, loudTile, 0))
        content.addView(tileRow(widthTile, onOff, 10))
        th.setPadding(ui.dp(4), ui.dp(10), ui.dp(4), 0)
        content.addView(th)

        // Otomatik ses dengeleme (efekt anahtarından bağımsız çalışır)
        fun autoLabel() = if (f.autoLevel) tx("Otomatik ses dengeleme: Açık", "Auto volume leveling: On")
        else tx("Otomatik ses dengeleme: Kapalı", "Auto volume leveling: Off")
        val autoBtn = wide(autoLabel(), f.autoLevel) {}
        autoBtn.setOnClickListener {
            f.autoLevel = !f.autoLevel
            f.applyAll()
            f.save()
            autoBtn.text = autoLabel()
            autoBtn.setTextColor(if (f.autoLevel) Color.WHITE else ui.color(R.color.vortex_text))
            autoBtn.background = ui.pillBackground(f.autoLevel)
        }
        autoBtn.layoutParams = LinearLayout.LayoutParams(mp, wc).apply { topMargin = ui.dp(14) }
        content.addView(autoBtn)
        content.addView(hintText(tx(
            "Şarkılar arası ses farkını kendiliğinden azaltır: çok yüksek şarkıyı biraz kısar, kısık olanı biraz açar.",
            "Reduces volume differences between songs: lowers very loud tracks a little and lifts quiet ones."
        )))

        // Animasyon senkronu: çubuklar ve girdap sesle birlikte hareket etsin
        content.addView(ui.sectionTitle(tx("Animasyon senkronu", "Animation sync")))
        val syncRow = LinearLayout(ui.activity)
        syncRow.orientation = LinearLayout.HORIZONTAL
        syncRow.gravity = Gravity.CENTER_VERTICAL
        syncRow.layoutParams = LinearLayout.LayoutParams(mp, wc)
        // Ortadaki kutu: ayar 0'dan farklıysa mor yanar, dokununca sıfırlar
        val syncChip = TextView(ui.activity)
        fun paintSync() {
            val v = f.syncAdjust
            val on = !f.syncOff
            syncChip.text = when {
                f.syncOff -> tx("Kapalı", "Off")
                v == 0 -> tx("Otomatik", "Auto")
                v > 0 -> "+$v ms"
                else -> "$v ms"
            }
            syncChip.setTextColor(if (on) Color.WHITE else ui.color(R.color.vortex_text))
            syncChip.background = ui.pillBackground(on)
        }
        fun nudgeSync(delta: Int) {
            f.syncOff = false
            f.syncAdjust = (f.syncAdjust + delta).coerceIn(-100, 300)
            f.applySync()
            f.save()
            paintSync()
        }
        val minus = ui.actionPill("−20") { nudgeSync(-20) }
        val plus = ui.actionPill("+20") { nudgeSync(20) }
        syncChip.gravity = Gravity.CENTER
        syncChip.textSize = 14f
        syncChip.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        syncChip.setPadding(ui.dp(12), ui.dp(10), ui.dp(12), ui.dp(10))
        syncChip.layoutParams = LinearLayout.LayoutParams(0, wc, 1.4f).apply { marginEnd = ui.dp(6) }
        // Dokununca: Kapalı -> Otomatik, elle ayar -> Otomatik, Otomatik -> Kapalı
        syncChip.setOnClickListener {
            if (f.syncOff) {
                f.syncOff = false
                f.syncAdjust = 0
            } else if (f.syncAdjust != 0) {
                f.syncAdjust = 0
            } else {
                f.syncOff = true
            }
            f.applySync()
            f.save()
            paintSync()
        }
        paintSync()
        syncRow.addView(minus)
        syncRow.addView(syncChip)
        syncRow.addView(plus)
        content.addView(syncRow)
        content.addView(hintText(tx(
            "Otomatik: çıkış cihazına göre ayarlanır. Animasyon sesten önce hareket ediyorsa +, sonra hareket ediyorsa − bas. Ortadaki kutuya dokununca sırayla Otomatik ve Kapalı olur.",
            "Auto: set from the output device. If the animation moves before the sound, tap +; if after, tap −. Tap the middle box to switch between Auto and Off."
        )))

        // ---------- Profillerim ----------
        content.addView(ui.sectionTitle(tx("Profillerim", "My profiles")))
        val prCard = card()
        val prb = LinearLayout(ui.activity)
        prb.orientation = LinearLayout.VERTICAL
        profileBox = prb
        prCard.addView(prb)
        fillProfiles(f)
        val actions = LinearLayout(ui.activity)
        actions.orientation = LinearLayout.HORIZONTAL
        actions.layoutParams = LinearLayout.LayoutParams(mp, wc).apply { topMargin = ui.dp(12) }
        actions.addView(ui.actionPill(tx("Dışa aktar", "Export")) { exportProfiles() })
        actions.addView(ui.actionPill(tx("İçe aktar", "Import")) { importProfiles() })
        prCard.addView(actions)
        prCard.addView(wide(tx("Profil olarak kaydet", "Save as profile"), true) { askSaveProfile() })
        content.addView(prCard)

        // ---------- Çıkış cihazı ----------
        content.addView(ui.sectionTitle(tx("Çıkış cihazı", "Output device")))
        val outCard = card()
        val outName = if (f.currentOutput.isEmpty()) tx("Telefon hoparlörü", "Phone speaker") else outputLabel(f.currentOutput)
        outCard.addView(TextView(ui.activity).apply {
            text = tx("Şu an", "Now")
            textSize = 12f
            setTextColor(ui.color(R.color.vortex_text_secondary))
        })
        outCard.addView(TextView(ui.activity).apply {
            text = outName
            textSize = 18f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(ui.color(R.color.vortex_text))
        })
        if (f.autoProfileName.isNotEmpty()) {
            outCard.addView(TextView(ui.activity).apply {
                text = tx("Profil: ${f.autoProfileName}", "Profile: ${f.autoProfileName}")
                textSize = 13f
                setTextColor(accent)
            })
        }
        val autoRow = LinearLayout(ui.activity)
        autoRow.orientation = LinearLayout.HORIZONTAL
        autoRow.gravity = Gravity.CENTER_VERTICAL
        autoRow.layoutParams = LinearLayout.LayoutParams(mp, wc).apply { topMargin = ui.dp(16) }
        val autoText = LinearLayout(ui.activity)
        autoText.orientation = LinearLayout.VERTICAL
        autoText.layoutParams = LinearLayout.LayoutParams(0, wc, 1f)
        autoText.addView(TextView(ui.activity).apply {
            text = tx("Cihaza göre otomatik EQ", "Automatic EQ by device")
            textSize = 15f
            setTextColor(ui.color(R.color.vortex_text))
        })
        autoText.addView(TextView(ui.activity).apply {
            text = tx(
                "Kulaklık bağlanınca ona kayıtlı profil kendiliğinden uygulanır.",
                "When headphones connect, the profile saved for them is applied automatically."
            )
            textSize = 12f
            setTextColor(ui.color(R.color.vortex_text_secondary))
        })
        autoRow.addView(autoText)
        autoRow.addView(TextView(ui.activity).apply {
            text = if (f.autoEnabled) tx("Açık", "On") else tx("Kapalı", "Off")
            textSize = 14f
            gravity = Gravity.CENTER
            setTextColor(if (f.autoEnabled) Color.WHITE else ui.color(R.color.vortex_text))
            background = ui.pillBackground(f.autoEnabled)
            setPadding(ui.dp(18), ui.dp(8), ui.dp(18), ui.dp(8))
            setOnClickListener {
                f.setAuto(!f.autoEnabled)
                render()
            }
        })
        outCard.addView(autoRow)
        if (needsBluetoothPermission()) {
            outCard.addView(
                wide(tx("Bluetooth cihaz adını okumak için izin ver", "Allow reading the Bluetooth device name"), false) {
                    ui.activity.requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_CONNECT), 301)
                }
            )
            outCard.addView(ui.noteText(tx("İzin verdikten sonra bu ekranı kapatıp tekrar aç.", "After allowing, close this screen and open it again.")))
        }
        outCard.addView(
            ui.noteText(
                tx(
                    "Profil kaydederken Kulaklık alanına cihazın adını yaz (bağlıyken kendiliğinden dolar).",
                    "When saving a profile, type the device name in the Headphones field (it fills in while connected)."
                )
            )
        )
        content.addView(outCard)
        content.addView(View(ui.activity).apply {
            layoutParams = LinearLayout.LayoutParams(mp, ui.dp(24))
        })
        dimForPure(f)
    }

    // Kilit: ses ayarları yanlışlıkla değişmesin (Saf mod dahil, hepsi kilitlenir)
    private fun applyLock(on: Boolean, save: Boolean) {
        content.locked = on
        lockBar.text = if (on) tx("Kilitli · açmak için dokun", "Locked · tap to unlock")
        else tx("Ayarlar açık · kilitlemek için dokun", "Settings open · tap to lock")
        lockBar.setTextColor(if (on) ui.color(R.color.vortex_text) else ui.color(R.color.vortex_text_secondary))
        lockRow.background = ui.pillBackground(false)
        lockBtn.locked = on
        if (save) {
            content.animate().alpha(if (on) 0.4f else 1f).setDuration(260).start()
            audioPrefs.edit().putBoolean("eqlock", on).apply()
        } else {
            content.alpha = if (on) 0.4f else 1f
        }
    }

    // Saf mod açıkken efekt bölümleri soluk gri görünür (ayarlar silinmez)
    private fun dimForPure(f: AudioFxController) {
        for (i in 3 until content.childCount) {
            content.getChildAt(i).alpha = if (f.pure) 0.3f else 1f
        }
    }

    private fun askSaveProfile() {
        val f = fxProvider() ?: return
        val box = LinearLayout(ui.activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(ui.dp(20), ui.dp(8), ui.dp(20), 0)
        }
        val nameInput = EditText(ui.activity).apply {
            hint = tx("Profil adı", "Profile name")
            setSingleLine(true)
        }
        val phoneInput = EditText(ui.activity).apply {
            hint = tx("Kulaklık adı (isteğe bağlı)", "Headphone name (optional)")
            setSingleLine(true)
            if (f.currentOutput.isNotEmpty()) setText(f.currentOutput)
        }
        box.addView(nameInput)
        box.addView(phoneInput)
        AlertDialog.Builder(ui.activity)
            .setTitle(tx("Profil olarak kaydet", "Save as profile"))
            .setView(box)
            .setPositiveButton(tx("Kaydet", "Save")) { _, _ ->
                if (f.saveCurrentAsProfile(nameInput.text.toString(), phoneInput.text.toString())) {
                    ui.toast(tx("Profil kaydedildi", "Profile saved"))
                    render()
                } else {
                    ui.toast(tx("Profil adı boş olamaz", "Profile name cannot be empty"))
                }
            }
            .setNegativeButton(tx("Vazgeç", "Cancel"), null)
            .show()
    }

    private fun profileOptions(name: String) {
        val f = fxProvider() ?: return
        SheetDialog.Builder(ui.activity, ui)
            .setTitle(name)
            .setItems(arrayOf(tx("Profili sil", "Delete profile"))) { _, _ ->
                f.deleteProfile(name)
                render()
            }
            .create().show()
    }

    @Suppress("DEPRECATION")
    private fun exportProfiles() {
        val f = fxProvider() ?: return
        if (f.profiles.isEmpty()) {
            ui.toast(tx("Dışa aktarılacak profil yok", "No profiles to export"))
            return
        }
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType("application/json")
            .putExtra(Intent.EXTRA_TITLE, tx("vortex-ses-profilleri.json", "vortex-audio-profiles.json"))
        ui.activity.startActivityForResult(intent, REQ_EXPORT)
    }

    @Suppress("DEPRECATION")
    private fun importProfiles() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType("*/*")
        ui.activity.startActivityForResult(intent, REQ_IMPORT)
    }

    private fun readLimited(uri: Uri): String? {
        return try {
            ui.activity.contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use { r ->
                val sb = StringBuilder()
                val buf = CharArray(4096)
                while (true) {
                    val n = r.read(buf)
                    if (n < 0) break
                    sb.append(buf, 0, n)
                    if (sb.length > 500000) return@use null
                }
                sb.toString()
            }
        } catch (e: Exception) {
            null
        }
    }

    // MainActivity.onActivityResult buraya yönlendirir. İlgilenmediyse false döner.
    fun handleResult(requestCode: Int, resultCode: Int, data: Intent?): Boolean {
        if (requestCode != REQ_EXPORT && requestCode != REQ_IMPORT) return false
        if (resultCode != Activity.RESULT_OK) return true
        val uri = data?.data ?: return true
        val f = fxProvider() ?: return true
        if (requestCode == REQ_EXPORT) {
            try {
                ui.activity.contentResolver.openOutputStream(uri)?.use {
                    it.write(f.exportJson().toByteArray(Charsets.UTF_8))
                }
                ui.toast(tx("Profiller dışa aktarıldı", "Profiles exported"))
            } catch (e: Exception) {
                ui.toast(tx("Dosya yazılamadı", "Could not write the file"))
            }
        } else {
            val text = readLimited(uri)
            if (text == null) {
                ui.toast(tx("Dosya okunamadı ya da çok büyük", "The file could not be read or is too large"))
                return true
            }
            val n = f.importJson(text)
            if (n > 0) ui.toast(tx("$n profil içe aktarıldı", if (n == 1) "1 profile imported" else "$n profiles imported"))
            else ui.toast(tx("Geçerli bir Vortex profil dosyası değil", "Not a valid Vortex profile file"))
            if (isOpen) render()
        }
        return true
    }

    companion object {
        private const val REQ_EXPORT = 101
        private const val REQ_IMPORT = 102
    }
}

// ---------- Doldurma kutusu: parmağı sağa sürükledikçe renkle dolar ----------
class EffectTileView(ctx: Context, private val d: Float) : View(ctx) {
    var title = ""
    var valueText = ""
        set(v) { field = v; invalidate() }
    var value = 0
        set(v) { field = v.coerceIn(0, 1000); invalidate() }
    var accent = Color.GREEN
    var track = Color.DKGRAY
    var textColor = Color.WHITE
    var subColor = Color.GRAY
    var isToggle = false
    var on = false
        set(v) { field = v; invalidate() }
    var onChange: (Int) -> Unit = {}
    var onDone: () -> Unit = {}
    var onTap: () -> Unit = {}

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private val clip = Path()
    private val slop = ViewConfiguration.get(ctx).scaledTouchSlop
    private var downX = 0f
    private var dragging = false
    private var lastTap = 0L

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val r = 24f * d
        rect.set(0f, 0f, w, h)
        paint.style = Paint.Style.FILL
        paint.color = track
        canvas.drawRoundRect(rect, r, r, paint)
        val frac = if (isToggle) (if (on) 1f else 0f) else value / 1000f
        if (frac > 0f) {
            canvas.save()
            clip.reset()
            clip.addRoundRect(rect, r, r, Path.Direction.CW)
            canvas.clipPath(clip)
            paint.color = accent
            paint.alpha = if (isToggle) 255 else 255
            canvas.drawRect(0f, 0f, w * frac, h, paint)
            paint.alpha = 255
            canvas.restore()
        }
        val pad = 14f * d
        val fillW = w * frac
        for (pass in 0..1) {
            if (pass == 1 && fillW <= 0f) break
            canvas.save()
            if (pass == 1) canvas.clipRect(0f, 0f, fillW, h) else canvas.clipRect(fillW, 0f, w, h)
            val over = pass == 1
            paint.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            paint.textSize = 15f * d
            paint.color = if (over) Color.WHITE else textColor
            canvas.drawText(title, pad, h * 0.42f, paint)
            paint.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            paint.textSize = 13f * d
            paint.color = if (over) Color.WHITE else textColor
            paint.alpha = if (over) 235 else 200
            canvas.drawText(valueText, pad, h * 0.42f + 22f * d, paint)
            paint.alpha = 255
            canvas.restore()
        }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.x
                dragging = false
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (isToggle) return true
                if (!dragging && abs(e.x - downX) > slop) {
                    dragging = true
                    parent?.requestDisallowInterceptTouchEvent(true)
                }
                if (dragging) {
                    val nv = (e.x / width * 1000f).toInt().coerceIn(0, 1000)
                    if (nv != value) {
                        value = nv
                        onChange(nv)
                    }
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                if (isToggle) {
                    if (abs(e.x - downX) < slop) onTap()
                } else if (dragging) {
                    onDone()
                } else if (abs(e.x - downX) < slop) {
                    val now = System.currentTimeMillis()
                    if (now - lastTap < 350) {
                        value = 0
                        onChange(0)
                        onDone()
                    }
                    lastTap = now
                }
                dragging = false
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                if (dragging) onDone()
                dragging = false
                return true
            }
        }
        return true
    }
}

// ---------- Kilit düğmesi: halka içinde asma kilit, kilitliyken mor dolgu ----------
class LockButtonView(ctx: Context, private val d: Float) : View(ctx) {
    var accent = Color.MAGENTA
    var locked = false
        set(v) { field = v; invalidate() }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val oval = RectF()
    private val body = RectF()

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val cx = w / 2f
        val cy = height / 2f
        val purple = accent
        val stroke = 2f * d
        val r = w / 2f - stroke
        paint.style = Paint.Style.FILL
        paint.color = if (locked) purple else Color.TRANSPARENT
        if (locked) canvas.drawCircle(cx, cy, r, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = stroke
        paint.color = if (locked) purple else Color.argb(150, 255, 255, 255)
        canvas.drawCircle(cx, cy, r, paint)
        val ink = if (locked) Color.WHITE else Color.argb(235, 255, 255, 255)
        paint.color = ink
        paint.strokeWidth = 2.2f * d
        paint.strokeCap = Paint.Cap.ROUND
        val bw = w * 0.34f
        val bh = w * 0.25f
        val top = cy - bh * 0.15f
        body.set(cx - bw / 2f, top, cx + bw / 2f, top + bh)
        paint.style = Paint.Style.FILL
        canvas.drawRoundRect(body, 3f * d, 3f * d, paint)
        paint.style = Paint.Style.STROKE
        val sw = bw * 0.62f
        val sh = w * 0.2f
        val shift = if (locked) 0f else sw * 0.5f
        val lift = if (locked) 0f else 4f * d
        val left = cx - sw / 2f + shift
        val right = cx + sw / 2f + shift
        val arcTop = top - sh - lift
        oval.set(left, arcTop, right, arcTop + sw)
        canvas.drawArc(oval, 180f, 180f, false, paint)
        val legTop = arcTop + sw / 2f
        canvas.drawLine(right, legTop, right, top - lift + 0f, paint)
        if (locked) {
            canvas.drawLine(left, legTop, left, top, paint)
        } else {
            canvas.drawLine(left, legTop, left, legTop + 3f * d, paint)
        }
        paint.strokeCap = Paint.Cap.BUTT
        if (!locked) {
            paint.style = Paint.Style.FILL
            paint.color = if (locked) purple else Color.argb(255, 40, 30, 70)
            canvas.drawCircle(cx, top + bh / 2f, 2f * d, paint)
        } else {
            paint.style = Paint.Style.FILL
            paint.color = purple
            canvas.drawCircle(cx, top + bh / 2f, 2f * d, paint)
        }
    }
}

// ---------- Saf mod kartı: kapalıyken dalgalı çizgi, açıkken dümdüz çizgi ----------
class PureCardView(ctx: Context, private val d: Float) : View(ctx) {
    var accent = Color.MAGENTA
    var surface = Color.DKGRAY
    var textColor = Color.WHITE
    var subColor = Color.GRAY
    var title = ""
    var offText = ""
    var onText = ""

    private var p = 0f
    private var isOn = false
    private var anim: ValueAnimator? = null
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private val path = Path()

    fun setOn(on: Boolean, animate: Boolean) {
        isOn = on
        anim?.cancel()
        val target = if (on) 1f else 0f
        if (!animate) {
            p = target
            invalidate()
            return
        }
        anim = ValueAnimator.ofFloat(p, target).apply {
            duration = 320
            addUpdateListener { p = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    private fun mix(a: Int, b: Int, t: Float): Int = Color.argb(
        (Color.alpha(a) + (Color.alpha(b) - Color.alpha(a)) * t).toInt(),
        (Color.red(a) + (Color.red(b) - Color.red(a)) * t).toInt(),
        (Color.green(a) + (Color.green(b) - Color.green(a)) * t).toInt(),
        (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * t).toInt()
    )

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val r = 26f * d
        rect.set(0f, 0f, w, h)
        paint.style = Paint.Style.FILL
        paint.color = mix(surface, mix(surface, accent, 0.28f), p)
        canvas.drawRoundRect(rect, r, r, paint)
        if (p > 0.01f) {
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 1.5f * d
            paint.color = Color.argb((160 * p).toInt(), Color.red(accent), Color.green(accent), Color.blue(accent))
            rect.set(0.75f * d, 0.75f * d, w - 0.75f * d, h - 0.75f * d)
            canvas.drawRoundRect(rect, r, r, paint)
        }
        val cy = h / 2f
        val ic = 26f * d
        val icx = 18f * d + ic
        paint.style = Paint.Style.FILL
        paint.color = mix(Color.argb(40, 255, 255, 255), accent, p)
        canvas.drawCircle(icx, cy, ic, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 2.6f * d
        paint.strokeCap = Paint.Cap.ROUND
        paint.color = Color.WHITE
        path.reset()
        val half = 15f * d
        val amp = 8f * d * (1f - p)
        var i = 0
        val n = 40
        while (i <= n) {
            val t = i / n.toFloat()
            val x = icx - half + 2f * half * t
            val y = cy - amp * Math.sin(t * Math.PI * 3.0).toFloat()
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            i++
        }
        canvas.drawPath(path, paint)
        val tx0 = icx + ic + 16f * d
        paint.style = Paint.Style.FILL
        paint.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        paint.textSize = 17f * d
        paint.color = textColor
        canvas.drawText(title, tx0, cy - 3f * d, paint)
        paint.typeface = Typeface.DEFAULT
        paint.textSize = 13f * d
        paint.color = subColor
        val sub = if (isOn) onText else offText
        val maxW = w - tx0 - 84f * d
        var shown = sub
        while (shown.length > 3 && paint.measureText(shown) > maxW) shown = shown.dropLast(2)
        if (shown != sub) shown = shown.trimEnd() + "…"
        canvas.drawText(shown, tx0, cy + 19f * d, paint)
        val sw = 52f * d
        val sh = 30f * d
        val sx = w - 18f * d - sw
        rect.set(sx, cy - sh / 2f, sx + sw, cy + sh / 2f)
        paint.color = mix(Color.argb(70, 255, 255, 255), accent, p)
        canvas.drawRoundRect(rect, sh / 2f, sh / 2f, paint)
        val tr = (11f + 4f * p) * d
        val tcx = sx + sh / 2f + (sw - sh) * p
        paint.color = mix(Color.argb(230, 255, 255, 255), Color.WHITE, p)
        canvas.drawCircle(tcx, cy, tr, paint)
    }
}
