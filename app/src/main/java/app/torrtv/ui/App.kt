package app.torrtv.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.text.font.FontWeight
import app.torrtv.core.CacheManager
import app.torrtv.data.Media
import app.torrtv.data.MediaType
import app.torrtv.data.torrents.TorrentResult
import app.torrtv.state.DetailState
import app.torrtv.state.Graph
import app.torrtv.state.Library
import app.torrtv.torrent.StreamController.Stage

private val tabs = listOf("🏠 Начало", "🎬 Филми", "📺 Сериали", "♥ Любими", "🎲 Зарче", "🔍 Търсене", "⚙ Настройки")
private const val TAB_SETTINGS = 6

/** Отворен диалог за пускане: за кой екран, кой торент и от коя секунда да продължи (0 = отначало). */
private class Sheet(val st: DetailState, val t: TorrentResult, val resumeMs: Long = 0L)

@Composable
fun App() {
    // Лекият режим се чете веднъж при старт (преди първия кадър).
    remember { Look.lite = Graph.prefs.lite; 0 }
    var tab by remember { mutableStateOf(0) }
    val stack = remember { mutableStateListOf<DetailState>() }
    var sheet by remember { mutableStateOf<Sheet?>(null) }
    // "Продължи гледане" от началото: отваря заглавието и пуска диалога, щом данните са заредени.
    var pending by remember { mutableStateOf<Library.Progress?>(null) }
    val home = Graph.home
    val streaming = Graph.stream.ui.stage != Stage.IDLE
    val tabFocus = remember { List(tabs.size) { FocusRequester() } }

    LaunchedEffect(Unit) { home.checkUpdate() }

    // Автоматично чистене на кеша на всеки 15 минути (по зададения интервал в Настройки) и показване на RAM.
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val ramMb by androidx.compose.runtime.produceState(0) {
        var n = 0
        while (true) {
            value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { CacheManager.memory(ctx).totalMb }
            if (n++ % 90 == 0) {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    CacheManager.maybeAutoClean(ctx, Graph.stream.ui.stage != Stage.IDLE)
                }
            }
            kotlinx.coroutines.delay(10_000)
        }
    }

    // След края на гледане (и при старт): ако кешът е над лимита - чисти се.
    LaunchedEffect(streaming) {
        if (!streaming) {
            kotlinx.coroutines.delay(4000)
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { CacheManager.maybeAutoClean(ctx, false) }
        }
    }

    fun open(m: Media) {
        if (stack.size >= 8) stack.removeAt(0).cancel()
        stack.add(DetailState(m.type, m.id))
    }

    fun pop() {
        pending = null
        if (stack.isNotEmpty()) stack.removeAt(stack.size - 1).cancel()
    }

    fun resume(p: Library.Progress) {
        open(p.media)
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

    BackHandler(enabled = sheet != null || streaming || stack.isNotEmpty() || tab != 0) {
        when {
            sheet != null -> sheet = null
            streaming -> Graph.stream.close()
            stack.isNotEmpty() -> pop()
            else -> tab = 0
        }
    }

    Box(Modifier.fillMaxSize()) {
        val top = stack.lastOrNull()
        if (streaming) {
            // пред плеъра не държим друг фокусируем интерфейс отзад
        } else if (top != null) {
            DetailScreen(
                top, onOpen = { open(it) },
                onPlay = { t, at -> sheet = Sheet(top, t, at) }
            )
        } else {
            Column(Modifier.fillMaxSize()) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 36.d, vertical = 8.d),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.d)
                ) {
                    Row(verticalAlignment = Alignment.Bottom) {
                        androidx.compose.material3.Text("TORR", color = C.text, fontSize = 20.t, fontWeight = FontWeight.Light, letterSpacing = 2.t)
                        androidx.compose.material3.Text("TV", color = C.accent, fontSize = 20.t, fontWeight = FontWeight.Black, letterSpacing = 1.t)
                    }
                    Spacer(Modifier.width(14.d))
                    tabs.forEachIndexed { i, name ->
                        Chip(name, tab == i, { tab = i }, focusRequester = tabFocus[i], compact = true)
                    }
                    Spacer(Modifier.weight(1f))
                    if (home.update != null) {
                        PrimaryButton("⬆ Нова версия", { tab = TAB_SETTINGS })
                    } else if (ramMb > 0) Pill("RAM $ramMb MB", C.card, if (ramMb > 450) C.amber else C.dim)
                }
                Box(Modifier.fillMaxWidth().weight(1f)) {
                    when (tab) {
                        0 -> HomeScreen(home, ::open, ::resume)
                        1 -> BrowseScreen(Graph.browseMovies, ::open)
                        2 -> BrowseScreen(Graph.browseTv, ::open)
                        3 -> FavoritesScreen(::open)
                        4 -> DiceScreen(Graph.dice, ::open)
                        5 -> SearchScreen(Graph.search, ::open)
                        else -> SettingsScreen()
                    }
                }
            }
        }

        sheet?.let { sh ->
            PlaySheet(sh.st, sh.t, sh.resumeMs, onSwitch = { nt -> sheet = Sheet(sh.st, nt, 0L) }) { sheet = null }
        }
        if (streaming) PrepareScreen(stack.lastOrNull())
    }
}
