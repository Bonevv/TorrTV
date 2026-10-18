package app.torrtv.state

import android.content.Context
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.torrtv.data.Media
import app.torrtv.data.MediaType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.json.JSONArray
import org.json.JSONObject
import java.util.TimeZone

/**
 * Правилата за "Избор на седмицата": кои заглавия са допустими и как се подбират 10 от тях за дадена седмица.
 * Без Android и без мрежа - един и същ вход винаги дава един и същ избор.
 */
object WeeklyLogic {
    const val COUNT = 10
    private const val ANIMATION = 16
    private const val DOCUMENTARY = 99
    private const val DAY_MS = 86_400_000L
    private val MONTHS = arrayOf(
        "януари", "февруари", "март", "април", "май", "юни",
        "юли", "август", "септември", "октомври", "ноември", "декември"
    )

    /** Номер на седмицата (понеделник - неделя) по местно време. Седмица 0 започва на 5 януари 1970 г. */
    fun weekIndex(nowMs: Long, zoneOffsetMs: Long): Long =
        ((nowMs + zoneOffsetMs).floorDiv(DAY_MS) - 4L).floorDiv(7L)

    /** Без аниме (анимация на японски) и без документални. */
    fun eligible(m: Media): Boolean =
        m.posterPath != null && DOCUMENTARY !in m.genreIds && !(ANIMATION in m.genreIds && m.originalLanguage == "ja")

    /**
     * Подбира [count] заглавия за седмицата [week]. Кандидатите се разбъркват с фиксирано семе и се раздават
     * по [count] за седмица, така че съседни седмици да не повтарят заглавия, докато стигнат кандидатите.
     * [salt] разделя филмите от сериите. Резултатът е подреден по оценка (№1 е с най-висока).
     */
    fun choose(pool: List<Media>, week: Long, salt: Int, count: Int = COUNT): List<Media> {
        val base = pool.filter(::eligible).distinctBy { it.key }.sortedBy { it.id }
        if (base.size <= count) return base.sortedByDescending { it.rating }
        val slots = base.size / count
        val cycle = week.floorDiv(slots.toLong())
        val slot = week.mod(slots.toLong()).toInt()
        val order = base.shuffled(java.util.Random(cycle * 7919L + salt))
        return order.subList(slot * count, slot * count + count).sortedByDescending { it.rating }
    }

    /** Например "20 - 26 октомври" или "28 септември - 4 октомври". */
    fun range(week: Long): String {
        val start = week * 7 + 4
        val a = civil(start)
        val b = civil(start + 6)
        return if (a.first == b.first) "${a.second} – ${b.second} ${MONTHS[b.first - 1]}"
        else "${a.second} ${MONTHS[a.first - 1]} – ${b.second} ${MONTHS[b.first - 1]}"
    }

    /** Ден от епохата -> (месец 1..12, ден от месеца). */
    private fun civil(epochDay: Long): Pair<Int, Int> {
        val z = epochDay + 719468
        val era = z.floorDiv(146097L)
        val doe = z - era * 146097
        val yoe = (doe - doe / 1460 + doe / 36524 - doe / 146096) / 365
        val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
        val mp = (5 * doy + 2) / 153
        val day = (doy - (153 * mp + 2) / 5 + 1).toInt()
        val month = (if (mp < 10) mp + 3 else mp - 9).toInt()
        return month to day
    }
}

/** Един ред "Избор на седмицата" (филми или сериали) на началния екран. */
class WeeklyRow(val type: MediaType, val title: String) {
    val items = mutableStateListOf<Media>()
    val listState = LazyListState()

    var loading by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var range by mutableStateOf("")
        private set

    private val movie get() = type == MediaType.MOVIE
    private fun sp() = Graph.app.getSharedPreferences("weekly", Context.MODE_PRIVATE)

    suspend fun load() {
        if (loading || items.isNotEmpty()) return
        loading = true
        error = null
        try {
            val now = System.currentTimeMillis()
            val week = WeeklyLogic.weekIndex(now, TimeZone.getDefault().getOffset(now).toLong())
            range = WeeklyLogic.range(week)
            val lang = Graph.prefs.metaLang
            // Изборът се пази до края на седмицата, за да не се променя от ден на ден.
            val list = read(week, lang) ?: WeeklyLogic.choose(fetchPool(), week, type.ordinal).also {
                if (it.isNotEmpty()) write(week, lang, it)
            }
            if (list.isEmpty()) error = "Няма достатъчно заглавия."
            items.clear()
            items.addAll(list)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = e.message ?: e.javaClass.simpleName
        } finally {
            loading = false
        }
    }

    suspend fun retry() {
        error = null
        load()
    }

    /** Най-гледаните заглавия с висока оценка и много гласове - от тях се подбира. */
    private suspend fun fetchPool(): List<Media> = coroutineScope {
        (1..PAGES).map { page ->
            async {
                Graph.tmdb.discover(
                    type, null, page, "popularity.desc",
                    if (movie) 2500 else 1200, if (movie) 7.2 else 7.6, false, 99
                ).items
            }
        }.awaitAll().flatten()
    }

    private fun read(week: Long, lang: String): List<Media>? = try {
        val o = JSONObject(sp().getString(type.path, "") ?: "")
        if (o.optLong("week", -1) != week || o.optString("lang") != lang) null
        else {
            val a = o.getJSONArray("items")
            val out = ArrayList<Media>()
            for (i in 0 until a.length()) Library.mediaFrom(a.getJSONObject(i))?.let { out.add(it) }
            if (out.size >= WeeklyLogic.COUNT / 2) out else null
        }
    } catch (_: Exception) {
        null
    }

    private fun write(week: Long, lang: String, list: List<Media>) {
        try {
            val a = JSONArray()
            list.forEach { a.put(Library.mediaTo(it)) }
            val o = JSONObject().put("week", week).put("lang", lang).put("items", a)
            sp().edit().putString(type.path, o.toString()).apply()
        } catch (_: Exception) {
        }
    }

    private companion object {
        const val PAGES = 5
    }
}
