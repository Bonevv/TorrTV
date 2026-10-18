package app.torrtv.data.torrents

import android.util.LruCache
import app.torrtv.core.Prefs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

class TorrentAggregator(private val prefs: Prefs) {

    private val builtIn: List<TorrentSource> = listOf(
        Apibay(prefs), Yts(prefs), Eztv(prefs), Nyaa(prefs), Knaben(), TorrentsCsv(),
        StremioAddon("torrentsdb", "TorrentsDB", "https://torrentsdb.com"),
        StremioAddon("torrentio", "Torrentio", "https://torrentio.strem.fun")
    )

    /** Вградените плюс свои Stremio addon-и от страницата за телефона. */
    val sources: List<TorrentSource>
        get() = builtIn + StremioAddon.custom(prefs, setOf("torrentsdb.com", "torrentio.strem.fun"))

    data class Update(val source: String, val results: List<TorrentResult>, val error: String?)

    private class Cached(val at: Long, val updates: List<Update>)

    private val cache = LruCache<String, Cached>(16)

    /** Търси паралелно във всички включени източници; всеки източник връща резултат веднага щом е готов. */
    fun search(q: SearchQuery): Flow<Update> = channelFlow {
        val hit = synchronized(cache) { cache.get(q.cacheKey) }
        if (hit != null && System.currentTimeMillis() - hit.at < 10 * 60_000) {
            hit.updates.forEach { send(it) }
            return@channelFlow
        }
        val active = sources.filter { prefs.sourceEnabled(it.id) && it.supports(q) }
        val collected = ArrayList<Update>()
        val jobs = active.map { src ->
            launch {
                val u = try {
                    val r = withTimeoutOrNull(28_000) { src.search(q) }
                    if (r == null) Update(src.name, emptyList(), "не отговори навреме")
                    else Update(src.name, r, null)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Update(src.name, emptyList(), e.message ?: e.javaClass.simpleName)
                }
                synchronized(collected) { collected.add(u) }
                send(u)
            }
        }
        jobs.joinAll()
        // В кеша влизат само пълни търсения, без грешки.
        if (collected.isNotEmpty() && collected.none { it.error != null }) {
            synchronized(cache) { cache.put(q.cacheKey, Cached(System.currentTimeMillis(), collected.toList())) }
        }
    }

    companion object {
        /** Събира резултатите от всички източници: без дубликати, най-много сийдъри най-отпред. */
        fun merge(all: Collection<Update>): List<TorrentResult> {
            val best = HashMap<String, TorrentResult>()
            val sites = HashMap<String, MutableSet<String>>()
            for (u in all) for (r in u.results) {
                sites.getOrPut(r.hash) { LinkedHashSet() }.add(r.source)
                val old = best[r.hash]
                if (old == null || r.seeders > old.seeders) best[r.hash] = r
            }
            return best.values.map { r ->
                val others = sites[r.hash].orEmpty() - r.source
                if (others.isEmpty()) r else r.copy(alsoOn = others)
            }.sortedWith(
                compareBy<TorrentResult> { if (it.isCam) 1 else 0 }.thenByDescending { it.seeders }
            )
        }

        /** Оценка на торент: качество, сийдъри и разумен размер. По-голямо = по-добре. */
        fun score(r: TorrentResult): Int {
            val q = when (r.quality) {
                "1080p" -> 40
                "720p" -> 28
                "4K" -> 18
                "480p" -> 5
                else -> 0
            }
            val sizeOk = if (r.size in 1..(25L * 1024 * 1024 * 1024)) 0 else -20
            return minOf(r.seeders, 120) + q + sizeOk
        }
    }
}
