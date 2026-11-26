package io.github.vortexmusic.player

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.SharedPreferences
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsetsController
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
private const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

object ThemeStore {

    // a, b: vurgu renkleri (0 = özel durum: Vortex, tek renk ya da dinamik)
    // surfaces: koyu modda isteğe bağlı zemin renkleri [zemin, zemin sonu, kart, açık kart]
    class Scheme(val name: String, val a: Int, val b: Int, val surfaces: IntArray? = null)

    const val VORTEX = 0
    const val MONO = 1
    const val DYNAMIC = 9
    const val SAGE = 8
    const val NIGHT = 10
    const val SNOW = 11

    // Görünüm ekranında gösterilen temalar (sistem rengi yok). İlk üçü: Adaçayı, Gece Siyahı, Kar Beyazı
    val visible = intArrayOf(SAGE, NIGHT, SNOW, VORTEX, 2, 3, 4, 5, 6, 7)

    val schemes = listOf(
        Scheme("Vortex (varsayılan)", 0, 0),
        Scheme("Tek renk", 0, 0),
        Scheme("Alacakaranlık", Color.parseColor("#FF6B8B"), Color.parseColor("#FFB347")),
        Scheme("Derin Okyanus", Color.parseColor("#2DD4BF"), Color.parseColor("#3B82F6")),
        Scheme("Zümrüt Orman", Color.parseColor("#34D399"), Color.parseColor("#A3E635")),
        Scheme("Gül Bahçesi", Color.parseColor("#F472B6"), Color.parseColor("#A78BFA")),
        Scheme("Altın Saat", Color.parseColor("#FBBF24"), Color.parseColor("#F97316")),
        Scheme(
            "Kadife Bordo", Color.parseColor("#B8243F"), Color.parseColor("#6F1830"),
            intArrayOf(
                Color.parseColor("#0A0507"),
                Color.parseColor("#12070B"),
                Color.parseColor("#190B10"),
                Color.parseColor("#28121A")
            )
        ),
        Scheme(
            "Adaçayı", Color.parseColor("#4E9279"), Color.parseColor("#2E6B58"),
            intArrayOf(
                Color.parseColor("#060A08"),
                Color.parseColor("#0A100D"),
                Color.parseColor("#0E1613"),
                Color.parseColor("#18241F")
            )
        ),
        Scheme("Dinamik", 0, 0),
        Scheme("Gece Siyahı", 0, 0),
        Scheme("Kar Beyazı", 0, 0)
    )

    // 0 sistem, 1 açık, 2 koyu
    var mode = 2
    var scheme = 0
    var lines = true
    var blur = false

    var light = false
        private set

    val amoled: Boolean get() = !light

    private var dyn: IntArray? = null

    private val lightA = Color.parseColor("#7B3FE4")
    private val lightB = Color.parseColor("#1E6FD9")
    private val monoDarkA = Color.WHITE
    private val monoDarkB = Color.parseColor("#C8C8D2")
    private val monoLightA = Color.parseColor("#14161B")
    private val monoLightB = Color.parseColor("#3A3A44")

    fun mono(): Boolean = scheme == MONO || scheme == NIGHT || scheme == SNOW

    fun isDynamicAvailable(): Boolean = Build.VERSION.SDK_INT >= 31

    fun systemIsLight(ctx: Context): Boolean =
        (ctx.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) !=
            Configuration.UI_MODE_NIGHT_YES

    fun lightFor(ctx: Context, m: Int): Boolean = when (m) {
        1 -> true
        2 -> false
        else -> systemIsLight(ctx)
    }

    // Telefonun duvar kağıdından gelen renkler (Android 12 ve üstü)
    private fun dynColors(ctx: Context, isLight: Boolean): IntArray? {
        if (Build.VERSION.SDK_INT < 31) return null
        return try {
            val r = ctx.resources
            val t = ctx.theme
            if (isLight) {
                intArrayOf(
                    r.getColor(android.R.color.system_accent1_700, t),
                    r.getColor(android.R.color.system_accent1_900, t)
                )
            } else {
                intArrayOf(
                    r.getColor(android.R.color.system_accent1_500, t),
                    r.getColor(android.R.color.system_accent1_700, t)
                )
            }
        } catch (e: Exception) {
            null
        }
    }

    // Açık zeminde vurgu renkleri okunsun diye koyulaştırılır
    private fun darken(c: Int): Int {
        val k = 0.70f
        return Color.rgb(
            (Color.red(c) * k).toInt(),
            (Color.green(c) * k).toInt(),
            (Color.blue(c) * k).toInt()
        )
    }

    // Eski kayıtları yeni sisteme taşır: [mod, şema]
    private fun migrate(sp: SharedPreferences): IntArray {
        if (sp.contains("theme_id")) {
            val id = sp.getInt("theme_id", 0)
            return when (id) {
                0 -> intArrayOf(2, 0)
                1 -> intArrayOf(2, 1)
                2 -> intArrayOf(1, 1)
                in 3..8 -> intArrayOf(2, id - 1)
                else -> intArrayOf(2, 0)
            }
        }
        val oldLight = sp.getBoolean("light", false)
        val oldPreset = sp.getInt("preset", 0)
        return when {
            oldLight -> intArrayOf(1, 1)
            oldPreset in 1..5 -> intArrayOf(2, oldPreset + 1)
            else -> intArrayOf(2, 0)
        }
    }

    fun load(ctx: Context) {
        I18n.load(ctx)
        val sp = ctx.getSharedPreferences("vortex_theme", Context.MODE_PRIVATE)
        if (sp.contains("mode")) {
            mode = sp.getInt("mode", 2).coerceIn(0, 2)
            scheme = sp.getInt("scheme", 0)
            // Listeye Adaçayı eklenince Dinamik'in sırası 8'den 9'a kaydı
            if (sp.getInt("scheme_v", 1) < 2 && scheme == 8) scheme = DYNAMIC
            scheme = scheme.coerceIn(0, schemes.size - 1)
        } else {
            val m = migrate(sp)
            mode = m[0]
            scheme = m[1]
        }
        // Yeni düzen: mod seçimi yok. Açık tema kullananlar Kar Beyazı'na, tek renkliler Gece Siyahı'na, sistem rengi kullananlar Adaçayı'na geçer.
        scheme = when {
            mode == 1 -> SNOW
            scheme == MONO -> NIGHT
            visible.contains(scheme) -> scheme
            else -> SAGE
        }
        mode = if (scheme == SNOW) 1 else 2
        lines = sp.getBoolean("lines", true)
        blur = sp.getBoolean("blur", false)
        light = lightFor(ctx, mode)
        dyn = if (scheme == DYNAMIC) dynColors(ctx, light) else null
    }

    fun save(ctx: Context) {
        ctx.getSharedPreferences("vortex_theme", Context.MODE_PRIVATE).edit()
            .putInt("mode", mode)
            .putInt("scheme", scheme)
            .putInt("scheme_v", 3)
            .putBoolean("lines", lines)
            .putBoolean("blur", blur)
            .commit()
    }

    // Listedeki dairelerin renkleri (seçilen moda göre)
    fun previewColors(ctx: Context, index: Int, isLight: Boolean): IntArray {
        val s = schemes[index]
        return when (index) {
            VORTEX -> if (isLight) {
                intArrayOf(lightA, lightB)
            } else {
                intArrayOf(
                    ctx.resources.getColor(R.color.vortex_purple, ctx.theme),
                    ctx.resources.getColor(R.color.vortex_blue, ctx.theme)
                )
            }
            MONO -> if (isLight) intArrayOf(monoLightA, monoLightB) else intArrayOf(monoDarkA, monoDarkB)
            NIGHT -> intArrayOf(monoDarkA, monoDarkB)
            SNOW -> intArrayOf(monoLightA, monoLightB)
            DYNAMIC -> dynColors(ctx, isLight) ?: intArrayOf(lightA, lightB)
            else -> if (isLight) intArrayOf(darken(s.a), darken(s.b)) else intArrayOf(s.a, s.b)
        }
    }

    fun displayName(index: Int, isLight: Boolean): String = when (index) {
        0 -> "Vortex"
        MONO -> if (isLight) tx("İlk Kar", "First Snow") else tx("Gece Yarısı", "Midnight")
        2 -> tx("Alacakaranlık", "Twilight")
        3 -> tx("Derin Okyanus", "Deep Ocean")
        4 -> tx("Zümrüt Orman", "Emerald Forest")
        5 -> tx("Gül Bahçesi", "Rose Garden")
        6 -> tx("Altın Saat", "Golden Hour")
        7 -> tx("Kadife Bordo", "Velvet Burgundy")
        8 -> tx("Adaçayı", "Sage")
        DYNAMIC -> tx("Dinamik", "Dynamic")
        NIGHT -> tx("Gece Siyahı", "Night Black")
        SNOW -> tx("Kar Beyazı", "Snow White")
        else -> schemes[index].name
    }

    private fun accentA(): Int? = when (scheme) {
        VORTEX -> if (light) lightA else null
        MONO, NIGHT, SNOW -> if (light) monoLightA else monoDarkA
        DYNAMIC -> dyn?.get(0)
        else -> if (light) darken(schemes[scheme].a) else schemes[scheme].a
    }

    private fun accentB(): Int? = when (scheme) {
        VORTEX -> if (light) lightB else null
        MONO, NIGHT, SNOW -> if (light) monoLightB else monoDarkB
        DYNAMIC -> dyn?.get(1)
        else -> if (light) darken(schemes[scheme].b) else schemes[scheme].b
    }

    fun override(id: Int): Int? {
        when (id) {
            R.color.vortex_purple, R.color.primary -> {
                val a = accentA()
                if (a != null) return a
            }
            R.color.vortex_blue -> {
                val b = accentB()
                if (b != null) return b
            }
        }
        if (light) {
            // Açık mod: göz almayan, yumuşatılmış beyaz
            when (id) {
                R.color.vortex_background -> return Color.parseColor("#F7F8FA")
                R.color.vortex_background_end -> return Color.parseColor("#EEF0F4")
                R.color.vortex_surface -> return Color.parseColor("#FFFFFF")
                R.color.vortex_surface_light -> return Color.parseColor("#ECEEF2")
                R.color.vortex_text -> return Color.parseColor("#14161B")
                R.color.vortex_text_secondary -> return Color.parseColor("#5C616B")
            }
        } else {
            val s = schemes[scheme].surfaces
            val isMono = mono()
            when (id) {
                R.color.vortex_background -> return s?.get(0) ?: Color.BLACK
                R.color.vortex_background_end ->
                    return s?.get(1) ?: (if (isMono) Color.BLACK else Color.parseColor("#06070C"))
                R.color.vortex_surface ->
                    return s?.get(2) ?: Color.parseColor(if (isMono) "#111114" else "#0E0E14")
                R.color.vortex_surface_light ->
                    return s?.get(3) ?: Color.parseColor(if (isMono) "#1E1E23" else "#1B1B24")
                R.color.vortex_text -> {
                    if (isMono) return Color.parseColor("#F5F5F7")
                }
                R.color.vortex_text_secondary -> {
                    if (isMono) return Color.parseColor("#A3A6AD")
                }
            }
        }
        return null
    }
}

object ThemeTool {

    fun install(host: ToolHost) {
        val activity = host.activity
        // Ekran hazır olduktan sonra: sistem çubukları ve XML'den gelen yazı renkleri temaya uyar
        activity.window.decorView.post {
            val ui = host.ui
            val bg = ui.color(R.color.vortex_background)
            activity.window.statusBarColor = bg
            activity.window.navigationBarColor = bg
            applySystemBars(activity, ThemeStore.light)
            findText(activity.window.decorView, "Vortex")
                ?.setTextColor(ui.color(R.color.vortex_purple))
            tint(activity, R.id.statusText, ui.color(R.color.vortex_text_secondary))
            tint(activity, R.id.nowTitle, ui.color(R.color.vortex_text))
            tint(activity, R.id.nowArtist, ui.color(R.color.vortex_text_secondary))
            tint(activity, R.id.npTitle, ui.color(R.color.vortex_text))
            tint(activity, R.id.npArtist, ui.color(R.color.vortex_text_secondary))
            tint(activity, R.id.npPosition, ui.color(R.color.vortex_text_secondary))
            tint(activity, R.id.npDuration, ui.color(R.color.vortex_text_secondary))
            activity.findViewById<EditText>(R.id.searchBox)?.apply {
                setTextColor(ui.color(R.color.vortex_text))
                setHintTextColor(ui.color(R.color.vortex_text_secondary))
            }
        }
    }

    private fun tint(activity: Activity, id: Int, color: Int) {
        activity.findViewById<TextView>(id)?.setTextColor(color)
    }

    // Açık temada durum çubuğu ve gezinme çubuğu simgeleri koyu olur
    @Suppress("DEPRECATION")
    private fun applySystemBars(activity: Activity, light: Boolean) {
        if (Build.VERSION.SDK_INT >= 30) {
            val c = activity.window.insetsController
            if (c != null) {
                val mask = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
                    WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
                c.setSystemBarsAppearance(if (light) mask else 0, mask)
            }
        } else {
            var flags = 0
            if (light) {
                flags = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
                if (Build.VERSION.SDK_INT >= 26) {
                    flags = flags or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
                }
            }
            activity.window.decorView.systemUiVisibility = flags
        }
    }

    private fun findText(v: View, text: String): TextView? {
        if (v is TextView && v.text.toString() == text) return v
        if (v is ViewGroup) {
            for (i in 0 until v.childCount) {
                val r = findText(v.getChildAt(i), text)
                if (r != null) return r
            }
        }
        return null
    }

    fun entries(host: ToolHost): List<ToolEntry> = listOf(
        ToolEntry(
            tx("Görünüm", "Appearance"),
            tx(
                "Renk teması, bulanık renk ve neon çizgileri",
                "Color theme, blurred color and neon lines"
            ),
            IconDrawable.DISC
        ) { open(host) },
        ToolEntry(
            tx("Dil", "Language"),
            tx("Uygulama dili: sistem, Türkçe ya da English", "App language: system, Türkçe or English"),
            IconDrawable.DISC
        ) { openLanguage(host) }
    )

    private fun openLanguage(host: ToolHost) {
        val ui = host.ui
        val activity = host.activity
        val box = LinearLayout(activity)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(ui.dp(20), ui.dp(4), ui.dp(20), ui.dp(8))

        val names = arrayOf(tx("Sistem", "System"), "Türkçe", "English")
        var dialog: AlertDialog? = null
        names.forEachIndexed { i, label ->
            val on = I18n.choice == i
            box.addView(TextView(activity).apply {
                text = label
                textSize = 15f
                gravity = Gravity.CENTER
                setTextColor(if (on) Color.WHITE else ui.color(R.color.vortex_text))
                background = ui.pillBackground(on)
                setPadding(ui.dp(16), ui.dp(13), ui.dp(16), ui.dp(13))
                layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = ui.dp(10) }
                setOnClickListener {
                    dialog?.dismiss()
                    if (I18n.choice != i) {
                        I18n.save(activity, i)
                        activity.recreate()
                    }
                }
            })
        }

        val dialogTheme = if (ThemeStore.light) {
            android.R.style.Theme_Material_Light_Dialog_Alert
        } else {
            android.R.style.Theme_Material_Dialog_Alert
        }
        val d = AlertDialog.Builder(activity, dialogTheme)
            .setTitle(tx("Dil", "Language"))
            .setView(box)
            .setNegativeButton(tx("Vazgeç", "Cancel"), null)
            .create()
        dialog = d
        d.show()
        d.window?.setBackgroundDrawable(
            GradientDrawable().apply {
                setColor(ui.color(R.color.vortex_surface))
                cornerRadius = ui.dp(24).toFloat()
            }
        )
        d.getButton(AlertDialog.BUTTON_NEGATIVE)
            ?.setTextColor(ui.color(R.color.vortex_text_secondary))
    }

    private fun mixWhite(c: Int, k: Float): Int = Color.rgb(
        (Color.red(c) + (255 - Color.red(c)) * k).toInt(),
        (Color.green(c) + (255 - Color.green(c)) * k).toInt(),
        (Color.blue(c) + (255 - Color.blue(c)) * k).toInt()
    )

    // Üzerine yazı konacak zemine göre okunur renk
    private fun readableOn(c: Int): Int {
        val l = (0.299f * Color.red(c) + 0.587f * Color.green(c) + 0.114f * Color.blue(c)) / 255f
        return if (l > 0.62f) Color.parseColor("#0B0B0F") else Color.WHITE
    }

    // Üç parçalı daire renkleri: [üst, sol alt, sağ alt]
    private fun swatch(ctx: Context, index: Int): IntArray = when (index) {
        ThemeStore.NIGHT -> intArrayOf(
            Color.parseColor("#3A3A42"), Color.parseColor("#131316"), Color.parseColor("#F5F5F7")
        )
        ThemeStore.SNOW -> intArrayOf(
            Color.WHITE, Color.parseColor("#DDE0E6"), Color.parseColor("#14161B")
        )
        ThemeStore.VORTEX -> {
            val pc = ThemeStore.previewColors(ctx, ThemeStore.VORTEX, false)
            intArrayOf(mixWhite(pc[0], 0.6f), pc[1], pc[0])
        }
        else -> {
            val sc = ThemeStore.schemes[index]
            intArrayOf(mixWhite(sc.a, 0.6f), sc.b, sc.a)
        }
    }

    private fun open(host: ToolHost) {
        val ui = host.ui
        val activity = host.activity
        var scheme = ThemeStore.scheme
        var lines = ThemeStore.lines
        var blur = ThemeStore.blur

        val box = LinearLayout(activity)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(ui.dp(20), ui.dp(4), ui.dp(20), 0)
        val scroll = ScrollView(activity)
        scroll.addView(box)

        fun toggle(label: String, on: Boolean, onClick: () -> Unit): TextView =
            TextView(activity).apply {
                text = label
                textSize = 14f
                gravity = Gravity.CENTER
                setTextColor(if (on) Color.WHITE else ui.color(R.color.vortex_text))
                background = ui.pillBackground(on)
                setPadding(ui.dp(16), ui.dp(12), ui.dp(16), ui.dp(12))
                layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = ui.dp(14) }
                setOnClickListener { onClick() }
            }

        var savedX = 0

        fun render() {
            box.removeAllViews()

            // Renk teması: üç parçalı daire kartları
            box.addView(ui.noteText(tx("Renk teması", "Color theme")))
            val hs = HorizontalScrollView(activity)
            hs.isHorizontalScrollBarEnabled = false
            hs.layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = ui.dp(10) }
            val row = LinearLayout(activity)
            row.orientation = LinearLayout.HORIZONTAL
            for (i in ThemeStore.visible) {
                val sel = i == scheme
                val sw = swatch(activity, i)

                val tile = LinearLayout(activity)
                tile.orientation = LinearLayout.VERTICAL
                tile.gravity = Gravity.CENTER_HORIZONTAL
                tile.setPadding(ui.dp(6), ui.dp(14), ui.dp(6), ui.dp(10))
                tile.background = GradientDrawable().apply {
                    setColor(ui.color(R.color.vortex_surface_light))
                    cornerRadius = ui.dp(20).toFloat()
                    if (sel) setStroke(ui.dp(2), ui.color(R.color.vortex_purple))
                }
                tile.layoutParams = LinearLayout.LayoutParams(ui.dp(100), WRAP).apply {
                    marginStart = ui.dp(4)
                    marginEnd = ui.dp(4)
                }

                val holder = FrameLayout(activity)
                holder.layoutParams = LinearLayout.LayoutParams(ui.dp(64), ui.dp(64))
                val sv = SwatchView(activity, sw[0], sw[1], sw[2])
                sv.layoutParams = FrameLayout.LayoutParams(MATCH, MATCH)
                holder.addView(sv)
                if (sel) {
                    val check = TextView(activity)
                    check.text = "✓"
                    check.textSize = 16f
                    check.gravity = Gravity.CENTER
                    check.setTextColor(readableOn(sw[2]))
                    check.background = GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        setColor(sw[2])
                    }
                    check.layoutParams = FrameLayout.LayoutParams(ui.dp(28), ui.dp(28), Gravity.CENTER)
                    holder.addView(check)
                }
                tile.addView(holder)
                tile.addView(TextView(activity).apply {
                    text = ThemeStore.displayName(i, false)
                    textSize = 12f
                    gravity = Gravity.CENTER
                    maxLines = 2
                    setTextColor(
                        if (sel) ui.color(R.color.vortex_text) else ui.color(R.color.vortex_text_secondary)
                    )
                    if (sel) setTypeface(typeface, Typeface.BOLD)
                    layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = ui.dp(8) }
                })
                tile.setOnClickListener {
                    scheme = i
                    render()
                }
                row.addView(tile)
            }
            hs.addView(row)
            box.addView(hs)

            // Sayfa noktaları (yana kaydırınca ilerler)
            val pages = (ThemeStore.visible.size + 2) / 3
            val dots = LinearLayout(activity)
            dots.orientation = LinearLayout.HORIZONTAL
            dots.gravity = Gravity.CENTER
            dots.layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = ui.dp(10) }
            val dotViews = ArrayList<View>()
            for (p in 0 until pages) {
                val d = View(activity)
                d.layoutParams = LinearLayout.LayoutParams(ui.dp(8), ui.dp(8)).apply {
                    marginStart = ui.dp(4)
                    marginEnd = ui.dp(4)
                }
                dotViews.add(d)
                dots.addView(d)
            }
            fun setDots(x: Int) {
                val max = (row.width - hs.width).coerceAtLeast(1)
                val page = ((x.toFloat() / max) * (pages - 1) + 0.5f).toInt().coerceIn(0, pages - 1)
                for (p in dotViews.indices) {
                    dotViews[p].background = GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        setColor(
                            if (p == page) ui.color(R.color.vortex_purple)
                            else ui.color(R.color.vortex_text_secondary)
                        )
                    }
                }
            }
            hs.setOnScrollChangeListener { _, x, _, _, _ ->
                savedX = x
                setDots(x)
            }
            box.addView(dots)
            hs.post {
                hs.scrollTo(savedX, 0)
                setDots(savedX)
            }

            box.addView(
                toggle(tx("Bulanık renk: ", "Blurred color: ") + (if (blur) tx("Açık", "On") else tx("Kapalı", "Off")), blur) {
                    blur = !blur
                    render()
                }
            )
            box.addView(
                ui.noteText(
                    tx(
                        "Zemine, seçtiğin renk temasından yumuşak ve yayılmış renk lekeleri ekler.",
                        "Adds soft, diffused color blobs from your chosen color theme to the background."
                    )
                )
            )
            box.addView(
                toggle(tx("Neon çizgileri: ", "Neon lines: ") + (if (lines) tx("Açık", "On") else tx("Kapalı", "Off")), lines) {
                    lines = !lines
                    render()
                }
            )
        }
        render()

        // Pencere, o an açık olan temaya göre açık ya da koyu açılır
        val dialogTheme = if (ThemeStore.light) {
            android.R.style.Theme_Material_Light_Dialog_Alert
        } else {
            android.R.style.Theme_Material_Dialog_Alert
        }
        val dialog = AlertDialog.Builder(activity, dialogTheme)
            .setTitle(tx("Görünüm", "Appearance"))
            .setView(scroll)
            .setPositiveButton(tx("Uygula", "Apply")) { _, _ ->
                ThemeStore.scheme = scheme
                ThemeStore.mode = if (scheme == ThemeStore.SNOW) 1 else 2
                ThemeStore.lines = lines
                ThemeStore.blur = blur
                ThemeStore.save(activity)
                activity.recreate()
            }
            .setNegativeButton(tx("Vazgeç", "Cancel"), null)
            .create()
        dialog.show()
        dialog.window?.setBackgroundDrawable(
            GradientDrawable().apply {
                setColor(ui.color(R.color.vortex_surface))
                cornerRadius = ui.dp(24).toFloat()
            }
        )
        dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            ?.setTextColor(ui.color(R.color.vortex_purple))
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE)
            ?.setTextColor(ui.color(R.color.vortex_text_secondary))
    }
}

// Üç parçalı renk dairesi (üst yarım, sol alt çeyrek, sağ alt çeyrek)
private class SwatchView(
    context: Context,
    private val top: Int,
    private val left: Int,
    private val right: Int
) : View(context) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()

    override fun onDraw(canvas: Canvas) {
        val size = minOf(width, height).toFloat()
        val l = (width - size) / 2f
        val t = (height - size) / 2f
        rect.set(l, t, l + size, t + size)
        paint.style = Paint.Style.FILL
        paint.color = top
        canvas.drawArc(rect, 180f, 180f, true, paint)
        paint.color = left
        canvas.drawArc(rect, 90f, 90f, true, paint)
        paint.color = right
        canvas.drawArc(rect, 0f, 90f, true, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 2f
        paint.color = Color.parseColor("#66808080")
        canvas.drawCircle(l + size / 2f, t + size / 2f, size / 2f - 1f, paint)
    }
}
