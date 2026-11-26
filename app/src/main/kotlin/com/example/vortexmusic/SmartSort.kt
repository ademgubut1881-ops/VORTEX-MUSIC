package io.github.vortexmusic.player

import android.content.Context

// Akıllı sıralama: "The Weeknd" W'de durur, "Şarkı 2" ile "Şarkı 10" doğru sırada olur.
object SmartSort {
    var enabled = true
        private set

    private val digits = Regex("\\d+")

    fun load(ctx: Context) {
        enabled = ctx.getSharedPreferences("vortex_artist", Context.MODE_PRIVATE).getBoolean("smart", true)
    }

    fun set(ctx: Context, on: Boolean) {
        enabled = on
        ctx.getSharedPreferences("vortex_artist", Context.MODE_PRIVATE).edit().putBoolean("smart", on).apply()
    }

    // Sıralama anahtarı
    fun key(text: String): String {
        var s = SongSearch.normalize(text)
        if (!enabled) return s
        s = s.trim()
        if (s.startsWith("the ") && s.length > 4) s = s.substring(4)
        return digits.replace(s) { m ->
            val v = m.value
            if (v.length >= 12) v else v.padStart(12, '0')
        }
    }
}
