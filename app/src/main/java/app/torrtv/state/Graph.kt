package app.torrtv.state

import android.app.Application
import app.torrtv.core.Prefs
import app.torrtv.data.MediaType
import app.torrtv.data.Tmdb
import app.torrtv.data.subs.SubtitleAggregator
import app.torrtv.data.subs.SubtitleStore
import app.torrtv.data.torrents.TorrentAggregator
import app.torrtv.torrent.LocalServer
import app.torrtv.torrent.StreamController
import app.torrtv.torrent.TorrentEngine
import app.torrtv.update.Updater
import app.torrtv.web.ConfigServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** Единно място за всички дълготрайни обекти на приложението. */
object Graph {
    lateinit var app: Application

    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    val prefs by lazy { Prefs(app) }
    val tmdb by lazy { Tmdb(prefs) }
    val torrents by lazy { TorrentAggregator(prefs) }
    val subs by lazy { SubtitleAggregator(prefs) }
    val subStore by lazy { SubtitleStore(app.cacheDir, subs.providers) }
    val engine by lazy { TorrentEngine(app) }
    val server by lazy { LocalServer() }
    val stream by lazy { StreamController(app, engine, subStore, server) }
    val updater by lazy { Updater(app) }
    val config by lazy { ConfigServer(prefs) }

    // Състояние на екраните (живее колкото приложението; размерите са ограничени)
    val home by lazy { HomeState() }
    val browseMovies by lazy { BrowseState(MediaType.MOVIE) }
    val browseTv by lazy { BrowseState(MediaType.TV) }
    val search by lazy { SearchState() }
    val dice by lazy { DiceState() }
}
