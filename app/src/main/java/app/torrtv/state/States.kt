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

class BrowseState(val type: MediaType) {
    var genre by mutableStateOf<Int?>(null)
    var sort by mutableStateOf(0) // 0 популярни, 1 най-нови, 2 най-високо оценени
    var anime by mutableStateOf(false)
    var paged by mutableStateOf(make())
    val gridState = LazyGridState()
    var focusKey: String? = null

    private fun make(): Paged<Media> = Paged(200, { p -> fetchPage(p) }) { it.key }

    private suspend fun fetchPage(p: Int): Page<Media> {
        val movie = type == MediaType.MOVIE
        return when (sort) {
            1 -> Graph.tmdb.discover(type, genre, p, if (movie) "primary_release_date.desc" else "first_air_date.desc", 40, 0.0, anime)
            2 -> Graph.tmdb.discover(type, genre, p, "vote_average.desc", if (anime) 250 else 600, 0.0, anime)
            else -> Graph.tmdb.discover(type, genre, p, "popularity.desc", 60, 0.0, anime)
        }
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

    fun onQuery(q: String) {
        query = q
        job?.cancel()
        val t = q.trim()
        if (t.length < 2) {
            paged = null
            return
        }
        job = Graph.scope.launch {
            delay(450)
            val p = Paged<Media>(120, { page -> Graph.tmdb.search(t, page) }) { it.key }
            paged = p
            p.loadNext()
        }
    }
}

class DiceState {
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

class DetailState(val type: MediaType, val id: Int) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var torrentJob: Job? = null
    private var subJob: Job? = null

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
                loadTorrents()
                loadSubs()
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

    fun loadTorrents() {
        val q = searchQuery() ?: return
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
        try {
            episodes = Graph.tmdb.episodes(id, season)
            if (episodes.none { it.number == episode }) episode = episodes.firstOrNull()?.number ?: 1
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            episodes = emptyList()
        }
    }

    fun selectSeason(n: Int) {
        if (n == season) return
        season = n
        episode = 1
        scope.launch {
            loadEpisodes()
            loadTorrents()
            loadSubs()
        }
    }

    fun selectEpisode(n: Int) {
        if (n == episode) return
        episode = n
        loadTorrents()
        loadSubs()
    }

    /** Субтитри, подредени за конкретен торент (по езици и съвпадение с релийза). */
    fun rankedSubs(forTorrent: TorrentResult?): List<SubtitleResult> = rankedByHint(forTorrent?.title)

    private fun rankedByHint(hint: String?): List<SubtitleResult> {
        val order = Langs.choices.map { it.first }.filter { it in Graph.prefs.subLangs }.ifEmpty { listOf("bg", "en") }
        // Ако за този сериал вече е избран субтитър на ръка, подобен му се предлага първи и за другите епизоди.
        val prefer = detail?.media?.key?.let { SubChoice.memory.get(it) }
        return SubRank.rank(subResults, order, hint, prefer)
    }

    /** Етикет на следващия епизод („S01E04 · Име“) или null: филм, последен епизод или още неизлязъл. */
    fun nextLabel(): String? {
        val d = detail ?: return null
        if (type != MediaType.TV) return null
        val n = NextEpisode.after(episodes.map { it.number }, episode)
        if (n != null) {
            val ep = episodes.first { it.number == n }
            return if (NextEpisode.aired(ep.airDate, NextEpisode.today())) NextEpisode.label(season, n, ep.name) else null
        }
        val ns = NextEpisode.nextSeason(d.seasons.map { it.number }, season) ?: return null
        return NextEpisode.label(ns, 1, "")
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
        return playRequest(t, rankedSubs(t).take(1), "builtin", 0L)
    }

    /** Чака търсенето; щом има добър кандидат и са минали 7 секунди, не чака останалите сайтове. */
    private suspend fun awaitTorrent(prev: TorrentResult?): TorrentResult? {
        val t0 = System.currentTimeMillis()
        while (System.currentTimeMillis() - t0 < 30_000) {
            val c = NextEpisode.choose(prev, torrents)
            if (!searching) return c
            if (c != null && System.currentTimeMillis() - t0 > 7_000) return c
            delay(300)
        }
        return NextEpisode.choose(prev, torrents)
    }

    /** Най-доброто съвпадение на намерените субтитри с този торент (за белега в списъка). */
    fun subFit(t: TorrentResult): Fit {
        val langs = Langs.choices.map { it.first }.filter { it in Graph.prefs.subLangs }.ifEmpty { listOf("bg", "en") }
        return SubRank.bestFit(subResults.filter { it.lang in langs }, t.title)
    }

    fun playRequest(t: TorrentResult, subs: List<SubtitleResult>, player: String, resumeMs: Long = 0L): StreamController.Req =
        buildReq(t, t.magnet, subs, player, resumeMs, rankedSubs(t), t.title)

    /** Заявка за гледане от уеб сайт: без торент; субтитрите се избират сами (най-подходящ за всеки от двата езика). */
    fun webRequest(): StreamController.Req {
        // Потоците от сайтове най-често са WEB-DL / WEBRip - субтитрите за такова издание са първи.
        val ranked = rankedByHint("WEB-DL")
        val subs = ranked.distinctBy { it.lang }.take(2)
        return buildReq(null, "", subs, "builtin", 0L, ranked, null)
    }

    /** Адресите на уеб сайтовете за избрания филм / епизод (празно, ако са изключени). */
    fun webSources(): List<app.torrtv.web.WebSources.Source> {
        val d = detail ?: return emptyList()
        if (!Graph.prefs.webEnabled) return emptyList()
        return app.torrtv.web.WebSources.sources(Graph.prefs, type == MediaType.TV, id, d.imdbId, season, episode)
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
            next = if (t != null) nextLabel()?.let { label -> StreamController.NextEp(label) { nextRequest(t) } } else null
        )
    }
}
