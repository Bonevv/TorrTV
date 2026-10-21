package app.torrtv.data.subs

import app.torrtv.data.MediaType
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SubtitleStoreTest {
    @get:Rule val tmp = TemporaryFolder()
    private val result = SubtitleResult("test", "Test", "bg", "Film." + "Long.Release.Name.".repeat(8), "1", 0, false, false)
    private val query = SubtitleQuery(MediaType.MOVIE, "Film", emptyList(), "2024", "tt123", 123, null, null, listOf("bg"))

    private class Provider : SubtitleProvider {
        override val id = "test"
        override val name = "Test"
        var calls = 0
        var text = "1\n00:00:01,000 --> 00:00:03,000\nSubtitle\n"
        override fun configured() = true
        override suspend fun search(q: SubtitleQuery) = error("Cache must not search")
        override suspend fun download(r: SubtitleResult, q: SubtitleQuery): List<SubFile> {
            calls++
            delay(20)
            return listOf(SubFile("sub.srt", text.toByteArray()))
        }
    }

    @Test fun reuseSurvivesNewStoreAndPreservesFullLabel() = runBlocking {
        val provider = Provider()
        val first = SubtitleStore(tmp.root, listOf(provider)).fetch(result, query)!!
        val again = SubtitleStore(tmp.root, listOf(provider)).fetch(result, query)!!
        assertEquals(1, provider.calls)
        assertEquals(first.file, again.file)
        assertTrue(again.label.endsWith(result.release))
        assertEquals(provider.text, again.file.readText())
    }

    @Test fun concurrentRequestsOnlyConsumeOneDownload() = runBlocking {
        val provider = Provider()
        val store = SubtitleStore(tmp.root, listOf(provider))
        val files = (1..8).map { async(Dispatchers.Default) { store.fetch(result, query)!!.file } }.awaitAll()
        assertEquals(1, provider.calls)
        assertEquals(1, files.distinct().size)
        assertFalse(tmp.root.walk().any { it.extension == "part" })
    }

    @Test fun episodesAndLanguagesDoNotShareTheWrongFile() = runBlocking {
        val provider = Provider()
        val store = SubtitleStore(tmp.root, listOf(provider))
        val one = store.fetch(result, query.copy(type = MediaType.TV, season = 1, episode = 1))!!
        val two = store.fetch(result, query.copy(type = MediaType.TV, season = 1, episode = 2))!!
        val english = store.fetch(result.copy(lang = "en"), query.copy(type = MediaType.TV, season = 1, episode = 1))!!
        assertEquals(3, provider.calls)
        assertEquals(3, listOf(one.file, two.file, english.file).distinct().size)
    }

    @Test fun blankDownloadsAreNotCachedAndCanBeRetried() = runBlocking {
        val provider = Provider().also { it.text = " " }
        val store = SubtitleStore(tmp.root, listOf(provider))
        assertNull(store.fetch(result, query))
        provider.text = "Valid subtitle"
        assertNotNull(store.fetch(result, query))
        assertEquals(2, provider.calls)
    }

    @Test fun cacheTrimKeepsTheMostRecentlyUsedFile() = runBlocking {
        val provider = Provider()
        val store = SubtitleStore(tmp.root, listOf(provider))
        val old = store.fetch(result, query)!!.file
        val recent = store.fetch(result.copy(ref = "2"), query)!!.file
        old.setLastModified(1000)
        recent.setLastModified(2000)
        store.trimToSize(recent.length())
        assertFalse(old.exists())
        assertTrue(recent.exists())
        store.clear()
        assertFalse(recent.exists())
    }
}
