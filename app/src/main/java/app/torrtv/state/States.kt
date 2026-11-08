package app.torrtv.state

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.torrtv.data.Detail
import app.torrtv.data.Episode
import app.torrtv.data.Genres
import app.torrtv.data.Media
import app.torrtv.data.MediaType
import app.torrtv.data.Page
import app.torrtv.data.Tmdb
import app.torrtv.data.subs.Fit
import app.torrtv.data.subs.Langs
import app.torrtv.data.subs.SubRank
import app.torrtv.data.subs.SubtitleQuery
import app.torrtv.data.subs.SubtitleResult
import app.torrtv.data.torrents.SearchQuery
import app.torrtv.data.torrents.TorrentAggregator
import app.torrtv.data.torrents.TorrentResult
import app.torrtv.torrent.StreamController
import app.torrtv.update.Updater
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.max
import kotlin.math.min

/**
 * Странициран списък: зарежда следваща страница, когато потребителят стигне края.
 * Има таван на броя елементи, за да не расте паметта.
 */
class Paged<T>(
    private val maxItems: Int,
    private val fetch: suspend (Int) -> Page<T>,
    private val keyOf: (T) -> Any
) {
    val items = mutableStateListOf<T>()
    var order:Comparator<T>? = null

    var loading by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var exhausted by mutableStateOf(false)
        private set
    var started by mutableStateOf(false)
        private set

    private var page = 0
    private val seen = HashSet<Any>()

    suspend fun loadNext() {
        if (loading || exhausted) return
        loading = true
        started = true
        error = null
        try {
            val p = fetch(page + 1)
            page = p.page
            for (x in p.items) if (seen.add(keyOf(x))) items.add(x)
            order?.let{cmp->val sorted=items.sortedWith(cmp);if(sorted!=items.toList()){items.clear();items.addAll(sorted)}}
            if (p.page >= p.totalPages || items.size >= maxItems) exhausted = true
        } catch (e: CancellationException) {
            // Екранът е излязъл по средата на зареждането. Ако още няма нищо, следващото показване да почне отначало,
            // иначе редът остава празен и въртящ се завинаги.
            if (items.isEmpty()) started = false
            throw e
        } catch (e: Exception) {
            error = e.message ?: e.javaClass.simpleName
        } finally {
            loading = false
        }
    }

    suspend fun retry() {
        error = null
        loadNext()
    }
}

class HomeState {
    class Row(val id: String, val title: String, fetch: suspend (Int) -> Page<Media>) {
        val data = Paged(160, fetch) { it.key }
        val listState = LazyListState()
    }

    private fun genreRow(id: Int, title: String) = Row("g$id", title) { p ->
        Graph.tmdb.discover(MediaType.MOVIE, id, p, "popularity.desc", 300, 0.0)
    }

    /** Първите два реда на началото: избор на седмицата (10 филма и 10 сериала). */
    val picks: List<WeeklyRow> = listOf(
        WeeklyRow(MediaType.MOVIE, "Избор на седмицата · Филми"),
        WeeklyRow(MediaType.TV, "Избор на седмицата · Сериали")
    )

    val rows: List<Row> = listOf(
        Row("trend", "В тренда тази седмица") { p -> Graph.tmdb.trending(p) },
        Row("pop_m", "Популярни филми") { p -> Graph.tmdb.list(MediaType.MOVIE, "popular", p) },
        Row("pop_t", "Популярни сериали") { p -> Graph.tmdb.list(MediaType.TV, "popular", p) },
        Row("soon", "Скоро по кината") { p -> Graph.tmdb.list(MediaType.MOVIE, "upcoming", p) },
        Row("top_m", "Най-високо оценени филми") { p -> Graph.tmdb.list(MediaType.MOVIE, "top_rated", p) },
        Row("top_t", "Най-добрите сериали") { p -> Graph.tmdb.list(MediaType.TV, "top_rated", p) },
        genreRow(28, "Екшън"),
        genreRow(35, "Комедия"),
        genreRow(878, "Фантастика"),
        genreRow(27, "Ужаси"),
        genreRow(53, "Трилър"),
        genreRow(16, "Анимация"),
        genreRow(18, "Драма"),
        genreRow(12, "Приключенски"),
        genreRow(80, "Криминални"),
        genreRow(99, "Документални")
    )

    val listState = LazyListState()
    var heroTarget by mutableStateOf<Media?>(null)
    var focusKey: String? = null
    var update by mutableStateOf<Updater.Release?>(null)

    /**
     * Зарежда редовете малко преди да се стигне до тях. Иначе от последния видим ред няма накъде да се слезе -
     * редът отдолу още е празен и не може да получи фокус. Върви в общия обхват на приложението, за да не се
     * прекъсва, когато някой ред излезе от екрана. [lastVisibleRow] е номерът на най-долния видим ред (-1 = няма).
     */
    fun warm(lastVisibleRow: Int) {
        val count = RowWarmUp.count(lastVisibleRow, rows.size)
        Graph.scope.launch { for (w in picks) if (w.items.isEmpty() && !w.loading && w.error == null) w.load() }
        Graph.scope.launch {
            for (i in 0 until count) {
                val d = rows[i].data
                if (d.items.isEmpty() && !d.loading && d.error == null && !d.exhausted) d.loadNext()
            }
        }
    }

    private var updateChecked = false

    suspend fun checkUpdate() {
        if (updateChecked) return
        updateChecked = true
        try {
            val r = Graph.updater.latest()
            if (r != null && Graph.updater.isNewer(r.tag)) update = r
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // без интернет или без издание - пропускаме тихо
        }
    }
}

/** Колко реда да са готови: всички до най-долния видим и още [AHEAD] под него. */
object RowWarmUp {
    const val AHEAD = 3
    fun count(lastVisibleRow: Int, total: Int): Int = minOf(total, maxOf(lastVisibleRow, -1) + 1 + AHEAD)
}

/** Платформите за избор: взимат се веднъж от TMDB за България; докато не се вземат (или при грешка) - кратък стандартен списък. */
object PlatformCatalog {
    var list by mutableStateOf(app.torrtv.data.Platforms.fallback)
    private var loaded = false

    suspend fun ensure() {
        if (loaded) return
        try {
            val all = Graph.tmdb.watchProviders(MediaType.MOVIE) + Graph.tmdb.watchProviders(MediaType.TV)
            val r = app.torrtv.data.Platforms.resolve(all)
            if (r.isNotEmpty()) {
                list = r
                loaded = true
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
        }
    }
}

class BrowseState(initialType: MediaType, val forceAnime: Boolean = false) {
    var type by mutableStateOf(initialType)
    var genre by mutableStateOf<Int?>(null)
    var sort by mutableStateOf(0) // 0 популярни, 1 най-нови, 2 най-високо оценени
    var anime by mutableStateOf(forceAnime)
    var provider by mutableStateOf<String?>(null)
    var year by mutableStateOf<Int?>(null)
        private set
    var bgOnly by mutableStateOf(false)
        private set
    var paged by mutableStateOf(make())
    val gridState = LazyGridState()
    var focusKey: String? = null

    private fun make(): Paged<Media> {
        val mediaType = type
        val movie = mediaType == MediaType.MOVIE
        val pv = provider
        val selectedGenre = genre; val selectedAnime = anime; val selectedYear = year; val onlyBg = bgOnly
        val order = sort
        if(onlyBg)return bgCatalogue(mediaType,selectedGenre,selectedYear,selectedAnime,pv,order)
        return Paged(200, { requested ->
            if (onlyBg) Graph.bgWeb.refresh(type = mediaType)
            var page = requested
            var total = requested
            val found = ArrayList<Media>()
            var rounds = 0
            do {
                // Explicit filters must not silently discard matching titles with few votes.
                val sortBy = when (order) { 1 -> if (movie) "primary_release_date.desc" else "first_air_date.desc"; 2 -> "vote_average.desc"; else -> "popularity.desc" }
                val votes = if (selectedYear != null || onlyBg) 0 else when (order) { 1 -> if (pv != null) 10 else 40; 2 -> if (pv != null) 150 else if (selectedAnime) 250 else 600; else -> if (pv != null) 20 else 60 }
                val result = Graph.tmdb.discover(mediaType, selectedGenre, page, sortBy, votes, 0.0, selectedAnime, null, pv, selectedYear)
                total = result.totalPages
                found.addAll(if (onlyBg) result.items.filter { Graph.bgWeb.has(it) } else result.items)
                if (!onlyBg || found.size >= 20 || page >= total || ++rounds >= 4) break
                page++
            } while (true)
            Page(found.distinctBy { it.key }, page, total)
        }) { it.key }
    }

    fun chooseYear(value: Int?) { year = value; paged = make() }
    fun chooseBgOnly(value: Boolean) { bgOnly = value; paged = make() }
    fun clearFilters() {
        year = null; bgOnly = false
        paged = make()
    }

    fun pickProvider(id: String?) {
        if (provider == id) return
        provider = id
        paged = make()
    }

    /** Само за раздел "Аниме": смяна между филми и сериали (жанрът започва отначало). */
    fun changeType(t: MediaType) {
        if (t == type) return
        type = t
        genre = null
        paged = make()
    }

    fun apply(newGenre: Int?, newSort: Int, newAnime: Boolean = anime) {
        genre = newGenre
        sort = newSort
        anime = newAnime
        paged = make()
    }
}

class SearchState {
    var query by mutableStateOf("")
    var paged by mutableStateOf<Paged<Media>?>(null)
    val gridState = LazyGridState()
    var focusKey: String? = null
    private var job: Job? = null
    private var generation = 0

    /** Последните търсения (до 8) - за бързи бутони, когато полето е празно. */
    val recent = mutableStateListOf<String>()

    /** Расте всеки път, когато от телефона дойде ново търсене (екранът го ползва, за да се отвори търсенето). */
    var remoteStamp by mutableStateOf(0)

    init {
        recent.addAll(RecentSearches.decode(Graph.prefs.recentSearches))
    }

    var actorMode by mutableStateOf(false)
        private set
    var actor by mutableStateOf<app.torrtv.data.Actor?>(null)
        private set
    val actors = mutableStateListOf<app.torrtv.data.Actor>()
    var actorLoading by mutableStateOf(false)
        private set
    var actorError by mutableStateOf<String?>(null)
        private set
    var year by mutableStateOf<Int?>(null)
        private set
    var bgOnly by mutableStateOf(false)
        private set

    fun onQuery(q: String, delayMs: Long = 450L) {
        query = q
        actor = null
        restart(delayMs)
    }
    fun chooseActorMode(value: Boolean) { actorMode = value; actor = null; restart(0) }
    fun pickActor(value: app.torrtv.data.Actor) { actor = value; restart(0) }
    fun chooseYear(value: Int?) { year = value; restart(0) }
    fun chooseBgOnly(value: Boolean) { bgOnly = value; restart(0) }
    fun clearFilters() { year = null; bgOnly = false; restart(0) }
    fun refreshBg() {
        job?.cancel()
        job = Graph.scope.launch {
            try { Graph.bgWeb.refresh(force = true); Graph.bgWeb.sweep() }
            catch (ex: CancellationException) { throw ex } catch (_: Exception) { }
            restart(0)
        }
    }

    private fun restart(delayMs: Long) {
        val ticket = ++generation
        job?.cancel()
        paged = null
        actors.clear()
        actorError = null
        actorLoading = false
        val q = query.trim()
        val selected = actor
        val selectedYear = year
        val onlyBg = bgOnly
        val people = actorMode
        if (q.length < 2 && (people || selectedYear == null && !onlyBg)) return
        job = Graph.scope.launch {
            try {
                if (delayMs > 0) delay(delayMs)
                if (people && selected == null) {
                    actorLoading = true
                    val result = Graph.tmdb.actors(q)
                    if (ticket == generation) actors.addAll(result)
                    return@launch
                }
                if (!onlyBg && selected == null && q.length >= 2 && Graph.prefs.webEnabled) launch {
                    Graph.bgWeb.refresh(q)
                }
                val credits = selected?.let { Graph.tmdb.actorCredits(it.id) }
                    ?.filter { selectedYear == null || it.year == selectedYear.toString() }
                if(onlyBg && credits==null){
                    if(ticket!=generation)return@launch
                    val bg=bgCatalogue(null,null,selectedYear,false,null,0,q)
                    paged=bg;bg.loadNext();return@launch
                }
                val p = Paged<Media>(200, { requested ->
                    if (onlyBg) {
                        Graph.bgWeb.refresh()
                        if (selected == null && q.length >= 2) Graph.bgWeb.refresh(q)
                        if (Graph.bgWeb.count() == 0 && Graph.bgWeb.status.value.contains("не отговориха"))
                            throw java.io.IOException(Graph.bgWeb.status.value)
                    }
                    if (credits != null) {
                        val filtered = if (onlyBg) credits.filter { Graph.bgWeb.has(it) } else credits
                        app.torrtv.data.Page(filtered.drop((requested - 1) * 20).take(20), requested, maxOf(1, (filtered.size + 19) / 20))
                    } else {
                        val found = ArrayList<Media>()
                        var page = requested
                        var total = requested
                        var rounds = 0
                        do {
                            val result = Graph.tmdb.search(q, page, selectedYear)
                            total = result.totalPages
                            found.addAll(if (onlyBg) result.items.filter { Graph.bgWeb.has(it) } else result.items)
                            rounds++
                            if (!onlyBg || found.size >= 20 || page >= total || rounds >= 8) break
                            page++
                        } while (true)
                        app.torrtv.data.Page(found.distinctBy { it.key }, page, total)
                    }
                }) { it.key }
                if (ticket != generation) return@launch
                paged = p
                p.loadNext()
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { if (ticket == generation) actorError = e.message ?: "Търсенето не успя" }
            finally { if (ticket == generation) actorLoading = false }
        }
    }

    /** Запомня търсенето в списъка с последните (вика се при потвърждаване и при отваряне на резултат). */
    fun commit(q: String = query) {
        val l = RecentSearches.add(recent.toList(), q)
        if (l == recent.toList()) return
        recent.clear()
        recent.addAll(l)
        Graph.prefs.recentSearches = RecentSearches.encode(l)
    }

    fun clearRecent() {
        recent.clear()
        Graph.prefs.recentSearches = ""
    }

    /** Избрано от последните търсения: търси веднага. */
    fun pick(q: String) {
        onQuery(q, 0L)
        commit(q)
    }

    /** Дошло от страницата на телефона. */
    fun fromPhone(q: String) {
        val t = RecentSearches.clean(q) ?: return
        onQuery(t, 0L)
        commit(t)
        remoteStamp++
    }
}

class DiceState {
    /** „Зарче за двама“ (виж PairDiceState): включва се от бутона „За двама“. */
    var pairMode by mutableStateOf(false)
    val pair = PairDiceState()

    var type by mutableStateOf(MediaType.MOVIE)
    var genre by mutableStateOf<Int?>(null) // null = "Изненада ме"
    var minRating by mutableStateOf(0)
    var results by mutableStateOf<List<Media>>(emptyList())
    var rolling by mutableStateOf(false)
    var face by mutableStateOf(5)
    var pickedGenre by mutableStateOf<String?>(null)
    var error by mutableStateOf<String?>(null)
    var rollId by mutableStateOf(0)
    var started = false

    private val seen = HashSet<String>()
    private val totalPages = HashMap<String, Int>()
    private var job: Job? = null

    fun changeType(t: MediaType) {
        if (t == type) return
        type = t
        genre = null
        roll()
    }

    fun changeGenre(g: Int?) {
        genre = g
        roll()
    }

    fun changeRating(r: Int) {
        minRating = r
        roll()
    }

    fun roll() {
        job?.cancel()
        rolling = true
        error = null
        results = emptyList()
        started = true
        job = Graph.scope.launch {
            val t0 = System.currentTimeMillis()
            try {
                val chosen = genre ?: Genres.forType(type).random().id
                pickedGenre = Genres.forType(type).firstOrNull { it.id == chosen }?.name
                val key = "${type.path}|$chosen|$minRating"
                val found = ArrayList<Media>()
                var attempts = 0
                while (found.size < 5 && attempts < 6) {
                    attempts++
                    var total = totalPages[key]
                    if (total == null) {
                        val first = Graph.tmdb.discover(type, chosen, 1, "popularity.desc", 300, minRating.toDouble())
                        total = max(1, min(first.totalPages, 25))
                        totalPages[key] = total
                    }
                    val page = (1..total).random()
                    val pg = Graph.tmdb.discover(type, chosen, page, "popularity.desc", 300, minRating.toDouble())
                    val cand = pg.items.filter { c ->
                        c.key !in seen && c.overview.isNotBlank() && found.none { it.key == c.key }
                    }.shuffled()
                    for (m in cand) {
                        if (found.size >= 5) break
                        found.add(m)
                        seen.add(m.key)
                    }
                }
                if (found.size < 5) seen.clear() // жанрът е почти изчерпан - позволяваме повторения
                val elapsed = System.currentTimeMillis() - t0
                if (elapsed < 1500) delay(1500 - elapsed)
                face = (1..6).random()
                results = found
                rollId++
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = e.message ?: e.javaClass.simpleName
            } finally {
                rolling = false
            }
        }
    }
}

/**
 * [deferTorrents] - отворено е от „Продължи гледане“: торентът вече е известен, затова търсенето в сайтовете се
 * отлага, докато не потрябва ([ensureTorrents]). Така продължаването не пуска излишно 5-8 заявки.
 */
class DetailState(val type: MediaType, val id: Int, private val deferTorrents: Boolean = false) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var torrentJob: Job? = null
    private var subJob: Job? = null

    var apiWebSources by mutableStateOf<List<app.torrtv.web.WebSources.Source>>(emptyList())
    var bgWebSources by mutableStateOf<List<app.torrtv.web.WebSources.Source>>(emptyList())
        private set
    private var webJob: Job? = null
    private var webApiJob: Job? = null
    private var episodeJob: Job? = null
    var webSeries by mutableStateOf(app.torrtv.web.WebSeriesResolver.Result())
        private set
    var webLoading by mutableStateOf(false)
        private set
    var episodeLoading by mutableStateOf(false)
        private set
    private fun clearWeb() {
        webJob?.cancel();webApiJob?.cancel()
        bgWebSources=emptyList();apiWebSources=emptyList()
    }
    private fun loadWeb() {
        clearWeb()
        val d = detail ?: return
        val s = season; val e = episode
        webJob = scope.launch {
            webLoading=true
            try { if (Graph.prefs.webEnabled) {
                if(type==MediaType.TV){
                    val result=Graph.bgWeb.series(d.media,d.imdbId,s,e)
                    if(s==season && e==episode){webSeries=result;bgWebSources=result.sources}
                }else{
                    val result=Graph.bgWeb.sources(d.media,d.imdbId,s,e)
                    if(s==season && e==episode)bgWebSources=result
                }
            } }
            catch (ex: CancellationException) { throw ex }
            catch (_: Exception) { }
            finally { if(s==season && e==episode)webLoading=false }
        }
        webApiJob = scope.launch {
            try {
                val urls = app.torrtv.web.WebSources.templates(Graph.prefs, type == MediaType.TV).map {
                    it.replace("{tmdb}", id.toString()).replace("{season}", s.toString()).replace("{episode}", e.toString())
                }
                val result = if (Graph.prefs.webEnabled) app.torrtv.web.WebApiClient.resolve(urls) else emptyList()
                if (s == season && e == episode) { apiWebSources = result }
            } catch (ex: CancellationException) { throw ex } catch (_: Exception) { }
        }
    }

    var detail by mutableStateOf<Detail?>(null)
    var error by mutableStateOf<String?>(null)

    var season by mutableStateOf(1)
    var episode by mutableStateOf(1)
    var episodes by mutableStateOf<List<Episode>>(emptyList())

    var torrents by mutableStateOf<List<TorrentResult>>(emptyList())
    var searching by mutableStateOf(false)
    val torrentStatus = mutableStateMapOf<String, String>()
    private val torrentUpdates = LinkedHashMap<String, TorrentAggregator.Update>()

    var subResults by mutableStateOf<List<SubtitleResult>>(emptyList())
    var subSearching by mutableStateOf(false)
    val subStatus = mutableStateMapOf<String, String>()
    private val subUpdates = LinkedHashMap<String, List<SubtitleResult>>()

    val recs = Paged<Media>(100, { p -> Graph.tmdb.recommendations(type, id, p) }) { it.key }

    fun cancel() {
        scope.cancel()
    }

    fun start() {
        scope.launch {
            try {
                val d = Graph.tmdb.detail(type, id)
                detail = d
                if (type == MediaType.TV) {
                    season = d.seasons.firstOrNull()?.number ?: 1
                    // Ако вече е гледан - отваря се точно на епизода, който се продължава.
                    Library.progressOf(d.media.key)?.let { pr ->
                        if (pr.season > 0 && d.seasons.any { it.number == pr.season }) {
                            season = pr.season
                            episode = pr.episode
                        }
                    }
                    loadEpisodes()
                }
                if (!deferTorrents) loadTorrents()
                loadSubs()
                loadWeb()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = e.message ?: e.javaClass.simpleName
            }
        }
    }

    fun searchQuery(): SearchQuery? {
        val d = detail ?: return null
        val m = d.media
        val alt = listOf(m.originalTitle, m.title).filter { it.isNotBlank() }.distinct()
        return SearchQuery(
            type = type,
            title = d.englishTitle.ifBlank { m.originalTitle.ifBlank { m.title } },
            altTitles = alt,
            year = m.year,
            imdbId = d.imdbId,
            season = if (type == MediaType.TV) season else null,
            episode = if (type == MediaType.TV) episode else null,
            anime = d.isAnime
        )
    }

    private fun subQuery(): SubtitleQuery? {
        val d = detail ?: return null
        val pref = Graph.prefs.subLangs
        val langs = Langs.choices.map { it.first }.filter { it in pref }.ifEmpty { listOf("bg", "en") }
        val m = d.media
        return SubtitleQuery(
            type = type,
            title = d.englishTitle.ifBlank { m.originalTitle.ifBlank { m.title } },
            altTitles = listOf(m.originalTitle, m.title).filter { it.isNotBlank() }.distinct(),
            year = m.year,
            imdbId = d.imdbId,
            tmdbId = id,
            season = if (type == MediaType.TV) season else null,
            episode = if (type == MediaType.TV) episode else null,
            langs = langs
        )
    }

    private var torrentsRequested = false

    /** Пуска търсенето на торенти, ако още не е пускано (при „Продължи“ то се отлага). */
    fun ensureTorrents() {
        if (!torrentsRequested && detail != null) loadTorrents()
    }

    fun loadTorrents() {
        val q = searchQuery() ?: return
        torrentsRequested = true
        torrentJob?.cancel()
        torrentUpdates.clear()
        torrentStatus.clear()
        torrents = emptyList()
        searching = true
        torrentJob = scope.launch {
            try {
                Graph.torrents.search(q).collect { u ->
                    torrentUpdates[u.source] = u
                    torrentStatus[u.source] = if (u.error != null) "✗ ${u.error}" else "${u.results.size}"
                    torrents = TorrentAggregator.merge(torrentUpdates.values)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                torrentStatus["Търсене"] = "✗ ${e.message}"
            } finally {
                searching = false
            }
        }
    }

    fun loadSubs() {
        val q = subQuery() ?: return
        subJob?.cancel()
        subUpdates.clear()
        subStatus.clear()
        subResults = emptyList()
        subSearching = true
        subJob = scope.launch {
            try {
                Graph.subs.search(q).collect { u ->
                    subUpdates[u.provider] = u.results
                    subStatus[u.provider] = if (u.error != null) "✗ ${u.error}" else "${u.results.size}"
                    subResults = subUpdates.values.flatten()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                subStatus["Търсене"] = "✗ ${e.message}"
            } finally {
                subSearching = false
            }
        }
    }

    private suspend fun loadEpisodes() {
        val selectedSeason=season
        episodeLoading=true
        try {
            val result=Graph.tmdb.episodes(id,selectedSeason)
            if(selectedSeason!=season)return
            episodes=result
            if (episodes.none { it.number == episode } && webSeries.episodes.none{it.season==season && it.number==episode}) episode = episodes.firstOrNull()?.number ?: 1
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if(selectedSeason==season)episodes = emptyList()
        }
        finally {if(selectedSeason==season)episodeLoading=false}
    }

    fun selectSeason(n: Int) {
        if (n == season) return
        season = n
        episode = 1
        episodeJob?.cancel();clearWeb();episodes=emptyList()
        torrentJob?.cancel();subJob?.cancel();torrents=emptyList();subResults=emptyList()
        episodeJob=scope.launch {
            loadEpisodes()
            if(season!=n)return@launch
            loadTorrents()
            loadSubs()
            loadWeb()
        }
    }

    fun selectEpisode(n: Int) {
        if (n == episode) return
        episode = n
        loadTorrents()
        loadSubs()
        loadWeb()
    }

    /** Субтитри, подредени за конкретен торент (по езици и съвпадение с релийза). */
    fun rankedSubs(forTorrent: TorrentResult?): List<SubtitleResult> = rankedByHint(forTorrent?.title)

    private fun rankedByHint(hint: String?): List<SubtitleResult> {
        val order = Langs.choices.map { it.first }.filter { it in Graph.prefs.subLangs }.ifEmpty { listOf("bg", "en") }
        // Ако за този сериал вече е избран субтитър на ръка, подобен му се предлага първи и за другите епизоди.
        val prefer = detail?.media?.key?.let { SubChoice.memory.get(it) }
        return SubRank.rank(subResults, order, hint, prefer)
    }

    class NextTarget(val season: Int, val episode: Int, val label: String)

    /** Следващият епизод (сезон, епизод, етикет „S01E04 · Име“) или null: филм, последен епизод или още неизлязъл. */
    fun nextTarget(): NextTarget? {
        val d = detail ?: return null
        if (type != MediaType.TV) return null
        val n = NextEpisode.after(episodes.map { it.number }, episode)
        if (n != null) {
            val ep = episodes.first { it.number == n }
            return if (NextEpisode.aired(ep.airDate, NextEpisode.today())) NextTarget(season, n, NextEpisode.label(season, n, ep.name)) else null
        }
        val ns = NextEpisode.nextSeason(d.seasons.map { it.number }, season) ?: return null
        return NextTarget(ns, 1, NextEpisode.label(ns, 1, ""))
    }


    /**
     * Заявка за следващия епизод: премества избора на него (така и екранът се връща на правилния епизод),
     * търси торент (по възможност като предишния) и субтитри. Връща null, ако няма такъв епизод или торент.
     */
    suspend fun nextRequest(prev: TorrentResult?): StreamController.Req? {
        val d = detail ?: return null
        if (type != MediaType.TV) return null
        val n = NextEpisode.after(episodes.map { it.number }, episode)
        if (n != null) {
            episode = n
        } else {
            season = NextEpisode.nextSeason(d.seasons.map { it.number }, season) ?: return null
            episode = 0
            loadEpisodes() // ако епизод 0 го няма, взема първия
            if (episodes.isEmpty()) episode = 1
        }
        loadTorrents()
        loadSubs()
        val t = awaitTorrent(prev) ?: return null
        withTimeoutOrNull(10_000) { subJob?.join() }
        return playRequest(t, if (Graph.prefs.subAuto) rankedSubs(t).take(1) else emptyList(), "builtin", 0L)
    }

    /** Чака търсенето; щом има добър кандидат и са минали 7 секунди, не чака останалите сайтове. */
    private suspend fun awaitTorrent(prev: TorrentResult?): TorrentResult? {
        val t0 = System.currentTimeMillis()
        while (System.currentTimeMillis() - t0 < 30_000) {
            val c = NextEpisode.choose(prev, torrents, Graph.prefs.player == "builtin")
            if (!searching) return c
            if (c != null && System.currentTimeMillis() - t0 > 7_000) return c
            delay(300)
        }
        return NextEpisode.choose(prev, torrents, Graph.prefs.player == "builtin")
    }

    /** Най-доброто съвпадение на намерените субтитри с този торент (за белега в списъка). */
    fun subFit(t: TorrentResult): Fit {
        val langs = Langs.choices.map { it.first }.filter { it in Graph.prefs.subLangs }.ifEmpty { listOf("bg", "en") }
        // Само онези, които наистина се показват в списъка на торента - иначе белегът обещава субтитри, които не се виждат.
        return SubRank.bestFit(rankedSubs(t).take(14).filter { it.lang in langs }, t.title)
    }

    fun playRequest(t: TorrentResult, subs: List<SubtitleResult>, player: String, resumeMs: Long = 0L): StreamController.Req =
        buildReq(t, t.magnet, subs, player, resumeMs, rankedSubs(t), t.title)

    /** Заявка за гледане от уеб сайт: без торент; субтитрите се избират сами (най-подходящ за всеки от двата езика). */
    fun webRequest(): StreamController.Req {
        // Потоците от сайтове най-често са WEB-DL / WEBRip - субтитрите за такова издание са първи.
        val ranked = rankedByHint("WEB-DL")
        val subs = if (Graph.prefs.subAuto) ranked.distinctBy { it.lang }.take(2) else emptyList()
        return buildReq(null, "", subs, "builtin", 0L, ranked, null)
    }

    /** Адресите на уеб сайтовете за избрания филм / епизод (празно, ако са изключени). */
    fun webSources(): List<app.torrtv.web.WebSources.Source> {
        val d = detail ?: return emptyList()
        if (!Graph.prefs.webEnabled) return emptyList()
        return (bgWebSources + apiWebSources + app.torrtv.web.WebSources.sources(Graph.prefs, type == MediaType.TV, id, d.imdbId, season, episode)).distinctBy { it.key }
    }

    private fun buildReq(
        t: TorrentResult?, magnet: String, subs: List<SubtitleResult>, player: String, resumeMs: Long,
        all: List<SubtitleResult>, fitTitle: String?
    ): StreamController.Req {
        val d = detail
        val title = d?.media?.title ?: ""
        val epName = if (type == MediaType.TV) {
            NextEpisode.label(season, episode, episodes.firstOrNull { it.number == episode }?.name.orEmpty())
        } else ""
        val runtimeMin = if (type == MediaType.TV) {
            episodes.firstOrNull { it.number == episode }?.runtime?.takeIf { it > 0 } ?: (d?.runtime ?: 0)
        } else d?.runtime ?: 0
        return StreamController.Req(
            magnet = magnet,
            title = title,
            subtitle = epName,
            season = if (type == MediaType.TV) season else null,
            episode = if (type == MediaType.TV) episode else null,
            subs = subs,
            subQuery = subQuery(),
            player = player,
            backdrop = Tmdb.img(d?.media?.backdropPath, "w780"),
            media = d?.media,
            torrent = t,
            resumeMs = resumeMs,
            allSubs = all.take(40),
            fitTitle = fitTitle,
            next = if (t != null) nextTarget()?.let { nt -> StreamController.NextEp(nt.label, nt.season, nt.episode) { nextRequest(t) } } else null,
            runtimeMin = runtimeMin
        )
    }
}
