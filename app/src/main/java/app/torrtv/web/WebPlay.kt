package app.torrtv.web

import android.content.Context
import android.content.Intent
import app.torrtv.core.AppLog
import app.torrtv.torrent.StreamController

/** Предава на екрана за уеб сайтове кои адреси да пробва и за какво заглавие (субтитри и т.н.). */
object WebPlay {
    class Pending(val sources: List<WebSources.Source>, val start: Int, val req: StreamController.Req)

    @Volatile
    var pending: Pending? = null

    fun open(ctx: Context, sources: List<WebSources.Source>, start: Int, req: StreamController.Req) {
        if (sources.isEmpty()) return
        val selected = sources[start.coerceIn(0, sources.size - 1)]
        if (selected.direct) {
            val request = req.copy(fitTitle = selected.release.ifBlank { null })
            app.torrtv.state.Graph.stream.playWeb(selected.url, selected.headers, request, selected.host)
            return
        }
        val pages = BgPlayerPresentation.alternatives(sources, selected)
        pending = Pending(pages, pages.indexOfFirst { it.key == selected.key }.coerceAtLeast(0), req)
        AppLog.d("web", "отварям уеб източник: ${sources[start.coerceIn(0, sources.size - 1)].host} (${sources.size} общо)")
        ctx.startActivity(Intent(ctx, WebSourceActivity::class.java))
    }
}
