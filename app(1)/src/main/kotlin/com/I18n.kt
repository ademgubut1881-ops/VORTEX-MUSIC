package io.github.vortexmusic.player

import android.content.Context
import java.util.Locale

// Uygulama dili: 0 = Sistem, 1 = Türkçe, 2 = English
object I18n {

    const val SYSTEM = 0
    const val TR = 1
    const val EN = 2

    var choice = SYSTEM
        private set

    var english = false
        private set

    fun load(ctx: Context) {
        val sp = ctx.getSharedPreferences("vortex_lang", Context.MODE_PRIVATE)
        choice = sp.getInt("choice", SYSTEM).coerceIn(0, 2)
        resolve()
    }

    fun save(ctx: Context, value: Int) {
        choice = value.coerceIn(0, 2)
        ctx.getSharedPreferences("vortex_lang", Context.MODE_PRIVATE).edit()
            .putInt("choice", choice)
            .commit()
        resolve()
    }

    private fun resolve() {
        english = when (choice) {
            TR -> false
            EN -> true
            else -> Locale.getDefault().language != "tr"
        }
    }

    fun locale(): Locale = if (english) Locale.ENGLISH else Locale.forLanguageTag("tr")

    fun text(tr: String, en: String): String = if (english) en else tr
}

// Kısa yazım: tx("Beğen", "Like")  ->  dile göre doğru yazıyı verir
fun tx(tr: String, en: String): String = I18n.text(tr, en)
