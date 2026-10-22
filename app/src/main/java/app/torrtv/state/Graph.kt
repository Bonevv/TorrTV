package app.torrtv.state

import android.app.Application
import app.torrtv.core.Prefs
import app.torrtv.data.MediaType
import app.torrtv.data.Remote
import app.torrtv.data.Tmdb
import app.torrtv.data.subs.SubtitleAggregator
import app.torrtv.data.subs.SubtitleStore
import app.torrtv.data.torrents.TorrentAggregator
import app.torrtv.torrent.LocalServer
import app.torrtv.torrent.StreamController
import app.torrtv.update.Updater
import app.torrtv.web.ConfigServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Единно място за всички дълготрайни обекти на приложението. */
object Graph {
    lateinit var app: Application

    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    val prefs by lazy { Prefs(app) }
    val tmdb by lazy { Tmdb(prefs, java.io.File(app.cacheDir, "tmdb")) }
    val torrents by lazy { TorrentAggregator(prefs) }
    val subs by lazy { SubtitleAggregator(prefs) }
    val subStore by lazy { SubtitleStore(app.cacheDir, subs.providers) }
    val server by lazy { LocalServer() }
    val stream by lazy { StreamController(app, subStore, server) }
    val updater by lazy { Updater(app) }
    val sync by lazy { SyncState(prefs) }
    val config by lazy {
        ConfigServer(prefs).also { c ->
            c.recent = { RecentSearches.decode(prefs.recentSearches) }
            // Търсене от телефона: идва от нишка на сървъра, а състоянието на екраните се пипа само от главната.
            c.onSearch = { q -> scope.launch { search.fromPhone(q) } }
            // Вход от телефона: изпълнява се на главната нишка, а нишката на сървъра чака отговора.
            c.onLogin = { n, p ->
                val done = kotlinx.coroutines.CompletableDeferred<String>()
                scope.launch { done.complete(sync.login(n, p).let { r -> if (r.ok) "" else Remote.message(r).orEmpty() }) }
                try {
                    kotlinx.coroutines.runBlocking { kotlinx.coroutines.withTimeout(40_000) { done.await() } }
                } catch (e: Exception) {
                    "Няма отговор. Опитай пак."
                }
            }
        }
    }

    // Състояние на екраните (живее колкото приложението; размерите са ограничени)
    val home by lazy { HomeState() }
    val browseMovies by lazy { BrowseState(MediaType.MOVIE) }
    val browseTv by lazy { BrowseState(MediaType.TV) }
    val browseAnime by lazy { BrowseState(MediaType.TV, forceAnime = true) }
    val search by lazy { SearchState() }
    val dice by lazy { DiceState() }
}
