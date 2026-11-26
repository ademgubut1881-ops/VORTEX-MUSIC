package io.github.vortexmusic.player

import android.content.Context
import java.text.Collator
import java.text.Normalizer
import java.util.Locale

// Sanatçı gruplama: "Atakan Ilgazdağ, X" gibi çoklu sanatçı yazılarını ayırır,
// büyük/küçük harf ve noktalı-noktasız harf farkını yok sayar ve alfabetik sıralar.
object ArtistGroups {

    // Bölünmemesi gereken bilinen grup adları
    private val keep = listOf(
        "Tyler, The Creator",
        "Earth, Wind & Fire",
        "Crosby, Stills, Nash & Young",
        "Crosby, Stills & Nash",
        "Simon & Garfunkel",
        "Hall & Oates"
    )

    // Ayırıcı seçenekleri (bit): 1 virgül, 2 noktalı virgül, 4 &, 8 /, 16 feat./ft.
    const val SEP_ALL = 31
    private var mask = SEP_ALL
    private var splitter: Regex? = build(SEP_ALL)

    private fun build(m: Int): Regex? {
        val p = ArrayList<String>()
        if (m and 1 != 0) p.add(",")
        if (m and 2 != 0) p.add(";")
        if (m and 4 != 0) p.add("\\s&\\s")
        if (m and 8 != 0) p.add("\\s/\\s")
        if (m and 16 != 0) p.add("\\sfeat\\.?\\s|\\sft\\.?\\s|\\sfeaturing\\s")
        if (p.isEmpty()) return null
        return Regex("\\s*(?:" + p.joinToString("|") + ")\\s*", RegexOption.IGNORE_CASE)
    }

    fun mask(): Int = mask

    fun load(ctx: Context) {
        val m = ctx.getSharedPreferences("vortex_artist", Context.MODE_PRIVATE).getInt("sep", SEP_ALL)
        mask = m
        splitter = build(m)
    }

    fun save(ctx: Context, m: Int) {
        ctx.getSharedPreferences("vortex_artist", Context.MODE_PRIVATE).edit().putInt("sep", m).apply()
        mask = m
        splitter = build(m)
    }

    fun names(raw: String): List<String> {
        var s = raw
        val holders = ArrayList<Pair<String, String>>()
        for ((i, k) in keep.withIndex()) {
            if (s.contains(k, ignoreCase = true)) {
                val ph = "\u0001$i\u0001"
                s = s.replace(k, ph, ignoreCase = true)
                holders.add(ph to k)
            }
        }
        val out = ArrayList<String>()
        val sp = splitter
        val parts = if (sp == null) listOf(s) else s.split(sp)
        for (part in parts) {
            var p = part.trim()
            for ((ph, k) in holders) p = p.replace(ph, k)
            if (p.isNotEmpty() && out.none { key(it) == key(p) }) out.add(p)
        }
        if (out.isEmpty()) out.add(raw.trim())
        return out
    }

    // Harf büyüklüğü, aksan ve "İ/ı" farkını yok sayan anahtar
    fun key(name: String): String {
        val d = Normalizer.normalize(name.replace('ı', 'i').replace('İ', 'I'), Normalizer.Form.NFD)
        return d.replace(Regex("\\p{Mn}+"), "")
            .lowercase(Locale.ROOT)
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    // Her şarkı, içindeki her sanatçının grubuna girer. Alfabetik döner.
    fun <T> groups(items: List<T>, artistOf: (T) -> String): List<Pair<String, List<T>>> {
        val map = LinkedHashMap<String, MutableList<T>>()
        val spellings = HashMap<String, HashMap<String, Int>>()
        for (it in items) {
            for (n in names(artistOf(it))) {
                val k = key(n)
                map.getOrPut(k) { ArrayList() }.add(it)
                val sp = spellings.getOrPut(k) { HashMap() }
                sp[n] = (sp[n] ?: 0) + 1
            }
        }
        val list = map.map { (k, v) ->
            val best = spellings[k]!!.entries.maxByOrNull { it.value }!!.key
            best to v as List<T>
        }
        val col = Collator.getInstance(Locale("tr", "TR"))
        col.strength = Collator.PRIMARY
        return if (SmartSort.enabled) {
            list.sortedWith { a, b ->
                val c = SmartSort.key(a.first).compareTo(SmartSort.key(b.first))
                if (c != 0) c else col.compare(a.first, b.first)
            }
        } else {
            list.sortedWith { a, b -> col.compare(a.first, b.first) }
        }
    }

    fun <T> has(item: T, artistOf: (T) -> String, groupName: String): Boolean {
        val k = key(groupName)
        return names(artistOf(item)).any { key(it) == k }
    }

    fun count(artists: List<String>): Int {
        val s = HashSet<String>()
        for (a in artists) for (n in names(a)) s.add(key(n))
        return s.size
    }
}
