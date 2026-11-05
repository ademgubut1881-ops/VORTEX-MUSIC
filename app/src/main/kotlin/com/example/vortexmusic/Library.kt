package io.github.vortexmusic.player

import android.content.Context

object SmartLists {

    const val MOST_PLAYED = 0
    const val RECENTLY_ADDED = 1
    const val NEVER_PLAYED = 2
    const val FORGOTTEN = 3
    const val RECENT = 4

    val titles = arrayOf(
        "En çok dinlenen",
        "Son eklenen",
        "Hiç dinlenmeyen",
        "Unutulanlar",
        "Son çalınan"
    )

    val hints = arrayOf(
        "En az 20 sn dinlediğin şarkılar, çalma sayısına göre.",
        "Telefona en son eklenenler.",
        "Henüz hiç çalmadığın şarkılar.",
        "Bir zamanlar dinleyip 14 günden uzun süredir açmadıkların. Kayıt biriktikçe dolar.",
        "Geçmişin: en son çaldıkların."
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