package io.github.vortexmusic.player

import android.app.Activity
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import java.util.Locale

class Ui(val activity: Activity) {

    val density: Float = activity.resources.displayMetrics.density

    init {
        ThemeStore.load(activity)
        val sp = activity.getSharedPreferences("vortex_theme", Context.MODE_PRIVATE)
        if (!sp.contains("lines")) ThemeStore.lines = false
    }

    fun dp(value: Int): Int = (value * density).toInt()

    fun color(id: Int): Int =
        ThemeStore.override(id) ?: activity.resources.getColor(id, activity.theme)

    fun mix(a: Int, b: Int, t: Float): Int {
        val r = (Color.red(a) + (Color.red(b) - Color.red(a)) * t).toInt()
        val g = (Color.green(a) + (Color.green(b) - Color.green(a)) * t).toInt()
        val bl = (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * t).toInt()
        return Color.rgb(r, g, bl)
    }

    fun toast(msg: String) {
        Toast.makeText(activity, msg, Toast.LENGTH_SHORT).show()
    }

    fun fmt(ms: Long): String {
        val s = ms / 1000
        return "%d:%02d".format(s / 60, s % 60)
    }

    fun fmtLong(ms: Long): String {
        val s = ms / 1000
        val h = s / 3600
        val m = (s % 3600) / 60
        val sec = s % 60
        return when {
            h > 0 -> "$h sa $m dk"
            m > 0 -> "$m dk $sec sn"
            else -> "$sec sn"
        }
    }

    fun fmtFreq(f: Int): String =
        if (f >= 1000) {
            if (f % 1000 == 0) "${f / 1000} kHz" else String.format(Locale.US, "%.1f kHz", f / 1000f)
        } else {
            "$f Hz"
        }

    fun icon(
        kind: Int,
        tint: Int,
        sizeDp: Int,
        filled: Boolean = false,
        glow: Boolean = false
    ): IconDrawable {
        var t = tint
        // Açık temada seçili (beyaz) simge görünmez olur, vurgu rengine çevrilir
        if (glow && ThemeStore.light && tint == Color.WHITE) t = color(R.color.vortex_purple)
        // Parlama sadece koyu temada
        val glowColor = if (glow && !ThemeStore.light) color(R.color.vortex_purple) else 0
        return IconDrawable(kind, t, dp(sizeDp), filled, glowColor)
    }

    // Dolu düğmelerin zemini. Koyu temada "tek renk" seçiliyken parlak gri (beyaz yazı okunsun diye),
    // açık temada siyah, renkli vurguda renkli degrade.
    fun neonGradient(oval: Boolean, radiusDp: Int = 16): GradientDrawable {
        val colors = if (ThemeStore.mono() && !ThemeStore.light) {
            intArrayOf(Color.parseColor("#5C5C68"), Color.parseColor("#3E3E48"))
        } else {
            intArrayOf(color(R.color.vortex_purple), color(R.color.vortex_blue))
        }
        val g = GradientDrawable(GradientDrawable.Orientation.TL_BR, colors)
        if (oval) g.shape = GradientDrawable.OVAL else g.cornerRadius = dp(radiusDp).toFloat()
        return g
    }

    fun circleBackground(active: Boolean): GradientDrawable {
        if (active) return neonGradient(true)
        return GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(color(R.color.vortex_surface_light))
        }
    }

    fun pillBackground(active: Boolean): GradientDrawable {
        if (active) return neonGradient(false, 24)
        return GradientDrawable().apply {
            setColor(color(R.color.vortex_surface_light))
            cornerRadius = dp(24).toFloat()
        }
    }

    // Zemin: "tek renk" seçiliyken saf siyah ya da yumuşak beyaz (renk karışmaz).
    // Neon çizgileri ve bulanık renk lekeleri yalnızca Görünüm'den açılırsa çizilir.
    fun neonBackground(): NeonBackgroundDrawable {
        val base = color(R.color.vortex_background)
        val k = if (ThemeStore.mono()) 0f else if (ThemeStore.light) 0.16f else 0.10f
        val glow = if (k == 0f) base else mix(base, color(R.color.vortex_purple), k)

        var blobA = 0
        var blobB = 0
        var strength = 0
        if (ThemeStore.blur) {
            blobA = color(R.color.vortex_purple)
            blobB = color(R.color.vortex_blue)
            // Lekelerin gücü (0-255): tek renkte silik, koyu zeminde biraz güçlü
            strength = if (ThemeStore.mono()) 40 else if (ThemeStore.light) 75 else 85
        }

        return if (ThemeStore.lines) {
            NeonBackgroundDrawable(
                glow, base,
                color(R.color.vortex_purple), color(R.color.vortex_blue),
                density, blobA, blobB, strength
            )
        } else {
            NeonBackgroundDrawable(glow, base, glow, base, density, blobA, blobB, strength)
        }
    }

    fun sectionTitle(label: String): TextView = TextView(activity).apply {
        text = label
        textSize = 16f
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(color(R.color.vortex_text))
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            topMargin = dp(22)
            bottomMargin = dp(8)
        }
    }

    fun noteText(label: String): TextView = TextView(activity).apply {
        text = label
        textSize = 13f
        setTextColor(color(R.color.vortex_text_secondary))
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(8) }
    }

    fun actionPill(label: String, onClick: () -> Unit): TextView = TextView(activity).apply {
        text = label
        textSize = 13f
        gravity = Gravity.CENTER
        setTextColor(color(R.color.vortex_text))
        background = pillBackground(false)
        setPadding(dp(12), dp(10), dp(12), dp(10))
        layoutParams = LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
        ).apply { marginEnd = dp(6) }
        setOnClickListener { onClick() }
    }

    fun slider(
        label: String,
        maxValue: Int,
        startValue: Int,
        format: (Int) -> String,
        onChange: (Int) -> Unit,
        onDone: () -> Unit
    ): View {
        val box = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(2), dp(8), dp(2), dp(2))
        }
        val top = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        val name = TextView(activity).apply {
            text = label
            textSize = 14f
            setTextColor(color(R.color.vortex_text))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val value = TextView(activity).apply {
            text = format(startValue)
            textSize = 13f
            setTextColor(color(R.color.vortex_text_secondary))
        }
        top.addView(name)
        top.addView(value)

        val bar = SeekBar(activity)
        bar.max = maxValue
        bar.progress = startValue
        bar.progressTintList = ColorStateList.valueOf(color(R.color.vortex_purple))
        bar.thumbTintList = ColorStateList.valueOf(color(R.color.vortex_blue))
        bar.progressBackgroundTintList = ColorStateList.valueOf(color(R.color.vortex_surface_light))
        bar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                value.text = format(progress)
                if (fromUser) onChange(progress)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                onDone()
            }
        })
        box.addView(top)
        box.addView(bar)
        return box
    }

    // Tam ekran katman: zemin, kenar boşlukları
    fun overlay(): LinearLayout {
        val screen = LinearLayout(activity)
        screen.orientation = LinearLayout.VERTICAL
        screen.visibility = View.GONE
        screen.isClickable = true
        screen.isFocusable = true
        screen.background = neonBackground()
        screen.layoutParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
        screen.setOnApplyWindowInsetsListener { v, insets ->
            v.setPadding(
                dp(16),
                insets.systemWindowInsetTop + dp(12),
                dp(16),
                insets.systemWindowInsetBottom + dp(12)
            )
            insets
        }
        return screen
    }

    // Geri oku + başlık satırı
    fun header(title: String, onClose: () -> Unit): LinearLayout {
        val row = LinearLayout(activity)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        val close = ImageView(activity).apply {
            setPadding(dp(11), dp(11), dp(11), dp(11))
            isClickable = true
            isFocusable = true
            setImageDrawable(icon(IconDrawable.CHEVRON, color(R.color.vortex_purple), 24))
            layoutParams = LinearLayout.LayoutParams(dp(46), dp(46))
            setOnClickListener { onClose() }
        }
        val text = TextView(activity).apply {
            this.text = title
            textSize = 24f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(color(R.color.vortex_text))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = dp(4) }
        }
        row.addView(close)
        row.addView(text)
        return row
    }
}