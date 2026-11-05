package io.github.vortexmusic.player

object SongSearch {

    const val SCOPE_ALL = 0
    const val SCOPE_TITLE = 1
    const val SCOPE_ARTIST = 2
    const val SCOPE_ALBUM = 3

    const val SORT_BEST = 0
    const val SORT_AZ = 1
    const val SORT_NEWEST = 2

    val scopeLabels = arrayOf("Hepsi", "Şarkı", "Sanatçı", "Albüm")
    val sortLabels = arrayOf("En uygun", "A-Z", "Yeni")

    // Türkçe harfleri ve büyük/küçük harfi yok sayar
    fun normalize(text: String): String {
        val sb = StringBuilder(text.length)
        for (ch in text) {
            val c = when (ch) {
                'ç', 'Ç' -> 'c'
                'ğ', 'Ğ' -> 'g'
                'ı', 'İ', 'I', 'î', 'Î' -> 'i'
                'ö', 'Ö' -> 'o'
                'ş', 'Ş' -> 's'
                'ü', 'Ü', 'û', 'Û' -> 'u'
                'â', 'Â' -> 'a'
                else -> ch.lowercaseChar()
            }
            sb.append(c)
        }
        return sb.toString()
    }

    private fun score(text: String, token: String, weight: Int): Int {
        val idx = text.indexOf(token)
        if (idx < 0) return 0
        return when {
            idx == 0 -> weight * 3
            text[idx - 1] == ' ' -> weight * 2
            else -> weight
        }
    }

    private class Hit(val song: Song, val score: Int, val key: String)

    fun search(songs: List<Song>, query: String, scope: Int, sort: Int): List<Song> {
        val tokens = normalize(query).split(' ').filter { it.isNotEmpty() }
        if (tokens.isEmpty()) return emptyList()
        val hits = ArrayList<Hit>()
        for (s in songs) {
            val title = normalize(s.title)
            val artist = normalize(s.artist)
            val album = normalize(s.album)
            var total = 0
            var ok = true
            for (tok in tokens) {
                var best = 0
                if (scope == SCOPE_ALL || scope == SCOPE_TITLE) best = maxOf(best, score(title, tok, 3))
                if (scope == SCOPE_ALL || scope == SCOPE_ARTIST) best = maxOf(best, score(artist, tok, 2))
                if (scope == SCOPE_ALL || scope == SCOPE_ALBUM) best = maxOf(best, score(album, tok, 1))
                if (best == 0) {
                    ok = false
                    break
                }
                total += best
            }
            if (ok) hits.add(Hit(s, total, title))
        }
        val sorted = when (sort) {
            SORT_AZ -> hits.sortedBy { it.key }
            SORT_NEWEST -> hits.sortedByDescending { it.song.added }
            else -> hits.sortedWith(compareByDescending<Hit> { it.score }.thenBy { it.key })
        }
        return sorted.map { it.song }
    }
}