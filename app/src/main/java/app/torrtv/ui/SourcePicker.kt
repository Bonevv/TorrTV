package app.torrtv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.torrtv.core.formatSize
import app.torrtv.data.torrents.TorrentResult
import app.torrtv.state.DetailState
import app.torrtv.state.Graph

/**
 * „Откъде да гледаш?“ - други торенти (по сийдъри) и уеб сайтове на едно място.
 * excludeHash - торентът, който вече е избран (не се показва пак).
 */
@Composable
fun SourcePicker(
    st: DetailState,
    excludeHash: String?,
    onTorrent: (TorrentResult) -> Unit,
    onWeb: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    val torrents = remember(st.torrents, excludeHash) {
        st.torrents.filter { !it.isCam && it.hash != excludeHash }.sortedByDescending { it.seeders }.take(12)
    }
    val web = remember(st.detail, st.season, st.episode) { st.webSources() }
    val webOn = Graph.prefs.webEnabled
    // По подразбиране са торентите; уеб сайтовете се избират със стрелките наляво/надясно.
    var tab by remember { mutableStateOf(0) }
    val tabFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        st.ensureTorrents() // при „Продължи“ търсенето е отложено до тук
        tabFocus.tryRequest()
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier.fillMaxWidth(0.66f).clip(RoundedCornerShape(20.d)).background(C.bg2)
                .border(1.d, C.line, RoundedCornerShape(20.d)).padding(20.d)
        ) {
            Text2("Откъде да гледаш?", 18, C.text, FontWeight.ExtraBold, 1)
            Spacer(Modifier.height(10.d))
            Row(horizontalArrangement = Arrangement.spacedBy(10.d), verticalAlignment = Alignment.CenterVertically) {
                Chip("Торенти (${torrents.size})", tab == 0, { tab = 0 }, onFocus = { tab = 0 }, focusRequester = tabFocus)
                Chip("Уеб сайтове (${web.size})", tab == 1, { tab = 1 }, onFocus = { tab = 1 })
                if (st.searching) Spinner(16.d)
            }
            Spacer(Modifier.height(4.d))
            Text2(
                if (tab == 0) "Първо са торентите с най-много сийдъри. Надолу е списъкът, надясно - уеб сайтовете."
                else "Гледане онлайн, без торент. Надолу е списъкът, наляво - торентите.",
                11, C.dim, FontWeight.Normal, 2
            )
            Spacer(Modifier.height(8.d))

            LazyColumn(Modifier.heightIn(max = 270.d), verticalArrangement = Arrangement.spacedBy(4.d)) {
                if (tab == 0) {
                    if (torrents.isEmpty()) item(key = "none") {
                        Text2(
                            if (st.searching) "Търсене…" else "Няма други торенти. Пробвай уеб сайтовете (надясно).",
                            12, C.dim, FontWeight.Normal, 2, Modifier.padding(start = 4.d)
                        )
                    }
                    itemsIndexed(torrents, key = { _, t -> "t" + t.hash + t.source }) { _, t ->
                        PickRow(
                            left = t.quality, title = t.title,
                            sub = formatSize(t.size) + " · " + t.source,
                            right = if (t.seeders < 0) "?" else "${t.seeders}", rightColor = seedColor(t.seeders)
                        ) { onTorrent(t); onDismiss() }
                    }
                } else if (!webOn) {
                    item(key = "weboff") {
                        Text2("Уеб сайтовете са изключени. Включват се от Настройки → Уеб сайтове.", 12, C.dim, FontWeight.Normal, 2, Modifier.padding(start = 4.d))
                    }
                } else if (web.isEmpty()) {
                    item(key = "webnone") {
                        Text2("Няма адреси на сайтове (или филмът още се зарежда).", 12, C.dim, FontWeight.Normal, 2, Modifier.padding(start = 4.d))
                    }
                } else {
                    item(key = "webnote") {
                        Text2(
                            "Рекламите се блокират. Когато видеото тръгне, можеш да го прехвърлиш във вградения плеър (със субтитри) или да го гледаш в самата страница.",
                            11, C.dim, FontWeight.Normal, 3, Modifier.padding(start = 4.d, bottom = 2.d)
                        )
                    }
                    itemsIndexed(web, key = { i, s -> "w$i" + s.host }) { i, s ->
                        PickRow(
                            left = "WEB", title = s.host,
                            sub = if (i == 0) "основен" else "резервен " + i,
                            right = "▶", rightColor = C.accent
                        ) { onDismiss(); onWeb(i) }
                    }
                }
            }
            Spacer(Modifier.height(10.d))
            PrimaryButton("Затвори", onDismiss, primary = false)
        }
    }
}

@Composable
internal fun PickRow(
    left: String, title: String, sub: String, right: String, rightColor: Color,
    focusRequester: FocusRequester? = null, onClick: () -> Unit
) {
    Focusable(onClick, Modifier.fillMaxWidth(), RoundedCornerShape(10.d), 1.015f, focusRequester = focusRequester) { f ->
        Row(
            Modifier.fillMaxWidth().background(if (f) C.cardHi else C.card).padding(horizontal = 10.d, vertical = 7.d),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.d)
        ) {
            Pill(left, if (left == "WEB") C.cardHi else C.accent, if (left == "WEB") C.accent2 else C.onAccent)
            Column(Modifier.weight(1f)) {
                Text2(title, 12, C.text, FontWeight.SemiBold, 1)
                Text2(sub, 10, C.dim, FontWeight.Normal, 1)
            }
            Text2(right, 15, rightColor, FontWeight.ExtraBold, 1)
        }
    }
}
