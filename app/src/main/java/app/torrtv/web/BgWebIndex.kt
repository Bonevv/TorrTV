package app.torrtv.web

import app.torrtv.core.Http
import app.torrtv.core.enc
import app.torrtv.data.Media
import app.torrtv.data.MediaType
import app.torrtv.web.catalog.MovieCatalog
import app.torrtv.web.catalog.MovieSources
import app.torrtv.web.catalog.MovieEpisodes
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import java.io.File
import java.text.Normalizer
import java.util.Locale

/** Catalogue metadata is cached; audio badges require explicit evidence for that version. */
class BgWebIndex(private val directory: File, private val get: suspend (String) -> String = { Http.get(it) },
    private val enabled: () -> Set<String> = { setOf("NINE", "TWO_K", "ZENO", "ALL", "FEN", "ZT", "ZIP") },
    private val allowNetwork: () -> Boolean = { true }) {
    data class Record(val entry: MovieCatalog.Entry, val checkedAt: Long)
    val revision = MutableStateFlow(0)
    val status = MutableStateFlow("")
    @Volatile private var records: List<Record> = emptyList()
    private data class Lookup(val ids: Map<String, List<Record>>, val names: Map<String, List<Record>>, val series: Map<String,List<Record>>)
    @Volatile private var lookup = Lookup(emptyMap(), emptyMap(), emptyMap())
    private val seriesResolver by lazy { WebSeriesResolver(::fetch) }
    private val indexFile get() = File(directory, "index-audio-v2.json")
    private val lock = Mutex()
    private val attempts = LinkedHashMap<String, Long>()
    private var restored = false
    private val nextPages = java.util.concurrent.ConcurrentHashMap<String, String>()
    private suspend fun fetch(url: String): String {
        if (!allowNetwork()) throw CancellationException("Каталозите са спрени по време на гледане")
        return get(url)
    }
    companion object {
        const val TTL = 24 * 60 * 60_000L
        fun normalize(s: String): String = Normalizer.normalize(s, Normalizer.Form.NFKC)
            .lowercase(Locale.ROOT).replace('ѝ', 'и').replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()
        fun matches(m: Media, e: MovieCatalog.Entry, imdb: String? = null): Boolean {
            if (e.series != (m.type == MediaType.TV)) return false
            if (!imdb.isNullOrBlank() && e.imdb.isNotBlank() && imdb != e.imdb) return false
            if (e.tmdb.isNotBlank() && e.tmdb != "0") return e.tmdb == m.id.toString()
            if (!imdb.isNullOrBlank() && e.imdb.isNotBlank()) return imdb == e.imdb
            val year = if (e.series && e.seriesYear > 0) e.seriesYear else e.year
            if (year <= 0 || year.toString() != m.year) return false
            val key: (String)->String = if(e.series) MovieEpisodes::baseTitle else ::normalize
            val names = setOf(key(m.title), key(m.originalTitle)) - ""
            return listOf(e.title, e.original).any { key(it) in names }
        }
    }
    private fun replaceRecords(value: List<Record>) {
        val ids = HashMap<String, MutableList<Record>>()
        val names = HashMap<String, MutableList<Record>>()
        val series = HashMap<String,MutableList<Record>>()
        for (r in value) {
            val e = r.entry; val type = if (e.series) "tv" else "movie"
            for (id in listOf(e.tmdb, e.imdb).filter { it.isNotBlank() && it != "0" }) ids.getOrPut("$type:$id") { ArrayList() }.add(r)
            val labels=if(e.series)setOf(MovieEpisodes.baseTitle(e.title),MovieEpisodes.baseTitle(e.original)) else setOf(normalize(e.title),normalize(e.original))
            val year=if(e.seriesYear>0)e.seriesYear else e.year
            for (name in labels - "") {
                names.getOrPut("$type:$year:$name") { ArrayList() }.add(r)
                if(e.series)series.getOrPut(name){ArrayList()}.add(r)
            }
        }
        records = value
        lookup = Lookup(ids, names, series)
    }
    fun find(m: Media, imdb: String? = null, onlyBg: Boolean = true): List<Record> {
        val snapshot = lookup; val providers = enabled(); val now = System.currentTimeMillis()
        val type = m.type.path
        val candidates = ArrayList<Record>()
        candidates.addAll(snapshot.ids["$type:${m.id}"].orEmpty())
        if (!imdb.isNullOrBlank()) candidates.addAll(snapshot.ids["$type:$imdb"].orEmpty())
        val labels=if(m.type==MediaType.TV)setOf(MovieEpisodes.baseTitle(m.title),MovieEpisodes.baseTitle(m.originalTitle)) else setOf(normalize(m.title),normalize(m.originalTitle))
        for (name in labels - "") candidates.addAll(snapshot.names["$type:${m.year}:$name"].orEmpty())
        return candidates.distinctBy { it.entry.page }.filter {
            now - it.checkedAt in 0..TTL && it.entry.provider in providers && (!onlyBg || it.entry.bgAudio && it.entry.audioVerified) && matches(m, it.entry, imdb)
        }
    }
    // Catalogue badges describe a title; sources() separately checks the selected episode.
    fun has(m: Media): Boolean = find(m).isNotEmpty()
    fun count(type:MediaType?=null): Int { val providers=enabled();val now=System.currentTimeMillis();return records.count {
        it.entry.provider in providers && it.entry.bgAudio && it.entry.audioVerified && now-it.checkedAt in 0..TTL && (type==null || it.entry.series==(type==MediaType.TV))
    } }
    fun settingsChanged() { revision.value++ }

    private fun persist() {
        // The index is expendable: a full or cleared cache must not break browsing.
        try {
            directory.mkdirs()
            val tmp = File(directory, "index.tmp")
            tmp.writeText(encode(records))
            tmp.renameTo(indexFile)
        } catch (_: java.io.IOException) { }
    }
    private fun restoreLocked() {
        if (restored) return
        restored = true
        try { replaceRecords(decode(indexFile.readText())) } catch (_: Exception) { }
        revision.value++
    }
    suspend fun restore() = withContext(Dispatchers.IO) { lock.withLock { restoreLocked() } }

    suspend fun refresh(query: String = "", force: Boolean = false, type: MediaType? = null) = withContext(Dispatchers.IO) {
        lock.withLock {
            restoreLocked()
            if (!allowNetwork()) return@withLock
            val providers = enabled()
            val q = query.trim().take(120)
            val attemptKey = q + ":" + (type?.path ?: "all") + providers.sorted().joinToString(",")
            if (providers.isEmpty()) { status.value = "БГ уеб източниците са изключени от настройките на профила."; revision.value++; return@withLock }
            if (!force && System.currentTimeMillis() - (attempts[attemptKey] ?: 0L) < 30 * 60_000L) return@withLock
            status.value = "Проверяват се БГ уеб източниците…"
            val gate = Semaphore(3)
            val updates = coroutineScope {
                (MovieSources.Provider.values().filter { it.name in providers }.map { p -> async {
                    gate.withPermit {
                        if (!allowNetwork()) return@withPermit null
                        try { withTimeout(9_000) { Pair(p.name, load(p, q, type)) } }
                        catch (e: CancellationException) { if (e !is TimeoutCancellationException) throw e else null }
                        catch (_: Exception) { null }
                    }
                } } + async {
                    gate.withPermit {
                        if (!allowNetwork()) return@withPermit null
                        try { withTimeout(9_000) { if ("TWO_K" in providers) Pair("TWO_K", loadTwoK(q)) else null } }
                        catch (e: CancellationException) { if (e !is TimeoutCancellationException) throw e else null }
                        catch (_: Exception) { null }
                    }
                }).awaitAll().filterNotNull()
            }
            val now = System.currentTimeMillis()
            val merged = records.filter { now - it.checkedAt in 0..TTL }.associateBy { it.entry.page }.toMutableMap()
            for ((_, entries) in updates) for (e in entries) { if (e.bgAudio || e.series) merged[e.page] = Record(e, now) else merged.remove(e.page) }
            replaceRecords(merged.values.sortedByDescending { it.checkedAt }.take(3000))
            if (updates.isNotEmpty()) {
                attempts[attemptKey] = now
                if (attempts.size > 64) attempts.remove(attempts.keys.first())
                persist()
            }
            revision.value++
            status.value = if (updates.isEmpty()) "Уеб източниците не отговориха. Наличността на БГ аудио е непроверена."
                else "Потвърдени БГ ${if(type==MediaType.TV)"сериали" else if(type==MediaType.MOVIE)"филми" else "версии"}: ${count(type)} · отговорили източници: ${updates.size}/${providers.size}. Проверката е частична."
        }
    }
    private suspend fun load(p: MovieSources.Provider, q: String, type: MediaType?): List<MovieCatalog.Entry> {
        if (p == MovieSources.Provider.NINE) {
            val url = if (q.isBlank()) "https://cdn.filmi9.com/movies/?type=${if (type == MediaType.TV) "series" else "movie"}&isBgAudio=true&page=1&limit=30"
                else "https://cdn.filmi9.com/movies/search/?term=${enc(q)}&page=1&limit=30"
            val j = JSONObject(fetch(url))
            if (!j.has("data")) j.put("data", j.optJSONArray("results") ?: JSONArray())
            val doc = org.jsoup.nodes.Document.createShell(url)
            doc.body().text(j.toString())
            val parsed = MovieSources.parse(p, doc)
            if (q.isBlank()) nextPages[p.name] = if (parsed.entries.size >= 30) url.replace("page=1", "page=2") else ""
            return parsed.entries.toList()
        }
        val url = if (q.isBlank() && type == MediaType.TV) p.seriesStart() else p.start(q, MovieCatalog.BG_AUDIO)
        val parsed = MovieSources.parse(p, url, fetch(url))
        if (p == MovieSources.Provider.ZENO && q.isNotBlank()) {
            for (entry in parsed.entries.filter { !it.bgAudio }.take(3)) {
                val confirmed = withTimeoutOrNull(1500) { ZenoProvider.hasBg(entry.page, fetch(entry.page)) } == true
                if (confirmed) { entry.bgAudio = true; entry.audioVerified = true }
            }
        }
        if (q.isBlank()) nextPages[p.name] = parsed.next.takeIf { p.owns(it) && it != url }.orEmpty()
        return parsed.entries.toList()
    }
    private suspend fun loadTwoK(q: String): List<MovieCatalog.Entry> {
        val base = "https://www.filmi2k.com"
        val tags = JSONArray(fetch("$base/wp-json/wp/v2/tags?slug=filmi-bg-audio&per_page=1"))
        if (tags.length() == 0) return emptyList()
        val tag = tags.getJSONObject(0).getInt("id")
        val posts = JSONArray(fetch("$base/wp-json/wp/v2/posts?tags=$tag&per_page=30&page=1" + if (q.isBlank()) "" else "&search=${enc(q)}"))
        if (q.isBlank()) nextPages["TWO_K"] = if (posts.length() >= 30) "$base/wp-json/wp/v2/posts?tags=$tag&per_page=30&page=2" else ""
        return parseTwoK(posts, tag)
    }
    private fun parseTwoK(posts: JSONArray, tag: Int): List<MovieCatalog.Entry> {
        return (0 until posts.length()).mapNotNull { i ->
            val o = posts.getJSONObject(i)
            val ts = o.optJSONArray("tags") ?: return@mapNotNull null
            if ((0 until ts.length()).none { ts.optInt(it) == tag }) return@mapNotNull null
            val url = o.optString("link")
            if (java.net.URI(url).host !in setOf("www.filmi2k.com", "filmi2k.com")) return@mapNotNull null
            val title = Jsoup.parse(o.optJSONObject("title")?.optString("rendered").orEmpty()).text()
            val year = Regex("\\b(?:19|20)\\d{2}\\b").find(title)?.value?.toIntOrNull() ?: 0
            MovieCatalog.Entry().also { e ->
                e.provider = "TWO_K"; e.page = url; e.bgAudio = true; e.audioVerified = true; e.year = year
                val names = title.replace(Regex("\\s*\\(\\d{4}\\).*"), "").split(" / ")
                e.original = names.first().trim(); e.title = names.last().trim()
            }
        }
    }
    /** Gradually completes catalogue pages while browsing; cancelled when playback starts. */
    suspend fun sweep(maxPages: Int = 7) = withContext(Dispatchers.IO) {
        val visited = HashSet<String>()
        val failed = HashSet<String>()
        val completed = HashMap<String, Int>()
        var pages = 0
        while (allowNetwork() && pages < maxPages && count() < 3000) {
            val pending = nextPages.entries.filter { it.key in enabled() && it.key !in failed && it.value.isNotBlank() && it.value !in visited }
                .minByOrNull { completed[it.key] ?: 0 } ?: break
            val provider = pending.key; val url = pending.value
            visited.add(url)
            try {
                val entries: List<MovieCatalog.Entry>
                val next: String
                if (provider == "TWO_K") {
                    // Reuse the tag-checked parser while retaining the current pagination query.
                    val text = withTimeout(8_000) { fetch(url) }
                    val posts = JSONArray(text)
                    val tag = Regex("[?&]tags=(\\d+)").find(url)?.groupValues?.get(1)?.toInt() ?: break
                    entries = parseTwoK(posts, tag)
                    next = if (posts.length() >= 30) incrementPage(url) else ""
                } else {
                    val p = MovieSources.Provider.valueOf(provider)
                    if (!p.owns(url)) { nextPages[provider]="";continue }
                    val body = withTimeout(8_000) { fetch(url) }
                    val parsed = if (provider == "NINE") {
                        val doc=org.jsoup.nodes.Document.createShell(url);doc.body().text(body);MovieSources.parse(p,doc)
                    } else MovieSources.parse(p,url,body)
                    entries=parsed.entries.toList()
                    next=if (provider=="NINE") { if(entries.size>=30) incrementPage(url) else "" }
                        else parsed.next.takeIf { p.owns(it) && it != url }.orEmpty()
                }
                lock.withLock {
                    val now=System.currentTimeMillis()
                    val merged=records.filter { now-it.checkedAt in 0..TTL }.associateBy { it.entry.page }.toMutableMap()
                    for(e in entries) if(e.bgAudio || e.series) merged[e.page]=Record(e,now) else merged.remove(e.page)
                    replaceRecords(merged.values.sortedByDescending { it.checkedAt }.take(3000))
                    nextPages[provider]=next
                    persist()
                    revision.value++
                    status.value="Потвърдени записи: ${count()} · проверката продължава…"
                }
                pages++
                completed[provider] = (completed[provider] ?: 0) + 1
                delay(250)
            } catch(e: CancellationException) { if(e !is TimeoutCancellationException)throw e else failed.add(provider) }
            catch(_:Exception) { failed.add(provider) }
        }
        if (pages > 0) status.value= "Потвърдени записи: ${count()} · " +
            if(nextPages.any { it.key in enabled() && it.value.isNotBlank() }) "проверката е частична; можеш да я продължиш." else "наличните страници са обходени; някои източници може да са непроверени."
    }
    private fun incrementPage(url:String):String = Regex("([?&]page=)(\\d+)").replace(url) { it.groupValues[1]+(it.groupValues[2].toInt()+1) }
    suspend fun sources(m: Media, imdb: String?, season: Int, episode: Int): List<WebSources.Source> = withContext(Dispatchers.IO) {
        if(m.type==MediaType.TV)return@withContext series(m,imdb,season,episode).sources
        refresh(type = m.type)
        refresh(m.originalTitle.ifBlank { m.title })
        val checkedEntries=ArrayList<MovieCatalog.Entry>()
        val sources=find(m, imdb, onlyBg=false).mapNotNull { record ->
            val e = decodeEntry(encodeEntry(record.entry))
            try {
                val checked=if(e.audioVerified)e else MovieSources.detail(e,Jsoup.parse(fetch(e.page),e.page))
                if(!e.audioVerified)checkedEntries.add(checked)
                if(!matches(m,checked,imdb))return@mapNotNull null
                if(!checked.bgAudio || !checked.audioVerified)return@mapNotNull null
                if (e.provider == "ZENO") {
                    // Only a provider explicitly returned in the BG group may be selected.
                    val embed = withTimeoutOrNull(5_000) { ZenoProvider.players(e.page, fetch(e.page)).firstOrNull() }
                        ?: return@mapNotNull null
                    BgPlayerPresentation.source(WebSources.hostOf(e.page), e.page, embed)
                } else BgPlayerPresentation.source(WebSources.hostOf(e.page), e.page, checked.embed)
            } catch (ex: CancellationException) { throw ex } catch (_: Exception) { null }
        }.distinctBy { it.key }
        rememberDetails(checkedEntries)
        sources
    }
    suspend fun series(m:Media,imdb:String?,season:Int,episode:Int):WebSeriesResolver.Result = withContext(Dispatchers.IO) {
        restore()
        refresh(m.originalTitle.ifBlank{m.title},type=MediaType.TV)
        val providers=enabled();val now=System.currentTimeMillis();val snapshot=lookup
        val candidates=(find(m,imdb,onlyBg=false)+listOf(m.title,m.originalTitle).flatMap{snapshot.series[MovieEpisodes.baseTitle(it)].orEmpty()})
            .distinctBy{it.entry.page}.filter{it.entry.provider in providers && now-it.checkedAt in 0..TTL}
            .map{it.entry}.filter{(it.tmdb.isBlank() || it.tmdb=="0" || it.tmdb==m.id.toString()) && (imdb.isNullOrBlank() || it.imdb.isBlank() || it.imdb==imdb)}
        val result=seriesResolver.resolve(m,imdb,candidates,season,episode)
        rememberDetails(result.entries)
        result
    }
    private suspend fun rememberDetails(entries:List<MovieCatalog.Entry>) {
        if(entries.isNotEmpty())lock.withLock {
            val now=System.currentTimeMillis()
            val merged=records.associateBy{it.entry.page}.toMutableMap()
            for(e in entries)merged[e.page]=Record(e,now)
            replaceRecords(merged.values.sortedByDescending{it.checkedAt}.take(3000));persist();revision.value++
        }
    }
    private fun encodeEntry(e: MovieCatalog.Entry) = JSONObject().put("provider", e.provider).put("page", e.page)
        .put("title", e.title).put("original", e.original).put("year", e.year).put("tmdb", e.tmdb)
        .put("imdb", e.imdb).put("series", e.series).put("bgAudio", e.bgAudio).put("embed", e.embed)
        .put("audioVerified",e.audioVerified).put("season",e.season).put("seriesYear",e.seriesYear)
    private fun decodeEntry(j: JSONObject) = MovieCatalog.Entry().also {
        it.provider=j.getString("provider");it.page=j.getString("page");it.title=j.getString("title")
        it.original=j.optString("original");it.year=j.optInt("year");it.tmdb=j.optString("tmdb")
        it.imdb=j.optString("imdb");it.series=j.optBoolean("series");it.bgAudio=j.optBoolean("bgAudio")
        it.embed=j.optString("embed")
        it.audioVerified=j.optBoolean("audioVerified");it.season=j.optInt("season",-1);it.seriesYear=j.optInt("seriesYear")
        MovieSources.cleanIds(it)
    }
    private fun encode(r: List<Record>) = JSONArray(r.map { JSONObject().put("entry", encodeEntry(it.entry)).put("checkedAt", it.checkedAt) }).toString()
    private fun decode(s: String): List<Record> {
        val a = JSONArray(s)
        return (0 until minOf(a.length(), 3000)).map { i -> val j=a.getJSONObject(i);Record(decodeEntry(j.getJSONObject("entry")), j.getLong("checkedAt")) }
            .filter { System.currentTimeMillis() - it.checkedAt in 0..TTL }
    }
}
