package app.torrtv.web

import app.torrtv.data.Media
import app.torrtv.data.Page
import app.torrtv.web.catalog.MovieCatalog
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/** Pages through source evidence, then resolves cards from TMDB; never intersects two partial lists. */
class BgAudioPager(
    private val initial:suspend()->Unit,
    private val entries:()->List<MovieCatalog.Entry>,
    private val expand:suspend()->Unit,
    private val hasMore:()->Boolean,
    private val resolve:suspend(MovieCatalog.Entry)->Media?,
    private val accept:(Media)->Boolean = {true},
    private val onChecked:suspend(List<MovieCatalog.Entry>)->Unit = {},
    private val batchSize:Int=12
) {
    private var initialized=false
    private val checked=HashSet<String>()
    private val resolved=LinkedHashMap<String,Media>()
    private val failures=HashMap<String,Int>()
    private val pendingSave=LinkedHashMap<String,MovieCatalog.Entry>()
    private val gate=Semaphore(2)

    suspend fun next(page:Int):Page<Media> = withContext(Dispatchers.IO) {
        if(!initialized){initial();initialized=true}
        fun pending()=entries().filter{it.page !in checked}.sortedBy{failures[it.page]?:0}
        var todo=pending()
        if(todo.size<batchSize && hasMore()){expand();todo=pending()}
        val batch=todo.take(batchSize).map{copy(it)}
        val results=coroutineScope {batch.map{e->async{
            gate.withPermit {
                try { Triple(e,resolve(e),null as Exception?) }
                catch(ex:CancellationException){if(ex is TimeoutCancellationException)Triple(e,null,ex) else throw ex}
                catch(ex:Exception){Triple(e,null,ex)}
            }
        }}.awaitAll()}
        var failed=0
        for((e,m,error) in results){
            if(error!=null){
                failed++;failures[e.page]=(failures[e.page]?:0)+1
                if(failures.getValue(e.page)>=2)checked.add(e.page)
            }else{
                checked.add(e.page)
                if(m!=null && accept(m))resolved[m.key]=m
            }
        }
        for((e,_,error) in results)if(error==null)pendingSave[e.page]=e
        if(pendingSave.isNotEmpty()){onChecked(pendingSave.values.toList());pendingSave.clear()}
        if(failed>0 && results.all{it.third!=null} && resolved.isEmpty())throw java.io.IOException("Заглавията не можаха да се свържат с каталога. Опитай отново.")
        val more=pending().isNotEmpty() || hasMore()
        // Returning all resolved cards also recovers a batch cancelled just before UI delivery.
        Page(resolved.values.toList(),page,if(more)Int.MAX_VALUE else page)
    }
    private fun copy(e:MovieCatalog.Entry)=MovieCatalog.Entry().also{
        it.provider=e.provider;it.page=e.page;it.title=e.title;it.original=e.original;it.year=e.year
        it.tmdb=e.tmdb;it.imdb=e.imdb;it.series=e.series;it.season=e.season;it.seriesYear=e.seriesYear
        it.bgAudio=e.bgAudio;it.audioVerified=e.audioVerified;it.embed=e.embed;it.genres.addAll(e.genres)
    }
}
