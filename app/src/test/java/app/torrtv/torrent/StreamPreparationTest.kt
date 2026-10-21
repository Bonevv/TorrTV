package app.torrtv.torrent

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class StreamPreparationTest {
    @Test fun cancelPreventsPlaybackEvenWhenPreparationIgnoresCancellation() = runTest {
        val pending = StreamPreparation(this)
        val events = mutableListOf<String>()
        pending.start(
            prepare = { withContext(NonCancellable) { delay(100); "episode" } },
            ready = { events.add(it) }, failed = { events.add("error") }, finished = { events.add("finished") }
        )
        runCurrent()
        pending.cancel()
        advanceUntilIdle()
        assertTrue(events.isEmpty())
    }

    @Test fun replacementIgnoresOldResultAndItsCleanup() = runTest {
        val pending = StreamPreparation(this)
        val events = mutableListOf<String>()
        pending.start({ withContext(NonCancellable) { delay(100); "old" } },
            { events.add(it) }, { fail("old failure") }, { events.add("old finished") })
        runCurrent()
        pending.start({ delay(200); "new" },
            { events.add(it) }, { fail("new failure") }, { events.add("new finished") })
        advanceUntilIdle()
        assertEquals(listOf("new", "new finished"), events)
    }

    @Test fun startingPlaybackMayCancelPreparationWithoutCancellingTheCaller() = runTest {
        val pending = StreamPreparation(this)
        var started = false
        pending.start({ delay(10); 1 }, { pending.cancel(); started = true }, { fail(it.message) }, {})
        advanceUntilIdle()
        assertTrue(started)
        assertTrue(coroutineContext.isActive)
    }

    @Test fun failureIsReportedAndPreparationFinishes() = runTest {
        val pending = StreamPreparation(this)
        val events = mutableListOf<String>()
        pending.start<Int>({ throw IllegalStateException("failure") }, { fail("Unexpected playback") },
            { events.add(it.message!!) }, { events.add("finished") })
        advanceUntilIdle()
        assertEquals(listOf("failure", "finished"), events)
    }
}
