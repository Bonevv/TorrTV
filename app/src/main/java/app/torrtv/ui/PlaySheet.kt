package app.torrtv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.runtime.rememberUpdatedState
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
import app.torrtv.data.subs.Fit
import app.torrtv.data.subs.Langs
import app.torrtv.data.subs.SubRank
import app.torrtv.data.subs.SubtitleResult
import app.torrtv.data.torrents.TorrentResult
import app.torrtv.state.DetailState
import app.torrtv.state.Graph
import app.torrtv.torrent.ExternalPlayers
import kotlinx.coroutines.delay

/** Диалог преди пускане: избор на плеър и субтитри за този конкретен торент. */
@Composable
fun PlaySheet(st: DetailState, t: TorrentResult, resumeMs: Long = 0L, onSwitch: (TorrentResult) -> Unit = {}, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val ranked = remember(t, st.subResults) { st.rankedSubs(t).take(14) }
    val fits = remember(ranked, t) { ranked.associate { it.uid to SubRank.fit(it, t.title) } }
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
    var subsOpen by remember { mutableStateOf(false) }
    var pickOpen by remember { mutableStateOf(false) }
    val startFocus = remember { FocusRequester() }
    LaunchedEffect(t) { startFocus.tryRequest() }

    // Субтитрите често са още на път, когато натиснеш „Пусни“. Чака се до 6 секунди; втори натиск пуска веднага.
    val rankedNow by rememberUpdatedState(ranked)
    val pickedNow by rememberUpdatedState(picked)
    val playerNow by rememberUpdatedState(player)
    var waitFrom by remember { mutableStateOf(-1L) }
    val started = remember { java.util.concurrent.atomic.AtomicBoolean(false) }
    fun begin(from: Long) {
        if (!started.compareAndSet(false, true)) return
        val chosen = rankedNow.filter { it.uid in pickedNow }
        Graph.stream.start(st.playRequest(t, chosen, playerNow, from))
        onDismiss()
    }
    fun go(from: Long) {
        if (waitFrom < 0 && st.subSearching && rankedNow.isEmpty()) waitFrom = from else begin(from)
    }
    LaunchedEffect(waitFrom) {
        if (waitFrom < 0) return@LaunchedEffect
        val from = waitFrom
        val t0 = System.currentTimeMillis()
        while (st.subSearching && rankedNow.isEmpty() && System.currentTimeMillis() - t0 < 6000L) delay(120)
        begin(from)
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier.fillMaxWidth(0.72f).clip(RoundedCornerShape(20.d)).background(C.bg2).border(1.d, C.line, RoundedCornerShape(20.d)).padding(22.d)
        ) {
            Text2(t.title, 14, C.text, FontWeight.Bold, 2)
            Spacer(Modifier.height(4.d))
            Row(horizontalArrangement = Arrangement.spacedBy(8.d)) {
                Pill(t.quality, C.accent, C.onAccent)
                Pill(if (t.seeders >= 0) "${t.seeders} сийдъра" else "сийдъри: ?", C.card, seedColor(t.seeders))
                Pill(app.torrtv.core.formatSize(t.size))
            }
            if (t.seeders in 0..4) {
                Spacer(Modifier.height(8.d))
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(10.d)).background(Color(0x22FFB020)).padding(horizontal = 12.d, vertical = 8.d),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.d)
                ) {
                    Text2("Този торент има малко сийдъри. Гледай от:", 12, C.amber, FontWeight.Bold, 2, Modifier.weight(1f))
                    PrimaryButton("Откъде?", { pickOpen = true })
                }
            }

            Spacer(Modifier.height(14.d))
            Text2("Плеър", 13, C.dim, FontWeight.Bold, 1)
            Spacer(Modifier.height(6.d))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.d)) {
                items(options, key = { it }) { id ->
                    Chip(ExternalPlayers.label(id), player == id, { player = id; Graph.prefs.player = id })
                }
            }
            if (player == "builtin" && ("DTS" in t.tags || "TrueHD" in t.tags)) {
                Spacer(Modifier.height(4.d))
                Text2("Файлът е със звук DTS/TrueHD и във вградения плеър може да няма звук. Избери торент с AAC или AC3 или пусни външен плеър.", 11, C.amber, FontWeight.Medium, 2)
            }

            Spacer(Modifier.height(14.d))
            Row(horizontalArrangement = Arrangement.spacedBy(10.d)) {
                PrimaryButton(
                    when {
                        waitFrom >= 0 -> "Търсят се субтитри…"
                        resumeMs > 0 -> "▶  Продължи от ${app.torrtv.state.Library.fmt(resumeMs)}"
                        else -> "▶  Пусни"
                    },
                    { go(resumeMs) }, focusRequester = startFocus
                )
                if (resumeMs > 0) PrimaryButton("От началото", { begin(0L) }, primary = false)
                if (t.seeders !in 0..4) PrimaryButton("Откъде?", { pickOpen = true }, primary = false)
                PrimaryButton("Откажи", onDismiss, primary = false)
            }

            Spacer(Modifier.height(14.d))
            val summary = when {
                ranked.isEmpty() -> if (st.subSearching) "търсят се…" else "няма намерени"
                picked.isEmpty() -> "без субтитри"
                else -> ranked.filter { it.uid in picked }.joinToString(", ") {
                    Langs.choices.firstOrNull { l -> l.first == it.lang }?.second ?: it.lang.uppercase()
                }
            }
            Focusable({ subsOpen = !subsOpen }, Modifier.fillMaxWidth(), RoundedCornerShape(10.d), 1.02f) { f ->
                Row(
                    Modifier.fillMaxWidth().background(if (f) C.cardHi else C.card).padding(horizontal = 12.d, vertical = 9.d),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.d)
                ) {
                    Text2("Субтитри", 13, C.text, FontWeight.Bold, 1)
                    Text2(summary, 12, C.accent2, FontWeight.Medium, 1, Modifier.weight(1f))
                    if (ranked.isNotEmpty()) Text2(if (subsOpen) "▲" else "▼", 13, C.dim, FontWeight.Bold, 1)
                }
            }
            // Дали избраният субтитър е за това издание на филма - най-важното за синхрона със звука.
            val firstPicked = picked.firstOrNull()?.let { uid -> fits[uid] }
            if (firstPicked != null) {
                Spacer(Modifier.height(5.d))
                when (firstPicked) {
                    Fit.EXACT -> Text2("Субтитрите са за това издание и трябва да пасват.", 11, C.green, FontWeight.Medium, 2)
                    Fit.SIMILAR -> Text2("Субтитрите са за подобно издание и може да се разминават малко. В плеъра можеш да ги наместиш.", 11, C.amber, FontWeight.Medium, 2)
                    Fit.OTHER -> Text2("Субтитрите са за друго издание и вероятно ще се разминават. Избери други субтитри или ги намести в плеъра.", 11, C.amber, FontWeight.Medium, 2)
                    Fit.UNKNOWN -> Text2("Не се знае за кое издание са тези субтитри. Ако се разминават, намести ги в плеъра.", 11, C.dim, FontWeight.Normal, 2)
                }
            }
            if (subsOpen && ranked.isNotEmpty()) {
                Spacer(Modifier.height(6.d))
                LazyColumn(Modifier.heightIn(max = 190.d), verticalArrangement = Arrangement.spacedBy(4.d)) {
                    items(ranked, key = { it.uid }) { s -> SubRow(s, fits[s.uid] ?: Fit.UNKNOWN, s.uid in picked) {
                        if (s.uid in picked) picked.remove(s.uid) else if (picked.size < 4) picked.add(s.uid)
                    } }
                }
                Spacer(Modifier.height(4.d))
                Text2("Избери до 4 - превключваш ги в плеъра. Най-подходящият е най-горе.", 10, C.dim, FontWeight.Normal, 1)
            }
        }
    }
    if (pickOpen) {
        SourcePicker(
            st, t.hash,
            onTorrent = { onSwitch(it) },
            onWeb = { i -> onDismiss(); app.torrtv.web.WebPlay.open(ctx, st.webSources(), i, st.webRequest()) },
            onDismiss = { pickOpen = false }
        )
    }
}

@Composable
private fun SubRow(s: SubtitleResult, fit: Fit, on: Boolean, toggle: () -> Unit) {
    Focusable(toggle, Modifier.fillMaxWidth(), RoundedCornerShape(8.d), 1.02f) { f ->
        Row(
            Modifier.fillMaxWidth().background(if (f) C.cardHi else C.card).padding(horizontal = 10.d, vertical = 6.d),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.d)
        ) {
            Text2(if (on) "☑" else "☐", 14, if (on) C.accent2 else C.dim, FontWeight.Bold, 1)
            Column(Modifier.weight(1f)) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.d), verticalAlignment = Alignment.CenterVertically) {
                    Pill(Langs.choices.firstOrNull { it.first == s.lang }?.second ?: s.lang.uppercase(), C.cardHi, C.text)
                    when (fit) {
                        Fit.EXACT -> Pill("точно издание", Color(0x333DDC84), C.green)
                        Fit.SIMILAR -> Pill("подобно", Color(0x33FFB020), C.amber)
                        Fit.OTHER -> Pill("друго издание", Color(0x33FF5C6C), C.red)
                        Fit.UNKNOWN -> Pill("неясно", C.cardHi, C.dim)
                    }
                    if (s.trusted) Pill("доверен", Color(0x223DDC84), C.green)
                    if (s.hearing) Pill("за глухи", C.cardHi, C.dim)
                    Text2(s.providerName, 10, C.dim, FontWeight.Normal, 1)
                }
                Spacer(Modifier.height(2.d))
                Text2(s.release.ifBlank { "(без име на издание)" }, 11, C.text, FontWeight.Normal, 2)
            }
        }
    }
}
