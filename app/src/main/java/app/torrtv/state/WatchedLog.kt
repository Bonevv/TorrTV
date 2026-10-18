package app.torrtv.state

/**
 * История на изгледаното: кои филми и епизоди са гледани до края. Чиста логика без Android - ключът е
 * "movie-123" за филм и "tv-456:2:5" за сезон 2, епизод 5. Пази се най-много [MAX] записа (най-старите отпадат).
 */
object WatchedLog {
    const val MAX = 600

    fun epKey(mediaKey: String, season: Int, episode: Int): String = "$mediaKey:$season:$episode"

    /** Кои ключове да се махнат, за да останат най-много [max]; най-старите първи. */
    fun overflow(map: Map<String, Long>, max: Int = MAX): List<String> =
        if (map.size <= max) emptyList()
        else map.entries.sortedBy { it.value }.take(map.size - max).map { it.key }

    /** Един запис на ред: ключ, табулация, време. */
    fun encode(map: Map<String, Long>): String =
        map.entries.joinToString("\n") { it.key + "\t" + it.value }

    fun decode(text: String): Map<String, Long> {
        val out = LinkedHashMap<String, Long>()
        for (line in text.lines()) {
            val i = line.indexOf('\t')
            if (i <= 0) continue
            val t = line.substring(i + 1).trim().toLongOrNull() ?: continue
            out[line.substring(0, i)] = t
        }
        return out
    }
}
