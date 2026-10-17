package app.torrtv.data.torrents

import app.torrtv.core.pad2
import app.torrtv.core.stripAccents
import app.torrtv.data.MediaType
import java.util.Locale
import kotlin.math.abs

data class SearchQuery(
    val type: MediaType,
    /** Основно (по възможност английско) заглавие, по което се търси. */
    val title: String,
    val altTitles: List<String>,
    val year: String,
    val imdbId: String?,
    val season: Int?,
    val episode: Int?,
    val anime: Boolean
) {
    val cacheKey: String get() = "${type.path}|$title|$year|$imdbId|$season|$episode"

    /** Текстови заявки за сайтове без търсене по IMDb. */
    fun textQueries(): List<String> {
        val s = season
        val e = episode
        return when {
            type == MediaType.MOVIE -> listOf(if (year.isNotBlank()) "$title $year" else title)
            s != null && e != null -> listOf("$title S${pad2(s)}E${pad2(e)}", "$title S${pad2(s)}")
            s != null -> listOf("$title S${pad2(s)}")
            else -> listOf(title)
        }
    }
}

data class TorrentResult(
    val title: String,
    val magnet: String,
    val hash: String,
    val seeders: Int,
    val leechers: Int,
    val size: Long,
    val source: String,
    val quality: String,
    val tags: List<String>,
    val pack: Boolean
) {
    val isCam: Boolean get() = tags.contains("CAM")
}

interface TorrentSource {
    val id: String
    val name: String
    fun supports(q: SearchQuery): Boolean
    suspend fun search(q: SearchQuery): List<TorrentResult>
}

object Quality {
    fun of(title: String): String {
        val t = title.lowercase(Locale.US)
        return when {
            Regex("2160p|\\b4k\\b|\\buhd\\b").containsMatchIn(t) -> "4K"
            Regex("1080[pi]").containsMatchIn(t) -> "1080p"
            Regex("720p").containsMatchIn(t) -> "720p"
            Regex("480p|\\bdvdrip\\b|\\bxvid\\b").containsMatchIn(t) -> "480p"
            else -> "SD"
        }
    }

    fun tags(title: String): List<String> {
        val t = title.lowercase(Locale.US)
        val out = ArrayList<String>()
        if (Regex("\\b(x265|hevc|h\\.?265)\\b").containsMatchIn(t)) out.add("HEVC")
        if (Regex("\\b(hdr10\\+?|hdr)\\b").containsMatchIn(t)) out.add("HDR")
        if (Regex("\\b(dv|dolby\\.?vision)\\b").containsMatchIn(t)) out.add("DV")
        if (Regex("\\bremux\\b").containsMatchIn(t)) out.add("REMUX")
        if (Regex("\\b(blu-?ray|bdrip|brrip)\\b").containsMatchIn(t)) out.add("BluRay")
        if (Regex("\\b(web-?dl|webrip|web)\\b").containsMatchIn(t)) out.add("WEB")
        if (Regex("\\bhdtv\\b").containsMatchIn(t)) out.add("HDTV")
        if (Regex("\\b(atmos)\\b").containsMatchIn(t)) out.add("Atmos")
        if (Regex("\\b(dts|dts-?hd)\\b").containsMatchIn(t)) out.add("DTS")
        if (Regex("\\b(cam|hdcam|camrip|telesync|hdts|ts|telecine|tc)\\b").containsMatchIn(t)) out.add("CAM")
        return out
    }

    fun make(
        q: SearchQuery,
        title: String,
        hash: String,
        magnet: String,
        seeders: Int,
        leechers: Int,
        size: Long,
        source: String
    ): TorrentResult {
        val pack = q.type == MediaType.TV && q.episode != null && Relevance.tvMatch(q, title) == 2
        return TorrentResult(
            title = title,
            magnet = magnet,
            hash = hash.lowercase(Locale.US),
            seeders = seeders,
            leechers = leechers,
            size = size,
            source = source,
            quality = of(title),
            tags = tags(title),
            pack = pack
        )
    }
}

object Relevance {
    private val yearRe = Regex("\\b(19|20)\\d{2}\\b")

    fun norm(s: String): String =
        stripAccents(s).lowercase(Locale.US)
            .replace("&", " and ")
            .replace("'", "")
            .replace("’", "")
            .replace(Regex("[^a-z0-9]+"), " ")
            .trim()

    private fun names(q: SearchQuery): List<String> =
        (listOf(q.title) + q.altTitles).map { norm(it) }.filter { it.length >= 2 }.distinct()

    /** 0 - не е това, 1 - точният епизод, 2 - пакет за целия сезон. */
    fun tvMatch(q: SearchQuery, title: String): Int {
        val s = q.season ?: return 1
        val e = q.episode
        val t = norm(title)
        if (e != null) {
            val epRe = Regex("\\bs0*$s ?e0*$e\\b|\\b${s}x0*$e\\b")
            if (epRe.containsMatchIn(t)) return 1
            if (Regex("\\bs0*\\d+ ?e0*\\d+\\b|\\b\\d+x\\d+\\b").containsMatchIn(t)) return 0
        }
        val seasonRe = Regex("\\bs0*$s\\b|\\bseason ?0*$s\\b|\\bcomplete\\b")
        return if (seasonRe.containsMatchIn(t)) 2 else 0
    }

    /** Дали името на торента отговаря на търсената продукция. */
    fun accept(q: SearchQuery, torrentTitle: String): Boolean {
        val t = " " + norm(torrentTitle) + " "
        val ns = names(q)
        if (ns.none { t.contains(" $it ") }) return false
        if (q.type == MediaType.MOVIE) {
            val y = q.year.toIntOrNull()
            val years = yearRe.findAll(t).map { it.value.toInt() }.toList()
            if (y != null && years.isNotEmpty() && years.none { abs(it - y) <= 1 }) {
                val titleHasYear = ns.any { yearRe.containsMatchIn(it) }
                if (!titleHasYear) return false
            }
            return true
        }
        return tvMatch(q, torrentTitle) > 0
    }
}
