package io.github.vortexmusic.player

import android.content.Context

object SmartLists {

    const val MOST_PLAYED = 0
    const val RECENTLY_ADDED = 1
    const val NEVER_PLAYED = 2
    const val FORGOTTEN = 3
    const val RECENT = 4

    val titles: Array<String>
        get() = arrayOf(
            tx("En çok dinlenen", "Most played"),
            tx("Son eklenen", "Recently added"),
            tx("Hiç dinlenmeyen", "Never played"),
            tx("Unutulanlar", "Forgotten"),
            tx("Son çalınan", "Recently played")
        )

    val hints: Array<String>
        get() = arrayOf(
            tx(
                "En az 20 sn dinlediğin şarkılar, çalma sayısına göre.",
                "Songs you listened to for at least 20 s, by play count."
            ),
            tx("Telefona en son eklenenler.", "The newest additions to your phone."),
            tx("Henüz hiç çalmadığın şarkılar.", "Songs you have not played yet."),
            tx(
                "Bir zamanlar dinleyip 14 günden uzun süredir açmadıkların. Kayıt biriktikçe dolar.",
                "Songs you once played but have not opened for over 14 days. Fills up as history builds."
            ),
            tx("Geçmişin: en son çaldıkların.", "Your history: what you played most recently.")
        )

    private const val DAY_MS = 24L * 3600L * 1000L
    private const val LIMIT = 100

    fun build(context: Context, kind: Int, ids: List<Long>, added: Map<Long, Long>): List<Long> {
        val prefs = context.getSharedPreferences("vortex_stats", Context.MODE_PRIVATE)
        val counts = HashMap<Long, Int>()
        val last = HashMap<Long, Long>()
        for ((k, v) in prefs.all) {
            if (k.startsWith("c_")) {
                val id = k.substring(2).toLongOrNull() ?: continue
                val n = v as? Int ?: continue
                counts[id] = n
            } else if (k.startsWith("l_")) {
                val id = k.substring(2).toLongOrNull() ?: continue
                val t = v as? Long ?: continue
                last[id] = t
            }
        }
        val now = System.currentTimeMillis()
        return when (kind) {
            MOST_PLAYED -> ids
                .filter { (counts[it] ?: 0) > 0 }
                .sortedByDescending { counts[it] ?: 0 }
                .take(LIMIT)
            RECENTLY_ADDED -> ids
                .sortedByDescending { added[it] ?: 0L }
                .take(LIMIT)
            NEVER_PLAYED -> ids
                .filter { (counts[it] ?: 0) == 0 && !last.containsKey(it) }
            FORGOTTEN -> ids
                .filter { id ->
                    val l = last[id]
                    l != null && (counts[id] ?: 0) > 0 && now - l > 14 * DAY_MS
                }
                .sortedByDescending { counts[it] ?: 0 }
            RECENT -> ids
                .filter { last.containsKey(it) }
                .sortedByDescending { last[it] ?: 0L }
                .take(LIMIT)
            else -> emptyList()
        }
    }
}
