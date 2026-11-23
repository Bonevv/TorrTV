package app.torrtv.web

import app.torrtv.data.*
import app.torrtv.web.catalog.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class WebsiteCatalogueTest {
    @get:Rule val temp=TemporaryFolder()
    private val movie=Media(557,MediaType.MOVIE,"Спайдърмен","Spider-Man","",null,null,"2002",7.0,100,emptyList(),"en")
    private val card="""<a class="item-poster" href="/filmi/spider-man.html"><img alt="Spider-Man"><span class="item-poster__year">2002</span><span>БГ аудио</span></a>"""

    @Test fun aGenericMovieWithAnAudioLabelRemainsAnOrdinaryWebSource()=runBlocking {
        val index=BgWebIndex(temp.root,get={url->when{
            url.contains("/filmi-bg-audio/")->"<main></main>"
            url.contains("spider-man.html")->"<main><h1>Spider-Man</h1><span class='movie-language'>БГ аудио</span></main>"
            else->card
        }},enabled={setOf("FEN")})
        index.refresh("Spider-Man",type=MediaType.MOVIE,audioOnly=false)
        assertEquals(1,index.catalogueEntries(MediaType.MOVIE,false).size)
        assertTrue(index.audioEntries().isEmpty())
        val sources=index.sources(movie,null,1,1)
        assertEquals(1,sources.size)
        assertFalse(sources.single().bgAudio)
        assertTrue(sources.single().originalPlayer)
        assertTrue(sources.single().playerOnly)
        assertFalse(index.has(movie))
    }
    @Test fun ordinaryRefreshCannotEraseCategoryMembershipAndCacheRetainsIt()=runBlocking {
        val index=BgWebIndex(temp.root,get={card.replace("<span>БГ аудио</span>","")},enabled={setOf("FEN")})
        index.refresh(type=MediaType.MOVIE)
        assertTrue(index.has(movie))
        index.refresh("Spider-Man",type=MediaType.MOVIE,audioOnly=false)
        assertTrue(index.has(movie))
        val restored=BgWebIndex(temp.root,get={error("no network")},enabled={setOf("FEN")})
        restored.restore()
        assertTrue(restored.has(movie))
    }
    @Test fun thePreviousAudioCacheIsDiscardedEvenIfItsFlagsWereVerified()=runBlocking {
        java.io.File(temp.root,"index-audio-v2.json").writeText("""[{"checkedAt":${System.currentTimeMillis()},"entry":{"provider":"NINE","page":"https://filmi9.com/film/spider-man","title":"Spider-Man","year":2002,"bgAudio":true,"audioVerified":true}}]""")
        val index=BgWebIndex(temp.root,get={error("no network")})
        index.restore()
        assertFalse(index.has(movie))
    }
    @Test fun allHtmlAudioCataloguesLoadSeriesDirectlyFromTheAudioCategory()=runBlocking {
        for(p in MovieSources.Provider.values().filter{it.hasBgCategory() && it.hasSeries() && it!=MovieSources.Provider.NINE}){
            val file=when(p.name){"ALL"->"all";"ZENO"->"zeno";"ZT"->"zt";else->"fen"}
            val html=javaClass.getResourceAsStream("/web-catalog/$file-series.html")!!.bufferedReader().use{it.readText()}
            val requests=java.util.Collections.synchronizedList(ArrayList<String>())
            val index=BgWebIndex(temp.newFolder(),get={requests.add(it);html},enabled={setOf(p.name)})
            index.refresh(type=MediaType.TV)
            val bgStart=p.start("",MovieCatalog.BG_AUDIO).let{if(p==MovieSources.Provider.ZENO)"$it?type=tv" else it}
            assertEquals(p.name,setOf(bgStart,p.seriesStart()),requests.toSet())
            val entries=index.audioEntries(MediaType.TV)
            assertFalse(p.name,entries.isEmpty())
            assertTrue(entries.all{it.series && MovieSources.hasBgAudio(it)})
        }
    }
    @Test fun sidebarRecommendationsAndExplicitNegativeAudioAreNotCategoryMembers() {
        val p=MovieSources.Provider.FEN
        val html="<main>"+card.replace("spider-man.html","actual.html")+"</main><aside>"+card+"</aside>"
        val entries=MovieSources.parse(p,p.start("",MovieCatalog.BG_AUDIO),html).entries
        assertEquals(1,entries.size)
        assertTrue(entries.single().page.endsWith("actual.html"))
        val negative=MovieSources.parse(p,p.start("",MovieCatalog.BG_AUDIO),card.replace("БГ аудио","Няма БГ аудио")).entries.single()
        assertFalse(MovieSources.hasBgAudio(negative))
    }
    @Test fun aSourceWithoutAnAudioCategoryIsExcludedOnlyFromTheAudioFilter()=runBlocking {
        var requests=0
        val html=javaClass.getResourceAsStream("/web-catalog/zip-list.html")!!.bufferedReader().use{it.readText()}
        val index=BgWebIndex(temp.root,get={requests++;html},enabled={setOf("ZIP")})
        index.refresh(type=MediaType.MOVIE)
        assertEquals(0,requests)
        assertTrue(index.audioEntries().isEmpty())
        index.refresh(type=MediaType.MOVIE,audioOnly=false)
        assertEquals(1,requests)
        assertEquals(2,index.catalogueEntries(MediaType.MOVIE,false).size)
        assertTrue(index.audioEntries().isEmpty())
    }
    @Test fun ordinaryAndAudioPaginationHaveIndependentCursors()=runBlocking {
        val requests=java.util.Collections.synchronizedList(ArrayList<String>())
        val index=BgWebIndex(temp.root,get={u->requests.add(u);val page=if(u.contains("page=2"))2 else 1
            """{"data":[{"title":"Movie $page","slug":"movie-$page","year":2002,"isBgAudio":true}]}"""
        },enabled={setOf("NINE")})
        index.refresh(type=MediaType.MOVIE)
        index.refresh(type=MediaType.MOVIE,audioOnly=false)
        index.sweep(1,MediaType.MOVIE,"",true)
        index.sweep(1,MediaType.MOVIE,"",false)
        assertEquals(2,requests.count{it.contains("page=2")})
        assertEquals(1,requests.count{it.contains("page=2") && it.contains("isBgAudio=true")})
    }
    @Test fun allZenoMoviesRemainAvailableWithoutAnAudioBadge()=runBlocking {
        val html=javaClass.getResourceAsStream("/web-catalog/zeno-list.html")!!.bufferedReader().use{it.readText()}
        val requests=java.util.Collections.synchronizedList(ArrayList<String>())
        val index=BgWebIndex(temp.root,get={requests.add(it);html},enabled={setOf("ZENO","FEN")})
        index.refresh(type=MediaType.MOVIE,audioOnly=false,source="ZENO")
        assertEquals(listOf("https://zenoplay.to/movies/"),requests)
        assertEquals(2,index.catalogueEntries(MediaType.MOVIE,false,"ZENO").size)
        assertTrue(index.audioEntries().isEmpty())
    }
    @Test fun anExplicitFalseJsonAudioFieldNeverBecomesADubbedMovie()=runBlocking {
        val body="""{"data":[{"title":"Spider-Man","slug":"spider-man","year":2002,"tmdb_id":557,"isBgAudio":false,"videoUrl":"https://player.example.com/en"}]}"""
        val index=BgWebIndex(temp.root,get={body},enabled={setOf("NINE")})
        index.refresh(type=MediaType.MOVIE,audioOnly=false)
        assertEquals(1,index.catalogueEntries(MediaType.MOVIE,false).size)
        assertTrue(index.audioEntries().isEmpty())
        val source=index.sources(movie,null,1,1).single()
        assertEquals("https://player.example.com/en",source.url)
        assertFalse(source.bgAudio)
    }
    @Test fun categoryPaginationCannotEscapeIntoTheOrdinaryCatalogue()=runBlocking {
        val html=card+"<a rel='next' href='/filmi/page/2/'>Напред</a>"
        var requests=0
        val index=BgWebIndex(temp.root,get={requests++;html},enabled={setOf("FEN")})
        index.refresh(type=MediaType.MOVIE)
        index.sweep(20,MediaType.MOVIE,"")
        assertEquals(1,requests)
        assertFalse(index.hasMore(MediaType.MOVIE,""))
    }
}
