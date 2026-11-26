package io.github.vortexmusic.player

import android.app.Activity
import android.app.Dialog
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

// Material 3 alt sayfası: aşağıdan kayarak açılan, üstü yuvarlak liste.
// AlertDialog.Builder gibi kullanılır: setTitle, setItems, setSingleChoiceItems, create().show()

class SheetDialog(
    private val activity: Activity,
    private val ui: Ui,
    private val title: String?,
    private val items: List<String>,
    private val selected: Int,
    private val onPick: (SheetDialog, Int) -> Unit,
    private val multi: BooleanArray? = null,
    private val saveText: String? = null,
    private val onSave: (() -> Unit)? = null
) {

    private var dialog: Dialog? = null
    private var sheet: View? = null

    fun show() {
        val d = Dialog(activity)
        d.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog = d

        val surface = ui.color(R.color.vortex_surface)
        val textCol = ui.color(R.color.vortex_text)
        val second = ui.color(R.color.vortex_text_secondary)
        val accent = ui.color(R.color.vortex_purple)
        val top = ui.dp(28).toFloat()

        val box = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(ui.mix(surface, accent, 0.10f))
                cornerRadii = floatArrayOf(top, top, top, top, 0f, 0f, 0f, 0f)
            }
            setPadding(ui.dp(8), ui.dp(10), ui.dp(8), ui.dp(24))
        }

        // Tutamaç çizgisi
        box.addView(View(activity).apply {
            background = GradientDrawable().apply {
                setColor((second and 0x00FFFFFF) or 0x99000000.toInt())
                cornerRadius = ui.dp(2).toFloat()
            }
            layoutParams = LinearLayout.LayoutParams(ui.dp(36), ui.dp(4)).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                bottomMargin = ui.dp(14)
            }
        })

        if (!title.isNullOrEmpty()) {
            box.addView(TextView(activity).apply {
                text = title
                textSize = 14f
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                setTextColor(second)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                setPadding(ui.dp(16), 0, ui.dp(16), ui.dp(8))
            })
        }

        val list = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        val rowH = ui.dp(56)
        for ((i, label) in items.withIndex()) {
            val on = i == selected
            val row = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(ui.dp(16), 0, ui.dp(16), 0)
                isClickable = true
                background = StateListDrawable().apply {
                    val press = GradientDrawable().apply {
                        setColor(ui.mix(surface, accent, 0.30f))
                        cornerRadius = ui.dp(16).toFloat()
                    }
                    val sel = GradientDrawable().apply {
                        setColor(ui.mix(surface, accent, 0.22f))
                        cornerRadius = ui.dp(16).toFloat()
                    }
                    addState(intArrayOf(android.R.attr.state_pressed), press)
                    addState(intArrayOf(), if (on) sel else ColorDrawable(Color.TRANSPARENT))
                }
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, rowH)
                
            }
            val label_ = TextView(activity)
            row.addView(label_.apply {
                text = label
                textSize = 16f
                if (on) typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                setTextColor(textCol)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
            val tick = TextView(activity).apply {
                text = "✓"
                textSize = 18f
                setTextColor(accent)
            }
            if (multi != null) {
                tick.visibility = if (multi[i]) View.VISIBLE else View.INVISIBLE
                label_.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                row.addView(tick)
                row.setOnClickListener {
                    multi[i] = !multi[i]
                    tick.visibility = if (multi[i]) View.VISIBLE else View.INVISIBLE
                }
            } else {
                if (on) row.addView(tick)
                row.setOnClickListener { onPick(this@SheetDialog, i) }
            }
            list.addView(row)
        }

        val maxH = (activity.resources.displayMetrics.heightPixels * 0.6f).toInt()
        val wanted = rowH * items.size
        val scroll = ScrollView(activity).apply {
            isVerticalScrollBarEnabled = false
            addView(list)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                if (wanted > maxH) maxH else ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        box.addView(scroll)
        if (saveText != null) {
            box.addView(TextView(activity).apply {
                text = saveText
                textSize = 15f
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER
                background = GradientDrawable().apply {
                    setColor(accent)
                    cornerRadius = ui.dp(24).toFloat()
                }
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ui.dp(48)).apply {
                    topMargin = ui.dp(14)
                    leftMargin = ui.dp(8)
                    rightMargin = ui.dp(8)
                }
                setOnClickListener {
                    onSave?.invoke()
                    dismiss()
                }
            })
        }
        sheet = box

        d.setContentView(box)
        d.setCanceledOnTouchOutside(true)
        d.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            setGravity(Gravity.BOTTOM)
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setDimAmount(0.55f)
        }
        d.show()

        // Aşağıdan yukarı kayarak açılır
        box.visibility = View.INVISIBLE
        box.post {
            box.translationY = box.height.toFloat()
            box.visibility = View.VISIBLE
            box.animate().translationY(0f).setDuration(220).start()
        }
    }

    fun dismiss() {
        val d = dialog ?: return
        val b = sheet
        if (b == null) {
            d.dismiss()
            return
        }
        b.animate().translationY(b.height.toFloat()).setDuration(160).withEndAction {
            try { d.dismiss() } catch (_: Exception) {}
        }.start()
    }

    class Builder(private val activity: Activity, private val ui: Ui) {
        private var title: String? = null
        private var items: List<String> = emptyList()
        private var selected = -1
        private var pick: (SheetDialog, Int) -> Unit = { _, _ -> }
        private var multi: BooleanArray? = null
        private var saveText: String? = null
        private var onSave: (() -> Unit)? = null

        fun setTitle(t: CharSequence?): Builder {
            title = t?.toString()
            return this
        }

        fun setItems(arr: Array<out CharSequence>, cb: (SheetDialog, Int) -> Unit): Builder {
            items = arr.map { it.toString() }
            pick = { dlg, i ->
                dlg.dismiss()
                cb(dlg, i)
            }
            return this
        }

        fun setSingleChoiceItems(arr: Array<out CharSequence>, checked: Int, cb: (SheetDialog, Int) -> Unit): Builder {
            items = arr.map { it.toString() }
            selected = checked
            pick = { dlg, i -> cb(dlg, i) }
            return this
        }

        fun setMultiChoiceItems(arr: Array<out CharSequence>, checked: BooleanArray, cb: Any?): Builder {
            items = arr.map { it.toString() }
            multi = checked
            return this
        }

        fun setPositiveButton(text: CharSequence?, cb: (Any?, Int) -> Unit): Builder {
            saveText = text?.toString()
            onSave = { cb(null, 0) }
            return this
        }

        // Alt sayfada ayrı "Kapat" düğmesi yok: dışarı dokununca ya da aşağı kaydırınca kapanır
        fun setNegativeButton(text: CharSequence?, listener: Any?): Builder = this

        fun create(): SheetDialog = SheetDialog(activity, ui, title, items, selected, pick, multi, saveText, onSave)
    }
}

// Eski pencere stilinin uyumluluğu için: alt sayfada ek bir stil gerekmez
fun DialogStyle.apply(@Suppress("UNUSED_PARAMETER") d: SheetDialog, @Suppress("UNUSED_PARAMETER") ui: Ui) {}
