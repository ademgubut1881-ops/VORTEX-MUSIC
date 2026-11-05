package io.github.vortexmusic.player

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

private fun withAlpha(c: Int, a: Int): Int =
    Color.argb(a, Color.red(c), Color.green(c), Color.blue(c))

// ---------- Yumuşak eğri grafik ----------

private class SmoothLineView(
    ctx: Context,
    private val values: List<Long>,
    private val lineColor: Int,
    private val guideColor: Int,
    private val density: Float,
    private val showAllDots: Boolean
) : View(ctx) {

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        strokeWidth = density * 2.6f
        color = lineColor
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val guidePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = density
        color = guideColor
    }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val path = Path()
    private val fillPath = Path()

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val n = values.size
        if (n < 2) return
        val left = density * 10f
        val right = width - density * 10f
        val top = density * 14f
        val bottom = height - density * 8f
        val h = bottom - top
        var maxV = 1L
        for (v in values) if (v > maxV) maxV = v
        val max = maxV.toFloat()

        for (i in 1..3) {
            val y = top + h * i / 4f
            canvas.drawLine(left, y, right, y, guidePaint)
        }

        val xs = FloatArray(n)
        val ys = FloatArray(n)
        for (i in 0 until n) {
            xs[i] = left + (right - left) * i / (n - 1).toFloat()
            ys[i] = bottom - h * (values[i] / max)
        }

        path.reset()
        path.moveTo(xs[0], ys[0])
        for (i in 0 until n - 1) {
            val cx = (xs[i] + xs[i + 1]) / 2f
            path.cubicTo(cx, ys[i], cx, ys[i + 1], xs[i + 1], ys[i + 1])
        }

        fillPath.reset()
        fillPath.moveTo(xs[0], ys[0])
        for (i in 0 until n - 1) {
            val cx = (xs[i] + xs[i + 1]) / 2f
            fillPath.cubicTo(cx, ys[i], cx, ys[i + 1], xs[i + 1], ys[i + 1])
        }
        fillPath.lineTo(xs[n - 1], bottom)
        fillPath.lineTo(xs[0], bottom)
        fillPath.close()
        fillPaint.shader = LinearGradient(
            0f, top, 0f, bottom,
            withAlpha(lineColor, 80), withAlpha(lineColor, 0),
            Shader.TileMode.CLAMP
        )
        canvas.drawPath(fillPath, fillPaint)
        canvas.drawPath(path, linePaint)

        var peak = 0
        for (i in 0 until n) if (values[i] > values[peak]) peak = i

        if (showAllDots) {
            dotPaint.color = lineColor
            for (i in 0 until n) canvas.drawCircle(xs[i], ys[i], density * 3f, dotPaint)
        }
        if (values[peak] > 0L) {
            dotPaint.color = withAlpha(lineColor, 60)
            canvas.drawCircle(xs[peak], ys[peak], density * 9f, dotPaint)
            dotPaint.color = lineColor
            canvas.drawCircle(xs[peak], ys[peak], density * 5f, dotPaint)
        }
    }
}

// ---------- Halka grafik ----------

private class DonutView(
    ctx: Context,
    private val shares: List<Float>,
    private val colors: List<Int>,
    private val trackColor: Int,
    private val density: Float
) : View(ctx) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.BUTT
    }
    private val rect = RectF()

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val size = minOf(width, height).toFloat()
        val stroke = density * 20f
        val inset = stroke / 2f + density * 2f
        rect.set(
            (width - size) / 2f + inset,
            (height - size) / 2f + inset,
            (width + size) / 2f - inset,
            (height + size) / 2f - inset
        )
        paint.strokeWidth = stroke
        paint.color = trackColor
        canvas.drawArc(rect, 0f, 360f, false, paint)
        var start = -90f
        val gap = if (shares.size > 1) 3f else 0f
        for (i in shares.indices) {
            val sweep = shares[i] * 360f
            val drawSweep = (sweep - gap).coerceAtLeast(1f)
            paint.color = colors[i % colors.size]
            canvas.drawArc(rect, start + gap / 2f, drawSweep, false, paint)
            start += sweep
        }
    }
}

// ---------- Saat çubukları ----------

private class HourBarsView(
    ctx: Context,
    private val values: LongArray,
    private val peak: Int,
    private val barColor: Int,
    private val peakTop: Int,
    private val peakBottom: Int,
    private val density: Float
) : View(ctx) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val rect = RectF()

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val n = values.size
        if (n == 0) return
        var maxV = 1L
        for (v in values) if (v > maxV) maxV = v
        val max = maxV.toFloat()
        val gap = density * 3f
        val barW = (width - gap * (n - 1)) / n
        val radius = barW / 2f
        val fullH = height.toFloat()
        for (i in 0 until n) {
            val h = if (values[i] <= 0L) density * 3f
            else (fullH * (values[i] / max)).coerceAtLeast(density * 4f)
            val l = i * (barW + gap)
            rect.set(l, fullH - h, l + barW, fullH)
            if (i == peak && values[i] > 0L) {
                paint.shader = LinearGradient(
                    0f, fullH - h, 0f, fullH, peakTop, peakBottom, Shader.TileMode.CLAMP
                )
            } else {
                paint.shader = null
                paint.color = barColor
            }
            canvas.drawRoundRect(rect, radius, radius, paint)
        }
        paint.shader = null
    }
}

// ---------- İstatistik ekranı ----------

class StatsScreen(private val ui: Ui, private val songsProvider: () -> List<Song>) {

    private val mp = ViewGroup.LayoutParams.MATCH_PARENT
    private val wc = ViewGroup.LayoutParams.WRAP_CONTENT

    private val content = LinearLayout(ui.activity)
    val view: LinearLayout = ui.overlay()

    // 0 bugün, 1 hafta, 2 ay, 3 tümü
    private var range = 1

    val isOpen: Boolean
        get() = view.visibility == View.VISIBLE

    init {
        content.orientation = LinearLayout.VERTICAL
        val scroll = ScrollView(ui.activity)
        scroll.isVerticalScrollBarEnabled = false
        scroll.layoutParams = LinearLayout.LayoutParams(mp, 0, 1f).apply { topMargin = ui.dp(8) }
        scroll.addView(content)
        view.addView(ui.header("İstatistikler") { hide() })
        view.addView(scroll)
    }

    fun show() {
        render()
        view.visibility = View.VISIBLE
    }

    fun hide() {
        view.visibility = View.GONE
    }

    // ---------- Yardımcılar ----------

    private fun dayKeys(n: Int): List<String> {
        val fmt = SimpleDateFormat("yyyyMMdd", Locale.US)
        val cal = Calendar.getInstance()
        val out = ArrayList<String>()
        for (i in 0 until n) {
            out.add(0, fmt.format(cal.time))
            cal.add(Calendar.DAY_OF_YEAR, -1)
        }
        return out
    }

    private fun dayLabel(key: String): String {
        return try {
            val d = SimpleDateFormat("yyyyMMdd", Locale.US).parse(key)
            if (d == null) key else SimpleDateFormat("d MMM", Locale.forLanguageTag("tr")).format(d)
        } catch (e: Exception) {
            key
        }
    }

    private fun card(): LinearLayout = LinearLayout(ui.activity).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(ui.dp(18), ui.dp(16), ui.dp(18), ui.dp(16))
        background = GradientDrawable().apply {
            setColor(ui.color(R.color.vortex_surface))
            cornerRadius = ui.dp(22).toFloat()
        }
        layoutParams = LinearLayout.LayoutParams(mp, wc).apply { topMargin = ui.dp(12) }
    }

    private fun chips(): View {
        val row = LinearLayout(ui.activity)
        row.orientation = LinearLayout.HORIZONTAL
        row.layoutParams = LinearLayout.LayoutParams(mp, wc).apply { topMargin = ui.dp(4) }
        val labels = arrayOf("Bugün", "Hafta", "Ay", "Tümü")
        labels.forEachIndexed { i, l ->
            row.addView(TextView(ui.activity).apply {
                text = l
                textSize = 14f
                setPadding(ui.dp(16), ui.dp(8), ui.dp(16), ui.dp(8))
                setTextColor(if (i == range) Color.WHITE else ui.color(R.color.vortex_text))
                background = ui.pillBackground(i == range)
                layoutParams = LinearLayout.LayoutParams(wc, wc).apply { marginEnd = ui.dp(8) }
                setOnClickListener {
                    range = i
                    render()
                }
            })
        }
        return row
    }

    private fun labelRow(left: String, right: String): View {
        val row = LinearLayout(ui.activity)
        row.orientation = LinearLayout.HORIZONTAL
        row.layoutParams = LinearLayout.LayoutParams(mp, wc).apply { topMargin = ui.dp(6) }
        row.addView(TextView(ui.activity).apply {
            text = left
            textSize = 11f
            setTextColor(ui.color(R.color.vortex_text_secondary))
            layoutParams = LinearLayout.LayoutParams(0, wc, 1f)
        })
        row.addView(TextView(ui.activity).apply {
            text = right
            textSize = 11f
            gravity = Gravity.END
            setTextColor(ui.color(R.color.vortex_text_secondary))
            layoutParams = LinearLayout.LayoutParams(0, wc, 1f)
        })
        return row
    }

    private fun donutColors(): List<Int> {
        val bg = ui.color(R.color.vortex_background)
        return if (ThemeStore.mono()) {
            val t = ui.color(R.color.vortex_text)
            listOf(t, ui.mix(t, bg, 0.35f), ui.mix(t, bg, 0.6f), ui.mix(t, bg, 0.78f))
        } else {
            val a = ui.color(R.color.vortex_purple)
            val b = ui.color(R.color.vortex_blue)
            listOf(a, b, ui.mix(a, bg, 0.5f), ui.mix(b, bg, 0.5f))
        }
    }

    private fun legendRow(color: Int, name: String, pct: String): View {
        val row = LinearLayout(ui.activity)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.setPadding(0, ui.dp(5), 0, ui.dp(5))
        row.addView(View(ui.activity).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(color)
            }
            layoutParams = LinearLayout.LayoutParams(ui.dp(10), ui.dp(10)).apply {
                marginEnd = ui.dp(10)
            }
        })
        row.addView(TextView(ui.activity).apply {
            text = name
            textSize = 14f
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setTextColor(ui.color(R.color.vortex_text))
            layoutParams = LinearLayout.LayoutParams(0, wc, 1f)
        })
        row.addView(TextView(ui.activity).apply {
            text = pct
            textSize = 13f
            setTextColor(ui.color(R.color.vortex_text_secondary))
            setPadding(ui.dp(8), 0, 0, 0)
        })
        return row
    }

    private fun rankRow(rank: Int, title: String, sub: String, ms: Long, max: Long): View {
        val box = LinearLayout(ui.activity)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(0, ui.dp(8), 0, ui.dp(4))

        val top = LinearLayout(ui.activity)
        top.orientation = LinearLayout.HORIZONTAL
        top.gravity = Gravity.CENTER_VERTICAL

        top.addView(TextView(ui.activity).apply {
            text = "$rank"
            textSize = 14f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(ui.color(R.color.vortex_blue))
            layoutParams = LinearLayout.LayoutParams(ui.dp(26), wc)
        })

        val col = LinearLayout(ui.activity)
        col.orientation = LinearLayout.VERTICAL
        col.layoutParams = LinearLayout.LayoutParams(0, wc, 1f)
        col.addView(TextView(ui.activity).apply {
            text = title
            textSize = 15f
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setTextColor(ui.color(R.color.vortex_text))
        })
        if (sub.isNotEmpty()) {
            col.addView(TextView(ui.activity).apply {
                text = sub
                textSize = 12f
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                setTextColor(ui.color(R.color.vortex_text_secondary))
            })
        }
        top.addView(col)

        top.addView(TextView(ui.activity).apply {
            text = ui.fmtLong(ms)
            textSize = 13f
            setTextColor(ui.color(R.color.vortex_text_secondary))
            setPadding(ui.dp(10), 0, 0, 0)
        })
        box.addView(top)

        val frac = (1000L * ms / max.coerceAtLeast(1L)).toInt().coerceIn(8, 1000)
        val barRow = LinearLayout(ui.activity)
        barRow.orientation = LinearLayout.HORIZONTAL
        barRow.layoutParams = LinearLayout.LayoutParams(mp, ui.dp(4)).apply {
            topMargin = ui.dp(6)
            marginStart = ui.dp(26)
        }
        val fill = View(ui.activity)
        fill.background = GradientDrawable(
            GradientDrawable.Orientation.LEFT_RIGHT,
            intArrayOf(ui.color(R.color.vortex_purple), ui.color(R.color.vortex_blue))
        ).apply { cornerRadius = ui.dp(2).toFloat() }
        fill.layoutParams = LinearLayout.LayoutParams(0, mp, frac.toFloat())
        val rest = View(ui.activity)
        rest.layoutParams = LinearLayout.LayoutParams(0, mp, (1000 - frac).toFloat())
        barRow.addView(fill)
        barRow.addView(rest)
        box.addView(barRow)
        return box
    }

    // ---------- Ekranı çiz ----------

    private fun render() {
        content.removeAllViews()
        content.addView(chips())

        val sp = ui.activity.getSharedPreferences("vortex_stats", Context.MODE_PRIVATE)
        val daysBack = when (range) {
            0 -> 1
            1 -> 7
            2 -> 30
            else -> 0
        }
        val cutoff = if (daysBack > 0) dayKeys(daysBack).first() else ""

        val songMs = HashMap<Long, Long>()
        val dayMs = HashMap<String, Long>()
        val hourMs = LongArray(24)
        var plays = 0

        for ((k, v) in sp.all) {
            if (k.startsWith("ds_")) {
                val ms = v as? Long ?: continue
                val parts = k.split("_")
                if (parts.size < 3) continue
                val day = parts[1]
                val id = parts[2].toLongOrNull() ?: continue
                dayMs[day] = (dayMs[day] ?: 0L) + ms
                if (cutoff.isEmpty() || day >= cutoff) {
                    songMs[id] = (songMs[id] ?: 0L) + ms
                }
            } else if (k.startsWith("h_")) {
                val h = k.substring(2).toIntOrNull() ?: continue
                val ms = v as? Long ?: continue
                if (h in 0..23) hourMs[h] = ms
            } else if (k.startsWith("c_")) {
                val n = v as? Int ?: continue
                plays += n
            }
        }

        val total = songMs.values.sum()
        if (total <= 0L) {
            content.addView(
                ui.noteText("Henüz dinleme kaydı yok. Bir şarkı çaldıkça ve duraklattıkça burada görünür.")
            )
            return
        }

        // Özet
        val summary = card()
        summary.addView(TextView(ui.activity).apply {
            text = ui.fmtLong(total)
            textSize = 32f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(ui.color(R.color.vortex_text))
        })
        var line = "${songMs.size} farklı şarkı"
        if (range == 3 && plays > 0) line += " · $plays çalma"
        summary.addView(TextView(ui.activity).apply {
            text = line
            textSize = 14f
            setTextColor(ui.color(R.color.vortex_text_secondary))
        })
        if (daysBack > 1) {
            summary.addView(TextView(ui.activity).apply {
                text = "Günlük ortalama: " + ui.fmtLong(total / daysBack)
                textSize = 13f
                setTextColor(ui.color(R.color.vortex_text_secondary))
                setPadding(0, ui.dp(4), 0, 0)
            })
        }
        content.addView(summary)

        // Günlük eğri grafik
        if (range == 1 || range == 2) {
            val n = if (range == 1) 7 else 30
            val keys = dayKeys(n)
            val values = keys.map { dayMs[it] ?: 0L }
            if (values.any { it > 0L }) {
                content.addView(ui.sectionTitle(if (n == 7) "Son 7 gün" else "Son 30 gün"))
                val c = card()
                c.addView(
                    SmoothLineView(
                        ui.activity,
                        values,
                        ui.color(R.color.vortex_purple),
                        withAlpha(ui.color(R.color.vortex_text_secondary), 40),
                        ui.density,
                        n <= 7
                    ).apply {
                        layoutParams = LinearLayout.LayoutParams(mp, ui.dp(150))
                    }
                )
                c.addView(labelRow(dayLabel(keys.first()), "Bugün"))
                var peakIdx = 0
                for (i in values.indices) if (values[i] > values[peakIdx]) peakIdx = i
                c.addView(
                    ui.noteText(
                        "En yoğun gün: ${dayLabel(keys[peakIdx])} · ${ui.fmtLong(values[peakIdx])}"
                    )
                )
                content.addView(c)
            }
        }

        val byId = songsProvider().associateBy { it.id }

        // Sanatçı payları (halka)
        val artistMs = HashMap<String, Long>()
        for ((id, ms) in songMs) {
            val s = byId[id] ?: continue
            artistMs[s.artist] = (artistMs[s.artist] ?: 0L) + ms
        }
        val sortedArtists = artistMs.entries.sortedByDescending { it.value }
        val artistTotal = sortedArtists.sumOf { it.value }
        if (artistTotal > 0L) {
            content.addView(ui.sectionTitle("Sanatçılar"))
            val c = card()
            val palette = donutColors()
            val other = ui.mix(
                ui.color(R.color.vortex_surface_light),
                ui.color(R.color.vortex_text_secondary),
                0.35f
            )
            val top = sortedArtists.take(4)
            val restMs = artistTotal - top.sumOf { it.value }
            val shares = ArrayList<Float>()
            val colors = ArrayList<Int>()
            val legend = LinearLayout(ui.activity)
            legend.orientation = LinearLayout.VERTICAL
            legend.layoutParams = LinearLayout.LayoutParams(0, wc, 1f).apply {
                marginStart = ui.dp(16)
            }
            top.forEachIndexed { i, e ->
                val share = e.value.toFloat() / artistTotal.toFloat()
                shares.add(share)
                colors.add(palette[i % palette.size])
                legend.addView(
                    legendRow(palette[i % palette.size], e.key, "${Math.round(share * 100f)}%")
                )
            }
            if (restMs > 0L) {
                val share = restMs.toFloat() / artistTotal.toFloat()
                shares.add(share)
                colors.add(other)
                legend.addView(legendRow(other, "Diğer", "${Math.round(share * 100f)}%"))
            }

            val row = LinearLayout(ui.activity)
            row.orientation = LinearLayout.HORIZONTAL
            row.gravity = Gravity.CENTER_VERTICAL

            val holder = FrameLayout(ui.activity)
            holder.layoutParams = LinearLayout.LayoutParams(ui.dp(150), ui.dp(150))
            holder.addView(
                DonutView(
                    ui.activity, shares, colors,
                    ui.color(R.color.vortex_surface_light), ui.density
                ).apply {
                    layoutParams = FrameLayout.LayoutParams(mp, mp)
                }
            )
            val center = LinearLayout(ui.activity)
            center.orientation = LinearLayout.VERTICAL
            center.gravity = Gravity.CENTER
            center.layoutParams = FrameLayout.LayoutParams(mp, mp)
            center.addView(TextView(ui.activity).apply {
                text = ui.fmtLong(artistTotal)
                textSize = 14f
                gravity = Gravity.CENTER
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(ui.color(R.color.vortex_text))
            })
            center.addView(TextView(ui.activity).apply {
                text = "toplam"
                textSize = 11f
                gravity = Gravity.CENTER
                setTextColor(ui.color(R.color.vortex_text_secondary))
            })
            holder.addView(center)

            row.addView(holder)
            row.addView(legend)
            c.addView(row)
            content.addView(c)
        }

        // Saat grafiği
        if (hourMs.any { it > 0L }) {
            content.addView(ui.sectionTitle("Günün saatleri (tüm zamanlar)"))
            val c = card()
            var peak = 0
            for (h in 0 until 24) if (hourMs[h] > hourMs[peak]) peak = h
            val barColor = ui.mix(
                ui.color(R.color.vortex_surface_light),
                ui.color(R.color.vortex_purple),
                0.4f
            )
            c.addView(
                HourBarsView(
                    ui.activity, hourMs, peak, barColor,
                    ui.color(R.color.vortex_purple), ui.color(R.color.vortex_blue),
                    ui.density
                ).apply {
                    layoutParams = LinearLayout.LayoutParams(mp, ui.dp(100))
                }
            )
            c.addView(labelRow("00:00", "23:00"))
            c.addView(ui.noteText("En çok dinlediğin saat: %02d:00".format(peak)))
            content.addView(c)
        }

        // En çok dinlenen şarkılar
        val topSongs = songMs.entries
            .sortedByDescending { it.value }
            .mapNotNull { e ->
                val s = byId[e.key]
                if (s == null) null else Triple(s.title, s.artist, e.value)
            }
            .take(10)
        if (topSongs.isNotEmpty()) {
            content.addView(ui.sectionTitle("En çok dinlenen şarkılar"))
            val c = card()
            val max = topSongs[0].third
            topSongs.forEachIndexed { i, t ->
                c.addView(rankRow(i + 1, t.first, t.second, t.third, max))
            }
            content.addView(c)
        }

        content.addView(View(ui.activity).apply {
            layoutParams = LinearLayout.LayoutParams(mp, ui.dp(24))
        })
    }
}