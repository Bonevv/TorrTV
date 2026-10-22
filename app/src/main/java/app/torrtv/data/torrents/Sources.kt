package app.torrtv.data.torrents

import app.torrtv.core.Http
import app.torrtv.core.Magnets
import app.torrtv.core.Prefs
import app.torrtv.core.baseUrl
import app.torrtv.core.cachedRegex
import app.torrtv.core.enc
import app.torrtv.core.intOr
import app.torrtv.core.long
import app.torrtv.core.str
import app.torrtv.data.MediaType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.parser.Parser
import java.util.Locale

private fun numeric(imdb: String?): String? = imdb?.removePrefix("tt")?.takeIf { it.isNotBlank() }

private val SIZE_RE = Regex("([0-9.,]+)\\s*([KMGT]?i?B)", RegexOption.IGNORE_CASE)
private val SPACES_RE = Regex("\\s+")
private val BATCH_RE = Regex("\\b(batch|complete|season)\\b")
private val NOISE_RE = Regex("\\b\\d{3,4}p\\b|\\bx26[45]\\b|\\b(19|20)\\d{2}\\b|\\b\\d{3,4}x\\d{3,4}\\b")

internal fun parseSize(s: String): Long {
    val m = SIZE_RE.find(s) ?: return 0L
    val n = m.groupValues[1].replace(",", "").toDoubleOrNull() ?: return 0L
    val mult = when (m.groupValues[2].uppercase(Locale.US).take(1)) {
        "K" -> 1024.0
        "M" -> 1024.0 * 1024
        "G" -> 1024.0 * 1024 * 1024
        "T" -> 1024.0 * 1024 * 1024 * 1024
        else -> 1.0
    }
    return (n * mult).toLong()
}

// ---------------------------------------------------------------- The Pirate Bay (apibay.org, JSON)

class Apibay(private val prefs: Prefs) : TorrentSource {
    override val id = "apibay"
    override val name = "The Pirate Bay"
    override fun supports(q: SearchQuery) = true

    override suspend fun search(q: SearchQuery): List<TorrentResult> = coroutineScope {
        val dom = baseUrl(prefs.domApibay, "https://apibay.org")
        q.textQueries().map { text -> async { fetch(dom, text, q) } }.awaitAll().flatten()
    }

    private suspend fun fetch(dom: String, text: String, q: SearchQuery): List<TorrentResult> {
        val arr = JSONArray(Http.get("$dom/q.php?q=${enc(text)}&cat=200"))
        val out = ArrayList<TorrentResult>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val hash = o.str("info_hash")
            if (o.str("id") == "0" || hash.isBlank() || hash.all { it == '0' }) continue
            val title = o.str("name")
            if (!Relevance.accept(q, title)) continue
            out.add(
                Quality.make(
                    q, title, hash, Magnets.build(hash, title),
                    o.intOr("seeders"), o.intOr("leechers"), o.long("size"), name
                )
            )
        }
        return out
    }
}

// ---------------------------------------------------------------- YTS (само филми, JSON API)

class Yts(private val prefs: Prefs) : TorrentSource {
    override val id = "yts"
    override val name = "YTS"
    override fun supports(q: SearchQuery) = q.type == MediaType.MOVIE

    override suspend fun search(q: SearchQuery): List<TorrentResult> {
        val term = q.imdbId ?: q.title
        val doms = (listOf(prefs.domYts).filter { it.isNotBlank() } +
                listOf("https://yts.mx", "https://yts.lt", "https://yts.am"))
            .map { baseUrl(it, "https://yts.mx") }.distinct()
        var last: Exception? = null
        for (d in doms) {
            try {
                val url = "$d/api/v2/list_movies.json?query_term=${enc(term)}&limit=20&sort_by=seeds&order_by=desc"
                val data = JSONObject(Http.get(url)).optJSONObject("data") ?: return emptyList()
                val movies = data.optJSONArray("movies") ?: return emptyList()
                val out = ArrayList<TorrentResult>()
                for (i in 0 until movies.length()) {
                    val mv = movies.getJSONObject(i)
                    val titleLong = mv.str("title_long").ifBlank { mv.str("title") }
                    if (q.imdbId == null && !Relevance.accept(q, titleLong)) continue
                    val ts = mv.optJSONArray("torrents") ?: continue
                    for (j in 0 until ts.length()) {
                        val t = ts.getJSONObject(j)
                        val hash = t.str("hash")
                        if (hash.isBlank()) continue
                        val quality = t.str("quality")
                        val title = "$titleLong $quality ${t.str("type").uppercase(Locale.US)} ${t.str("video_codec")} YTS".replace(SPACES_RE, " ")
                        out.add(
                            Quality.make(
                                q, title, hash, Magnets.build(hash, title),
                                t.intOr("seeds"), t.intOr("peers"), t.long("size_bytes"), name
                            )
                        )
                    }
                }
                return out
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                last = e
            }
        }
        throw last ?: IllegalStateException("няма достъпен домейн")
    }
}

// ---------------------------------------------------------------- EZTV (само сериали, JSON API по IMDb)

class Eztv(private val prefs: Prefs) : TorrentSource {
    override val id = "eztv"
    override val name = "EZTV"
    override fun supports(q: SearchQuery) = q.type == MediaType.TV && numeric(q.imdbId) != null

    override suspend fun search(q: SearchQuery): List<TorrentResult> {
        val imdb = numeric(q.imdbId) ?: return emptyList()
        val doms = (listOf(prefs.domEztv).filter { it.isNotBlank() } +
                listOf("https://eztvx.to", "https://eztv.re", "https://eztv.wf", "https://eztv.tf", "https://eztv.yt"))
            .map { baseUrl(it, "https://eztvx.to") }.distinct()
        var last: Exception? = null
        for (d in doms) {
            try {
                val first = JSONObject(Http.get("$d/api/get-torrents?imdb_id=$imdb&limit=100&page=1"))
                val total = first.intOr("torrents_count")
                val pages = minOf((total + 99) / 100, 8)
                val all = ArrayList<JSONObject>()
                collect(first, all)
                if (pages > 1) coroutineScope {
                    (2..pages).map { p ->
                        async {
                            try {
                                JSONObject(Http.get("$d/api/get-torrents?imdb_id=$imdb&limit=100&page=$p"))
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                null
                            }
                        }
                    }.awaitAll().forEach { if (it != null) collect(it, all) }
                }
                val out = ArrayList<TorrentResult>()
                for (o in all) {
                    val title = o.str("title").ifBlank { o.str("filename") }
                    val hash = o.str("hash")
                    if (hash.isBlank()) continue
                    if (q.season != null && Relevance.tvMatch(q, title) == 0) continue
                    val magnet = o.str("magnet_url").ifBlank { Magnets.build(hash, title) }
                    out.add(
                        Quality.make(
                            q, title, hash, magnet,
                            o.intOr("seeds"), o.intOr("peers"), o.long("size_bytes"), name
                        )
                    )
                }
                return out
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                last = e
            }
        }
        throw last ?: IllegalStateException("няма достъпен домейн")
    }

    private fun collect(j: JSONObject, into: MutableList<JSONObject>) {
        val arr = j.optJSONArray("torrents") ?: return
        for (i in 0 until arr.length()) into.add(arr.getJSONObject(i))
    }
}

// ---------------------------------------------------------------- Nyaa (аниме, RSS)

class Nyaa(private val prefs: Prefs) : TorrentSource {
    override val id = "nyaa"
    override val name = "Nyaa (аниме)"
    override fun supports(q: SearchQuery) = q.anime

    override suspend fun search(q: SearchQuery): List<TorrentResult> = coroutineScope {
        val dom = baseUrl(prefs.domNyaa, "https://nyaa.si")
        val ep = q.episode
        val queries = if (ep != null) listOf("${q.title} ${app.torrtv.core.pad2(ep)}", "${q.title} batch")
        else listOf(q.title)
        queries.map { text -> async { fetch(dom, text, q) } }.awaitAll().flatten()
    }

    private suspend fun fetch(dom: String, text: String, q: SearchQuery): List<TorrentResult> {
        val xml = Http.get("$dom/?page=rss&q=${enc(text)}&c=0_0&f=0&s=seeders&o=desc")
        val doc = Jsoup.parse(xml, "", Parser.xmlParser())
        val out = ArrayList<TorrentResult>()
        for (item in doc.select("item")) {
            val title = item.selectFirst("title")?.text() ?: continue
            val hash = item.getElementsByTag("nyaa:infoHash").first()?.text() ?: continue
            if (!acceptTitle(q, title)) continue
            val seeders = item.getElementsByTag("nyaa:seeders").first()?.text()?.toIntOrNull() ?: 0
            val leechers = item.getElementsByTag("nyaa:leechers").first()?.text()?.toIntOrNull() ?: 0
            val size = parseSize(item.getElementsByTag("nyaa:size").first()?.text() ?: "")
            out.add(Quality.make(q, title, hash, Magnets.build(hash, title), seeders, leechers, size, name))
        }
        return out
    }

    private fun acceptTitle(q: SearchQuery, title: String): Boolean {
        val ep = q.episode ?: return true
        val t = Relevance.norm(title)
        if (BATCH_RE.containsMatchIn(t)) return true
        if (cachedRegex("\\bs0*\\d+ ?e0*$ep\\b").containsMatchIn(t)) return true
        // "[Group] Name - 05 (1080p)": махаме резолюции и години, за да не ги вземем за номер
        val cleaned = t.replace(NOISE_RE, " ")
        return cachedRegex("(?<!\\d)0*$ep(?!\\d)").containsMatchIn(cleaned)
    }
}

// ---------------------------------------------------------------- Knaben (агрегатор, JSON API)

class Knaben(private val prefs: Prefs) : TorrentSource {
    override val id = "knaben"
    override val name = "Knaben"
    override fun supports(q: SearchQuery) = true

    override suspend fun search(q: SearchQuery): List<TorrentResult> = coroutineScope {
        q.textQueries().map { text -> async { fetch(text, q) } }.awaitAll().flatten()
    }

    private suspend fun fetch(text: String, q: SearchQuery): List<TorrentResult> {
        val body = JSONObject()
            .put("search_type", "100%")
            .put("search_field", "title")
            .put("query", text)
            .put("order_by", "seeders")
            .put("order_direction", "desc")
            .put("from", 0)
            .put("size", 60)
            .put("hide_unsafe", true)
        val resp = JSONObject(Http.postJson(baseUrl(prefs.domKnaben, "https://api.knaben.org") + "/v1", body.toString()))
        val hits = resp.optJSONArray("hits") ?: return emptyList()
        val out = ArrayList<TorrentResult>()
        for (i in 0 until hits.length()) {
            val o = hits.getJSONObject(i)
            val title = o.str("title")
            var hash = o.str("hash")
            val magnetRaw = o.str("magnetUrl")
            if (hash.isBlank()) hash = Magnets.infoHash(magnetRaw) ?: continue
            if (!Relevance.accept(q, title)) continue
            val magnet = if (magnetRaw.startsWith("magnet:")) magnetRaw else Magnets.build(hash, title)
            out.add(
                Quality.make(
                    q, title, hash, magnet,
                    o.intOr("seeders"), o.intOr("peers"), o.long("bytes"), name
                )
            )
        }
        return out
    }
}

// ---------------------------------------------------------------- Torrents-CSV (отворена база, JSON)

class TorrentsCsv(private val prefs: Prefs) : TorrentSource {
    override val id = "csv"
    override val name = "Torrents-CSV"
    override fun supports(q: SearchQuery) = true

    override suspend fun search(q: SearchQuery): List<TorrentResult> = coroutineScope {
        q.textQueries().map { text -> async { fetch(text, q) } }.awaitAll().flatten()
    }

    private suspend fun fetch(text: String, q: SearchQuery): List<TorrentResult> {
        val resp = JSONObject(Http.get(baseUrl(prefs.domCsv, "https://torrents-csv.com") + "/service/search?q=${enc(text)}&size=50"))
        val arr = resp.optJSONArray("torrents") ?: return emptyList()
        val out = ArrayList<TorrentResult>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val hash = o.str("infohash")
            val title = o.str("name")
            if (hash.isBlank() || !Relevance.accept(q, title)) continue
            out.add(
                Quality.make(
                    q, title, hash, Magnets.build(hash, title),
                    o.intOr("seeders"), o.intOr("leechers"), o.long("size_bytes"), name
                )
            )
        }
        return out
    }
}

// ---------------------------------------------------------------- Bitsearch (bitsearch.eu, JSON)
/**
 * Безплатният достъп е ограничен (около 200 заявки на ден), затова се пуска само първото търсене, не всички варианти на заглавието.
 * Отговорът се чете търпеливо: масивът може да е в results / data / torrents / items, а полетата да имат различни имена.
 */
class Bitsearch(private val prefs: Prefs) : TorrentSource {
    override val id = "bitsearch"
    override val name = "Bitsearch"
    override fun supports(q: SearchQuery) = true

    override suspend fun search(q: SearchQuery): List<TorrentResult> {
        val text = q.textQueries().firstOrNull() ?: return emptyList()
        return parse(q, Http.get("https://bitsearch.eu/api/v1/search?q=${enc(text)}"))
    }

    fun parse(q: SearchQuery, raw: String): List<TorrentResult> {
        val body = raw.trim()
        val arr = findArray(body) ?: return emptyList()
        val out = ArrayList<TorrentResult>()
        for (i in 0 until minOf(arr.length(), 100)) {
            val o = arr.optJSONObject(i) ?: continue
            val title = pick(o, "title", "name")
            if (title.isBlank() || !Relevance.accept(q, title)) continue
            val magnet = pick(o, "magnet", "magnetUrl", "magnet_url", "magnet_link")
            var hash = pick(o, "infohash", "info_hash", "infoHash", "hash").lowercase(Locale.US)
            if (!Regex("[0-9a-f]{40}").matches(hash)) hash = Magnets.infoHash(magnet) ?: continue
            val seeds = pick(o, "seeders", "seeds", "stats.seeders").filter { it.isDigit() }.take(9).toIntOrNull() ?: 0
            val leech = pick(o, "leechers", "peers", "stats.leechers").filter { it.isDigit() }.take(9).toIntOrNull() ?: 0
            val sz = pick(o, "size", "size_bytes", "bytes", "stats.size")
            val size = sz.toLongOrNull() ?: parseSize(sz)
            val mg = if (magnet.startsWith("magnet:")) magnet else Magnets.build(hash, title)
            out.add(Quality.make(q, title, hash, mg, seeds, leech, size, name))
        }
        return out
    }

    private fun findArray(body: String): JSONArray? {
        if (body.startsWith("[")) return JSONArray(body)
        var node: Any? = JSONObject(body)
        // Сред най-обичайните места: results, data, data.results, torrents, items.
        for (path in listOf("results", "data", "data.results", "torrents", "items", "data.torrents")) {
            var n: Any? = node
            for (seg in path.split('.')) n = (n as? JSONObject)?.opt(seg)
            if (n is JSONArray) return n
        }
        return null
    }

    private fun pick(o: JSONObject, vararg names: String): String {
        for (p in names) {
            var n: Any? = o
            for (seg in p.split('.')) n = (n as? JSONObject)?.opt(seg)
            if (n != null && n !== JSONObject.NULL && n.toString().isNotBlank()) return n.toString()
        }
        return ""
    }
}
