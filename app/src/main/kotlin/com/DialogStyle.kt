package io.github.vortexmusic.player

import android.app.AlertDialog
import android.graphics.drawable.GradientDrawable

// Material 3 tarzı pencereler: yuvarlak köşeli koyu/açık zemin, vurgu renkli düğmeler
object DialogStyle {

    fun theme(): Int =
        if (ThemeStore.light) R.style.VortexDialogLight else R.style.VortexDialog

    fun apply(d: AlertDialog, ui: Ui) {
        d.window?.setBackgroundDrawable(
            GradientDrawable().apply {
                setColor(ui.color(R.color.vortex_surface))
                cornerRadius = ui.dp(28).toFloat()
            }
        )
        val accent = ui.color(R.color.vortex_purple)
        for (b in intArrayOf(AlertDialog.BUTTON_POSITIVE, AlertDialog.BUTTON_NEGATIVE, AlertDialog.BUTTON_NEUTRAL)) {
            d.getButton(b)?.setTextColor(accent)
        }
    }
}
