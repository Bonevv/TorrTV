package app.torrtv.state

import app.torrtv.core.Magnets
import app.torrtv.core.TrackerCheck
import app.torrtv.data.MediaType
import app.torrtv.data.subs.SubtitleQuery
import app.torrtv.data.torrents.SearchQuery
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull

/** Бърз тест на всички източници - показва кой работи и каква е грешката на тези, които не работят. */
object Diagnostics {

    suspend fun run(report: (String) -> Unit) {
        val movie = SearchQuery(MediaType.MOVIE, "Inception", emptyList(), "2010", "tt1375666", null, null, false)
        val tv = SearchQuery(MediaType.TV, "Game of Thrones", emptyList(), "2011", "tt0944947", 1, 1, false)
        val anime = SearchQuery(MediaType.TV, "Attack on Titan", emptyList(), "2013", "tt2560140", 1, 1, true)

        report("— Торент източници —")
        for (src in Graph.torrents.sources) {
            if (!Graph.prefs.sourceEnabled(src.id)) {
                report("• ${src.name}: изключен")
                continue
            }
            val q = listOf(movie, tv, anime).firstOrNull { src.supports(it) }
            if (q == null) {
                report("• ${src.name}: пропуснат")
                continue
            }
            val label = when (q) {
                movie -> "филм"
                tv -> "сериал"
                else -> "аниме"
            }
            try {
                val r = withTimeoutOrNull(30_000) { src.search(q) }
                if (r == null) report("⏱ ${src.name}: не отговори навреме")
                else report("✓ ${src.name}: ${r.size} резултата ($label, най-много ${r.maxOfOrNull { it.seeders } ?: 0} сийдъра)")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                report("✗ ${src.name}: ${e.message}")
            }
        }

        report("— Субтитри —")
        val sq = SubtitleQuery(MediaType.MOVIE, "Inception", emptyList(), "2010", "tt1375666", 27205, null, null, listOf("en", "bg"))
        for (p in Graph.subs.providers) {
            if (!p.configured()) {
                report("• ${p.name}: няма ключ")
                continue
            }
            try {
                val r = withTimeoutOrNull(30_000) { p.search(sq) }
                if (r == null) report("⏱ ${p.name}: не отговори навреме")
                else report("✓ ${p.name}: ${r.size} субтитъра (BG: ${r.count { it.lang == "bg" }})")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                report("✗ ${p.name}: ${e.message}")
            }
        }

        report("— Тракери —")
        checkTrackers(report)

        report("— TMDB —")
        try {
            val p = Graph.tmdb.list(MediaType.MOVIE, "popular", 1)
            report("✓ TMDB: ${p.items.size} филма")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            report("✗ TMDB: ${e.message}")
        }
    }

    /** Проверява вградените тракери, запомня мъртвите и ги изключва от линковете (виж Magnets.ensureTrackers). */
    suspend fun checkTrackers(report: (String) -> Unit) {
        val res = TrackerCheck.checkAll(Magnets.trackers)
        for (r in res) {
            val n = TrackerCheck.shortName(r.url)
            report(if (r.ok) "✓ $n: ${r.ms} ms" else "✗ $n: ${r.note}")
        }
        val dead = res.filter { !it.ok }.map { it.url }.toSet()
        Graph.prefs.saveDeadTrackers(dead)
        Magnets.setDead(dead)
        when {
            dead.isEmpty() -> report("Всички тракери отговарят.")
            dead.size >= res.size -> report("Нито един не отговори - най-вероятно няма UDP връзка. Нищо не се изключва.")
            else -> report("Мъртвите (${dead.size}) се прескачат при пускане. Нова проверка ги връща, ако са оживели.")
        }
    }
}
