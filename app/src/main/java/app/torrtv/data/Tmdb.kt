package app.torrtv.data

import android.util.LruCache
import app.torrtv.core.Http
import app.torrtv.core.Prefs
import app.torrtv.core.dbl
import app.torrtv.core.enc
import app.torrtv.core.intOr
import app.torrtv.core.str
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import org.json.JSONArray
import org.json.JSONObject

class Tmdb(private val prefs: Prefs) {

    companion object {
        private const val BASE = "https://api.themoviedb.org/3"
        private const val IMG = "https://image.tmdb.org/t/p"

        /** size: w185, w342, w500, w780, w1280, original */
        fun img(path: String?, size: String): String? =
            if (path.isNullOrBlank()) null else "$IMG/$size$path"
    }

    private val cache = LruCache<String, JSONObject>(80)

    private fun uiLang(): String = if (prefs.metaLang == "en") "en-US" else "bg-BG"

    private suspend fun call(
        path: String,
        params: Map<String, String> = emptyMap(),
        lang: String = uiLang()
    ): JSONObject {
        val key = prefs.tmdbKey
        if (key.isBlank()) throw IllegalStateException("Липсва TMDB ключ. Добави го от Настройки → Настройка от телефон.")
        val sb = StringBuilder("$BASE$path?language=$lang&include_adult=false")
        val headers = HashMap<String, String>()
        // Дългите ключове (JWT) са "Read Access Token", късите - обикновен v3 ключ.
        if (key.length > 40) headers["Authorization"] = "Bearer $key"
        else sb.append("&api_key=").append(key)
        for ((k, v) in params) sb.append('&').append(k).append('=').append(enc(v))
        val url = sb.toString()
        synchronized(cache) { cache.get(url) }?.let { return it }
        val json = JSONObject(Http.get(url, headers))
        synchronized(cache) { cache.put(url, json) }
        return json
    }

    private fun parseMedia(o: JSONObject, forced: MediaType?): Media? {
        val type = forced ?: when (o.str("media_type")) {
            "movie" -> MediaType.MOVIE
            "tv" -> MediaType.TV
            else -> return null
        }
        val movie = type == MediaType.MOVIE
        val id = o.intOr("id", -1)
        if (id < 0) return null
        val title = o.str(if (movie) "title" else "name")
        val orig = o.str(if (movie) "original_title" else "original_name")
        val date = o.str(if (movie) "release_date" else "first_air_date")
        val genres = ArrayList<Int>()
        val ga = o.optJSONArray("genre_ids")
        if (ga != null) for (i in 0 until ga.length()) genres.add(ga.optInt(i))
        return Media(
            id = id,
            type = type,
            title = title.ifBlank { orig },
            originalTitle = orig.ifBlank { title },
            overview = o.str("overview"),
            posterPath = o.str("poster_path").ifBlank { null },
            backdropPath = o.str("backdrop_path").ifBlank { null },
            year = date.take(4),
            rating = o.dbl("vote_average"),
            votes = o.intOr("vote_count"),
            genreIds = genres,
            originalLanguage = o.str("original_language")
        )
    }

    private fun parsePage(j: JSONObject, forced: MediaType?): Page<Media> {
        val arr = j.optJSONArray("results") ?: JSONArray()
        val list = ArrayList<Media>()
        for (i in 0 until arr.length()) {
            val m = parseMedia(arr.getJSONObject(i), forced) ?: continue
            if (m.posterPath != null) list.add(m)
        }
        return Page(list, j.intOr("page", 1), minOf(j.intOr("total_pages", 1), 500))
    }

    suspend fun trending(page: Int): Page<Media> =
        parsePage(call("/trending/all/week", mapOf("page" to page.toString())), null)

    /** kind: popular | top_rated | now_playing | on_the_air */
    suspend fun list(type: MediaType, kind: String, page: Int): Page<Media> =
        parsePage(call("/${type.path}/$kind", mapOf("page" to page.toString())), type)

    suspend fun discover(
        type: MediaType,
        genre: Int?,
        page: Int,
        sort: String,
        minVotes: Int,
        minRating: Double
    ): Page<Media> {
        val p = LinkedHashMap<String, String>()
        p["page"] = page.toString()
        p["sort_by"] = sort
        p["vote_count.gte"] = minVotes.toString()
        if (minRating > 0) p["vote_average.gte"] = minRating.toString()
        if (genre != null) p["with_genres"] = genre.toString()
        return parsePage(call("/discover/${type.path}", p), type)
    }

    suspend fun search(query: String, page: Int): Page<Media> =
        parsePage(call("/search/multi", mapOf("query" to query, "page" to page.toString())), null)

    suspend fun recommendations(type: MediaType, id: Int, page: Int): Page<Media> {
        val r = parsePage(call("/${type.path}/$id/recommendations", mapOf("page" to page.toString())), type)
        if (r.items.isNotEmpty() || page > 1) return r
        return parsePage(call("/${type.path}/$id/similar", mapOf("page" to page.toString())), type)
    }

    suspend fun episodes(tvId: Int, season: Int): List<Episode> {
        val j = call("/tv/$tvId/season/$season")
        val arr = j.optJSONArray("episodes") ?: return emptyList()
        val out = ArrayList<Episode>()
        for (i in 0 until arr.length()) {
            val e = arr.getJSONObject(i)
            out.add(
                Episode(
                    number = e.intOr("episode_number"),
                    name = e.str("name"),
                    overview = e.str("overview"),
                    stillPath = e.str("still_path").ifBlank { null },
                    airDate = e.str("air_date"),
                    runtime = e.intOr("runtime")
                )
            )
        }
        return out
    }

    suspend fun detail(type: MediaType, id: Int): Detail = coroutineScope {
        val ext = mapOf("append_to_response" to "external_ids,credits")
        val main = async { call("/${type.path}/$id", ext) }
        val en = async<JSONObject?> {
            if (uiLang() == "en-US") null
            else try {
                call("/${type.path}/$id", mapOf("append_to_response" to "external_ids"), "en-US")
            } catch (e: Exception) {
                null
            }
        }
        val m = main.await()
        val e = en.await()
        val movie = type == MediaType.MOVIE

        val genres = ArrayList<Genre>()
        val ga = m.optJSONArray("genres")
        if (ga != null) for (i in 0 until ga.length()) {
            val g = ga.getJSONObject(i)
            genres.add(Genre(g.intOr("id"), g.str("name")))
        }

        val cast = ArrayList<String>()
        val ca = m.optJSONObject("credits")?.optJSONArray("cast")
        if (ca != null) for (i in 0 until minOf(ca.length(), 8)) cast.add(ca.getJSONObject(i).str("name"))

        var imdb = m.str("imdb_id")
        if (imdb.isBlank()) imdb = m.optJSONObject("external_ids")?.str("imdb_id") ?: ""

        val seasons = ArrayList<SeasonInfo>()
        val sa = m.optJSONArray("seasons")
        if (sa != null) for (i in 0 until sa.length()) {
            val s = sa.getJSONObject(i)
            val n = s.intOr("season_number")
            if (n > 0) seasons.add(SeasonInfo(n, s.str("name").ifBlank { "Сезон $n" }, s.intOr("episode_count")))
        }

        val runtime = if (movie) m.intOr("runtime")
        else m.optJSONArray("episode_run_time")?.optInt(0, 0) ?: 0

        val base = parseMedia(m, type)!!
        val overview = base.overview.ifBlank { e?.str("overview") ?: "" }
        val media = base.copy(
            overview = overview,
            genreIds = genres.map { it.id },
            posterPath = base.posterPath ?: e?.str("poster_path")?.ifBlank { null },
            backdropPath = base.backdropPath ?: e?.str("backdrop_path")?.ifBlank { null }
        )
        val english = e?.str(if (movie) "title" else "name")?.ifBlank { null }
            ?: if (uiLang() == "en-US") media.title else media.originalTitle

        Detail(
            media = media,
            englishTitle = english,
            tagline = m.str("tagline"),
            runtime = runtime,
            genres = genres,
            imdbId = imdb.ifBlank { null },
            cast = cast,
            seasons = seasons,
            status = m.str("status"),
            isAnime = genres.any { it.id == 16 } && media.originalLanguage == "ja"
        )
    }
}
