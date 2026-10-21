package app.torrtv.torrent

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlin.coroutines.coroutineContext

/** Подготовката се отменя отделно от активния поток. Използва се от главната нишка. */
internal class StreamPreparation(private val scope: CoroutineScope) {
    private var job: Job? = null
    private var generation = 0L

    fun <T> start(prepare: suspend () -> T, ready: (T) -> Unit, failed: (Throwable) -> Unit, finished: () -> Unit) {
        cancel()
        val ticket = generation
        val pending = scope.launch(start = CoroutineStart.LAZY) {
            try {
                val result = prepare()
                coroutineContext.ensureActive()
                if (ticket == generation) {
                    // ready може да стартира нов поток и да извика cancel(); не отменяме сами себе си.
                    job = null
                    ready(result)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                if (ticket == generation) failed(e)
            } finally {
                if (ticket == generation) {
                    job = null
                    finished()
                }
            }
        }
        job = pending
        pending.start()
    }

    fun cancel() {
        generation++
        job?.cancel()
        job = null
    }
}
