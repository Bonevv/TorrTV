package app.torrtv.data.torrents

import app.torrtv.core.Http
import app.torrtv.core.Magnets
import app.torrtv.core.enc
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.parser.Parser
import java.util.Locale

/**
 * Торент сайт, описан с един ред от услугата (без нова версия на приложението):
 *   име | адрес с {q} | json или rss | списък | заглавие | хеш | магнит | сийдъри | размер
 * Адресът има {q} (думите за търсене) и/или {imdb}. При json "списък" е път до масива (напр. data.items; празно - самият отговор е масив),
 * а полетата са имена, може с точка (info.hash). При rss "списък" не се ползва, а полетата са имена на тагове (или таг@атрибут).
 * Празните полета се познават по обичайните имена.
 */
class GenericSite(
    override val id: String,
    override val name: String,
    private val url: String,
    private val fmt: String,
    private val list: String,
    private val fTitle: String,
    private val fHash: String,
    private val fMagnet: String,
    private val fSeeds: String,
    private val fSize: String
) : TorrentSource {

    override fun supports(q: SearchQuery): Boolean = !url.contains("{imdb}") || !q.imdbId.isNullOrBlank()

    override suspend fun search(q: SearchQuery): List<TorrentResult> = coroutineScope {
        q.textQueries().map { text -> async { fetch(text, q) } }.awaitAll().flatten().distinctBy { it.hash }
    }

    private suspend fun fetch(text: String, q: SearchQuery): List<TorrentResult> {
        val u = url.replace("{q}", enc(text)).replace("{imdb}", q.imdbId.orEmpty())
        val rows = parseRows(Http.get(u))
        val out = ArrayList<TorrentResult>()
        for (r in rows) {
            if (!Relevance.accept(q, r.title)) continue
            out.add(Quality.make(q, r.title, r.hash, r.magnet, r.seeders, 0, r.size, name))
        }
        return out
    }

    class Row(val title: String, val hash: String, val magnet: String, val seeders: Int, val size: Long)

    /** Редовете от отговора на сайта. Хвърля грешка, ако отговорът не е в обявения вид. */
    fun parseRows(body: String): List<Row> {
        val raw = if (fmt == "rss") rssRows(body) else jsonRows(body)
        val out = ArrayList<Row>()
        for (m in raw) {
            val title = m.title.trim()
            if (title.isEmpty()) continue
            var hash = m.hash.trim().lowercase(Locale.US)
            if (!HASH_RE.matches(hash)) hash = Magnets.infoHash(m.magnet.trim()) ?: Magnets.infoHash(m.hash.trim()) ?: continue
            val magnet = if (m.magnet.trim().startsWith("magnet:")) m.magnet.trim() else Magnets.build(hash, title)
            out.add(Row(title, hash, magnet, toInt(m.seeds), toSize(m.size)))
        }
        return out
    }

    private class Raw(val title: String, val hash: String, val magnet: String, val seeds: String, val size: String)

    private fun jsonRows(body: String): List<Raw> {
        val t = body.trim()
        val arr: JSONArray = if (list.isBlank()) {
            if (t.startsWith("[")) JSONArray(t) else findList(JSONObject(t)) ?: throw IllegalStateException("отговорът не е списък")
        } else {
            var node: Any? = if (t.startsWith("[")) JSONArray(t) else JSONObject(t)
            for (seg in list.split('.')) node = (node as? JSONObject)?.opt(seg)
            node as? JSONArray ?: throw IllegalStateException("няма списък \"$list\"")
        }
        val out = ArrayList<Raw>()
        for (i in 0 until minOf(arr.length(), 200)) {
            val o = arr.optJSONObject(i) ?: continue
            fun f(spec: String, vararg dflt: String): String =
                if (spec.isNotBlank()) jsonGet(o, spec) else dflt.map { jsonGet(o, it) }.firstOrNull { it.isNotBlank() }.orEmpty()
            out.add(
                Raw(
                    f(fTitle, "title", "name"), f(fHash, "infohash", "info_hash", "infoHash", "hash"),
                    f(fMagnet, "magnet", "magnetUrl", "magnet_url", "magnet_link"),
                    f(fSeeds, "seeders", "seeds"), f(fSize, "size", "bytes", "size_bytes")
                )
            )
        }
        return out
    }

    /** Без посочен път: масивът е на обичайните места (results, data, data.results, torrents, items). */
    private fun findList(o: JSONObject): JSONArray? {
        for (path in listOf("results", "data", "data.results", "torrents", "items", "data.torrents")) {
            var n: Any? = o
            for (seg in path.split('.')) n = (n as? JSONObject)?.opt(seg)
            if (n is JSONArray) return n
        }
        return null
    }

    private fun rssRows(body: String): List<Raw> {
        val doc = Jsoup.parse(body, "", Parser.xmlParser())
        var items = doc.select("item")
        if (items.isEmpty()) items = doc.select("entry")
        if (items.isEmpty()) throw IllegalStateException("няма записи в отговора")
        val out = ArrayList<Raw>()
        for (it in items.take(200)) {
            fun f(spec: String, vararg dflt: String): String =
                if (spec.isNotBlank()) rssGet(it, spec) else dflt.map { d -> rssGet(it, d) }.firstOrNull { v -> v.isNotBlank() }.orEmpty()
            out.add(
                Raw(
                    f(fTitle, "title"), f(fHash, "infoHash", "torrent:infoHash"),
                    f(fMagnet, "magnetURI", "torrent:magnetURI", "link", "enclosure@url"),
                    f(fSeeds, "seeders", "torrent:seeds", "nyaa:seeders"), f(fSize, "size", "contentLength", "nyaa:size")
                )
            )
        }
        return out
    }

    companion object {
        /** Стандартните свои сайтове: ползват се, когато профилът няма свой списък (в панела са зад бутона "Стандартни"). */
        val defaultLines = listOf(
            "Magnetz|https://magnetz.eu/api/magnets/search?query={q}|json|data|name|info_hash|magnet_link|seeders|size",
            "Bitsearch|https://bitsearch.eu/api/v1/search?q={q}|json"
        )

        /** Сайтовете от настройките; празният списък дава стандартните. */
        fun effective(text: String): List<GenericSite> =
            parseAll(if (text.isBlank()) defaultLines.joinToString("\n") else text)

        private val HASH_RE = Regex("[0-9a-f]{40}")

        private fun jsonGet(o: JSONObject, path: String): String {
            var node: Any? = o
            for (seg in path.split('.')) node = (node as? JSONObject)?.opt(seg)
            if (node == null || node === JSONObject.NULL) return ""
            return node.toString()
        }

        private fun rssGet(item: org.jsoup.nodes.Element, spec: String): String {
            val tag = spec.substringBefore('@')
            val attr = if (spec.contains('@')) spec.substringAfter('@') else ""
            val e = item.getElementsByTag(tag).firstOrNull() ?: return ""
            return if (attr.isNotEmpty()) e.attr(attr) else e.text()
        }

        private fun toInt(s: String): Int = s.trim().toIntOrNull() ?: s.filter { it.isDigit() }.take(9).toIntOrNull() ?: 0

        private fun toSize(s: String): Long {
            val t = s.trim()
            return t.toLongOrNull() ?: if (t.isEmpty()) 0L else parseSize(t)
        }

        private fun slug(name: String): String {
            val s = name.lowercase(Locale.US).filter { it in 'a'..'z' || it in '0'..'9' }
            return s.ifEmpty { Integer.toHexString(name.hashCode()) }.take(30)
        }

        /** Един ред или null, ако не е годен. */
        fun parseLine(line: String): GenericSite? {
            val p = line.split('|').map { it.trim() }
            if (p.size < 3 || p[0].isEmpty()) return null
            val url = p[1]
            val f = p[2].lowercase(Locale.US)
            if (!url.startsWith("http://", true) && !url.startsWith("https://", true)) return null
            if (!url.contains("{q}") && !url.contains("{imdb}")) return null
            if (f != "json" && f != "rss") return null
            fun at(i: Int) = p.getOrNull(i).orEmpty()
            return GenericSite("site_" + slug(p[0]), p[0], url, f, at(3), at(4), at(5), at(6), at(7), at(8))
        }

        /** Редовете от настройките: невалидните се прескачат, еднаквите имена не се повтарят. */
        fun parseAll(text: String): List<GenericSite> =
            text.lines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }
                .mapNotNull { parseLine(it) }.distinctBy { it.id }
    }
}
