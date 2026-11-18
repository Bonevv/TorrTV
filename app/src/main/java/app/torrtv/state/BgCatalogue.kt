package app.torrtv.state

import app.torrtv.data.*
import app.torrtv.web.BgAudioPager
import app.torrtv.web.BgWebIndex
import kotlinx.coroutines.withTimeout

/** Source availability drives this list; TMDB enriches cards when an exact match is available. */
internal fun bgCatalogue(type:MediaType?,genre:Int?,year:Int?,anime:Boolean,provider:String?,sort:Int,query:String="",audioOnly:Boolean=true,source:String?=null):Paged<Media> {
    val index=Graph.bgWeb
    val catalogs:List<MediaType?> = if(type!=null || query.isNotBlank())listOf(type) else listOf(MediaType.MOVIE,MediaType.TV)
    val q=BgWebIndex.normalize(query)
    fun entries()=index.catalogueCandidates(type,audioOnly,source).filter{e->
        q.isBlank() || listOf(e.title,e.original).any{BgWebIndex.normalize(it).contains(q)}
    }
    var catalogueReady=false
    var turn=0
    suspend fun prepare(){
        for(t in catalogs)index.refresh(query,type=t,audioOnly=audioOnly,source=source)
        catalogueReady=true
        if(entries().isEmpty() && index.status.value.contains("не отговориха"))throw java.io.IOException(index.status.value)
    }
    val data=BgAudioPager(
        initial={index.restore();if(entries().isEmpty())prepare()},
        entries={entries()},
        expand={
            if(!catalogueReady)prepare()
            val pending=catalogs.filter{index.hasMore(it,query,audioOnly,source)}
            if(pending.isNotEmpty()){val t=pending[turn++%pending.size];index.sweep(maxPages=2,type=t,query=query,audioOnly=audioOnly,source=source)}
        },
        hasMore={!catalogueReady || catalogs.any{index.hasMore(it,query,audioOnly,source)}},
        resolve={e->withTimeout(12_000){
            val m=index.catalogueCard(e,audioOnly){Graph.tmdb.webMedia(it)}
            if(m!=null && provider!=null && (m.web!=null || !Graph.tmdb.availableOn(m,provider)))null else m
        }},
        accept={m->(type==null || m.type==type) && (genre==null || genre in m.genreIds) &&
            (year==null || m.year==year.toString()) && (!anime || m.originalLanguage=="ja" && 16 in m.genreIds)},
        onChecked={index.saveAudioCards(it)},batchSize=8,
        order=when(sort){
            1->compareByDescending<Media>{it.releaseDate.ifBlank{it.year}}.thenByDescending{it.popularity}
            2->compareByDescending<Media>{it.rating}.thenByDescending{it.votes}
            else->compareByDescending<Media>{it.popularity}.thenByDescending{it.votes}
        }.thenBy{it.key}
    )
    return Paged<Media>(1000,{page->data.next(page)}){it.key}
}
