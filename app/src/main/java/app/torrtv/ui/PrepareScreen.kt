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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
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
import app.torrtv.state.DidYouKnow
import app.torrtv.state.Graph
import app.torrtv.torrent.StreamController.Stage
import app.torrtv.torrent.EngineStats
import kotlinx.coroutines.delay

@Composable
fun PrepareScreen(st: app.torrtv.state.DetailState? = null) {
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

    // Колко време върви подготовката (за да познаем торент без сийдъри / твърде бавно теглене).
    val secs by produceState(0, ui.stage) {
        while (true) {
            value = ((android.os.SystemClock.elapsedRealtime() - sc.startedAt) / 1000).toInt()
            delay(1000)
        }
    }
    var pickOpen by remember { mutableStateOf(false) }
    val ctx = androidx.compose.ui.platform.LocalContext.current

    Box(Modifier.fillMaxSize().background(C.bg)) {
        Img(req?.backdrop, Modifier.fillMaxSize())
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xDD07090E), Color(0xF807090E)))))
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
                    Text2(ui.error ?: "Нещо се обърка.", 14, C.red, FontWeight.Medium, 5)
                    Spacer(Modifier.height(16.d))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.d)) {
                        PrimaryButton("Опитай пак", { req?.let { sc.start(it) } }, focusRequester = focus)
                        PrimaryButton("Назад", { sc.close() }, primary = false)
                    }
                }
                Stage.READY -> {
                    Text2("Плеърът е пуснат", 16, C.green, FontWeight.Bold, 1)
                    Spacer(Modifier.height(6.d))
                    if (ui.webHost.isNotEmpty()) {
                        Text2("Гледаш от сайт: ${ui.webHost}", 12, C.accent2, FontWeight.Medium, 1)
                    } else {
                        Text2(ui.fileName, 11, C.dim, FontWeight.Normal, 2)
                        live?.let { Text2("Пиъри ${it.peers} · сийдъри ${it.seeds} · ↓ ${formatSpeed(it.speed)}", 12, C.dim, FontWeight.Medium, 1) }
                    }
                    Spacer(Modifier.height(16.d))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.d)) {
                        PrimaryButton("▶ Пусни отново", { sc.relaunch() }, focusRequester = focus)
                        if (st != null) PrimaryButton("Откъде?", { pickOpen = true }, primary = false)
                        PrimaryButton("Спри", { sc.close() }, primary = false)
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
                    if (ui.webHost.isNotEmpty()) {
                        Text2("Видео от сайт: ${ui.webHost}", 12, C.accent2, FontWeight.Medium, 1)
                    } else {
                        Text2("Пиъри $peers · сийдъри $seeds · ↓ ${formatSpeed(speed)}", 12, C.dim, FontWeight.Medium, 1)
                    }
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
                    // Няма сийдъри или тегли много бавно: предлагаме друго място за гледане.
                    val weak = req?.torrent != null && st != null && when (ui.stage) {
                        Stage.CONNECTING, Stage.METADATA -> secs >= 25
                        Stage.BUFFERING -> (secs >= 15 && seeds == 0) || (secs >= 25 && ui.bufferPct < 70 && speed < 120_000)
                        else -> false
                    }
                    if (weak) {
                        LaunchedEffect(Unit) { focus.tryRequest() }
                        Spacer(Modifier.height(14.d))
                        Row(
                            Modifier.fillMaxWidth().background(Color(0x33FFB020), RoundedCornerShape(12.d)).padding(horizontal = 14.d, vertical = 10.d),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.d)
                        ) {
                            Text2("Този торент има малко сийдъри. Гледай от:", 13, C.amber, FontWeight.Bold, 2, Modifier.weight(1f))
                            PrimaryButton("Откъде?", { pickOpen = true }, focusRequester = focus)
                        }
                    }
                    // „Знаеш ли“: кратък факт за заглавието или съвет, докато се чака. Сменя се на всеки 8 секунди.
                    if (!weak && secs >= 3) {
                        val facts = remember(st?.detail, req?.media?.key) {
                            val d = st?.detail
                            DidYouKnow.mix(if (d != null && d.media.key == req?.media?.key) DidYouKnow.facts(d) else emptyList(), DidYouKnow.tips)
                        }
                        val n by produceState(0) {
                            while (true) {
                                delay(8000)
                                value += 1
                            }
                        }
                        if (facts.isNotEmpty()) {
                            Spacer(Modifier.height(16.d))
                            Column(
                                Modifier.fillMaxWidth().background(C.card, RoundedCornerShape(12.d)).padding(horizontal = 14.d, vertical = 10.d)
                            ) {
                                Text2("Знаеш ли", 11, C.accent2, FontWeight.Bold, 1)
                                Spacer(Modifier.height(3.d))
                                Text2(facts[n % facts.size], 13, C.text, FontWeight.Normal, 3)
                            }
                        }
                    }
                    Spacer(Modifier.height(18.d))
                    PrimaryButton("Отказ", { sc.close() }, primary = false, focusRequester = if (weak) null else focus)
                }
            }
        }
        if (pickOpen && st != null && req != null) {
            SourcePicker(
                st, req.torrent?.hash,
                onTorrent = { nt -> sc.start(st.playRequest(nt, req.subs, req.player, 0L)) },
                onWeb = { i -> sc.close(); app.torrtv.web.WebPlay.open(ctx, st.webSources(), i, st.webRequest()) },
                onDismiss = { pickOpen = false }
            )
        }
    }
}
