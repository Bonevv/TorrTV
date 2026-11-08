package app.torrtv.web

import app.torrtv.data.*
import app.torrtv.web.catalog.*
import kotlinx.coroutines.runBlocking
import org.jsoup.Jsoup
import org.junit.Assert.*
import org.junit.Test

class WebSeriesResolverTest {
    private val media=Media(123,MediaType.TV,"Пример","Example","",null,null,"2020",8.0,100,emptyList(),"en")
    private fun entry(provider:String="NINE",season:Int=2,bg:Boolean=true)=MovieCatalog.Entry().also {
        it.provider=provider;it.page=MovieSources.Provider.valueOf(provider).base+"/serial/example-season-$season"
        it.title="Пример Сезон $season";it.original="Example Season $season";it.series=true;it.season=season
        it.year=if(season==1)2020 else 2021;it.bgAudio=bg;it.audioVerified=bg
    }
    @Test fun seasonDecorationsAndReleaseYearsDoNotHideTheParentSeries() {
        val e=entry()
        assertFalse(BgWebIndex.matches(media,e)) // A later year alone cannot identify a remake.
        e.seriesYear=2020
        assertTrue(BgWebIndex.matches(media,e))
        assertEquals("example",MovieEpisodes.baseTitle("Example Season 2 (2021)"))
        assertEquals(3,MovieEpisodes.season("example-3x12/"))
        assertEquals(12,MovieEpisodes.number("example-3x12/"))
        assertEquals(-1,MovieEpisodes.season("A show 2025 season-42755"))
    }
    @Test fun jsonSeasonsKeepTheirOwnEpisodesPlayersAndAudio()=runBlocking {
        val html="""<script>{"movie":{"title":"Example Season 2","isBgAudio":true,"episodes":[{"episodeNumber":1,"videoUrl":"https://player.example.com/s2e1"}]},"seasons":[{"title":"Example Season 1","slug":"example-season-1","year":2020,"isBgAudio":false,"episodes":[{"episodeNumber":2,"videoUrl":"https://player.example.com/s1e2"}]}]}</script>"""
        val resolver=WebSeriesResolver({html})
        val second=resolver.resolve(media,null,listOf(entry()),2,1)
        assertEquals(listOf(1,2),second.seasons)
        assertEquals("https://player.example.com/s2e1",second.sources.single().url)
        assertTrue(second.sources.single().bgAudio)
        assertTrue(second.sources.single().originalPlayer)
        val first=resolver.resolve(media,null,listOf(entry()),1,2)
        assertEquals("https://player.example.com/s1e2",first.sources.single().url)
        assertFalse(first.sources.single().bgAudio)
        assertTrue(first.sources.single().headers["Referer"]!!.contains("example-season-1"))
    }
    @Test fun linkedSeasonsAreFetchedOnlyWhenSelectedAndThenCached()=runBlocking {
        val requests=ArrayList<String>()
        val resolver=WebSeriesResolver({url->requests.add(url);if(url.endsWith("season-1"))
            """<script>{"movie":{"title":"Example Season 1","isBgAudio":false,"episodes":[{"episodeNumber":1,"videoUrl":"https://player.example.com/s1e1"}]}}</script>"""
            else """<script>{"movie":{"title":"Example Season 2","isBgAudio":true,"episodes":[{"episodeNumber":1,"videoUrl":"https://player.example.com/s2e1"}]},"seasons":[{"title":"Example Season 1","slug":"example-season-1","year":2020,"isBgAudio":false}]}</script>"""
        })
        resolver.resolve(media,null,listOf(entry()),2,1)
        assertEquals(1,requests.size)
        val result=resolver.resolve(media,null,listOf(entry()),1,1)
        assertEquals(2,requests.size)
        assertEquals("https://player.example.com/s1e1",result.sources.single().url)
        resolver.resolve(media,null,listOf(entry()),1,1)
        assertEquals(2,requests.size)
    }
    @Test fun ordinarySubtitledSeriesAreNotExcludedOrLabelledAsDubbed()=runBlocking {
        val e=entry("ZENO",1,false).also{it.page="https://zenoplay.to/tv-show/example/"}
        val html="""<section data-season-id="season-42755"><a class="episode-card" data-season-number="3" data-episode-number="2" href="/episode/example-3x2/">Епизод 2</a></section>"""
        val result=WebSeriesResolver({html}).resolve(media,null,listOf(e),3,2)
        assertEquals(listOf(3),result.seasons)
        assertEquals(2,result.episodes.single().number)
        assertFalse(result.sources.single().bgAudio)
        assertEquals("https://zenoplay.to/episode/example-3x2/",result.sources.single().discoveryPage)
    }
    @Test fun currentFenEpisodeAndSeasonAreReadWithoutGuessing()=runBlocking {
        val e=entry("FEN",4,false).also{it.year=2020;it.page="https://filmifen.com/seriali/example-season-4-episode-8.html"}
        val html="""<a class="series-episode" href="/seriali/example-season-4-episode-7.html">7</a><span class="series-episode" aria-current="page">8</span>"""
        val result=WebSeriesResolver({html}).resolve(media,null,listOf(e),4,8)
        assertEquals(listOf(7,8),result.episodes.map{it.number})
        assertEquals(e.page,result.sources.single().discoveryPage)
    }
    @Test fun oneDubbedEpisodeDoesNotLabelOtherEpisodesAsDubbed()=runBlocking {
        val e=entry("FEN",4,true).also{it.year=2020;it.page="https://filmifen.com/seriali/example-season-4-episode-8.html"}
        val html="""<a class="series-episode" href="/seriali/example-season-4-episode-7.html">7</a><span class="series-episode" aria-current="page">8</span>"""
        val result=WebSeriesResolver({html}).resolve(media,null,listOf(e),4,7)
        assertFalse(result.sources.single().bgAudio)
        assertFalse(result.episodes.first{it.number==7}.bgAudio)
        assertTrue(result.episodes.first{it.number==8}.bgAudio)
    }
    @Test fun selectedZenoEpisodeUsesItsExplicitBgPlayerAndKeepsOriginalPlayback()=runBlocking {
        val e=entry("ZENO",1,true).also{it.page="https://zenoplay.to/tv-show/example/"}
        val html="""<a class="episode-card" data-season-number="1" data-episode-number="2" href="/episode/example-1x2/">Епизод 2</a>"""
        val resolver=WebSeriesResolver({html},{page,_->assertTrue(page.endsWith("/episode/example-1x2/"));listOf("https://player.example.com/bg")})
        val result=resolver.resolve(media,null,listOf(e),1,2)
        assertEquals("https://player.example.com/bg",result.sources.single().url)
        assertTrue(result.sources.single().bgAudio)
        assertTrue(result.sources.single().originalPlayer)
        assertFalse(result.sources.single().direct)
    }
    @Test fun anUnknownSeasonIsNotSilentlyTreatedAsSeasonOne()=runBlocking {
        val e=entry(season=1).also{it.title="Example";it.original="Example";it.page="https://filmi9.com/serial/example";it.season=-1}
        val result=WebSeriesResolver({"""<script>{"movie":{"title":"Example","episodes":[{"episodeNumber":1,"videoUrl":"https://player.example.com/unknown"}]}}</script>"""}).resolve(media,null,listOf(e),1,1)
        assertTrue(result.sources.isEmpty())
        assertTrue(result.seasons.isEmpty())
    }
    @Test fun explicitForeignEpisodeOverridesTheSeasonAudioBadge()=runBlocking {
        val e=entry(season=1)
        val html="""<script>{"movie":{"title":"Example Season 1","isBgAudio":true,"episodes":[{"episodeNumber":1,"isBgAudio":false,"videoUrl":"https://player.example.com/en"}]}}</script>"""
        val result=WebSeriesResolver({html}).resolve(media,null,listOf(e),1,1)
        assertFalse(result.sources.single().bgAudio)
    }
    @Test fun allSixSourceSeriesFormatsRemainAvailable() {
        for(p in MovieSources.Provider.values()){
            val file=when(p.name){"NINE"->"nine";"ALL"->"all";"ZENO"->"zeno";"ZIP"->"zip";"ZT"->"zt";else->"fen"}
            val html=javaClass.getResourceAsStream("/web-catalog/$file-series.html")!!.bufferedReader().use{it.readText()}
            val parsed=MovieSources.parse(p,p.seriesStart(),html)
            assertFalse(p.name,parsed.entries.isEmpty())
            assertTrue(p.name,parsed.entries.all{it.series})
        }
    }
}
