package app.torrtv.state

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.torrtv.data.Media
import app.torrtv.data.MediaType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

/** Чистата част на „Зарче за двама“: избор на заглавия и хвърляне на монетата. */
object PairDice {
    const val PER_SIDE = 2

    /** До [n] различни заглавия от кандидатите - без вече взетите ([exclude]) и без такива без описание. */
    fun pick(cands: List<Media>, exclude: Set<String>, n: Int = PER_SIDE, rnd: Random = Random.Default): List<Media> {
        val seen = HashSet(exclude)
        val out = ArrayList<Media>()
        for (m in cands.filter { it.overview.isNotBlank() }.shuffled(rnd)) {
            if (out.size >= n) break
            if (seen.add(m.key)) out.add(m)
        }
        return out
    }

    /** Монетата: 0 = печели първият, 1 = вторият. Ако едната страна е без заглавия, печели другата. */
    fun winner(countA: Int, countB: Int, rnd: Random = Random.Default): Int = when {
        countA == 0 && countB > 0 -> 1
        countB == 0 && countA > 0 -> 0
        else -> rnd.nextInt(2)
    }
}

/**
 * „Зарче за двама“: първият и вторият избират по един жанр, зарът подава по две заглавия от всеки жанр,
 * а монета решава чий жанр печели.
 */
class PairDiceState {
    var type by mutableStateOf(MediaType.MOVIE)
    var genreA by mutableStateOf<Int?>(null)
    var genreB by mutableStateOf<Int?>(null)
    var resultsA by mutableStateOf<List<Media>>(emptyList())
    var resultsB by mutableStateOf<List<Media>>(emptyList())
    var rolling by mutableStateOf(false)
    var coinSpinning by mutableStateOf(false)

    /** -1 = още няма решение, 0 = печели първият, 1 = вторият. */
    var winner by mutableStateOf(-1)
    var face by mutableStateOf(3)
    var error by mutableStateOf<String?>(null)
    var rollId by mutableStateOf(0)

    private val totalPages = HashMap<String, Int>()
    private var job: Job? = null

    val ready: Boolean get() = genreA != null && genreB != null

    private fun clearResults() {
        job?.cancel()
        rolling = false
        coinSpinning = false
        resultsA = emptyList()
        resultsB = emptyList()
        winner = -1
        error = null
    }

    fun changeType(t: MediaType) {
        if (t == type) return
        type = t
        genreA = null
        genreB = null
        clearResults()
    }

    fun pickA(g: Int) {
        genreA = g
        clearResults()
    }

    fun pickB(g: Int) {
        genreB = g
        clearResults()
    }

    fun roll() {
        val a = genreA ?: return
        val b = genreB ?: return
        clearResults()
        rolling = true
        job = Graph.scope.launch {
            val t0 = System.currentTimeMillis()
            try {
                val used = HashSet<String>()
                val ra = fetch(a, used)
                val rb = fetch(b, used)
                if (ra.isEmpty() && rb.isEmpty()) throw java.io.IOException("Не се намериха заглавия. Пробвайте други жанрове.")
                val elapsed = System.currentTimeMillis() - t0
                if (elapsed < 1500) delay(1500 - elapsed)
                face = (1..6).random()
                resultsA = ra
                resultsB = rb
                rollId++
                rolling = false
                coinSpinning = true
                delay(1300)
                winner = PairDice.winner(ra.size, rb.size)
                coinSpinning = false
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = e.message ?: e.javaClass.simpleName
            } finally {
                rolling = false
                coinSpinning = false
            }
        }
    }

    /** Две заглавия от жанра: случайна страница сред най-популярните, до няколко опита. */
    private suspend fun fetch(genre: Int, used: MutableSet<String>): List<Media> {
        val key = "${type.path}|$genre"
        var total = totalPages[key]
        if (total == null) {
            val first = Graph.tmdb.discover(type, genre, 1, "popularity.desc", 300, 6.0)
            total = max(1, min(first.totalPages, 15))
            totalPages[key] = total
        }
        val got = ArrayList<Media>()
        var attempts = 0
        while (got.size < PairDice.PER_SIDE && attempts < 4) {
            attempts++
            val pg = Graph.tmdb.discover(type, genre, (1..total).random(), "popularity.desc", 300, 6.0)
            for (m in PairDice.pick(pg.items, used, PairDice.PER_SIDE - got.size)) {
                got.add(m)
                used.add(m.key)
            }
        }
        return got
    }
}
