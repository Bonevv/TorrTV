package app.torrtv.web

import app.torrtv.data.Media
import app.torrtv.web.catalog.MovieCatalog
import app.torrtv.web.catalog.MovieEpisodes
import app.torrtv.web.catalog.MovieSources
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.jsoup.Jsoup
import java.util.concurrent.ConcurrentHashMap

/** Resolves source seasons lazily; the app's titles, artwork and history remain TMDB based. */
class WebSeriesResolver(private val get: suspend (String)->String,
    private val bgPlayers:suspend(String,String)->List<String> = {page,html->ZenoProvider.players(page,html)}) {
    data class Episode(val season:Int,val number:Int,val name:String,val bgAudio:Boolean)
    data class Result(val seasons:List<Int> = emptyList(),val episodes:List<Episode> = emptyList(),
        val sources:List<WebSources.Source> = emptyList(),val entries:List<MovieCatalog.Entry> = emptyList())
    private data class Cached(val entry:MovieCatalog.Entry,val at:Long)
    private val cache=ConcurrentHashMap<String,Cached>()
    private val gate=Semaphore(2)

    private suspend fun detail(e:MovieCatalog.Entry):MovieCatalog.Entry {
        cache[e.page]?.takeIf{System.currentTimeMillis()-it.at in 0..30*60_000L}?.let{return it.entry}
        return gate.withPermit {
            val parsed=withTimeout(8_000){MovieSources.detail(e,Jsoup.parse(get(e.page.substringBefore('#')),e.page))}
            if(cache.size>=48)cache.entries.minByOrNull{it.value.at}?.let{cache.remove(it.key)}
            cache[e.page]=Cached(parsed,System.currentTimeMillis())
            parsed
        }
    }

    suspend fun resolve(m:Media,imdb:String?,entries:List<MovieCatalog.Entry>,season:Int,episode:Int):Result = coroutineScope {
        val selected=entries.distinctBy{it.page}.groupBy{it.provider}.values.map{group->group.maxBy{e->
            (if(BgWebIndex.matches(m,e,imdb))100 else 0)+(if(e.season==season)300 else if(e.season<0)10 else if(e.season==1)5 else 0)
        }}.take(7)
        val loaded=selected.map{original->async(Dispatchers.IO){
            try {
                var e=detail(original)
                if(!BgWebIndex.matches(m,e,imdb)) {
                    // A later season's release year is not the series' first-air year.
                    // Establish the identity from its own first-season link before joining it.
                    val first=e.seasons.firstOrNull{it.number==1 && !it.loaded}
                    if(first!=null && e.season!=1) {
                        val anchor=detail(linked(e,first))
                        if(BgWebIndex.matches(m,anchor,imdb))e=copy(e).also{it.seriesYear=m.year.toIntOrNull()?:0}
                    }
                }
                if(!BgWebIndex.matches(m,e,imdb))return@async null
                val link=e.seasons.firstOrNull{it.number==season && !it.loaded}
                val extra=link?.let{detail(linked(e,it))}
                Pair(e,extra)
            }catch(ex:CancellationException){if(ex is TimeoutCancellationException)null else throw ex}
            catch(_:Exception){null}
        }}.awaitAll().filterNotNull()
        val pages=loaded.flatMap{listOfNotNull(it.first,it.second)}.distinctBy{it.page}
        val seasons=pages.flatMap{e->e.seasons.map{it.number}+e.episodes.map{it.season}}.filter{it>=0}.distinct().sorted()
        val all=pages.flatMap{e->e.episodes.filter{it.season>=0 && it.number>0}.map{ep->
            Episode(ep.season,ep.number,ep.name,audio(e,ep))
        }}.groupBy{it.season to it.number}.values.map{same->same.first().copy(bgAudio=same.any{it.bgAudio})}
            .sortedWith(compareBy<Episode>{it.season}.thenBy{it.number})
        val sources=pages.flatMap{e->e.episodes.filter{it.season==season && it.number==episode}.mapNotNull{ep->
            // Language belongs to this season/episode; another season cannot confer its badge.
            var bg=audio(e,ep)
            try {
                val embed=if(e.provider=="ZENO" && (bg || e.bgAudio && e.audioVerified))gate.withPermit{withTimeout(8_000){
                    bgPlayers(ep.page,get(ep.page.substringBefore('#'))).firstOrNull()
                }}?.also{bg=true} ?: ep.embed.also{bg=false} else ep.embed
                BgPlayerPresentation.source(WebSources.hostOf(ep.page),ep.page,embed,bg)
            }catch(ex:CancellationException){if(ex is TimeoutCancellationException)null else throw ex}catch(_:Exception){null}
        }}.distinctBy{it.key}
        Result(seasons,all.map{if(it.season==season && it.number==episode)it.copy(bgAudio=sources.any{source->source.bgAudio})else it},sources,pages)
    }

    private fun audio(e:MovieCatalog.Entry,ep:MovieCatalog.Episode):Boolean {
        ep.bgAudio?.let{return it}
        if(!e.bgAudio || !e.audioVerified || e.season<0 || e.season!=ep.season)return false
        val current=MovieEpisodes.number(e.page)
        return current<=0 || current==ep.number && e.page.substringBefore('#')==ep.page.substringBefore('#')
    }

    private fun copy(e:MovieCatalog.Entry)=MovieCatalog.Entry().also{
        it.provider=e.provider;it.page=e.page;it.title=e.title;it.original=e.original;it.year=e.year
        it.tmdb=e.tmdb;it.imdb=e.imdb;it.series=true;it.season=e.season;it.seriesYear=e.seriesYear
        it.bgAudio=e.bgAudio;it.audioVerified=e.audioVerified;it.embed=e.embed
        it.episodes.addAll(e.episodes);it.seasons.addAll(e.seasons)
    }
    private fun linked(e:MovieCatalog.Entry,link:MovieCatalog.SeasonLink)=MovieCatalog.Entry().also{
        it.provider=e.provider;it.page=link.page;it.title=e.title;it.original=e.original;it.series=true
        it.season=link.number;it.year=link.year;it.seriesYear=e.seriesYear
        it.bgAudio=link.bgAudio==true;it.audioVerified=link.bgAudio==true
    }
}
