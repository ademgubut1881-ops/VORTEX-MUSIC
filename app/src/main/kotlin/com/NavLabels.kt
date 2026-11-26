package io.github.vortexmusic.player

import android.app.Activity
import android.graphics.Typeface
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView

// Alt menüdeki simgelerin altına yazı ekler (Ana sayfa, Ara, Kütüphane).
// Seçili sekmenin yazısı mor ve kalın olur.
class NavLabels(activity: Activity, tabIds: IntArray, titles: Array<String>) {

    private val labels = ArrayList<TextView>()

    init {
        for (i in tabIds.indices) {
            val tab = activity.findViewById<LinearLayout>(tabIds[i])
            tab.orientation = LinearLayout.VERTICAL
            tab.gravity = Gravity.CENTER
            val tv = TextView(activity)
            tv.text = titles[i]
            tv.textSize = 11f
            tv.maxLines = 1
            tv.gravity = Gravity.CENTER
            tv.layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            tab.addView(tv)
            labels.add(tv)
        }
    }

    fun update(selected: Int, activeColor: Int, idleColor: Int) {
        for (i in labels.indices) {
            val on = i == selected
            labels[i].setTextColor(if (on) activeColor else idleColor)
            labels[i].setTypeface(null, if (on) Typeface.BOLD else Typeface.NORMAL)
        }
    }
}
