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
import app.torrtv.data.Media
import app.torrtv.data.MediaType
import app.torrtv.data.torrents.TorrentResult
import app.torrtv.state.DetailState
import app.torrtv.state.Graph
import app.torrtv.torrent.StreamController.Stage

private val tabs = listOf("🏠 Начало", "🎬 Филми", "📺 Сериали", "🎲 Зарче", "🔍 Търсене", "⚙ Настройки")

@Composable
fun App() {
    var tab by remember { mutableStateOf(0) }
    val stack = remember { mutableStateListOf<DetailState>() }
    var sheet by remember { mutableStateOf<Pair<DetailState, TorrentResult>?>(null) }
    val home = Graph.home
    val streaming = Graph.stream.ui.stage != Stage.IDLE
    val tabFocus = remember { List(tabs.size) { FocusRequester() } }

    LaunchedEffect(Unit) { home.checkUpdate() }

    fun open(m: Media) {
        if (stack.size >= 8) stack.removeAt(0).cancel()
        stack.add(DetailState(m.type, m.id))
    }

    fun pop() {
        if (stack.isNotEmpty()) stack.removeAt(stack.size - 1).cancel()
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
                onPlay = { t -> sheet = top to t }
            )
        } else {
            Column(Modifier.fillMaxSize()) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 36.d, vertical = 8.d),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.d)
                ) {
                    Text2("TorrTV", 20, C.accent, FontWeight.Black, 1)
                    Spacer(Modifier.width(14.d))
                    tabs.forEachIndexed { i, name ->
                        Chip(name, tab == i, { tab = i }, focusRequester = tabFocus[i])
                    }
                    Spacer(Modifier.weight(1f))
                    if (home.update != null) {
                        PrimaryButton("⬆ Нова версия", { tab = 5 })
                    }
                }
                Box(Modifier.fillMaxWidth().weight(1f)) {
                    when (tab) {
                        0 -> HomeScreen(home, ::open)
                        1 -> BrowseScreen(Graph.browseMovies, ::open)
                        2 -> BrowseScreen(Graph.browseTv, ::open)
                        3 -> DiceScreen(Graph.dice, ::open)
                        4 -> SearchScreen(Graph.search, ::open)
                        else -> SettingsScreen()
                    }
                }
            }
        }

        sheet?.let { (st, t) -> PlaySheet(st, t) { sheet = null } }
        if (streaming) PrepareScreen()
    }
}
