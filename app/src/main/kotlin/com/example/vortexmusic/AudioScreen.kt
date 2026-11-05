package io.github.vortexmusic.player

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Canvas
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

private fun dbInt(level: Int): String = if (level > 0) "+${level / 100}" else "${level / 100}"

// ---------- Eğri grafik: noktaları sürükleyerek ayar ----------

private class EqGraphView(
    ctx: Context,
    private val fx: AudioFxController,
    private val d: Float
) : View(ctx) {

    var accent = Color.WHITE
    var textColor = Color.WHITE
    var secondary = Color.GRAY
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
        fillPaint.color = alphaOf(accent, 38)
        canvas.drawPath(fillPath, fillPaint)
        linePaint.color = accent
        linePaint.strokeWidth = d * 2.6f
        canvas.drawPath(path, linePaint)
        canvas.restore()

        // Noktalar
        for (i in 0 until n) {
            val cx = xFor(i)
            val cy = yFor(levelAt(i))
            val isSel = i == sel
            val r = if (isSel) d * 13f else d * 10.5f
            fillPaint.color = if (isSel) accent else surface
            canvas.drawCircle(cx, cy, r, fillPaint)
            linePaint.color = accent
            linePaint.strokeWidth = d * 1.8f
            canvas.drawCircle(cx, cy, r, linePaint)
            textPaint.textAlign = Paint.Align.CENTER
            textPaint.textSize = d * (if (isSel) 12f else 11f)
            textPaint.typeface = Typeface.DEFAULT_BOLD
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

class AudioScreen(private val ui: Ui, private val fxProvider: () -> AudioFxController?) {

    private val mp = ViewGroup.LayoutParams.MATCH_PARENT
    private val wc = ViewGroup.LayoutParams.WRAP_CONTENT

    private val content = LinearLayout(ui.activity)
    val view: LinearLayout = ui.overlay()

    private var selectedBand = 0
    private var toggleView: TextView? = null
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
        view.addView(ui.header("Ses") { hide() })
        view.addView(scroll)
    }

    fun show() {
        render()
        view.visibility = View.VISIBLE
    }

    fun hide() {
        view.visibility = View.GONE
    }

    private fun chip(label: String, active: Boolean): TextView = TextView(ui.activity).apply {
        text = label
        textSize = 14f
        setPadding(ui.dp(16), ui.dp(8), ui.dp(16), ui.dp(8))
        setTextColor(if (active) Color.WHITE else ui.color(R.color.vortex_text))
        background = ui.pillBackground(active)
        layoutParams = LinearLayout.LayoutParams(wc, wc).apply { marginEnd = ui.dp(8) }
    }

    // Tam genişlikte düğme
    private fun wide(label: String, active: Boolean, onClick: () -> Unit): TextView =
        TextView(ui.activity).apply {
            text = label
            textSize = 15f
            gravity = Gravity.CENTER
            setTextColor(if (active) Color.WHITE else ui.color(R.color.vortex_text))
            background = ui.pillBackground(active)
            setPadding(ui.dp(16), ui.dp(12), ui.dp(16), ui.dp(12))
            layoutParams = LinearLayout.LayoutParams(mp, wc).apply { topMargin = ui.dp(8) }
            setOnClickListener { onClick() }
        }

    private fun needsBluetoothPermission(): Boolean =
        Build.VERSION.SDK_INT >= 31 &&
            ui.activity.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) !=
            PackageManager.PERMISSION_GRANTED

    private fun toggleLabel(f: AudioFxController): String =
        if (f.enabled) "Ses efektleri: Açık" else "Ses efektleri: Kapalı"

    private fun refreshToggle(f: AudioFxController) {
        val t = toggleView ?: return
        t.text = toggleLabel(f)
        t.setTextColor(if (f.enabled) Color.WHITE else ui.color(R.color.vortex_text))
        t.background = ui.pillBackground(f.enabled)
    }

    private fun updateBandInfo(f: AudioFxController) {
        val i = selectedBand
        bandTitle?.text = "Bant ${i + 1}"
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
            val c = chip(preset.name, f.enabled && f.matches(preset))
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
                ui.noteText("Henüz profil yok. Ayarlarını yapıp \"Profil olarak kaydet\" de.")
            )
            return
        }
        val scroll = HorizontalScrollView(ui.activity)
        scroll.isHorizontalScrollBarEnabled = false
        val row = LinearLayout(ui.activity)
        row.orientation = LinearLayout.HORIZONTAL
        for (prof in f.profiles.toList()) {
            val label = if (prof.headphone.isEmpty()) prof.name else "${prof.name} · ${prof.headphone}"
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
        box.addView(ui.noteText("Silmek için profile uzun bas."))
    }

    private fun refreshChips(f: AudioFxController) {
        fillPresets(f)
        fillProfiles(f)
    }

    fun render() {
        content.removeAllViews()
        toggleView = null
        graph = null
        bandTitle = null
        bandFreq = null
        bandGain = null
        presetBox = null
        profileBox = null

        val f = fxProvider()
        if (f == null) {
            content.addView(ui.noteText("Ses motoru henüz hazır değil. Birkaç saniye sonra tekrar aç."))
            return
        }

        val toggle = wide(toggleLabel(f), f.enabled) {
            f.enabled = !f.enabled
            f.applyAll()
            f.save()
            graph?.alpha = if (f.enabled) 1f else 0.5f
            refreshToggle(f)
            refreshChips(f)
        }
        toggleView = toggle
        content.addView(toggle)

        // ---------- Ekolayzır: eğri grafik ----------
        content.addView(ui.sectionTitle("Ekolayzır"))
        if (!f.eqAvailable || f.bandCount == 0) {
            content.addView(ui.noteText("Bu telefon ekolayzırı desteklemiyor."))
        } else {
            if (selectedBand !in 0 until f.bandCount) selectedBand = 0

            val card = LinearLayout(ui.activity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(ui.dp(10), ui.dp(12), ui.dp(10), ui.dp(14))
                background = GradientDrawable().apply {
                    setColor(ui.color(R.color.vortex_surface))
                    cornerRadius = ui.dp(22).toFloat()
                }
                layoutParams = LinearLayout.LayoutParams(mp, wc)
            }

            val g = EqGraphView(ui.activity, f, ui.density)
            g.accent = ui.color(R.color.vortex_purple)
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
                setTypeface(typeface, Typeface.BOLD)
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
                setTypeface(typeface, Typeface.BOLD)
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
            adj.addView(ui.actionPill("Sıfırla") { nudge(f, 0, true) })
            adj.addView(ui.actionPill("+ 0.5 dB") { nudge(f, 50, false) })
            card.addView(adj)

            card.addView(
                ui.noteText(
                    "Bir noktayı yukarı ya da aşağı sürükle, çift dokununca sıfırlanır. " +
                        "Frekanslar telefonun ekolayzırına göre sabittir."
                )
            )
            content.addView(card)
            updateBandInfo(f)
        }

        // ---------- Hazır ayarlar ----------
        content.addView(ui.sectionTitle("Hazır ayarlar"))
        val pb = LinearLayout(ui.activity)
        pb.orientation = LinearLayout.VERTICAL
        presetBox = pb
        content.addView(pb)
        fillPresets(f)

        // ---------- Efektler ----------
        content.addView(ui.sectionTitle("Efektler"))
        if (f.bassAvailable) {
            content.addView(
                ui.slider(
                    "Bas güçlendirici", 1000, f.bassStrength,
                    { p -> "${p / 10}%" },
                    { p ->
                        f.bassStrength = p
                        f.applyAll()
                    },
                    { f.save() }
                )
            )
        }
        if (f.loudAvailable) {
            content.addView(
                ui.slider(
                    "Ses yükseltme", 1000, f.loudnessMb,
                    { p -> String.format(Locale.US, "+%.1f dB", p / 100f) },
                    { p ->
                        f.loudnessMb = p
                        f.applyAll()
                    },
                    { f.save() }
                )
            )
        }
        if (f.virtAvailable) {
            content.addView(
                ui.slider(
                    "Sanal surround", 1000, f.virtStrength,
                    { p -> "${p / 10}%" },
                    { p ->
                        f.virtStrength = p
                        f.applyAll()
                    },
                    { f.save() }
                )
            )
        }
        if (!f.bassAvailable && !f.loudAvailable && !f.virtAvailable) {
            content.addView(ui.noteText("Bu telefon ek ses efektlerini desteklemiyor."))
        }
        content.addView(
            ui.noteText(
                "Efektlerin gücü telefon markasına ve kulaklığa göre değişir. " +
                    "Çok yüksek ses yükseltme sesi bozabilir."
            )
        )

        // ---------- Profillerim ----------
        content.addView(ui.sectionTitle("Profillerim"))
        val prb = LinearLayout(ui.activity)
        prb.orientation = LinearLayout.VERTICAL
        profileBox = prb
        content.addView(prb)
        fillProfiles(f)

        val actions = LinearLayout(ui.activity)
        actions.orientation = LinearLayout.HORIZONTAL
        actions.layoutParams = LinearLayout.LayoutParams(mp, wc).apply { topMargin = ui.dp(10) }
        actions.addView(ui.actionPill("Profil olarak kaydet") { askSaveProfile() })
        actions.addView(ui.actionPill("Dışa aktar") { exportProfiles() })
        actions.addView(ui.actionPill("İçe aktar") { importProfiles() })
        content.addView(actions)

        // ---------- Çıkış cihazı ----------
        content.addView(ui.sectionTitle("Çıkış cihazı"))
        val outName = if (f.currentOutput.isEmpty()) "Telefon hoparlörü" else f.currentOutput
        val active = if (f.autoProfileName.isNotEmpty()) " · Profil: ${f.autoProfileName}" else ""
        content.addView(ui.noteText("Şu an: $outName$active"))
        content.addView(
            wide(
                if (f.autoEnabled) "Cihaza göre otomatik EQ: Açık" else "Cihaza göre otomatik EQ: Kapalı",
                f.autoEnabled
            ) {
                f.setAuto(!f.autoEnabled)
                render()
            }
        )
        if (needsBluetoothPermission()) {
            content.addView(
                wide("Bluetooth cihaz adını okumak için izin ver", false) {
                    ui.activity.requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_CONNECT), 301)
                }
            )
            content.addView(ui.noteText("İzin verdikten sonra bu ekranı kapatıp tekrar aç."))
        }
        content.addView(
            ui.noteText(
                "Bir profil kaydederken Kulaklık alanına cihazın adını yaz (bağlıyken otomatik dolar). " +
                    "O cihaz bağlanınca profil kendiliğinden uygulanır, çıkarınca eski ayarlara dönülür."
            )
        )
        content.addView(View(ui.activity).apply {
            layoutParams = LinearLayout.LayoutParams(mp, ui.dp(24))
        })
    }

    private fun askSaveProfile() {
        val f = fxProvider() ?: return
        val box = LinearLayout(ui.activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(ui.dp(20), ui.dp(8), ui.dp(20), 0)
        }
        val nameInput = EditText(ui.activity).apply {
            hint = "Profil adı"
            setSingleLine(true)
        }
        val phoneInput = EditText(ui.activity).apply {
            hint = "Kulaklık adı (isteğe bağlı)"
            setSingleLine(true)
            if (f.currentOutput.isNotEmpty()) setText(f.currentOutput)
        }
        box.addView(nameInput)
        box.addView(phoneInput)
        AlertDialog.Builder(ui.activity)
            .setTitle("Profil olarak kaydet")
            .setView(box)
            .setPositiveButton("Kaydet") { _, _ ->
                if (f.saveCurrentAsProfile(nameInput.text.toString(), phoneInput.text.toString())) {
                    ui.toast("Profil kaydedildi")
                    render()
                } else {
                    ui.toast("Profil adı boş olamaz")
                }
            }
            .setNegativeButton("Vazgeç", null)
            .show()
    }

    private fun profileOptions(name: String) {
        val f = fxProvider() ?: return
        AlertDialog.Builder(ui.activity)
            .setTitle(name)
            .setItems(arrayOf("Sil")) { _, _ ->
                f.deleteProfile(name)
                render()
            }
            .show()
    }

    @Suppress("DEPRECATION")
    private fun exportProfiles() {
        val f = fxProvider() ?: return
        if (f.profiles.isEmpty()) {
            ui.toast("Dışa aktarılacak profil yok")
            return
        }
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType("application/json")
            .putExtra(Intent.EXTRA_TITLE, "vortex-ses-profilleri.json")
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
                ui.toast("Profiller dışa aktarıldı")
            } catch (e: Exception) {
                ui.toast("Dosya yazılamadı")
            }
        } else {
            val text = readLimited(uri)
            if (text == null) {
                ui.toast("Dosya okunamadı ya da çok büyük")
                return true
            }
            val n = f.importJson(text)
            if (n > 0) ui.toast("$n profil içe aktarıldı")
            else ui.toast("Geçerli bir Vortex profil dosyası değil")
            if (isOpen) render()
        }
        return true
    }

    companion object {
        private const val REQ_EXPORT = 101
        private const val REQ_IMPORT = 102
    }
}