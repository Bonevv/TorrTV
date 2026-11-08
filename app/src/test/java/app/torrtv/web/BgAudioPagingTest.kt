package app.torrtv.web

import app.torrtv.data.*
import app.torrtv.state.Paged
import app.torrtv.web.catalog.MovieCatalog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class BgAudioPagingTest {
    @get:Rule val temp=TemporaryFolder()
    private fun body(url:String,total:Int=25):String {
        val page=Regex("[?&]page=(\\d+)").find(url)?.groupValues?.get(1)?.toInt()?:1
        val series=url.contains("type=series")
        val a=JSONArray()
        for(n in (page-1)*10+1..minOf(page*10,total))a.put(JSONObject()
            .put("title","Title $n").put("slug","${if(series)"series" else "movie"}-$n")
            .put("year",2010).put("type",if(series)"series" else "movie")
            .put("tmdb_id",n).put("isBgAudio",n!=5))
        return JSONObject().put("data",a).toString()
    }
    private fun media(e:MovieCatalog.Entry)=Media(e.tmdb.toInt(),if(e.series)MediaType.TV else MediaType.MOVIE,
        e.title,e.title,"",null,null,"2010",7.0,100,emptyList(),"en")
    private fun pager(index:BgWebIndex,type:MediaType,accept:(Media)->Boolean={true})=BgAudioPager(
        initial={index.refresh(type=type)},entries={index.audioEntries(type)},
        expand={index.sweep(maxPages=2,type=type,query="")},hasMore={index.hasMore(type,"")},
        resolve={e->index.audioCard(e){media(it)}},accept=accept,onChecked={index.saveAudioCards(it)},batchSize=8)

    private suspend fun beyondTen(type:MediaType) {
        val requests=ArrayList<String>()
        val index=BgWebIndex(temp.newFolder(),get={url->requests.add(url);body(url)},enabled={setOf("NINE")})
        val source=pager(index,type)
        val ui=Paged<Media>(1000,{page->source.next(page)}){it.key}
        var loads=0
        while(!ui.exhausted && loads++<12)ui.loadNext()
        assertNull(ui.error)
        assertTrue(ui.exhausted)
        assertEquals(24,ui.items.size) // 25 source records, one explicitly without BG audio.
        assertTrue(ui.items.all{it.type==type && it.id!=5})
        assertTrue(requests.any{it.contains("page=3")})
        assertTrue(requests.any{it.contains("page=4")}) // A short third page is not assumed final.
    }
    @Test fun movieFilterContinuesPastAServerCappedTenRecords()=runBlocking {beyondTen(MediaType.MOVIE)}
    @Test fun seriesFilterContinuesPastAServerCappedTenRecords()=runBlocking {beyondTen(MediaType.TV)}

    @Test fun movieAndSeriesCursorsCannotOverwriteEachOther()=runBlocking {
        val requests=ArrayList<String>()
        val index=BgWebIndex(temp.root,get={url->requests.add(url);body(url)},enabled={setOf("NINE")})
        index.refresh(type=MediaType.MOVIE);index.refresh(type=MediaType.TV)
        index.sweep(maxPages=1,type=MediaType.MOVIE,query="")
        index.sweep(maxPages=1,type=MediaType.TV,query="")
        assertTrue(requests.any{it.contains("type=movie") && it.contains("page=2")})
        assertTrue(requests.any{it.contains("type=series") && it.contains("page=2")})
        assertEquals(19,index.audioEntries(MediaType.MOVIE).size)
        assertEquals(19,index.audioEntries(MediaType.TV).size)
    }
    @Test fun aFilteredBatchWithoutNewCardsDoesNotExhaustTheList()=runBlocking {
        val index=BgWebIndex(temp.root,get={body(it)},enabled={setOf("NINE")})
        val source=pager(index,MediaType.MOVIE){it.id>=18}
        val first=source.next(1)
        assertTrue(first.items.isEmpty())
        assertTrue(first.totalPages>first.page)
        var last=first
        for(page in 2..10){last=source.next(page);if(last.page==last.totalPages)break}
        assertEquals((18..25).toSet(),last.items.map{it.id}.toSet())
    }
    @Test fun aServerRepeatingTheFirstPageIsStoppedWithoutLooping()=runBlocking {
        var calls=0
        val index=BgWebIndex(temp.root,get={calls++;body("https://cdn.filmi9.com/movies/?page=1",10)},enabled={setOf("NINE")})
        index.refresh(type=MediaType.MOVIE)
        index.sweep(maxPages=100,type=MediaType.MOVIE,query="")
        assertEquals(2,calls)
        assertFalse(index.hasMore(MediaType.MOVIE,""))
    }
    @Test fun explicitPaginationCanFinishAShortPageWithoutAnExtraRequest()=runBlocking {
        var calls=0
        val index=BgWebIndex(temp.root,get={calls++;JSONObject(body("?page=1",10)).put("pagination",JSONObject().put("totalPages",1)).toString()},enabled={setOf("NINE")})
        index.refresh(type=MediaType.MOVIE)
        index.sweep(maxPages=100,type=MediaType.MOVIE,query="")
        assertEquals(1,calls)
        assertFalse(index.hasMore(MediaType.MOVIE,""))
    }
    @Test fun cancelledUiDeliveryDoesNotLoseAlreadyResolvedCards()=runBlocking {
        val entries=(1..4).map{n->MovieCatalog.Entry().also{it.page="https://example.com/$n";it.tmdb=n.toString()}}
        var cancelled=false;val resolves=java.util.concurrent.atomic.AtomicInteger();var saves=0
        val source=BgAudioPager(initial={},entries={entries},expand={},hasMore={false},resolve={resolves.incrementAndGet();media(it)},
            onChecked={saves++;if(!cancelled){cancelled=true;throw CancellationException("left screen")}})
        try{source.next(1);fail("expected cancellation")}catch(_:CancellationException){}
        val restored=source.next(1)
        assertEquals(4,restored.items.size)
        assertEquals(4,resolves.get())
        assertEquals(2,saves)
    }
    @Test fun duplicateVersionsDoNotInflateTheTitleCount()=runBlocking {
        val entries=(1..24).map{n->MovieCatalog.Entry().also{it.page="https://example.com/version-$n";it.tmdb=((n-1)%12+1).toString()}}
        val source=BgAudioPager(initial={},entries={entries},expand={},hasMore={false},resolve={media(it)},batchSize=8)
        var last=source.next(1)
        for(page in 2..5){last=source.next(page);if(last.page==last.totalPages)break}
        assertEquals(12,last.items.size)
    }
}
