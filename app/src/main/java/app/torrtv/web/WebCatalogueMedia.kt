package app.torrtv.web

import app.torrtv.data.*
import app.torrtv.web.catalog.MovieCatalog
import app.torrtv.web.catalog.MovieSources

/** A site's actual record stays playable even when optional TMDB enrichment cannot identify it. */
object WebCatalogueMedia {
    fun allowed(provider:String,page:String):Boolean = try {
        val uri=java.net.URI(page)
        uri.scheme in setOf("https","http") && if(provider=="TWO_K")uri.host in setOf("filmi2k.com","www.filmi2k.com")
            else MovieSources.Provider.valueOf(provider).owns(page)
    }catch(_:Exception){false}

    fun card(e:MovieCatalog.Entry):Media? {
        if(e.title.isBlank() || !allowed(e.provider,e.page))return null
        val type=if(e.series)MediaType.TV else MediaType.MOVIE
        val genres=Genres.forType(type).filter{g->e.genres.any{BgWebIndex.normalize(it)==BgWebIndex.normalize(g.name)}}.map{it.id}
        return Media(-(e.page.hashCode() and Int.MAX_VALUE).coerceAtLeast(1),type,e.title,e.original.ifBlank{e.title},e.description,
            e.poster.takeIf{WebSubtitleProvider.publicUrl(it)},null,
            (if(e.seriesYear>0)e.seriesYear else e.year).takeIf{it>0}?.toString().orEmpty(),0.0,0,genres,"",
            web=WebRef(e.provider,e.page))
    }

    fun detail(m:Media,e:MovieCatalog.Entry):Detail {
        val seasons=(e.seasons.map{it.number}+e.episodes.map{it.season}+listOf(e.season)).filter{it>=0}.distinct().sorted()
        val genres=Genres.forType(m.type).filter{g->e.genres.any{BgWebIndex.normalize(it)==BgWebIndex.normalize(g.name)}}
        val media=m.copy(title=e.title.ifBlank{m.title},originalTitle=e.original.ifBlank{m.originalTitle},
            overview=e.description.ifBlank{m.overview},posterPath=e.poster.takeIf{WebSubtitleProvider.publicUrl(it)}?:m.posterPath,
            genreIds=genres.map{it.id})
        return Detail(media,e.original,"",0,genres,e.imdb.takeIf{it.isNotBlank()},emptyList(),
            seasons.map{n->SeasonInfo(n,if(n==0)"Специални" else "Сезон $n",e.episodes.count{it.season==n})},"",false)
    }

    fun episodes(result:WebSeriesResolver.Result,season:Int)=result.episodes.filter{it.season==season}
        .map{Episode(it.number,it.name,"",null,"",0)}
}
