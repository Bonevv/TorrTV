package app.torrtv.web

import app.torrtv.data.*
import app.torrtv.state.Library
import app.torrtv.state.Paged
import app.torrtv.web.catalog.*
import kotlinx.coroutines.*
import org.jsoup.Jsoup
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Synthetic failures around the reported Malcolm case; not a claim of a live site snapshot. */
class WebAvailabilityRegressionTest {
    @get:Rule val temp=TemporaryFolder()
    private val root="https://zenoplay.to/tv-show/malcolm/"
    private val listing="""<a class="media-card" href="$root"><img src="https://images.example.com/malcolm.jpg" alt="Малкълм"><h2>Малкълм</h2><span class="media-year">2025</span></a>"""
    private val detail="""<main><h1>Малкълм</h1><a class="episode-card" data-tmdb-id="123" data-season-number="1" data-episode-number="1" href="/episode/malcolm-1x1/">Епизод 1</a><a class="episode-card" data-season-number="1" data-episode-number="2" href="/episode/malcolm-1x2/">Епизод 2</a></main>"""
    private fun zenoGet(url:String)=when {
        url==root->detail
        url.contains("/tv-shows/")->listing
        url.contains("/episode/")->"<div data-post-id='1234' data-player-token='example'></div>"
        url.contains("?s=")->listing
        else->"<main></main>"
    }
    private fun zenoIndex()=BgWebIndex(temp.root,get={zenoGet(it)},enabled={setOf("ZENO")},
        bgPlayers={page,_->if(page.contains("1x1"))listOf("https://player.example.com/bg")else emptyList()})

    @Test fun missingTmdbMatchDoesNotEraseZenoOrFenSeriesAndTheOriginalEpisodeRemainsPlayable()=runBlocking {
        for(p in listOf(MovieSources.Provider.ZENO,MovieSources.Provider.FEN)){
            val html=javaClass.getResourceAsStream("/web-catalog/${if(p==MovieSources.Provider.ZENO)"zeno" else "fen"}-series.html")!!.bufferedReader().use{it.readText()}
            val episodes=javaClass.getResourceAsStream("/web-catalog/${if(p==MovieSources.Provider.ZENO)"zeno" else "fen"}-episodes.html")!!.bufferedReader().use{it.readText()}
            val index=BgWebIndex(temp.newFolder(),get={url->if(url==p.seriesStart())html else episodes},enabled={setOf(p.name)},bgPlayers={_,_->emptyList()})
            index.refresh(type=MediaType.TV,audioOnly=false)
            val e=index.catalogueEntries(MediaType.TV,false).first()
            val card=index.catalogueCard(e,false){null}!!
            assertEquals(p.name,card.web!!.provider)
            assertTrue(card.id<0)
            assertNotNull(card.posterPath)
            val own=index.sourceEntry(card)
            val d=WebCatalogueMedia.detail(card,own)
            assertEquals(card.key,d.media.key)
            assertTrue(d.seasons.isNotEmpty())
            val ep=own.episodes.first()
            val result=index.series(card,null,ep.season,ep.number)
            assertTrue(p.name,result.sources.isNotEmpty())
            assertTrue(result.sources.all{it.originalPlayer && it.playerOnly && !it.direct})
            assertEquals(ep.number,WebCatalogueMedia.episodes(result,ep.season).first().number)
        }
    }
    @Test fun aTmdbIdPublishedOnlyOnTheSeriesDetailStillEnrichesTheNormalCatalogueCard()=runBlocking {
        val index=zenoIndex();index.refresh(type=MediaType.TV,audioOnly=false)
        val e=index.catalogueEntries(MediaType.TV,false).single()
        var attempts=0
        val m=index.catalogueCard(e,false){record->
            attempts++
            if(record.tmdb=="123")Media(123,MediaType.TV,"Малкълм","Malcolm","",null,null,"2000",7.0,10,emptyList(),"en") else null
        }!!
        assertEquals(2,attempts);assertEquals(123,m.id);assertNull(m.web)
        assertTrue(BgWebIndex.matches(m,e))
    }
    @Test fun metadataNetworkErrorsRetainTheRealMovieInsteadOfReportingNoMovies()=runBlocking {
        val p=MovieSources.Provider.FEN
        val html=javaClass.getResourceAsStream("/web-catalog/fen-list.html")!!.bufferedReader().use{it.readText()}
        val index=BgWebIndex(temp.root,get={html},enabled={setOf(p.name)})
        index.refresh(type=MediaType.MOVIE,audioOnly=false)
        val e=index.catalogueEntries(MediaType.MOVIE,false).first()
        val m=index.catalogueCard(e,false){throw java.io.IOException("TMDB unavailable")}!!
        assertEquals(e.title,m.title)
        assertEquals(e.page,m.web!!.page)
        assertFalse(index.has(m))
    }
    @Test fun cancellingEnrichmentDoesNotTurnIntoASpuriousFallbackSuccess()=runBlocking {
        val index=zenoIndex();index.refresh(type=MediaType.TV,audioOnly=false)
        try{index.catalogueCard(index.catalogueEntries(MediaType.TV,false).first(),false){throw CancellationException("left screen")};fail("cancellation swallowed")}
        catch(_:CancellationException){}
    }
    @Test fun audioFilterFindsMalcolmInTheTypedSeriesFeedAndChecksItsActualEpisode()=runBlocking {
        val index=zenoIndex();index.refresh(type=MediaType.TV)
        assertTrue(index.audioEntries(MediaType.TV).isEmpty()) // ordinary presence is not proof
        val e=index.catalogueCandidates(MediaType.TV,true).single()
        val m=index.audioCard(e){null}!!
        index.saveAudioCards(listOf(e))
        assertTrue(index.status.value.contains("сериали: 1"))
        assertTrue(index.has(m))
        val first=index.series(m,null,1,1)
        assertTrue(first.sources.single().bgAudio)
        assertEquals("https://player.example.com/bg",first.sources.single().url)
        val second=index.series(m,null,1,2)
        assertFalse(second.sources.single().bgAudio)
        assertFalse(second.episodes.first{it.number==2}.bgAudio)
        assertTrue(second.sources.single().originalPlayer)
        val restored=BgWebIndex(temp.root,get={error("offline")},enabled={setOf("ZENO")})
        restored.restore();assertTrue(restored.has(m))
        assertEquals(1,restored.audioEntries().single().bgEpisodeNumber)
    }
    @Test fun aTimedOutAudioCategoryCannotDiscardTheWorkingTypedSeriesFeed()=runBlocking {
        val index=BgWebIndex(temp.root,get={url->
            if(url.contains("/genre/bg-audio/"))withTimeout(20){delay(100);""} else listing
        },enabled={setOf("ZENO")},bgPlayers={_,_->emptyList()})
        index.refresh(type=MediaType.TV)
        assertEquals(root,index.catalogueCandidates(MediaType.TV,true).single().page)
        assertTrue(index.audioEntries().isEmpty())
    }
    @Test fun bgSeriesSearchCanFindAnOlderShowOutsideTheFirstCataloguePage()=runBlocking {
        val requests=java.util.Collections.synchronizedList(ArrayList<String>())
        val index=BgWebIndex(temp.root,get={url->requests.add(url);if(url==root || url.contains("/episode/") || url.contains("?s="))zenoGet(url)else "<main></main>"},
            enabled={setOf("ZENO")},bgPlayers={_,_->listOf("https://player.example.com/bg")})
        index.refresh("Малкълм",type=MediaType.TV)
        val e=index.catalogueCandidates(MediaType.TV,true).single()
        assertNotNull(index.audioCard(e){null})
        assertTrue(requests.any{it.contains("?s=")})
    }
    @Test fun normalTmdbSeriesDiscoversTheBgEpisodeWithoutAnExistingCategoryBadge()=runBlocking {
        val m=Media(123,MediaType.TV,"Малкълм","Malcolm","",null,null,"2000",7.0,10,emptyList(),"en")
        val index=zenoIndex()
        val result=index.series(m,null,1,1)
        assertTrue(result.sources.single().bgAudio)
        assertTrue(index.has(m))
        assertFalse(index.series(m,null,1,2).sources.single().bgAudio)
    }
    @Test fun fenAudioFilterChecksEachRecordAndContinuesToLaterSeriesPagesInStableOrder()=runBlocking {
        val requests=java.util.Collections.synchronizedList(ArrayList<String>())
        val index=BgWebIndex(temp.root,get={u->requests.add(u);when{
            u.contains("/filmi-bg-audio/")->"<main></main>"
            u.endsWith(".html")->"<main><h1>Series ${if(u.contains("two"))"two" else "one"}</h1>"+
                (if(u.contains("two"))"<div class='pmovie__header-row'><a href='/filmi-bg-audio/'>БГ аудио</a></div>" else "<span class='movie-language'>БГ субтитри</span>")+"</main>"
            u.contains("/page/2/")->"<a class='item-poster' href='/seriali/two-season-2-episode-1.html'><img alt='Series two'><span class='item-poster__year'>2024</span></a>"
            else->"<a class='item-poster' href='/seriali/one-season-1-episode-1.html'><img alt='Series one'><span class='item-poster__year'>2020</span></a><a rel='next' href='/seriali/page/2/'>Напред</a>"
        }},enabled={setOf("FEN")})
        val pager=BgAudioPager(initial={index.refresh(type=MediaType.TV)},entries={index.catalogueCandidates(MediaType.TV,true)},
            expand={index.sweep(1,MediaType.TV,"",true)},hasMore={index.hasMore(MediaType.TV,"",true)},
            resolve={index.audioCard(it){null}},onChecked={index.saveAudioCards(it)},batchSize=1)
        val ui=Paged<Media>(100,{pager.next(it)}){it.key}
        repeat(4){ui.loadNext()}
        assertNull(ui.error)
        assertTrue(requests.any{it.contains("/seriali/page/2/")})
        // "Series one" only has BG subtitles: it is still listed, as an unconfirmed site card without the BG badge.
        assertEquals(2,ui.items.size)
        assertTrue(ui.items.single{index.has(it)}.web!!.page.contains("two-season-2"))
        assertTrue(ui.items.single{!index.has(it)}.web!!.page.contains("one-season-1"))
    }
    @Test fun recordCategoryIsAudioEvidenceButNavigationAndRecommendationsAreNot() {
        val e=MovieCatalog.Entry().also{it.provider="FEN";it.page="https://filmifen.com/filmi/example.html";it.title="Example"}
        fun parse(html:String)=MovieSources.detail(e,Jsoup.parse(html,e.page))
        assertTrue(MovieSources.hasBgAudio(parse("<main><h1>Example</h1><div class='pmovie__header-row'><a href='/filmi-bg-audio/'>БГ аудио</a></div></main>")))
        for(wrapper in listOf("nav","aside","footer","div class='related'")){
            val tag=wrapper.substringBefore(' ')
            assertFalse(wrapper,MovieSources.hasBgAudio(parse("<main><h1>Example</h1><$wrapper><div class='pmovie__header-row'><a href='/filmi-bg-audio/'>БГ аудио</a></div></$tag></main>")))
        }
        assertFalse(MovieSources.hasBgAudio(parse("<main><h1>Example</h1><div class='movie-language'>БГ аудио</div></main>")))
    }
    @Test fun onlyTheExactOwnJsonRecordCanPromoteAudioOrSetExternalIds() {
        val e=MovieCatalog.Entry().also{it.provider="NINE";it.page="https://filmi9.com/film/example";it.title="Example"}
        fun parse(slug:String)=MovieSources.detail(e,Jsoup.parse("""<main><h1>Example</h1><script>{"movie":{"title":"Example","slug":"$slug","isBgAudio":true,"tmdb_id":123}}</script></main>""",e.page))
        assertTrue(MovieSources.hasBgAudio(parse("example")))
        val other=parse("another-movie")
        assertFalse(MovieSources.hasBgAudio(other));assertEquals("",other.tmdb)
    }
    @Test fun sameNamedAnotherSeasonCannotReplaceTheSelectedSeasonsAudioOrEpisodes() {
        val e=MovieCatalog.Entry().also{it.provider="NINE";it.page="https://filmi9.com/serial/example-2";it.title="Example Season 2";it.series=true;it.season=2}
        val html="""<main><h1>Example Season 2</h1><script>{"movie":{"title":"Example Season 1","isBgAudio":true,"episodes":[{"episodeNumber":7,"videoUrl":"https://player.example.com/wrong-season"}]}}</script></main>"""
        val parsed=MovieSources.detail(e,Jsoup.parse(html,e.page))
        assertFalse(MovieSources.hasBgAudio(parsed));assertTrue(parsed.episodes.isEmpty())
    }
    @Test fun aMovieFoundBySearchUpdatesBothItsWebSourceAndThePosterBadge()=runBlocking {
        val m=Media(557,MediaType.MOVIE,"Example","Example","",null,null,"2002",7.0,1,emptyList(),"en")
        val index=BgWebIndex(temp.root,get={u->when{
            u.contains("/filmi-bg-audio/")->"<main></main>"
            u.endsWith(".html")->"<main><h1>Example</h1><div class='pmovie__header-row'><a href='/filmi-bg-audio/'>БГ аудио</a></div></main>"
            else->"<a class='item-poster' href='/filmi/example.html'><img alt='Example'><span class='item-poster__year'>2002</span></a>"
        }},enabled={setOf("FEN")})
        assertTrue(index.sources(m,null,1,1).single().bgAudio)
        assertTrue(index.has(m))
    }
    @Test fun sourceReferencesAndAbsoluteArtworkSurviveFavoritesAndHistorySerialization() {
        val e=MovieSources.parse(MovieSources.Provider.ZENO,"https://zenoplay.to/tv-shows/",listing).entries.single()
        val m=WebCatalogueMedia.card(e)!!
        val restored=Library.mediaFrom(Library.mediaTo(m))!!
        assertEquals(m.key,restored.key);assertEquals(m.web,restored.web)
        assertEquals(m.posterPath,Tmdb.img(restored.posterPath,"w342"))
        assertNull(Library.mediaFrom(Library.mediaTo(m).put("web_page","https://unrelated.example.com/movie")))
        assertTrue(BgWebIndex.matches(restored,e))
        assertFalse(BgWebIndex.matches(restored,e.also{it.page="https://zenoplay.to/tv-show/remake/"}))
    }
    @Test fun sourceTitlesWithoutTmdbIdsCannotShareSubtitleSearchResults() {
        val q=app.torrtv.data.subs.SubtitleQuery(MediaType.MOVIE,"Example",emptyList(),"2000",null,0,null,null,listOf("bg"))
        assertNotEquals(q.cacheKey,q.copy(title="Other").cacheKey)
        assertNotEquals(q.cacheKey,q.copy(year="2025").cacheKey)
        assertEquals(q.copy(tmdbId=123).cacheKey,q.copy(tmdbId=123,title="Translation").cacheKey)
    }
    @Test fun unknownSeasonKeepsTheOriginalSeriesPlayerAvailableInsteadOfGuessingEpisodeOne()=runBlocking {
        val index=BgWebIndex(temp.root,get={"<main><h1>Example</h1></main>"},enabled={setOf("ZENO")},bgPlayers={_,_->emptyList()})
        val e=MovieCatalog.Entry().also{it.provider="ZENO";it.page=root;it.title="Example";it.series=true}
        val m=WebCatalogueMedia.card(e)!!
        val result=index.series(m,null,-1,0)
        assertTrue(result.episodes.isEmpty())
        assertEquals(root,result.sources.single().discoveryPage)
        assertTrue(result.sources.single().originalPlayer)
        assertFalse(result.sources.single().bgAudio)
    }
}
