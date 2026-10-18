package app.torrtv.state

import android.content.Context
import androidx.compose.runtime.mutableStateListOf
import app.torrtv.data.Media
import app.torrtv.data.MediaType
import app.torrtv.data.torrents.TorrentResult
import app.torrtv.torrent.StreamController
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/**
 * "Любими" и "Продължи гледане". Пази се на самия телевизор (в приложението), без акаунт.
 * Позицията се записва от вградения плеър; външните плеъри не връщат къде си спрял.
 */
object Library {

    class Progress(
        val media: Media,
        val season: Int,
        val episode: Int,
        val epName: String,
        val posMs: Long,
        val durMs: Long,
        val updated: Long,
        val tTitle: String,
        val magnet: String,
        val hash: String,
        val size: Long,
        val quality: String,
        val source: String,
        val tags: List<String>
    ) {
        val fraction: Float get() = if (durMs > 0) (posMs.toFloat() / durMs).coerceIn(0f, 1f) else 0f

        /** Торентът, от който е гледано - за да се продължи от същото място. Сийдърите не се знаят (-1). */
        fun torrent(): TorrentResult = TorrentResult(
            title = tTitle, magnet = magnet, hash = hash, seeders = -1, leechers = 0, size = size,
            source = source, quality = quality, tags = tags, pack = false
        )

        fun label(): String {
            val ep = if (media.type == MediaType.TV) "С$season Е$episode · " else ""
            return ep + fmt(posMs) + if (durMs > 0) " от " + fmt(durMs) else ""
        }
    }

    val favorites = mutableStateListOf<Media>()
    val progress = mutableStateListOf<Progress>()

    private const val MAX_PROGRESS = 5
    private const val MAX_FAV = 300

    private fun sp() = Graph.app.getSharedPreferences("library", Context.MODE_PRIVATE)

    init {
        try {
            val f = JSONArray(sp().getString("fav", "[]"))
            for (i in 0 until f.length()) mediaFrom(f.getJSONObject(i))?.let { favorites.add(it) }
        } catch (_: Exception) {
        }
        try {
            val p = JSONArray(sp().getString("prog", "[]"))
            for (i in 0 until p.length()) progressFrom(p.getJSONObject(i))?.let { progress.add(it) }
            // По-старите записи над лимита (най-новите са първи) се отрязват.
            while (progress.size > MAX_PROGRESS) progress.removeAt(progress.size - 1)
        } catch (_: Exception) {
        }
    }

    // ---------- Любими ----------

    fun isFavorite(key: String): Boolean = favorites.any { it.key == key }

    fun toggleFavorite(m: Media): Boolean {
        val i = favorites.indexOfFirst { it.key == m.key }
        val now = i < 0
        if (now) {
            favorites.add(0, m)
            while (favorites.size > MAX_FAV) favorites.removeAt(favorites.size - 1)
        } else {
            favorites.removeAt(i)
        }
        saveFav()
        return now
    }

    // ---------- Продължи гледане ----------

    fun progressOf(key: String): Progress? = progress.firstOrNull { it.media.key == key }

    /** Вика се от вградения плеър на всеки няколко секунди и при затваряне. */
    fun saveProgress(req: StreamController.Req, posMs: Long, durMs: Long) {
        val m = req.media ?: return
        val t = req.torrent ?: return
        if (durMs > 0 && posMs > durMs * 0.93) {
            finish(m.key) // изгледан до края - вече не е за продължаване
            return
        }
        if (posMs < 45_000) return // първите секунди не се броят за "започнато"
        val p = Progress(
            media = m, season = req.season ?: 0, episode = req.episode ?: 0,
            epName = req.subtitle, posMs = posMs, durMs = durMs, updated = System.currentTimeMillis(),
            tTitle = t.title, magnet = t.magnet, hash = t.hash, size = t.size, quality = t.quality,
            source = t.source, tags = t.tags
        )
        val i = progress.indexOfFirst { it.media.key == m.key }
        if (i >= 0) progress.removeAt(i)
        progress.add(0, p)
        while (progress.size > MAX_PROGRESS) progress.removeAt(progress.size - 1)
        saveProg()
    }

    fun clearProgress() {
        progress.clear()
        saveProg()
    }

    fun finish(key: String) {
        val i = progress.indexOfFirst { it.media.key == key }
        if (i >= 0) {
            progress.removeAt(i)
            saveProg()
        }
    }

    fun fmt(ms: Long): String = fmtTime(ms)

    // ---------- Запис и четене ----------

    private fun saveFav() {
        try {
            val a = JSONArray()
            favorites.forEach { a.put(mediaTo(it)) }
            sp().edit().putString("fav", a.toString()).apply()
        } catch (_: Exception) {
        }
    }

    private fun saveProg() {
        try {
            val a = JSONArray()
            progress.forEach { a.put(progressTo(it)) }
            sp().edit().putString("prog", a.toString()).apply()
        } catch (_: Exception) {
        }
    }

    internal fun mediaTo(m: Media): JSONObject = JSONObject().apply {
        put("id", m.id)
        put("type", m.type.path)
        put("title", m.title)
        put("orig", m.originalTitle)
        put("ov", m.overview)
        put("poster", m.posterPath ?: "")
        put("back", m.backdropPath ?: "")
        put("year", m.year)
        put("rating", m.rating)
        put("votes", m.votes)
        put("genres", JSONArray(m.genreIds))
        put("lang", m.originalLanguage)
    }

    internal fun mediaFrom(o: JSONObject): Media? {
        val id = o.optInt("id", 0)
        if (id == 0) return null
        val g = o.optJSONArray("genres")
        return Media(
            id = id,
            type = if (o.optString("type") == "tv") MediaType.TV else MediaType.MOVIE,
            title = o.optString("title"),
            originalTitle = o.optString("orig"),
            overview = o.optString("ov"),
            posterPath = o.optString("poster").ifBlank { null },
            backdropPath = o.optString("back").ifBlank { null },
            year = o.optString("year"),
            rating = o.optDouble("rating", 0.0),
            votes = o.optInt("votes", 0),
            genreIds = List(g?.length() ?: 0) { g!!.optInt(it) },
            originalLanguage = o.optString("lang")
        )
    }

    internal fun progressTo(p: Progress): JSONObject = JSONObject().apply {
        put("m", mediaTo(p.media))
        put("s", p.season)
        put("e", p.episode)
        put("en", p.epName)
        put("pos", p.posMs)
        put("dur", p.durMs)
        put("up", p.updated)
        put("tt", p.tTitle)
        put("mg", p.magnet)
        put("h", p.hash)
        put("sz", p.size)
        put("q", p.quality)
        put("src", p.source)
        put("tags", JSONArray(p.tags))
    }

    internal fun progressFrom(o: JSONObject): Progress? {
        val m = mediaFrom(o.optJSONObject("m") ?: return null) ?: return null
        val magnet = o.optString("mg")
        if (magnet.isBlank()) return null
        val tg = o.optJSONArray("tags")
        return Progress(
            media = m, season = o.optInt("s"), episode = o.optInt("e"), epName = o.optString("en"),
            posMs = o.optLong("pos"), durMs = o.optLong("dur"), updated = o.optLong("up"),
            tTitle = o.optString("tt"), magnet = magnet, hash = o.optString("h"), size = o.optLong("sz"),
            quality = o.optString("q"), source = o.optString("src"),
            tags = List(tg?.length() ?: 0) { tg!!.optString(it) }
        )
    }
}

/** 75000 -> "1:15"; 3725000 -> "1:02:05". */
fun fmtTime(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    val h = s / 3600
    val m = s / 60 % 60
    val sec = s % 60
    return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, sec) else String.format(Locale.US, "%d:%02d", m, sec)
}
