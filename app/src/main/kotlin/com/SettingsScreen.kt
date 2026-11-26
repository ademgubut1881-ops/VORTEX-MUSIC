package io.github.vortexmusic.player

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

// Ayarlar sayfasının ihtiyaç duyduğu eylemler (MainActivity doldurur)
class SettingsActions(
    val openAudio: () -> Unit,
    val openStats: () -> Unit,
    val ringDialog: () -> Unit,
    val ringLabel: () -> String,
    val ringColorDialog: () -> Unit,
    val ringColorLabel: () -> String,
    val separatorDialog: () -> Unit,
    val refreshLists: () -> Unit,
    val tools: () -> List<ToolEntry>
)

class SRow(
    val iconKind: Int,
    val circle: Int,
    val title: String,
    val subtitle: String,
    val action: (() -> Unit)?
)

class SSection(val title: String, val rows: List<SRow>)

// Samsung ayarları gibi: gruplanmış yuvarlak kartlar, renkli yuvarlak simgeler
class SettingsScreen(
    private val activity: Activity,
    private val palette: Palette,
    bg: Drawable,
    private val actions: SettingsActions
) {

    private val density = activity.resources.displayMetrics.density
    private fun dp(v: Int): Int = (v * density).toInt()

    val view: LinearLayout = LinearLayout(activity)
    private val content = LinearLayout(activity)

    val isOpen: Boolean
        get() = view.visibility == View.VISIBLE

    private val cPurple = palette.purple
    private val cBlue = palette.blue
    private val cViolet = palette.blue
    private val cTeal = palette.purple
    private val cPink = palette.blue

    init {
        view.orientation = LinearLayout.VERTICAL
        view.visibility = View.GONE
        view.isClickable = true
        view.isFocusable = true
        view.background = bg
        view.layoutParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
        view.setOnApplyWindowInsetsListener { v, insets ->
            v.setPadding(
                dp(16),
                insets.systemWindowInsetTop + dp(12),
                dp(16),
                insets.systemWindowInsetBottom + dp(12)
            )
            insets
        }

        val header = LinearLayout(activity)
        header.orientation = LinearLayout.HORIZONTAL
        header.gravity = Gravity.CENTER_VERTICAL
        val close = ImageView(activity)
        close.setPadding(dp(11), dp(11), dp(11), dp(11))
        close.isClickable = true
        close.isFocusable = true
        close.setImageDrawable(IconDrawable(IconDrawable.CHEVRON, palette.purple, dp(24)))
        close.layoutParams = LinearLayout.LayoutParams(dp(46), dp(46))
        close.setOnClickListener { hide() }
        val title = TextView(activity)
        title.text = tx("Ayarlar", "Settings")
        title.textSize = 24f
        title.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        title.setTextColor(palette.text)
        title.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { marginStart = dp(4) }
        // Gizli geliştirici araçları: başlığa 7 kez dokun
        var devTaps = 0
        title.setOnClickListener {
            devTaps++
            if (devTaps >= 7) {
                devTaps = 0
                if (!AudioTestTool.unlocked(activity)) {
                    AudioTestTool.unlock(activity)
                    android.widget.Toast.makeText(
                        activity, tx("Geliştirici araçları açıldı", "Developer tools unlocked"),
                        android.widget.Toast.LENGTH_SHORT
                    ).show()
                    rebuild()
                }
            }
        }
        header.addView(close)
        header.addView(title)

        val scroll = ScrollView(activity)
        scroll.isVerticalScrollBarEnabled = false
        scroll.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
        ).apply { topMargin = dp(8) }
        content.orientation = LinearLayout.VERTICAL
        scroll.addView(
            content,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )

        view.addView(header)
        view.addView(scroll)
    }

    fun show() {
        rebuild()
        view.visibility = View.VISIBLE
    }

    fun hide() {
        view.visibility = View.GONE
    }

    private fun sections(): List<SSection> {
        val a = actions
        val list = ArrayList<SSection>()

        list.add(
            SSection(
                tx("Ses", "Sound"),
                listOf(
                    SRow(
                        IconDrawable.EQ, cPurple,
                        tx("Ekolayzır ve ses efektleri", "Equalizer and sound effects"),
                        tx("Vortex Audio Engine · 10 bant, bas, genişlik, otomatik denge", "Vortex Audio Engine · 10 bands, bass, width, auto level"),
                        { a.openAudio() }
                    )
                )
            )
        )

        list.add(
            SSection(
                tx("Görünüm", "Appearance"),
                listOf(
                    SRow(IconDrawable.DISC, cViolet, tx("Girdap animasyonu", "Vortex animation"), a.ringLabel(), { a.ringDialog() }),
                    SRow(IconDrawable.DISC, cPink, tx("Animasyon rengi", "Animation color"), a.ringColorLabel(), { a.ringColorDialog() })
                )
            )
        )

        list.add(
            SSection(
                tx("Kütüphane", "Library"),
                listOf(
                    SRow(
                        IconDrawable.NOTE, cBlue, tx("Akıllı sıralama", "Smart sorting"),
                        if (SmartSort.enabled) tx("Açık: \"The\" atlanır, sayılar doğru sıralanır", "On: skips \"The\", sorts numbers naturally")
                        else tx("Kapalı", "Off"),
                        {
                            SmartSort.set(activity, !SmartSort.enabled)
                            a.refreshLists()
                        }
                    ),
                    SRow(
                        IconDrawable.ARTIST, cTeal, tx("Sanatçı ayırıcıları", "Artist separators"),
                        tx("\"A, B\" gibi yazıları ayrı sanatçı say", "Treat \"A, B\" as separate artists"),
                        { a.separatorDialog() }
                    )
                )
            )
        )

        list.add(
            SSection(
                tx("İstatistikler", "Statistics"),
                listOf(
                    SRow(
                        IconDrawable.CHART, cBlue, tx("Dinleme istatistikleri", "Listening statistics"),
                        tx("En çok dinlediklerin ve dinleme saatlerin", "Your most played songs and listening hours"),
                        { a.openStats() }
                    )
                )
            )
        )

        val tools = a.tools()
        if (tools.isNotEmpty()) {
            val rows = ArrayList<SRow>()
            val colors = intArrayOf(cPurple, cTeal, cViolet, cBlue, cPink)
            for ((i, t) in tools.withIndex()) {
                rows.add(SRow(t.iconKind, colors[i % colors.size], t.title, t.subtitle, { t.action() }))
            }
            list.add(SSection(tx("Araçlar", "Tools"), rows))
        }

        list.add(
            SSection(
                tx("Hakkında", "About"),
                listOf(
                    SRow(
                        IconDrawable.HEART, cPink, "Vortex Music",
                        tx("Beta 2 · GPL-3.0 · reklamsız, takipsiz, internet izni yok", "Beta 2 · GPL-3.0 · no ads, no trackers, no internet permission"),
                        null
                    ),
                    SRow(
                        IconDrawable.FOLDER, cViolet, tx("Kaynak kodu", "Source code"),
                        "github.com/ademgubut1881-ops/VORTEX-MUSIC",
                        {
                            try {
                                activity.startActivity(
                                    Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/ademgubut1881-ops/VORTEX-MUSIC"))
                                )
                            } catch (e: Exception) {
                            }
                        }
                    )
                )
            )
        )
        return list
    }

    private fun rebuild() {
        content.removeAllViews()
        for (s in sections()) {
            val head = TextView(activity)
            head.text = s.title
            head.textSize = 14f
            head.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            head.setTextColor(palette.purple)
            head.setPadding(dp(8), dp(18), 0, dp(8))
            content.addView(head)

            val card = LinearLayout(activity)
            card.orientation = LinearLayout.VERTICAL
            card.background = GradientDrawable().apply {
                setColor(palette.surface)
                cornerRadius = dp(22).toFloat()
            }
            for ((i, r) in s.rows.withIndex()) {
                card.addView(rowView(r))
                if (i < s.rows.size - 1) {
                    val line = View(activity)
                    line.setBackgroundColor(Color.argb(40, 255, 255, 255))
                    line.layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, 1
                    ).apply { marginStart = dp(68); marginEnd = dp(16) }
                    card.addView(line)
                }
            }
            content.addView(card)
        }
        val pad = View(activity)
        pad.layoutParams = LinearLayout.LayoutParams(1, dp(24))
        content.addView(pad)
    }

    private fun rowView(r: SRow): View {
        val row = LinearLayout(activity)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.setPadding(dp(14), dp(12), dp(14), dp(12))

        val circle = ImageView(activity)
        circle.setPadding(dp(9), dp(9), dp(9), dp(9))
        // Material 3: tek tip tonlu zemin, vurgu renkli simge (her satıra ayrı renk yok)
        val tone = Color.rgb(
            (Color.red(palette.surface) * 0.68f + Color.red(palette.purple) * 0.32f).toInt(),
            (Color.green(palette.surface) * 0.68f + Color.green(palette.purple) * 0.32f).toInt(),
            (Color.blue(palette.surface) * 0.68f + Color.blue(palette.purple) * 0.32f).toInt()
        )
        circle.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(tone)
        }
        circle.setImageDrawable(IconDrawable(r.iconKind, palette.text, dp(22)))
        circle.layoutParams = LinearLayout.LayoutParams(dp(40), dp(40))

        val col = LinearLayout(activity)
        col.orientation = LinearLayout.VERTICAL
        col.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            .apply { marginStart = dp(14) }
        val t = TextView(activity)
        t.text = r.title
        t.textSize = 16f
        t.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        t.setTextColor(palette.text)
        val st = TextView(activity)
        st.text = r.subtitle
        st.textSize = 12.5f
        st.maxLines = 2
        st.ellipsize = TextUtils.TruncateAt.END
        st.setTextColor(palette.secondary)
        col.addView(t)
        col.addView(st)

        row.addView(circle)
        row.addView(col)

        if (r.action != null) {
            val arrow = ImageView(activity)
            arrow.setImageDrawable(IconDrawable(IconDrawable.CHEVRON, palette.secondary, dp(18)))
            arrow.rotation = 180f
            arrow.layoutParams = LinearLayout.LayoutParams(dp(22), dp(22)).apply { marginStart = dp(8) }
            row.addView(arrow)
            row.isClickable = true
            row.isFocusable = true
            row.setOnClickListener {
                r.action.invoke()
                // Ayar değişmiş olabilir: kısa süre sonra alt yazıları yenile
                row.postDelayed({ if (isOpen) rebuild() }, 400)
            }
        }
        return row
    }
}
