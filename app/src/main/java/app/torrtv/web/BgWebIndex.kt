package app.torrtv.web

import app.torrtv.core.AppLog
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

/** Catalogue metadata is cached; BG badges require membership of the site's BG audio catalogue. */
class BgWebIndex(private val directory: File, private val get: suspend (String) -> String = { Http.get(it, siteHeaders(it)) },
    private val enabled: () -> Set<String> = { setOf("NINE", "TWO_K", "ZENO", "ALL", "FEN", "ZIP") },
    private val allowNetwork: () -> Boolean = { true },
    private val bgPlayers:suspend(String,String)->List<String> = {page,html->ZenoProvider.players(page,html)},
    private val sitePlayers:suspend(String,List<String>)->List<String> = {page,urls->SitePlayers.resolve(page,urls)}) {
    data class Record(val entry: MovieCatalog.Entry, val checkedAt: Long)
    val revision = MutableStateFlow(0)
    val status = MutableStateFlow("")
    @Volatile private var records: List<Record> = emptyList()
    private data class Lookup(val ids: Map<String, List<Record>>, val names: Map<String, List<Record>>, val series: Map<String,List<Record>>)
    @Volatile private var lookup = Lookup(emptyMap(), emptyMap(), emptyMap())
    private val seriesResolver by lazy { WebSeriesResolver(::fetch,bgPlayers) }
    private val indexFile get() = File(directory, "index-categories-v3.json")
    private val lock = Mutex()
    private val attempts = LinkedHashMap<String, Long>()
    private var restored = false
    private val nextPages = java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.ConcurrentHashMap<String,String>>()
    private val pageEntries = java.util.concurrent.ConcurrentHashMap<String,MutableSet<String>>()
    private val pageCounts = java.util.concurrent.ConcurrentHashMap<String,Int>()
    private val advanceLock=Mutex()
    private var twoKAudioTag:Int?=null
    private data class Catalogue(val query:String="",val type:MediaType?=null,val audioOnly:Boolean=true)
    @Volatile private var activeCatalogue=Catalogue()
    private fun catalogueKey(q:String,type:MediaType?,audioOnly:Boolean)="${type?.path?:"all"}|$audioOnly|${q.trim().take(120)}"
    private fun cursors(q:String,type:MediaType?,audioOnly:Boolean)=nextPages.getOrPut(catalogueKey(q,type,audioOnly)){java.util.concurrent.ConcurrentHashMap()}
    private fun historyKey(q:String,type:MediaType?,route:String,audioOnly:Boolean)=catalogueKey(q,type,audioOnly)+"|"+route
    private fun firstPage(q:String,type:MediaType?,route:String,entries:List<MovieCatalog.Entry>,next:String,audioOnly:Boolean) {
        val key=historyKey(q,type,route,audioOnly)
        pageEntries[key]=java.util.concurrent.ConcurrentHashMap.newKeySet<String>().also{set->set.addAll(entries.map{it.page})}
        pageCounts[key]=1;cursors(q,type,audioOnly)[route]=next
    }
    private val fetchGate=Semaphore(3)
    private suspend fun fetch(url: String): String {
        if (!allowNetwork()) throw CancellationException("Каталозите са спрени по време на гледане")
        return fetchGate.withPermit{get(url)}
    }
    companion object {
        const val TTL = 24 * 60 * 60_000L
        /** FilmiZT answers its search with 403 ("Search from the site's pages") unless the request comes from the site. */
        fun siteHeaders(url:String):Map<String,String> = try{
            val u=java.net.URI(url)
            if(u.host?.removePrefix("www.")=="filmizt.com" && u.path.startsWith("/search"))mapOf("Referer" to "https://filmizt.com/") else emptyMap()
        }catch(_:Exception){emptyMap()}
        private val titleYear=Regex("\\s*\\((19|20)\\d{2}\\)\\s*$")
        private fun movieName(s:String)=normalize(titleYear.replace(s,""))
        fun normalize(s: String): String = Normalizer.normalize(s, Normalizer.Form.NFKC)
            .lowercase(Locale.ROOT).replace('ѝ', 'и').replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()
        fun matches(m: Media, e: MovieCatalog.Entry, imdb: String? = null): Boolean {
            if (e.series != (m.type == MediaType.TV)) return false
            m.web?.let{return it.provider==e.provider && it.page.substringBefore('#')==e.page.substringBefore('#')}
            if (!imdb.isNullOrBlank() && e.imdb.isNotBlank() && imdb != e.imdb) return false
            if (e.tmdb.isNotBlank() && e.tmdb != "0") return e.tmdb == m.id.toString()
            if (!imdb.isNullOrBlank() && e.imdb.isNotBlank()) return imdb == e.imdb
            val year = if (e.series && e.seriesYear > 0) e.seriesYear else e.year
            if (year <= 0 || year.toString() != m.year) return false
            val key: (String)->String = if(e.series) MovieEpisodes::baseTitle else ::movieName
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
            val labels=if(e.series)setOf(MovieEpisodes.baseTitle(e.title),MovieEpisodes.baseTitle(e.original)) else setOf(movieName(e.title),movieName(e.original))
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
        m.web?.let{ref->candidates.addAll(records.filter{it.entry.provider==ref.provider && it.entry.page==ref.page})}
        candidates.addAll(snapshot.ids["$type:${m.id}"].orEmpty())
        if (!imdb.isNullOrBlank()) candidates.addAll(snapshot.ids["$type:$imdb"].orEmpty())
        val labels=if(m.type==MediaType.TV)setOf(MovieEpisodes.baseTitle(m.title),MovieEpisodes.baseTitle(m.originalTitle)) else setOf(movieName(m.title),movieName(m.originalTitle))
        for (name in labels - "") candidates.addAll(snapshot.names["$type:${m.year}:$name"].orEmpty())
        return candidates.distinctBy { it.entry.page }.filter {
            now - it.checkedAt in 0..TTL && it.entry.provider in providers && (!onlyBg || MovieSources.hasBgAudio(it.entry)) && matches(m, it.entry, imdb)
        }
    }
    // Catalogue badges describe a title; sources() separately checks the selected episode.
    fun has(m: Media): Boolean = find(m).isNotEmpty()
    fun count(type:MediaType?=null,audioOnly:Boolean=true): Int { val providers=enabled();val now=System.currentTimeMillis();return records.count {
        it.entry.provider in providers && (!audioOnly || MovieSources.hasBgAudio(it.entry)) && now-it.checkedAt in 0..TTL && (type==null || it.entry.series==(type==MediaType.TV))
    } }
    fun settingsChanged() { revision.value++ }

    private fun persist() {
        // The index is expendable: a full or cleared cache must not break browsing.
        try {
            directory.mkdirs()
            val tmp = File(directory, "index.tmp")
            tmp.writeText(encode(records))
            tmp.renameTo(indexFile)
        } catch (ex: java.io.IOException) { AppLog.w("web", "индексът не се записа", ex) }
    }
    private fun restoreLocked() {
        if (restored) return
        restored = true
        try { replaceRecords(decode(indexFile.readText())) } catch (ex: Exception) { AppLog.w("web", "индексът не се възстанови", ex) }
        revision.value++
    }
    suspend fun restore() = withContext(Dispatchers.IO) { lock.withLock { restoreLocked() } }

    suspend fun refresh(query: String = "", force: Boolean = false, type: MediaType? = null,audioOnly:Boolean=true,source:String?=null) = withContext(Dispatchers.IO) {
        lock.withLock {
            restoreLocked()
            if (!allowNetwork()) return@withLock
            val providers = enabled().filter{source==null || it==source}.toSet()
            val q = query.trim().take(120)
            activeCatalogue=Catalogue(q,type,audioOnly)
            val attemptKey = catalogueKey(q,type,audioOnly)+providers.sorted().joinToString(",")
            if (providers.isEmpty()) { status.value = "БГ уеб източниците са изключени от настройките на профила."; revision.value++; return@withLock }
            if (!force && System.currentTimeMillis() - (attempts[attemptKey] ?: 0L) < 30 * 60_000L) return@withLock
            status.value = if(audioOnly)"Проверяват се БГ аудио категориите…" else "Проверяват се уеб каталозите…"
            val gate = Semaphore(3)
            val updates = coroutineScope {
                (MovieSources.Provider.values().filter { it.name in providers }.map { p -> async {
                    gate.withPermit {
                        if (!allowNetwork()) return@withPermit null
                        try { withTimeout(9_000) { Pair(p.name, load(p, q, type,audioOnly)) } }
                        catch (e: CancellationException) { if (e !is TimeoutCancellationException) throw e else null }
                        catch (_: Exception) { null }
                    }
                } } + async {
                    gate.withPermit {
                        if (!allowNetwork()) return@withPermit null
                        try { withTimeout(9_000) { if ("TWO_K" in providers) Pair("TWO_K", loadTwoK(q,type,audioOnly)) else null } }
                        catch (e: CancellationException) { if (e !is TimeoutCancellationException) throw e else null }
                        catch (_: Exception) { null }
                    }
                }).awaitAll().filterNotNull()
            }
            val now = System.currentTimeMillis()
            val merged = records.filter { now - it.checkedAt in 0..TTL }.associateBy { it.entry.page }.toMutableMap()
            for ((_, entries) in updates) for (e in entries) putEntry(merged,e,now)
            replaceRecords(merged.values.sortedByDescending { it.checkedAt }.take(3000))
            if (updates.isNotEmpty()) {
                attempts[attemptKey] = now
                if (attempts.size > 64) attempts.remove(attempts.keys.first())
                persist()
            }
            revision.value++
            status.value = if (updates.isEmpty()) "Уеб източниците не отговориха. Наличността на БГ аудио е непроверена."
                else "${if(audioOnly)"БГ аудио" else "Уеб"} ${if(type==MediaType.TV)"сериали" else if(type==MediaType.MOVIE)"филми" else "версии"}: ${count(type,audioOnly)} · отговорили източници: ${updates.size}/${providers.size}. Проверката е частична."
        }
    }
    private suspend fun load(p: MovieSources.Provider, q: String, type: MediaType?,audioOnly:Boolean): List<MovieCatalog.Entry> {
        if (p == MovieSources.Provider.NINE) {
            val url = if (q.isBlank()) "https://cdn.filmi9.com/movies/?type=${if (type == MediaType.TV) "series" else "movie"}${if(audioOnly)"&isBgAudio=true" else ""}&page=1&limit=30"
                else "https://cdn.filmi9.com/movies/search/?term=${enc(q)}&page=1&limit=30"
            val parsed = parseNine(url,fetch(url))
            // Servers may cap a requested 30 records at 10. Probe until empty/repeated instead.
            firstPage(q,type,p.name,parsed.entries,parsed.next,audioOnly)
            val found=parsed.entries.toMutableList()
            // Filmi9's whole BG audio series list is a few pages (50 records in 2 pages): read it all, so a series on
            // page 2 (Friends, Lucifer, Vikings...) shows its BG audio outside the BG filter too. Movies stay paged.
            if(q.isBlank() && audioOnly && type==MediaType.TV){
                var next=parsed.next;var pages=1
                while(next.isNotBlank() && pages++<5){
                    val more=parseNine(next,fetch(next))
                    val fresh=more.entries.filter{e->found.none{it.page==e.page}}
                    if(fresh.isEmpty())break
                    found.addAll(fresh);next=more.next
                }
            }
            return found
        }
        // Some BG categories contain only movies. Scan the typed series catalogue too,
        // retaining those records as unverified candidates until their own detail/player proves audio.
        suspend fun read(url:String,route:String,seriesRoute:Boolean=false):List<MovieCatalog.Entry>{
            val parsed=MovieSources.parse(p,url,fetch(url))
            val next=parsed.next.takeIf{validNext(p,it,audioOnly,seriesRoute) && it!=url}.orEmpty()
            firstPage(q,type,route,parsed.entries,next,audioOnly)
            return parsed.entries.toList()
        }
        if(audioOnly && !p.hasBgCategory())return emptyList()
        if(type==MediaType.TV && !p.hasSeries())return emptyList()
        if(audioOnly && type==MediaType.TV)return coroutineScope{
            val routes=listOfNotNull(if(p.hasBgCategory())p.start("",MovieCatalog.BG_AUDIO) to p.name else null,
                (if(q.isBlank())p.seriesStart() else p.start(q,MovieCatalog.ALL)) to "${p.name}@series")
            val results=routes.map{(url,route)->async{
                try{withTimeout(6_000){read(url,route,route.endsWith("@series"))}}
                catch(ex:CancellationException){if(ex is TimeoutCancellationException)null else throw ex}catch(_:Exception){null}
            }}.awaitAll()
            if(results.all{it==null})throw java.io.IOException("Каталогът не отговори")
            results.filterNotNull().flatten().distinctBy{it.page}
        }
        val url=if(audioOnly)p.start("",MovieCatalog.BG_AUDIO)
            else if(q.isBlank() && type==MediaType.TV)p.seriesStart() else p.start(q,MovieCatalog.ALL)
        return read(url,p.name)
    }
    private fun validNext(p:MovieSources.Provider,url:String,audioOnly:Boolean,seriesRoute:Boolean):Boolean {
        if(!p.owns(url))return false
        if(!audioOnly)return true
        if(!seriesRoute)return MovieSources.inBgCategory(p,url)
        return try{
            val path=java.net.URI(url).path.trimEnd('/')
            val start=java.net.URI(p.seriesStart()).path.trimEnd('/')
            path==start || path.startsWith("$start/page/") || p==MovieSources.Provider.ZT && path.startsWith("$start-")
        }catch(_:Exception){false}
    }
    private fun parseNine(url:String,body:String):MovieSources.Page {
        val j=JSONObject(body)
        if(j.optJSONArray("data")==null && j.optJSONArray("results")==null)throw java.io.IOException("Невалиден отговор от каталога")
        if(!j.has("data"))j.put("data",j.optJSONArray("results")?:JSONArray())
        val doc=org.jsoup.nodes.Document.createShell(url);doc.body().text(j.toString())
        val parsed=MovieSources.parse(MovieSources.Provider.NINE,doc)
        val meta=j.optJSONObject("pagination")?:j.optJSONObject("meta")?.optJSONObject("pagination")?:j.optJSONObject("meta")?:j
        val page=Regex("[?&]page=(\\d+)").find(url)?.groupValues?.get(1)?.toIntOrNull()?:1
        val total=meta.optInt("totalPages",meta.optInt("total_pages",0))
        val nextField=listOf("hasNextPage","has_next_page","hasNext").firstOrNull{meta.has(it)}
        val declared=nextField?.let{meta.optBoolean(it)}
        parsed.next=if(declared==false || total>0 && page>=total)"" else
            if(parsed.entries.isNotEmpty() || declared==true || total>page)incrementPage(url) else ""
        return parsed
    }
    private suspend fun loadTwoK(q: String,type:MediaType?,audioOnly:Boolean): List<MovieCatalog.Entry> {
        if(type==MediaType.TV)return emptyList() // This adapter has no verified series/episode format.
        val base = "https://www.filmi2k.com"
        val tags = JSONArray(fetch("$base/wp-json/wp/v2/tags?slug=filmi-bg-audio&per_page=1"))
        val tag = tags.optJSONObject(0)?.optInt("id")?.takeIf{it>0}
        twoKAudioTag=tag
        if(audioOnly && tag==null)return emptyList()
        val url="$base/wp-json/wp/v2/posts?per_page=30&page=1"+(if(audioOnly)"&tags=$tag" else "")+(if(q.isBlank())"" else "&search=${enc(q)}")
        val posts = JSONArray(fetch(url))
        val entries=parseTwoK(posts,tag)
        firstPage(q,type,"TWO_K",entries,if(posts.length()>0)incrementPage(url) else "",audioOnly)
        return entries
    }
    private fun parseTwoK(posts: JSONArray, tag: Int?): List<MovieCatalog.Entry> {
        return (0 until posts.length()).mapNotNull { i ->
            val o = posts.getJSONObject(i)
            val ts = o.optJSONArray("tags") ?: JSONArray()
            val bg=tag!=null && (0 until ts.length()).any { ts.optInt(it) == tag }
            val url = o.optString("link")
            if (java.net.URI(url).host !in setOf("www.filmi2k.com", "filmi2k.com")) return@mapNotNull null
            val title = Jsoup.parse(o.optJSONObject("title")?.optString("rendered").orEmpty()).text()
            if(MovieEpisodes.season(title+" "+url)>=0 || Regex("/(seriali|series|serial)/").containsMatchIn(url))return@mapNotNull null
            val year = Regex("\\b(?:19|20)\\d{2}\\b").find(title)?.value?.toIntOrNull() ?: 0
            MovieCatalog.Entry().also { e ->
                e.provider = "TWO_K"; e.page = url; e.bgAudio = bg; e.audioVerified = bg; e.bgCatalog=bg; e.year = year
                val names = title.replace(Regex("\\s*\\(\\d{4}\\).*"), "").split(" / ")
                e.original = names.first().trim(); e.title = names.last().trim()
            }
        }
    }
    /** Gradually completes catalogue pages while browsing; cancelled when playback starts. */
    fun hasMore(type:MediaType?=activeCatalogue.type,query:String=activeCatalogue.query,audioOnly:Boolean=activeCatalogue.audioOnly,source:String?=null):Boolean =
        cursors(query,type,audioOnly).any{it.key.substringBefore('@') in enabled() && (source==null || it.key.substringBefore('@')==source) && it.value.isNotBlank()}

    suspend fun sweep(maxPages: Int = 7,type:MediaType?=activeCatalogue.type,query:String=activeCatalogue.query,audioOnly:Boolean=activeCatalogue.audioOnly,source:String?=null) = withContext(Dispatchers.IO) { advanceLock.withLock {
        val nextPages=cursors(query,type,audioOnly)
        val visited = HashSet<String>()
        val failed = HashSet<String>()
        var pages = 0
        while (allowNetwork() && pages < maxPages) {
            val pending = nextPages.entries.filter { it.key.substringBefore('@') in enabled() && (source==null || it.key.substringBefore('@')==source) && it.key !in failed && it.value.isNotBlank() && it.value !in visited }
                .minWithOrNull(compareBy<Map.Entry<String,String>>{pageCounts[historyKey(query,type,it.key,audioOnly)]?:0}.thenBy{it.key}) ?: break
            val route=pending.key;val provider = route.substringBefore('@'); val url = pending.value
            visited.add(url)
            try {
                val entries: List<MovieCatalog.Entry>
                val next: String
                if (provider == "TWO_K") {
                    // Reuse the tag-checked parser while retaining the current pagination query.
                    val text = withTimeout(8_000) { fetch(url) }
                    val posts = JSONArray(text)
                    val tag = Regex("[?&]tags=(\\d+)").find(url)?.groupValues?.get(1)?.toInt() ?: twoKAudioTag
                    entries = parseTwoK(posts, tag)
                    next = if (posts.length()>0) incrementPage(url) else ""
                } else {
                    val p = MovieSources.Provider.valueOf(provider)
                    if (!p.owns(url)) { nextPages[route]="";continue }
                    val body = withTimeout(8_000) { fetch(url) }
                    val parsed = if (provider == "NINE") {
                        parseNine(url,body)
                    } else MovieSources.parse(p,url,body)
                    entries=parsed.entries.toList()
                    next=if (provider=="NINE") parsed.next
                        else parsed.next.takeIf { it != url && validNext(p,it,audioOnly,route.endsWith("@series")) }.orEmpty()
                }
                lock.withLock {
                    val now=System.currentTimeMillis()
                    val merged=records.filter { now-it.checkedAt in 0..TTL }.associateBy { it.entry.page }.toMutableMap()
                    for(e in entries)putEntry(merged,e,now)
                    replaceRecords(merged.values.sortedByDescending { it.checkedAt }.take(3000))
                    val history=pageEntries.getOrPut(historyKey(query,type,route,audioOnly)){java.util.concurrent.ConcurrentHashMap.newKeySet()}
                    val fresh=entries.any{it.page !in history}
                    history.addAll(entries.map{it.page})
                    nextPages[route]=if(entries.isEmpty() || fresh)next else ""
                    val key=historyKey(query,type,route,audioOnly);pageCounts[key]=(pageCounts[key]?:0)+1
                    persist()
                    revision.value++
                    status.value="${if(audioOnly)"БГ аудио" else "Уеб"} записи: ${count(type,audioOnly)} · проверката продължава…"
                }
                pages++
                delay(250)
            } catch(e: CancellationException) { if(e !is TimeoutCancellationException)throw e else failed.add(route) }
            catch(ex:Exception) {
                if(ex is Http.HttpError && (ex.code==404 || provider=="TWO_K" && ex.code==400))nextPages[route]=""
                failed.add(route)
            }
        }
        if (pages > 0) status.value= "${if(audioOnly)"БГ аудио" else "Уеб"} записи: ${count(type,audioOnly)} · " +
            if(hasMore(type,query,audioOnly,source)) "проверката е частична; можеш да я продължиш." else "наличните страници са обходени; някои източници може да са непроверени."
    } }

    fun audioEntries(type:MediaType?=null):List<MovieCatalog.Entry> = catalogueEntries(type)
    fun catalogueEntries(type:MediaType?=null,audioOnly:Boolean=true,source:String?=null):List<MovieCatalog.Entry> {
        val providers=enabled();val now=System.currentTimeMillis()
        return records.filter{it.entry.provider in providers && (source==null || it.entry.provider==source) && (!audioOnly || MovieSources.hasBgAudio(it.entry)) && now-it.checkedAt in 0..TTL && (type==null || it.entry.series==(type==MediaType.TV))}
            .map{it.entry}
    }
    /** Candidates are never used for flags; audioCard verifies their own metadata first. */
    fun catalogueCandidates(type:MediaType?=null,audioOnly:Boolean=true,source:String?=null):List<MovieCatalog.Entry> =
        catalogueEntries(type,false,source).filter{!audioOnly || MovieSources.hasBgAudio(it) || it.series && !it.audioRejected}
    suspend fun audioCard(e:MovieCatalog.Entry,resolve:suspend(MovieCatalog.Entry)->Media?):Media? = catalogueCard(e,true,resolve)
    suspend fun catalogueCard(e:MovieCatalog.Entry,audioOnly:Boolean,resolve:suspend(MovieCatalog.Entry)->Media?):Media? {
        val deadline=System.nanoTime()+11_000_000_000L
        fun remaining(max:Long)=minOf(max,(deadline-System.nanoTime())/1_000_000).coerceAtLeast(1)
        if(audioOnly && !MovieSources.hasBgAudio(e)){
            if(e.audioRejected)return null
            // An unreadable page leaves the record unconfirmed; it is not a reason to hide it.
            val checked=try{withTimeout(8_000){MovieSources.detail(e,Jsoup.parse(fetch(e.page),e.page))}}
                catch(ex:CancellationException){if(ex is TimeoutCancellationException)null else throw ex}
                catch(ex:Exception){AppLog.w("web","страницата не се прочете: ${e.page}",ex);null}
            if(checked!=null)copyDetails(e,checked)
            if(!MovieSources.hasBgAudio(e) && e.provider=="ZENO" && !e.audioRejected){
                val ep=e.episodes.sortedWith(compareBy<MovieCatalog.Episode>{it.season}.thenBy{it.number}).firstOrNull()
                val page=ep?.page?:e.page
                val embed=try{withTimeoutOrNull(3_000){bgPlayers(page,fetch(page.substringBefore('#'))).firstOrNull()}}
                    catch(ex:CancellationException){throw ex}catch(ex:Exception){AppLog.w("web","ZenoPlay плеъри",ex);null}
                if(embed!=null && ep?.bgAudio!=false){
                    e.bgCatalog=true;e.bgAudio=true;e.audioVerified=true
                    if(ep!=null){e.bgEpisodeSeason=ep.season;e.bgEpisodeNumber=ep.number}
                }
            }
            // A series whose site does not say either way is still offered, as a site card marked "unconfirmed"
            // (never a TMDB card, which would look like a confirmed one). The site's own "no BG audio" hides it.
            if(!MovieSources.hasBgAudio(e))return if(e.series && !e.audioRejected)WebCatalogueMedia.card(e) else null
        }
        // Availability belongs to the source, not to the optional metadata match.
        var metadataAvailable=true
        suspend fun enrich():Media?=try{
            val result=withTimeoutOrNull(remaining(2_500)){resolve(e) to true}
            if(result==null)metadataAvailable=false
            result?.first
        }catch(ex:CancellationException){throw ex}catch(_:Exception){metadataAvailable=false;null}
        var enriched=enrich()
        if(enriched==null && metadataAvailable && e.detailSchema<2 && remaining(3_000)>500){
            // Exact IDs are often published only on the detail page. Try those before the site card.
            val checked=try{withTimeoutOrNull(remaining(3_000)){MovieSources.detail(e,Jsoup.parse(fetch(e.page),e.page))}}
                catch(ex:CancellationException){throw ex}catch(_:Exception){null}
            if(checked!=null){copyDetails(e,checked);enriched=enrich()}
        }
        if(audioOnly && !MovieSources.hasBgAudio(e))return null
        if(enriched!=null){
            e.tmdb=enriched.id.toString()
            if(e.series)e.seriesYear=enriched.year.toIntOrNull()?:e.seriesYear
            return enriched
        }
        return WebCatalogueMedia.card(e)
    }
    private fun copyDetails(e:MovieCatalog.Entry,checked:MovieCatalog.Entry){
        e.bgAudio=checked.bgAudio;e.audioVerified=checked.audioVerified;e.bgCatalog=checked.bgCatalog;e.audioRejected=checked.audioRejected
        e.title=checked.title;e.original=checked.original;e.poster=checked.poster;e.description=checked.description
        e.tmdb=checked.tmdb;e.imdb=checked.imdb;e.year=checked.year;e.season=checked.season;e.seriesYear=checked.seriesYear
        e.detailed=checked.detailed;e.detailSchema=checked.detailSchema;e.embed=checked.embed
        e.players.clear();e.players.addAll(checked.players)
        e.bgEpisodeSeason=checked.bgEpisodeSeason;e.bgEpisodeNumber=checked.bgEpisodeNumber
        e.episodes.clear();e.episodes.addAll(checked.episodes);e.seasons.clear();e.seasons.addAll(checked.seasons)
        e.genres.clear();e.genres.addAll(checked.genres)
    }

    suspend fun sourceEntry(m:Media,force:Boolean=false):MovieCatalog.Entry = withContext(Dispatchers.IO){
        restore()
        val ref=m.web?:throw IllegalArgumentException("Липсва уеб източник")
        if(ref.provider !in enabled())throw java.io.IOException("Този уеб източник е изключен от профила.")
        if(!WebCatalogueMedia.allowed(ref.provider,ref.page))throw java.io.IOException("Невалиден уеб източник.")
        val cached=records.firstOrNull{it.entry.provider==ref.provider && it.entry.page==ref.page}?.entry
        val entry=cached?.let{cloneEntry(it)}?:MovieCatalog.Entry().also{
            it.provider=ref.provider;it.page=ref.page;it.title=m.title;it.original=m.originalTitle
            it.year=m.year.toIntOrNull()?:0;it.series=m.type==MediaType.TV;it.poster=m.posterPath.orEmpty()
        }
        if(!force && entry.detailSchema>=(if(entry.series)2 else MovieSources.DETAIL_SCHEMA) && (!entry.series || entry.episodes.isNotEmpty()))return@withContext entry
        val detail=readDetail(entry)
        rememberDetails(listOf(detail));detail
    }
    suspend fun saveAudioCards(entries:List<MovieCatalog.Entry>)=withContext(Dispatchers.IO){
        rememberDetails(entries)
        if(activeCatalogue.audioOnly && entries.isNotEmpty() && !status.value.contains("не отговориха"))
            status.value="БГ аудио ${if(activeCatalogue.type==MediaType.TV)"сериали" else if(activeCatalogue.type==MediaType.MOVIE)"филми" else "версии"}: ${count(activeCatalogue.type)} · проверката е частична."
    }
    private fun incrementPage(url:String):String = Regex("([?&]page=)(\\d+)").replace(url) { it.groupValues[1]+(it.groupValues[2].toInt()+1) }
    suspend fun sources(m: Media, imdb: String?, season: Int, episode: Int,force:Boolean=false): List<WebSources.Source> = withContext(Dispatchers.IO) {
        if(m.type==MediaType.TV)return@withContext series(m,imdb,season,episode,force).sources
        if(m.web!=null){
            val e=sourceEntry(m,force);val found=movieSources(e);rememberDetails(listOf(e))
            if(found.isEmpty())throw java.io.IOException("Плеърите на ${WebSources.hostOf(e.page)} за това заглавие не работят в момента. Проблемът е в сайта - опитай друг източник.")
            return@withContext found
        }
        refresh(type = m.type,force=force)
        refresh(m.originalTitle.ifBlank { m.title },type=m.type,audioOnly=false,force=force)
        // Every candidate page is read and checked at the same time: one after another made each extra site add seconds.
        val gate=Semaphore(4)
        val checks=coroutineScope{ find(m, imdb, onlyBg=false).map { record -> async {
            gate.withPermit {
                val e = decodeEntry(encodeEntry(record.entry))
                try {
                    val checked=if(e.detailSchema>=MovieSources.DETAIL_SCHEMA && !force)e else readDetail(e)
                    if(!matches(m,checked,imdb))null else checked to movieSources(checked)
                } catch (ex: CancellationException) { throw ex } catch (ex: Exception) { AppLog.w("web", "уеб източник ${e.page}", ex); null }
            }
        } }.awaitAll().filterNotNull() }
        val checkedEntries=checks.map{it.first}
        val sources=checks.flatMap{it.second}.distinctBy { it.key }
        rememberDetails(checkedEntries)
        sources
    }
    private suspend fun readDetail(e:MovieCatalog.Entry):MovieCatalog.Entry = try{
        withTimeout(8_000){MovieSources.detail(e,Jsoup.parse(fetch(e.page),e.page))}
    }catch(ex:CancellationException){if(ex is TimeoutCancellationException)e else throw ex}catch(_:Exception){e}
    /**
     * One source per player the site offers for a movie, in the site's order, so a dead first player no longer ends
     * playback: the next one is a channel button (or the menu) away. Players the host reports as gone are left out;
     * when that leaves nothing, the site has no working player for this title and none is offered.
     */
    private suspend fun movieSources(e:MovieCatalog.Entry):List<WebSources.Source> {
        val zeno:List<String> = if(e.provider!="ZENO")emptyList() else try{withTimeoutOrNull(5_000){bgPlayers(e.page,fetch(e.page))}.orEmpty()}
            catch(ex:CancellationException){throw ex}catch(ex:Exception){AppLog.w("web","ZenoPlay плеъри",ex);emptyList()}
        if(zeno.isNotEmpty() && !e.audioRejected){e.bgCatalog=true;e.bgAudio=true;e.audioVerified=true}
        val bg=MovieSources.hasBgAudio(e);val host=WebSources.hostOf(e.page)
        val listed=(zeno+e.players).distinct()
        if(listed.isEmpty())return listOf(BgPlayerPresentation.source(host,e.page,e.embed,bg))
        val playable:List<String> = try{sitePlayers(e.page,listed)}catch(ex:CancellationException){throw ex}
            catch(ex:Exception){AppLog.w("web","плеърите на $host не са проверени",ex);listed}
        if(playable.isEmpty()){AppLog.d("web","$host: нито един от ${listed.size} плеъра не работи за ${e.page}");return emptyList()}
        return playable.map{BgPlayerPresentation.source(host,e.page,it,bg,player=WebSources.hostOf(it))}.distinctBy{it.key}
    }
    suspend fun series(m:Media,imdb:String?,season:Int,episode:Int,force:Boolean=false):WebSeriesResolver.Result = withContext(Dispatchers.IO) {
        restore()
        if(force)seriesResolver.invalidate()
        if(m.web!=null){
            val entry=sourceEntry(m,force)
            val result=seriesResolver.resolve(m,imdb,listOf(entry),season,episode)
            rememberDetails(result.entries)
            return@withContext if(result.sources.isEmpty() && (season<0 || episode<=0))result.copy(sources=listOf(
                BgPlayerPresentation.source(WebSources.hostOf(entry.page),entry.page,entry.embed,MovieSources.hasBgAudio(entry)))) else result
        }
        refresh(type=MediaType.TV,force=force)
        refresh(m.originalTitle.ifBlank{m.title},type=MediaType.TV,audioOnly=false,force=force)
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
            for(e in entries)putEntry(merged,e,now)
            replaceRecords(merged.values.sortedByDescending{it.checkedAt}.take(3000));persist();revision.value++
        }
    }
    private fun putEntry(merged:MutableMap<String,Record>,e:MovieCatalog.Entry,now:Long) {
        val old=merged[e.page]?.entry?.takeIf{it.provider==e.provider}
        // An ordinary listing cannot add or erase evidence obtained from a BG category.
        if(old!=null && !e.bgCatalog && !e.audioRejected){
            e.bgCatalog=old.bgCatalog;e.bgAudio=old.bgAudio;e.audioVerified=old.audioVerified;e.audioRejected=old.audioRejected
            e.bgEpisodeSeason=old.bgEpisodeSeason;e.bgEpisodeNumber=old.bgEpisodeNumber
        }
        if(old!=null){
            if(e.tmdb.isBlank())e.tmdb=old.tmdb
            if(e.imdb.isBlank())e.imdb=old.imdb
            if(e.seriesYear<=0)e.seriesYear=old.seriesYear
        }
        merged[e.page]=Record(e,now)
    }
    private fun cloneEntry(e:MovieCatalog.Entry)=decodeEntry(encodeEntry(e)).also{
        it.episodes.addAll(e.episodes);it.seasons.addAll(e.seasons);it.genres.addAll(e.genres)
    }
    private fun encodeEntry(e: MovieCatalog.Entry) = JSONObject().put("provider", e.provider).put("page", e.page)
        .put("title", e.title).put("original", e.original).put("year", e.year).put("tmdb", e.tmdb)
        .put("imdb", e.imdb).put("series", e.series).put("bgAudio", e.bgAudio).put("embed", e.embed)
        .put("audioVerified",e.audioVerified).put("season",e.season).put("seriesYear",e.seriesYear)
        .put("bgCatalog",e.bgCatalog).put("audioRejected",e.audioRejected).put("detailed",e.detailed)
        .put("detailSchema",e.detailSchema).put("poster",e.poster).put("description",e.description).put("genres",JSONArray(e.genres.toList()))
        .put("bgEpisodeSeason",e.bgEpisodeSeason).put("bgEpisodeNumber",e.bgEpisodeNumber).put("players",JSONArray(e.players.toList()))
    private fun decodeEntry(j: JSONObject) = MovieCatalog.Entry().also {
        it.provider=j.getString("provider");it.page=j.getString("page");it.title=j.getString("title")
        it.original=j.optString("original");it.year=j.optInt("year");it.tmdb=j.optString("tmdb")
        it.imdb=j.optString("imdb");it.series=j.optBoolean("series");it.bgAudio=j.optBoolean("bgAudio")
        it.embed=j.optString("embed")
        it.audioVerified=j.optBoolean("audioVerified");it.season=j.optInt("season",-1);it.seriesYear=j.optInt("seriesYear")
        it.bgCatalog=j.optBoolean("bgCatalog");it.audioRejected=j.optBoolean("audioRejected");it.detailed=j.optBoolean("detailed")
        it.detailSchema=j.optInt("detailSchema");it.poster=j.optString("poster");it.description=j.optString("description")
        it.bgEpisodeSeason=j.optInt("bgEpisodeSeason",-1);it.bgEpisodeNumber=j.optInt("bgEpisodeNumber")
        j.optJSONArray("genres")?.let{a->for(n in 0 until a.length())it.genres.add(a.optString(n))}
        j.optJSONArray("players")?.let{a->for(n in 0 until a.length())it.players.add(a.optString(n))}
        MovieSources.cleanIds(it)
    }
    private fun encode(r: List<Record>) = JSONArray(r.map { JSONObject().put("entry", encodeEntry(it.entry)).put("checkedAt", it.checkedAt) }).toString()
    private fun decode(s: String): List<Record> {
        val a = JSONArray(s)
        return (0 until minOf(a.length(), 3000)).map { i -> val j=a.getJSONObject(i);Record(decodeEntry(j.getJSONObject("entry")), j.getLong("checkedAt")) }
            .filter { System.currentTimeMillis() - it.checkedAt in 0..TTL }
    }
}
