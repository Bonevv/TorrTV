package app.torrtv.data.subs

import android.util.LruCache
import app.torrtv.core.Prefs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

class SubtitleAggregator(private val prefs: Prefs) {

    val providers: List<SubtitleProvider> = listOf(
        OpenSubtitles(prefs), Subdl(prefs), SubSource(prefs), SabBz()
    )

    data class Update(val provider: String, val results: List<SubtitleResult>, val error: String?)

    private class Cached(val at: Long, val updates: List<Update>)

    private val cache = LruCache<String, Cached>(16)

    fun search(q: SubtitleQuery): Flow<Update> = channelFlow {
        val hit = synchronized(cache) { cache.get(q.cacheKey) }
        if (hit != null && System.currentTimeMillis() - hit.at < 10 * 60_000) {
            hit.updates.forEach { send(it) }
            return@channelFlow
        }
        val active = providers.filter { it.configured() }
        val collected = ArrayList<Update>()
        val jobs = active.map { p ->
            launch {
                val u = try {
                    val r = withTimeoutOrNull(25_000) { p.search(q) }
                    if (r == null) Update(p.name, emptyList(), "не отговори навреме")
                    else Update(p.name, r.take(25), null)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Update(p.name, emptyList(), subErrorText(e))
                }
                synchronized(collected) { collected.add(u) }
                send(u)
            }
        }
        jobs.joinAll()
        if (collected.isNotEmpty() && collected.none { it.error != null }) {
            synchronized(cache) { cache.put(q.cacheKey, Cached(System.currentTimeMillis(), collected.toList())) }
        }
    }
}
