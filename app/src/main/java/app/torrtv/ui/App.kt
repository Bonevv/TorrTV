package app.torrtv.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import app.torrtv.core.CacheManager
import app.torrtv.data.Media
import app.torrtv.data.MediaType
import app.torrtv.data.torrents.TorrentResult
import app.torrtv.state.DetailState
import app.torrtv.state.Graph
import app.torrtv.state.Library
import app.torrtv.torrent.StreamController.Stage

/** Брой раздели в страничния панел (виж SideRail); 6 е "Настройки". */
private const val TAB_COUNT = 8
private const val TAB_SEARCH = 5
private const val TAB_SETTINGS = 6

/** Отворен диалог за пускане: за кой екран, кой торент и от коя секунда да продължи (0 = отначало). */
private class Sheet(val st: DetailState, val t: TorrentResult, val resumeMs: Long = 0L)

@Composable
fun App() {
    // Лекият режим се чете веднъж при старт (преди първия кадър): избраното от потребителя или, по подразбиране, според устройството.
    val ctx = androidx.compose.ui.platform.LocalContext.current
    remember { Look.lite = Graph.prefs.liteOn(app.torrtv.core.Device.isWeak(ctx)); 0 }
    var tab by remember { mutableStateOf(0) }
    val stack = remember { mutableStateListOf<DetailState>() }
    var sheet by remember { mutableStateOf<Sheet?>(null) }
    // "Продължи гледане" от началото: отваря заглавието и пуска диалога, щом данните са заредени.
    var pending by remember { mutableStateOf<Library.Progress?>(null) }
    val home = Graph.home
    val streaming = Graph.stream.ui.stage != Stage.IDLE
    val tabFocus = remember { List(TAB_COUNT) { FocusRequester() } }
    var exitAsk by remember { mutableStateOf(false) }
    // Веднъж след първото влизане с профил: какъв е боксът и какво се препоръчва.
    var boxAsk by remember { mutableStateOf(app.torrtv.data.Remote.enabled && !Graph.prefs.boxChecked) }

    LaunchedEffect(Unit) { home.checkUpdate() }
    // Първото пускане в новия месец: тиха проверка за нови настройки (само ако приложението е свързано с профил).
    LaunchedEffect(Unit) { Graph.sync.monthlyCheck() }

    // Дума, изпратена от страницата на телефона: отваря се търсенето и се показват резултатите.
    val remote = Graph.search.remoteStamp
    LaunchedEffect(remote) { if (remote > 0 && stack.isEmpty()) tab = TAB_SEARCH }

    // Автоматично чистене на кеша на всеки 15 минути (по зададения интервал и лимит в Настройки).
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(15 * 60_000L)
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                CacheManager.maybeAutoClean(ctx, Graph.stream.ui.stage != Stage.IDLE)
            }
        }
    }

    // След края на гледане (и при старт): ако кешът е над лимита - чисти се.
    LaunchedEffect(streaming) {
        if (!streaming) {
            kotlinx.coroutines.delay(4000)
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { CacheManager.maybeAutoClean(ctx, false) }
        }
    }

    fun open(m: Media, deferTorrents: Boolean = false) {
        if (stack.size >= 8) stack.removeAt(0).cancel()
        stack.add(DetailState(m.type, m.id, deferTorrents))
    }

    fun pop() {
        pending = null
        if (stack.isNotEmpty()) stack.removeAt(stack.size - 1).cancel()
    }

    fun resume(p: Library.Progress) {
        if (p.nextUp) {
            // Следва нов епизод: отваря се страницата на сериала точно на него (виж DetailState.start).
            open(p.media)
            return
        }
        // Торентът е известен - търсенето в сайтовете се отлага, докато не потрябва.
        open(p.media, deferTorrents = true)
        pending = p
    }


    val topDetail = stack.lastOrNull()?.detail
    LaunchedEffect(topDetail, pending) {
        val pr = pending
        val st = stack.lastOrNull()
        if (pr != null && st != null && st.detail?.media?.key == pr.media.key) {
            sheet = Sheet(st, pr.torrent(), pr.posMs)
            pending = null
        }
    }

    // Назад от Начало пита същото като бутона "Изход", вместо да излиза тихо и да оставя процеса в паметта.
    BackHandler(enabled = true) {
        when {
            sheet != null -> sheet = null
            streaming -> Graph.stream.close()
            stack.isNotEmpty() -> pop()
            tab != 0 -> tab = 0
            else -> exitAsk = true
        }
    }

    Box(Modifier.fillMaxSize()) {
        val top = stack.lastOrNull()
        if (streaming) {
            // пред плеъра не държим друг фокусируем интерфейс отзад
        } else if (top != null) {
            DetailScreen(
                top, onOpen = { open(it) },
                onPlay = { t, at -> sheet = Sheet(top, t, at) },
                sheetOpen = sheet != null || pending != null
            )
        } else {
            Row(Modifier.fillMaxSize()) {
                SideRail(
                    selected = tab, settingsTab = TAB_SETTINGS, onSelect = { tab = it }, focus = tabFocus,
                    updateReady = home.update != null, onExit = { exitAsk = true }
                )
                // Началото е с банер до горния ръб; другите екрани получават малък отстъп отгоре.
                Box(Modifier.weight(1f).fillMaxHeight().padding(top = if (tab == 0) 0.d else 14.d).stayInContent()) {
                    when (tab) {
                        0 -> HomeScreen(home, ::open, ::resume)
                        1 -> BrowseScreen(Graph.browseMovies, ::open)
                        2 -> BrowseScreen(Graph.browseTv, ::open)
                        3 -> FavoritesScreen(::open)
                        7 -> BrowseScreen(Graph.browseAnime, ::open)
                        4 -> DiceScreen(Graph.dice, ::open)
                        TAB_SEARCH -> SearchScreen(Graph.search, ::open)
                        else -> SettingsScreen()
                    }
                }
            }
        }

        sheet?.let { sh ->
            PlaySheet(sh.st, sh.t, sh.resumeMs, onSwitch = { nt -> sheet = Sheet(sh.st, nt, 0L) }) { sheet = null }
        }
        if (streaming) PrepareScreen(stack.lastOrNull())
        if (boxAsk && !streaming) BoxCheckDialog(ctx) { Graph.prefs.boxChecked = true; boxAsk = false }
        if (exitAsk && !streaming) ExitDialog({ exitAsk = false; stopApp(ctx) }, { exitAsk = false })
    }
}
