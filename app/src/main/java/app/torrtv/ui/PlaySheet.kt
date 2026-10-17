package app.torrtv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.torrtv.data.subs.Langs
import app.torrtv.data.subs.SubtitleResult
import app.torrtv.data.torrents.TorrentResult
import app.torrtv.state.DetailState
import app.torrtv.state.Graph
import app.torrtv.torrent.ExternalPlayers

/** Диалог преди пускане: избор на плеър и субтитри за този конкретен торент. */
@Composable
fun PlaySheet(st: DetailState, t: TorrentResult, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val ranked = remember(t, st.subResults) { st.rankedSubs(t).take(14) }
    val installed = remember { ExternalPlayers.installed(ctx) }
    val options = remember { listOf("builtin") + installed.map { it.id } + "ask" }
    var player by remember {
        mutableStateOf(Graph.prefs.player.let { if (it in options) it else "builtin" })
    }
    val picked = remember(ranked) {
        mutableStateListOf<String>().also { l ->
            // По подразбиране: най-добрият субтитър
            ranked.firstOrNull()?.let { l.add(it.uid) }
        }
    }
    val startFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { startFocus.tryRequest() }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier.fillMaxWidth(0.72f).clip(RoundedCornerShape(18.d)).background(C.bg2).padding(22.d)
        ) {
            Text2(t.title, 14, C.text, FontWeight.Bold, 2)
            Spacer(Modifier.height(4.d))
            Row(horizontalArrangement = Arrangement.spacedBy(8.d)) {
                Pill(t.quality, C.accent, C.text)
                Pill("${t.seeders} сийдъра", C.card, seedColor(t.seeders))
                Pill(app.torrtv.core.formatSize(t.size))
            }
            if (t.seeders < 3) {
                Spacer(Modifier.height(6.d))
                Text2("⚠ Малко сийдъри - буферирането може да е бавно.", 12, C.amber, FontWeight.Medium, 2)
            }

            Spacer(Modifier.height(14.d))
            Text2("Плеър", 13, C.dim, FontWeight.Bold, 1)
            Spacer(Modifier.height(6.d))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.d)) {
                items(options, key = { it }) { id ->
                    Chip(ExternalPlayers.label(id), player == id, { player = id; Graph.prefs.player = id })
                }
            }
            if (player == "builtin") {
                Spacer(Modifier.height(4.d))
                Text2("Вграденият плеър не поддържа DTS звук - за такива файлове ползвай VLC / MX / Just Player.", 11, C.dim, FontWeight.Normal, 2)
            }

            Spacer(Modifier.height(14.d))
            Text2("Субтитри" + if (st.subSearching) " (търсят се…)" else "", 13, C.dim, FontWeight.Bold, 1)
            Spacer(Modifier.height(6.d))
            if (ranked.isEmpty()) {
                Text2("Няма намерени субтитри за този епизод/филм.", 12, C.dim, FontWeight.Normal, 2)
            } else {
                LazyColumn(Modifier.heightIn(max = 190.d), verticalArrangement = Arrangement.spacedBy(4.d)) {
                    items(ranked, key = { it.uid }) { s -> SubRow(s, s.uid in picked) {
                        if (s.uid in picked) picked.remove(s.uid) else if (picked.size < 4) picked.add(s.uid)
                    } }
                }
                Spacer(Modifier.height(4.d))
                Text2("Може да избереш до 4 - в плеъра се превключват.", 10, C.dim, FontWeight.Normal, 1)
            }

            Spacer(Modifier.height(16.d))
            Row(horizontalArrangement = Arrangement.spacedBy(10.d)) {
                PrimaryButton("▶  Пусни", {
                    val chosen = ranked.filter { it.uid in picked }
                    Graph.stream.start(st.playRequest(t, chosen, player))
                    onDismiss()
                }, focusRequester = startFocus)
                PrimaryButton("Откажи", onDismiss, primary = false)
            }
        }
    }
}

@Composable
private fun SubRow(s: SubtitleResult, on: Boolean, toggle: () -> Unit) {
    Focusable(toggle, Modifier.fillMaxWidth(), RoundedCornerShape(8.d), 1.02f) { f ->
        Row(
            Modifier.fillMaxWidth().background(if (f) C.cardHi else C.card).padding(horizontal = 10.d, vertical = 6.d),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.d)
        ) {
            Text2(if (on) "☑" else "☐", 14, if (on) C.accent2 else C.dim, FontWeight.Bold, 1)
            Pill(Langs.choices.firstOrNull { it.first == s.lang }?.second ?: s.lang.uppercase(), C.cardHi, C.text)
            Text2(s.release.ifBlank { "(без име)" }, 12, C.text, FontWeight.Medium, 1, Modifier.weight(1f))
            if (s.trusted) Pill("✓", Color(0x333DDC84), C.green)
            Text2(s.providerName, 10, C.dim, FontWeight.Normal, 1)
        }
    }
}
