package app.torrtv.data

import android.util.LruCache
import app.torrtv.core.Http
import app.torrtv.core.Prefs
import app.torrtv.core.dbl
import app.torrtv.core.enc
import app.torrtv.core.intOr
import app.torrtv.core.str
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import app.torrtv.web.BgWebIndex
import app.torrtv.web.catalog.MovieCatalog
import app.torrtv.web.catalog.MovieEpisodes

class Tmdb(private val prefs: Prefs, diskDir: File? = null) {

    companion object {
        private const val BASE = "https://api.themoviedb.org/3"
        private const val IMG = "https://image.tmdb.org/t/p"

        /** size: w185, w342, w500, w780, w1280, original */
        fun img(path: String?, size: String): String? =
            if (path.isNullOrBlank()) null else "$IMG/$size$path"
        internal fun yearParams(type: MediaType, year: Int?): Map<String, String> =
            if (year == null) emptyMap() else mapOf((if (type == MediaType.MOVIE) "primary_release_year" else "first_air_date_year") to year.toString())
        internal fun parseMedia(o: JSONObject, forced: MediaType?): Media? {
            if (o.optBoolean("adult")) return null
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
            val ga = o.optJSONArray("genre_ids") ?: o.optJSONArray("genres")
            if (ga != null) for (i in 0 until ga.length()) genres.add(ga.optJSONObject(i)?.optInt("id") ?: ga.optInt(i))
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
                originalLanguage = o.str("original_language"),
                popularity = o.dbl("popularity"), releaseDate = date
            )
        }


        internal fun parseActors(j: JSONObject): List<Actor> {
            val a = j.optJSONArray("results") ?: return emptyList()
            return (0 until a.length()).mapNotNull { i ->
                val o = a.getJSONObject(i)
                if (o.intOr("id") <= 0 || o.optBoolean("adult") || o.str("known_for_department") != "Acting") null
                else Actor(o.intOr("id"), o.str("name"), o.str("profile_path").ifBlank { null })
            }.distinctBy { it.id }.take(20)
        }
        internal fun parseCredits(j: JSONObject): List<Media> {
            val a = j.optJSONArray("cast") ?: return emptyList()
            return (0 until a.length()).mapNotNull { parseMedia(a.getJSONObject(it), null) }
                .distinctBy { it.key }.sortedByDescending { it.votes }
        }
    }

    // Разобраните отговори в паметта са малко (дървета от обекти са тежки); зад тях стои дисковият кеш.
    private val cache = LruCache<String, JSONObject>(24)
    private val disk = diskDir?.let { TmdbDiskCache(it) }

    private fun uiLang(): String = if (prefs.metaLang == "en") "en-US" else "bg-BG"

    private suspend fun call(
        path: String,
        params: Map<String, String> = emptyMap(),
        lang: String = uiLang()
    ): JSONObject {
        val key = prefs.tmdbKey
        if (key.isBlank()) throw IllegalStateException("Липсва TMDB ключ. Добави го от Настройки → Настройка от телефона.")
        val sb = StringBuilder("$BASE$path?language=$lang&include_adult=false")
        for ((k, v) in params) sb.append('&').append(k).append('=').append(enc(v))
        // Ключът на кеша е адресът БЕЗ API ключа - той никога не се записва на диска.
        val cacheKey = sb.toString()
        synchronized(cache) { cache.get(cacheKey) }?.let { return it }

        val ttl = TmdbDiskCache.ttlFor(path)
        val store = if (ttl != null) disk else null
        if (store != null) {
            val hit = withContext(Dispatchers.IO) { store.get(cacheKey, ttl!!) }
            val parsed = hit?.let { parse(it) }
            if (parsed != null) {
                synchronized(cache) { cache.put(cacheKey, parsed) }
                return parsed
            }
            if (hit != null) store.remove(cacheKey)
        }

        val headers = HashMap<String, String>()
        // Дългите ключове (JWT) са "Read Access Token", късите - обикновен v3 ключ.
        val url = if (key.length > 40) {
            headers["Authorization"] = "Bearer $key"
            cacheKey
        } else {
            "$cacheKey&api_key=$key"
        }
        val body = try {
            Http.get(url, headers)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Няма връзка (или TMDB не отговаря): по-добре последното запазено, отколкото грешка.
            val old = store?.let { withContext(Dispatchers.IO) { it.getStale(cacheKey) } }?.let { parse(it) }
            if (old != null) {
                synchronized(cache) { cache.put(cacheKey, old) }
                return old
            }
            throw e
        }
        val json = JSONObject(body)
        synchronized(cache) { cache.put(cacheKey, json) }
        if (store != null) withContext(Dispatchers.IO) { store.put(cacheKey, body) }
        return json
    }

    private fun parse(text: String): JSONObject? = try {
        JSONObject(text)
    } catch (_: Exception) {
        null
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

    /** kind: popular | top_rated | now_playing | on_the_air | upcoming */
    suspend fun list(type: MediaType, kind: String, page: Int): Page<Media> =
        parsePage(call("/${type.path}/$kind", mapOf("page" to page.toString())), type)

    suspend fun discover(
        type: MediaType,
        genre: Int?,
        page: Int,
        sort: String,
        minVotes: Int,
        minRating: Double,
        anime: Boolean = false,
        withoutGenre: Int? = null,
        provider: String? = null,
        year: Int? = null
    ): Page<Media> {
        val p = LinkedHashMap<String, String>()
        p["page"] = page.toString()
        p["sort_by"] = sort
        p["vote_count.gte"] = minVotes.toString()
        p.putAll(yearParams(type, year))
        if (minRating > 0) p["vote_average.gte"] = minRating.toString()
        // Аниме = анимация (жанр 16) на японски език - същото правило като при определяне на аниме в детайлите.
        // Запетаята в with_genres означава "и двата жанра".
        val genres = listOfNotNull(if (anime) 16 else null, genre).distinct()
        if (genres.isNotEmpty()) p["with_genres"] = genres.joinToString(",")
        if (anime) p["with_original_language"] = "ja"
        if (withoutGenre != null) p["without_genres"] = withoutGenre.toString()
        // Платформа: само каквото се предлага с абонамент в България (данните на TMDB идват от JustWatch).
        if (provider != null) {
            p["with_watch_providers"] = provider
            p["watch_region"] = "BG"
            p["with_watch_monetization_types"] = "flatrate"
        }
        return parsePage(call("/discover/${type.path}", p), type)
    }

    /** Платформите, които TMDB (JustWatch) познава за България: (номер, име). */
    suspend fun watchProviders(type: MediaType): List<Pair<Int, String>> {
        val arr = call("/watch/providers/${type.path}", mapOf("watch_region" to "BG")).optJSONArray("results") ?: return emptyList()
        val out = ArrayList<Pair<Int, String>>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            out.add(o.optInt("provider_id") to o.optString("provider_name"))
        }
        return out
    }

    suspend fun search(query: String, page: Int, year: Int? = null): Page<Media> = coroutineScope {
        if (year == null && query.isNotBlank()) return@coroutineScope parsePage(call("/search/multi", mapOf("query" to query, "page" to page.toString())), null)
        val pages = MediaType.entries.map { type -> async {
            val params = linkedMapOf("page" to page.toString())
            val path = if (query.isBlank()) "/discover/${type.path}" else "/search/${type.path}"
            if (query.isNotBlank()) params["query"] = query
            params.putAll(yearParams(type, year))
            parsePage(call(path, params), type)
        } }.map { it.await() }
        Page(pages.flatMap { it.items }.distinctBy { it.key }, page, pages.maxOf { it.totalPages })
    }

    suspend fun actors(query: String): List<Actor> = parseActors(call("/search/person", mapOf("query" to query)))
    private val webMatches=object:LinkedHashMap<String,Media>(128,0.75f,true){override fun removeEldestEntry(eldest:MutableMap.MutableEntry<String,Media>?)=size>256}
    suspend fun webMedia(e:MovieCatalog.Entry):Media? {
        val type=if(e.series)MediaType.TV else MediaType.MOVIE
        val key="${e.page}|${e.tmdb}|${e.imdb}|${e.year}|${e.seriesYear}"
        synchronized(webMatches){webMatches[key]}?.let{return it}
        val found=if(e.tmdb.toIntOrNull()?.let{it>0}==true){
            parseMedia(call("/${type.path}/${e.tmdb}"),type)
        }else if(e.imdb.matches(Regex("tt[0-9]{5,}"))){
            val array=call("/find/${e.imdb}",mapOf("external_source" to "imdb_id")).optJSONArray(if(e.series)"tv_results" else "movie_results")
            if(array?.length()==1)parseMedia(array.getJSONObject(0),type) else null
        }else{
            val name=if(e.series)MovieEpisodes.baseTitle(e.original.ifBlank{e.title}) else e.original.ifBlank{e.title}.replace(Regex("\\s*\\((19|20)\\d{2}\\)\\s*$"),"")
            if(name.isBlank())return null
            val matches=parsePage(call("/search/${type.path}",mapOf("query" to name)),type).items
            val exact=matches.filter{candidate->
                val labels=if(e.series)setOf(MovieEpisodes.baseTitle(e.title),MovieEpisodes.baseTitle(e.original)) else
                    setOf(e.title,e.original).map{BgWebIndex.normalize(it.replace(Regex("\\s*\\((19|20)\\d{2}\\)\\s*$"),""))}.toSet()
                listOf(candidate.title,candidate.originalTitle).any{(if(e.series)MovieEpisodes.baseTitle(it) else BgWebIndex.normalize(it)) in labels}
            }
            val sameYear=exact.filter{BgWebIndex.matches(it,e)}
            if(sameYear.size==1)sameYear.single() else if(e.series && exact.size==1 && e.season>1 && e.year>0){
                val candidate=exact.single()
                val seasons=call("/tv/${candidate.id}").optJSONArray("seasons")?:JSONArray()
                val valid=(0 until seasons.length()).any{i->val s=seasons.getJSONObject(i);s.optInt("season_number")==e.season && s.optString("air_date").take(4)==e.year.toString()}
                candidate.takeIf{valid}
            }else null
        }
        val media=found?.takeIf{it.posterPath!=null} ?: return null
        synchronized(webMatches){webMatches[key]=media}
        return media
    }
    suspend fun availableOn(m:Media,provider:String):Boolean {
        val ids=provider.split('|').mapNotNull{it.toIntOrNull()}.toSet()
        val a=call("/${m.type.path}/${m.id}/watch/providers").optJSONObject("results")?.optJSONObject("BG")?.optJSONArray("flatrate")?:return false
        return (0 until a.length()).any{a.getJSONObject(it).optInt("provider_id") in ids}
    }
    suspend fun actorCredits(id: Int): List<Media> = parseCredits(call("/person/$id/combined_credits"))

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
