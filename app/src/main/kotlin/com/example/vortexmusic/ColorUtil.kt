package io.github.vortexmusic.player

import android.graphics.Bitmap
import android.graphics.Color

// Albüm kapağından baskın rengi bulur
object ColorUtil {
    fun dominant(src: Bitmap): Int {
        try {
            val soft = if (src.config == Bitmap.Config.HARDWARE) {
                src.copy(Bitmap.Config.ARGB_8888, false)
            } else {
                src
            }
            if (soft == null) return 0
            val s = Bitmap.createScaledBitmap(soft, 24, 24, true)
            val hsv = FloatArray(3)
            val weight = FloatArray(12)
            val rs = FloatArray(12)
            val gs = FloatArray(12)
            val bs = FloatArray(12)
            for (y in 0 until 24) {
                for (x in 0 until 24) {
                    val c = s.getPixel(x, y)
                    Color.colorToHSV(c, hsv)
                    if (hsv[1] < 0.28f || hsv[2] < 0.25f) continue
                    val b = (hsv[0] / 30f).toInt().coerceIn(0, 11)
                    val w = hsv[1] * hsv[2]
                    weight[b] += w
                    rs[b] += Color.red(c) * w
                    gs[b] += Color.green(c) * w
                    bs[b] += Color.blue(c) * w
                }
            }
            var best = -1
            var bw = 0f
            for (i in 0 until 12) {
                if (weight[i] > bw) {
                    bw = weight[i]
                    best = i
                }
            }
            if (best < 0 || bw < 0.5f) return 0
            val c = Color.rgb(
                (rs[best] / weight[best]).toInt(),
                (gs[best] / weight[best]).toInt(),
                (bs[best] / weight[best]).toInt()
            )
            Color.colorToHSV(c, hsv)
            hsv[1] = hsv[1].coerceIn(0.45f, 0.95f)
            hsv[2] = hsv[2].coerceIn(0.70f, 0.95f)
            return Color.HSVToColor(hsv)
        } catch (e: Exception) {
            return 0
        }
    }
}
