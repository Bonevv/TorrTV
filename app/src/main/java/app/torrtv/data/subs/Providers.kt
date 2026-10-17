package app.torrtv.data.subs

import app.torrtv.core.Http
import app.torrtv.core.Prefs
import app.torrtv.core.enc
import app.torrtv.core.intOr
import app.torrtv.core.long
import app.torrtv.core.pad2
import app.torrtv.core.str
import app.torrtv.data.MediaType
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import java.nio.charset.Charset
import java.util.TreeMap

private fun JSONObject.flat(name: String): String {
    val v = opt(name)
    return when (v) {
        null, JSONObject.NULL -> ""
        is JSONArray -> if (v.length() > 0) v.optString(0, "") else ""
        else -> v.toString()
    }
}

// ---------------------------------------------------------------- OpenSubtitles (официален REST API)

class OpenSubtitles(private val prefs: Prefs) : SubtitleProvider {
    override val id = "opensubtitles"
    override val name = "OpenSubtitles"

    private val mutex = Mutex()
    private var token: String? = null
    private var tokenAt = 0L
    private var host = "api.opensubtitles.com"

    override fun configured() = prefs.osKey.isNotBlank()

    private fun headers(): Map<String, String> = mapOf(
        "Api-Key" to prefs.osKey,
        "User-Agent" to "TorrTV v1.0",
        "Accept" to "application/json",
        "Content-Type" to "application/json"
    )

    private suspend fun login(force: Boolean): String? = mutex.withLock {
        if (prefs.osUser.isBlank() || prefs.osPass.isBlank()) return@withLock null
        val t = token
        if (!force && t != null && System.currentTimeMillis() - tokenAt < 20 * 3600_000L) return@withLock t
        val body = JSONObject().put("username", prefs.osUser).put("password", prefs.osPass).toString()
        val resp = JSONObject(Http.postJson("https://api.opensubtitles.com/api/v1/login", body, headers()))
        val tk = resp.str("token")
        val bu = resp.str("base_url")
        if (bu.isNotBlank()) host = bu.removePrefix("https://").trimEnd('/')
        token = tk
        tokenAt = System.currentTimeMillis()
        tk
    }

    override suspend fun search(q: SubtitleQuery): List<SubtitleResult> {
        val imdb = q.imdbId?.removePrefix("tt")
        val p = TreeMap<String, String>()
        p["languages"] = q.langs.joinToString(",")
        p["order_by"] = "download_count"
        p["order_direction"] = "desc"
        if (q.type == MediaType.TV) {
            p["type"] = "episode"
            if (imdb != null) p["parent_imdb_id"] = imdb else p["query"] = q.title
            q.season?.let { p["season_number"] = it.toString() }
            q.episode?.let { p["episode_number"] = it.toString() }
        } else {
            p["type"] = "movie"
            if (imdb != null) p["imdb_id"] = imdb else {
                p["query"] = q.title
                if (q.year.isNotBlank()) p["year"] = q.year
            }
        }
        val url = "https://$host/api/v1/subtitles?" + p.entries.joinToString("&") { "${it.key}=${enc(it.value)}" }
        val data = JSONObject(Http.get(url, headers())).optJSONArray("data") ?: return emptyList()
        val out = ArrayList<SubtitleResult>()
        for (i in 0 until data.length()) {
            val a = data.getJSONObject(i).optJSONObject("attributes") ?: continue
            val files = a.optJSONArray("files") ?: continue
            if (files.length() == 0) continue
            val fileId = files.getJSONObject(0).long("file_id")
            if (fileId <= 0) continue
            out.add(
                SubtitleResult(
                    provider = id, providerName = name,
                    lang = Langs.norm(a.str("language")),
                    release = a.str("release").ifBlank { files.getJSONObject(0).str("file_name") },
                    ref = fileId.toString(),
                    downloads = a.intOr("download_count"),
                    trusted = a.optBoolean("from_trusted", false),
                    hearing = a.optBoolean("hearing_impaired", false)
                )
            )
        }
        return out
    }

    override suspend fun download(r: SubtitleResult, q: SubtitleQuery): List<SubFile> {
        var tk = login(false)
            ?: throw IllegalStateException("OpenSubtitles: въведи потребител и парола (Настройки)")

        suspend fun call(t: String): String = Http.postJson(
            "https://$host/api/v1/download",
            JSONObject().put("file_id", r.ref.toLong()).put("sub_format", "srt").toString(),
            headers() + ("Authorization" to "Bearer $t")
        )

        val resp = try {
            call(tk)
        } catch (e: Http.HttpError) {
            if (e.code == 401) {
                tk = login(true) ?: throw e
                call(tk)
            } else throw e
        }
        val j = JSONObject(resp)
        val link = j.str("link")
        if (link.isBlank()) throw IllegalStateException("OpenSubtitles: ${j.str("message").ifBlank { "няма линк за сваляне" }}")
        val bytes = Http.getBytes(link)
        return listOf(SubFile(j.str("file_name").ifBlank { "sub.srt" }, bytes))
    }
}

// ---------------------------------------------------------------- Subdl

class Subdl(private val prefs: Prefs) : SubtitleProvider {
    override val id = "subdl"
    override val name = "Subdl"

    override fun configured() = prefs.subdlKey.isNotBlank()

    override suspend fun search(q: SubtitleQuery): List<SubtitleResult> {
        val sb = StringBuilder("https://api.subdl.com/api/v1/subtitles?api_key=")
            .append(enc(prefs.subdlKey))
            .append("&subs_per_page=30&languages=")
            .append(q.langs.joinToString(",") { it.uppercase() })
        if (q.imdbId != null) sb.append("&imdb_id=").append(q.imdbId)
        else sb.append("&film_name=").append(enc(q.title))
        sb.append("&type=").append(if (q.type == MediaType.TV) "tv" else "movie")
        q.season?.let { sb.append("&season_number=").append(it) }
        q.episode?.let { sb.append("&episode_number=").append(it) }

        val j = JSONObject(Http.get(sb.toString()))
        if (!j.optBoolean("status", true)) throw IllegalStateException("Subdl: ${j.str("error").ifBlank { "грешка" }}")
        val arr = j.optJSONArray("subtitles") ?: return emptyList()
        val out = ArrayList<SubtitleResult>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val url = o.str("url")
            if (url.isBlank()) continue
            out.add(
                SubtitleResult(
                    provider = id, providerName = name,
                    lang = Langs.norm(o.str("language").ifBlank { o.str("lang") }),
                    release = o.str("release_name").ifBlank { o.str("name") },
                    ref = url,
                    downloads = 0,
                    trusted = false,
                    hearing = o.optBoolean("hi", false)
                )
            )
        }
        return out
    }

    override suspend fun download(r: SubtitleResult, q: SubtitleQuery): List<SubFile> {
        val url = if (r.ref.startsWith("http")) r.ref else "https://dl.subdl.com${r.ref}"
        return Archives.extract(Http.getBytes(url), java.io.File(System.getProperty("java.io.tmpdir") ?: "/data/local/tmp", "torrtv-rar"))
    }
}

// ---------------------------------------------------------------- SubSource (схемата на отговорите не е потвърдена - работи "най-добро усилие")

class SubSource(private val prefs: Prefs) : SubtitleProvider {
    override val id = "subsource"
    override val name = "SubSource"

    private val base = "https://api.subsource.net/api/v1"

    override fun configured() = prefs.subsourceKey.isNotBlank()

    private fun headers(): Map<String, String> = mapOf(
        "X-API-Key" to prefs.subsourceKey,
        "Accept" to "application/json"
    )

    private fun listOf(j: JSONObject): JSONArray =
        j.optJSONArray("data") ?: j.optJSONArray("results") ?: j.optJSONArray("subtitles")
        ?: j.optJSONArray("movies") ?: JSONArray()

    private suspend fun findMovieId(q: SubtitleQuery): String? {
        val url = if (q.imdbId != null) "$base/movies/search?searchType=imdb&imdb=${enc(q.imdbId)}"
        else "$base/movies/search?searchType=text&q=${enc(q.title)}"
        val arr = listOf(JSONObject(Http.get(url, headers())))
        if (arr.length() == 0) return null
        val first = arr.getJSONObject(0)
        return first.str("movieId").ifBlank { first.str("id") }.ifBlank { null }
    }

    override suspend fun search(q: SubtitleQuery): List<SubtitleResult> {
        val movieId = findMovieId(q) ?: return emptyList()
        val out = ArrayList<SubtitleResult>()
        for (lang in q.langs) {
            val full = Langs.englishNames[lang] ?: continue
            val arr = listOf(JSONObject(Http.get("$base/subtitles?movieId=$movieId&language=${enc(full)}", headers())))
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val sid = o.str("subtitleId").ifBlank { o.str("id") }
                if (sid.isBlank()) continue
                val release = o.flat("releaseInfo").ifBlank { o.flat("release_info") }
                    .ifBlank { o.flat("releases") }.ifBlank { o.str("name") }
                if (q.type == MediaType.TV && q.season != null && q.episode != null) {
                    val re = Regex("(?i)s0*${q.season} ?e0*${q.episode}(?!\\d)|\\b${q.season}x0*${q.episode}(?!\\d)")
                    val packRe = Regex("(?i)\\bs0*${q.season}\\b(?! ?e\\d)|season ?0*${q.season}\\b")
                    if (!re.containsMatchIn(release) && !packRe.containsMatchIn(release)) continue
                }
                out.add(
                    SubtitleResult(
                        provider = id, providerName = name, lang = lang,
                        release = release, ref = sid,
                        downloads = o.intOr("downloads"),
                        trusted = false,
                        hearing = o.optBoolean("hearingImpaired", false)
                    )
                )
            }
        }
        return out
    }

    override suspend fun download(r: SubtitleResult, q: SubtitleQuery): List<SubFile> {
        val bytes = Http.getBytes("$base/subtitles/${r.ref}/download", headers())
        return Archives.extract(bytes, java.io.File(System.getProperty("java.io.tmpdir") ?: "/data/local/tmp", "torrtv-rar"))
    }
}

// ---------------------------------------------------------------- Subs.sab.bz (български, няма API - HTML, "най-добро усилие")

class SabBz : SubtitleProvider {
    override val id = "sabbz"
    override val name = "Subs.sab.bz"

    private val site = "http://subs.sab.bz"
    private val cp1251: Charset = Charset.forName("windows-1251")

    override fun configured() = true

    override suspend fun search(q: SubtitleQuery): List<SubtitleResult> {
        if ("bg" !in q.langs) return emptyList()
        val text = if (q.type == MediaType.TV && q.season != null && q.episode != null)
            "${q.title} S${pad2(q.season)}E${pad2(q.episode)}" else q.title
        val url = "$site/index.php?act=search&movie=${enc(text)}&select-language=2&upl=&yr=${q.year}"
        val html = String(Http.getBytes(url, mapOf("Referer" to "$site/")), cp1251)
        val doc = Jsoup.parse(html, "$site/")
        val out = ArrayList<SubtitleResult>()
        val seen = HashSet<String>()
        for (a in doc.select("a[href*=attach_id]")) {
            val id = Regex("attach_id=(\\d+)").find(a.attr("href"))?.groupValues?.get(1) ?: continue
            if (!seen.add(id)) continue
            val row = a.closest("tr")?.text()?.trim().orEmpty()
            val label = a.text().ifBlank { a.attr("title") }.ifBlank { row }.take(140)
            out.add(
                SubtitleResult(
                    provider = this.id, providerName = name, lang = "bg",
                    release = label.ifBlank { "Субтитри $id" }, ref = id,
                    downloads = 0, trusted = false, hearing = false
                )
            )
            if (out.size >= 15) break
        }
        return out
    }

    override suspend fun download(r: SubtitleResult, q: SubtitleQuery): List<SubFile> {
        val bytes = Http.getBytes("$site/index.php?act=download&attach_id=${r.ref}", mapOf("Referer" to "$site/"))
        return Archives.extract(bytes, java.io.File(System.getProperty("java.io.tmpdir") ?: "/data/local/tmp", "torrtv-rar"))
    }
}
