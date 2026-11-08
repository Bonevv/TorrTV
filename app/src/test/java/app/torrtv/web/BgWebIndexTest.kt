package app.torrtv.web

import app.torrtv.data.*
import app.torrtv.web.catalog.MovieCatalog
import app.torrtv.web.catalog.MovieSources
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class BgWebIndexTest {
    @get:Rule val temp = TemporaryFolder()
    private fun media(year: String = "2002", type: MediaType = MediaType.MOVIE) =
        Media(557, type, "Спайдърмен", "Spider-Man", "", null, null, year, 7.0, 100, emptyList(), "en")
    private fun entry() = MovieCatalog.Entry().also { it.title="Спайдърмен";it.original="Spider-Man";it.year=2002;it.bgAudio=true }
    @Test fun cachedVideoIsRestoredAsAPlayerInsteadOfOpeningTheMoviePage() = runBlocking {
        val body="""{"data":[{"title":"Spider-Man","slug":"spider-man","year":2002,"tmdb_id":557,"isBgAudio":true,"videoUrl":"https://cdn.example.com/master.m3u8"}]}"""
        BgWebIndex(temp.root,get={body},enabled={setOf("NINE")}).refresh()
        val restored=BgWebIndex(temp.root,get={throw java.io.IOException("offline")},enabled={setOf("NINE")})
        val sources=restored.sources(media(),null,1,1)
        assertEquals(1,sources.size)
        assertTrue(sources.single().playerOnly)
        assertTrue(sources.single().direct)
        assertEquals("https://cdn.example.com/master.m3u8",sources.single().url)
        assertEquals("",sources.single().discoveryPage)
    }
    @Test fun scrollingReadsTheIndexWithoutNetworkAndWithoutScanningProviderSettingsForEveryRecord() = runBlocking {
        val data=org.json.JSONArray((1..3000).map { n -> org.json.JSONObject().put("title","Film $n").put("slug","film-$n").put("year",2002).put("isBgAudio",true) })
        var providerReads=0;var requests=0
        val index=BgWebIndex(temp.root,get={requests++;org.json.JSONObject().put("data",data).toString()},enabled={providerReads++;setOf("NINE")})
        index.refresh()
        providerReads=0
        repeat(50) { assertFalse(index.has(media())) }
        assertEquals(50,providerReads)
        assertEquals(1,requests)
        val restored=BgWebIndex(temp.root,get={error("cache restore must not use the network")},enabled={setOf("NINE")})
        restored.restore()
        assertEquals(3000,restored.count())
    }
    @Test fun seriesFilteringRequestsTheSeriesCatalogue() = runBlocking {
        val urls=ArrayList<String>()
        val index=BgWebIndex(temp.root,get={urls.add(it);"""{"data":[{"title":"Spider-Man","slug":"spider-man","year":2002,"isBgAudio":true,"type":"series","videoUrl":"https://player.example.com/embed/series"}]}"""},enabled={setOf("NINE")})
        index.refresh(type=MediaType.TV)
        assertTrue(urls.single().contains("type=series"))
        assertTrue(index.has(media(type=MediaType.TV)))
        assertFalse(index.has(media()))
    }
    @Test fun matchingDoesNotConfuseRemakesOrSeries() {
        assertTrue(BgWebIndex.matches(media(), entry()))
        assertFalse(BgWebIndex.matches(media("2012"), entry()))
        assertFalse(BgWebIndex.matches(media(type=MediaType.TV), entry()))
        assertFalse(BgWebIndex.matches(media(), entry().also { it.tmdb="999" }))
        assertFalse(BgWebIndex.matches(media(), entry().also { it.tmdb="557";it.imdb="tt0000001" }, "tt0145487"))
        assertFalse(BgWebIndex.matches(media(), entry().also { it.year=0 }))
    }
    @Test fun onlyExplicitAudioEvidenceCreatesBadgeAndCacheSurvivesRestart() = runBlocking {
        val body="""{"results":[{"title":"Spider-Man / Спайдърмен","slug":"spider-man","year":2002,"tmdb_id":557,"type":"movie","isBgAudio":true},{"title":"Other","slug":"other","year":2002,"isBgAudio":false}]}"""
        val index=BgWebIndex(temp.root, get={body}, enabled={setOf("NINE")})
        index.refresh("Spider-Man")
        assertTrue(index.has(media()))
        assertEquals(1,index.count())
        val restored=BgWebIndex(temp.root,get={throw java.io.IOException("offline")},enabled={setOf("NINE")})
        restored.refresh()
        assertTrue(restored.has(media()))
        assertTrue(restored.status.value.contains("непроверена"))
    }
    @Test fun networkFailureIsUnknownAndDoesNotInventAvailability() = runBlocking {
        val index=BgWebIndex(temp.root,get={throw java.io.IOException("offline")},enabled={setOf("NINE")})
        index.refresh()
        assertFalse(index.has(media()))
        assertEquals(0,index.count())
        assertTrue(index.status.value.contains("непроверена"))
    }
    @Test fun disabledSourcesMakeNoRequests() = runBlocking {
        var requests=0
        val index=BgWebIndex(temp.root,get={requests++;error("must not request")},enabled={emptySet()})
        index.refresh()
        assertEquals(0,requests)
        assertTrue(index.status.value.contains("изключени"))
    }
    @Test fun bulgarianPageLanguageOrNavigationDoesNotProveDubbedAudio() {
        val html="""<nav>БГ аудио</nav><a href="/film/spider-man" title="Spider-Man"><img alt="Spider-Man"><span>2002</span></a>"""
        val result=MovieSources.parse(MovieSources.Provider.NINE,"https://filmi9.com/filmi/1",html)
        assertEquals(1,result.entries.size)
        assertFalse(result.entries[0].bgAudio)
    }
    @Test fun anAudioCategoryIsEvidenceButTheSameWordsInASearchQueryAreNot() {
        val html="""<a href="/film/spider-man" title="Spider-Man"><img alt="Spider-Man"><span>2002</span></a>"""
        assertFalse(MovieSources.parse(MovieSources.Provider.NINE,"https://filmi9.com/search?term=bg-audio",html).entries.single().bgAudio)
        assertTrue(MovieSources.parse(MovieSources.Provider.NINE,"https://filmi9.com/filmi/bg-audio/page/2/",html).entries.single().bgAudio)
    }
    @Test fun allSixCatalogueLayoutsParseWithoutCollapsingProviders() {
        for (provider in MovieSources.Provider.values()) {
            val file = when(provider.name) { "ZIP"->"zip"; "NINE"->"nine"; "ZENO"->"zeno"; "ALL"->"all"; "ZT"->"zt"; else->"fen" }
            val html = javaClass.getResourceAsStream("/web-catalog/$file-list.html")!!.bufferedReader().use { it.readText() }
            val parsed = MovieSources.parse(provider,provider.base+provider.catalog,html)
            assertEquals(provider.name,2,parsed.entries.size)
            assertTrue(parsed.entries.all { it.provider == provider.name && it.title.isNotBlank() })
        }
    }
    @Test fun backgroundPaginationFindsOlderTitlesBeyondTheFirstPage() = runBlocking {
        val first = org.json.JSONArray((1..30).map { n -> org.json.JSONObject().put("title", "Other $n").put("slug", "other-$n").put("year", 2020).put("type", "movie").put("isBgAudio", true) })
        val next = """{"data":[{"title":"Spider-Man / Спайдърмен","slug":"spider-man","year":2002,"tmdb_id":557,"type":"movie","isBgAudio":true}]}"""
        var pageTwo = 0
        val index=BgWebIndex(temp.root,get={ url ->
            if (url.contains("page=2")) { pageTwo++;next } else org.json.JSONObject().put("data",first).toString()
        },enabled={setOf("NINE")})
        index.refresh()
        assertFalse(index.has(media()))
        index.sweep()
        assertTrue(index.has(media()))
        assertEquals(1,pageTwo)
    }
    @Test fun wordpressAudioTagIsCheckedInsteadOfTrustingTheSearchQuery() = runBlocking {
        val index=BgWebIndex(temp.root,get={ url ->
            if (url.contains("/tags?")) """[{"id":72}]""" else
                """[{"link":"https://www.filmi2k.com/spider-man/","title":{"rendered":"Spider-Man / Спайдърмен (2002)"},"tags":[72]},{"link":"https://www.filmi2k.com/other/","title":{"rendered":"Other (2020)"},"tags":[9]}]"""
        },enabled={setOf("TWO_K")})
        index.refresh("Spider-Man")
        assertEquals(1,index.count())
        assertTrue(index.has(media()))
    }
    @Test fun anUnspecifiedOrForeignDubDoesNotProveBulgarianAudio() {
        for (label in listOf("English dub", "Английски дублаж", "Руски дублаж", "Дублаж", "БГ субтитри")) {
            val e = MovieCatalog.Entry()
            MovieSources.markLanguage(e, label)
            assertFalse(label, e.bgAudio)
        }
        for (label in listOf("БГ аудио", "Български дублаж", "Bulgarian audio")) {
            val e = MovieCatalog.Entry()
            MovieSources.markLanguage(e, label)
            assertTrue(label, e.bgAudio)
        }
    }
    @Test fun oneBrokenCatalogueDoesNotPreventOtherCataloguesFromPaginating() = runBlocking {
        val firstNine = org.json.JSONArray((1..30).map { n -> org.json.JSONObject().put("title","Other $n").put("slug","other-$n").put("year",2020).put("isBgAudio",true) })
        val firstTwoK = org.json.JSONArray((1..30).map { n -> org.json.JSONObject().put("link","https://www.filmi2k.com/other-$n/").put("title",org.json.JSONObject().put("rendered","Other $n (2020)")).put("tags",org.json.JSONArray(listOf(72))) })
        val index=BgWebIndex(temp.root,get={url -> when {
            url.contains("/tags?") -> """[{"id":72}]"""
            url.contains("cdn.filmi9.com") && url.contains("page=2") -> throw java.io.IOException("offline")
            url.contains("cdn.filmi9.com") -> org.json.JSONObject().put("data",firstNine).toString()
            url.contains("page=2") -> """[{"link":"https://www.filmi2k.com/spider-man/","title":{"rendered":"Spider-Man / Спайдърмен (2002)"},"tags":[72]}]"""
            else -> firstTwoK.toString()
        }},enabled={setOf("NINE","TWO_K")})
        index.refresh()
        index.sweep()
        assertTrue(index.has(media()))
    }
    @Test fun cacheWriteFailureDoesNotBreakBrowsingOrDiscardConfirmedEvidence() = runBlocking {
        val notDirectory = temp.newFile("cache-file")
        val index=BgWebIndex(notDirectory,get={"""{"data":[{"title":"Spider-Man","slug":"spider-man","year":2002,"isBgAudio":true}]}"""},enabled={setOf("NINE")})
        index.refresh()
        assertTrue(index.has(media()))
    }
    @Test fun playbackStopsNetworkRequestsAndDisabledSourcesStopCreatingBadges() = runBlocking {
        var allow = true
        var providers = setOf("NINE")
        var requests = 0
        val index=BgWebIndex(temp.root,get={requests++;"""{"data":[{"title":"Spider-Man","slug":"spider-man","year":2002,"isBgAudio":true}]}"""},enabled={providers},allowNetwork={allow})
        index.refresh()
        assertTrue(index.has(media()))
        allow=false
        index.refresh("Spider-Man",force=true)
        index.sweep()
        assertEquals(1,requests)
        providers=emptySet()
        index.settingsChanged()
        assertFalse(index.has(media()))
    }
}
