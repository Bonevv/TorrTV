package app.torrtv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import app.torrtv.core.formatSize
import app.torrtv.core.formatSpeed
import app.torrtv.state.Graph
import app.torrtv.torrent.StreamController.Stage
import app.torrtv.torrent.EngineStats
import kotlinx.coroutines.delay

@Composable
fun PrepareScreen() {
    val sc = Graph.stream
    val ui = sc.ui
    val req = ui.req
    val focus = remember { FocusRequester() }
    LaunchedEffect(ui.stage) { focus.tryRequest() }

    val live by produceState<EngineStats?>(null, ui.stage) {
        while (ui.stage == Stage.READY || ui.stage == Stage.BUFFERING) {
            value = sc.stats()
            delay(1000)
        }
    }

    Box(Modifier.fillMaxSize().background(C.bg)) {
        Img(req?.backdrop, Modifier.fillMaxSize(), 780, 439)
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xDD080B12), Color(0xF8080B12)))))
        Column(
            Modifier.align(Alignment.Center).fillMaxWidth(0.62f),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text2(req?.title ?: "", 26, C.text, FontWeight.ExtraBold, 2)
            if (!req?.subtitle.isNullOrBlank()) Text2(req?.subtitle ?: "", 14, C.accent2, FontWeight.Medium, 1)
            Spacer(Modifier.height(22.d))

            when (ui.stage) {
                Stage.PICK_FILE -> {
                    Text2(ui.message, 14, C.text, FontWeight.Bold, 1)
                    Spacer(Modifier.height(8.d))
                    LazyColumn(Modifier.height(230.d), verticalArrangement = Arrangement.spacedBy(4.d)) {
                        items(ui.files, key = { it.index }) { f ->
                            Focusable({ sc.pickFile(f.index) }, Modifier.fillMaxWidth(), RoundedCornerShape(8.d), 1.02f) { fo ->
                                Row(
                                    Modifier.fillMaxWidth().background(if (fo) C.cardHi else C.card).padding(10.d),
                                    horizontalArrangement = Arrangement.spacedBy(10.d)
                                ) {
                                    Text2(f.path, 12, C.text, FontWeight.Medium, 1, Modifier.weight(1f))
                                    Text2(formatSize(f.size), 11, C.dim, FontWeight.Normal, 1)
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(12.d))
                    PrimaryButton("Отказ", { sc.close() }, primary = false, focusRequester = focus)
                }
                Stage.ERROR -> {
                    Text2("⚠ ${ui.error ?: "Грешка"}", 14, C.red, FontWeight.Medium, 5)
                    Spacer(Modifier.height(16.d))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.d)) {
                        PrimaryButton("Опитай пак", { req?.let { sc.start(it) } }, focusRequester = focus)
                        PrimaryButton("Назад", { sc.close() }, primary = false)
                    }
                }
                Stage.READY -> {
                    Text2("✓ Плеърът е пуснат", 16, C.green, FontWeight.Bold, 1)
                    Spacer(Modifier.height(6.d))
                    Text2(ui.fileName, 11, C.dim, FontWeight.Normal, 2)
                    live?.let { Text2("Пиъри ${it.peers} · сийдъри ${it.seeds} · ↓ ${formatSpeed(it.speed)}", 12, C.dim, FontWeight.Medium, 1) }
                    Spacer(Modifier.height(16.d))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.d)) {
                        PrimaryButton("▶ Пусни отново", { sc.relaunch() }, focusRequester = focus)
                        PrimaryButton("■ Спри", { sc.close() }, primary = false)
                    }
                }
                else -> {
                    Spinner(40.d)
                    Spacer(Modifier.height(14.d))
                    Text2(ui.message, 15, C.text, FontWeight.Bold, 1)
                    Spacer(Modifier.height(4.d))
                    val s = live
                    val peers = s?.peers ?: ui.peers
                    val seeds = s?.seeds ?: ui.seeds
                    val speed = s?.speed ?: ui.speed
                    Text2("Пиъри $peers · сийдъри $seeds · ↓ ${formatSpeed(speed)}", 12, C.dim, FontWeight.Medium, 1)
                    if (ui.stage == Stage.BUFFERING) {
                        Spacer(Modifier.height(12.d))
                        Box(Modifier.fillMaxWidth(0.8f).height(8.d).background(C.card, RoundedCornerShape(50))) {
                            Box(
                                Modifier.fillMaxWidth(ui.bufferPct.coerceIn(0, 100) / 100f).height(8.d)
                                    .background(C.grad, RoundedCornerShape(50))
                            )
                        }
                        Spacer(Modifier.height(4.d))
                        Text2("${ui.bufferPct}%  ·  ${ui.fileName}", 11, C.dim, FontWeight.Normal, 1)
                    }
                    if (ui.subsTotal > 0) {
                        Spacer(Modifier.height(6.d))
                        Text2("Субтитри: ${ui.subsDone}/${ui.subsTotal}", 11, C.dim, FontWeight.Normal, 1)
                    }
                    Spacer(Modifier.height(18.d))
                    PrimaryButton("Отказ", { sc.close() }, primary = false, focusRequester = focus)
                }
            }
        }
    }
}
