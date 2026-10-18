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
import androidx.compose.runtime.remember
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
        st.torrents.filter { !it.isCam && it.hash != excludeHash }.sortedByDescending { it.seeders }.take(8)
    }
    val web = remember(st.detail, st.season, st.episode) { st.webSources() }
    val webOn = Graph.prefs.webEnabled
    val firstFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { firstFocus.tryRequest() }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier.fillMaxWidth(0.66f).clip(RoundedCornerShape(20.d)).background(C.bg2)
                .border(1.d, C.line, RoundedCornerShape(20.d)).padding(20.d)
        ) {
            Text2("Откъде да гледаш?", 18, C.text, FontWeight.ExtraBold, 1)
            Spacer(Modifier.height(2.d))
            Text2("Избери друг торент или гледай онлайн от сайт. „Назад“ затваря.", 11, C.dim, FontWeight.Normal, 2)
            Spacer(Modifier.height(10.d))

            LazyColumn(Modifier.heightIn(max = 270.d), verticalArrangement = Arrangement.spacedBy(4.d)) {
                item(key = "h1") {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.d)) {
                        Text2("🧲  Други торенти - най-много сийдъри първи", 13, C.accent2, FontWeight.Bold, 1)
                        if (st.searching) Spinner(14.d)
                    }
                }
                if (torrents.isEmpty()) item(key = "none") {
                    Text2(if (st.searching) "Търся…" else "Няма други торенти.", 12, C.dim, FontWeight.Normal, 1, Modifier.padding(start = 4.d))
                }
                itemsIndexed(torrents, key = { _, t -> "t" + t.hash + t.source }) { i, t ->
                    PickRow(
                        left = t.quality, title = t.title,
                        sub = formatSize(t.size) + " · " + t.source,
                        right = "${t.seeders}", rightColor = seedColor(t.seeders),
                        focusRequester = if (i == 0) firstFocus else null
                    ) { onTorrent(t); onDismiss() }
                }

                item(key = "h2") {
                    Spacer(Modifier.height(8.d))
                    Text2("🌐  Уеб сайтове - гледане онлайн", 13, C.accent2, FontWeight.Bold, 1)
                }
                if (!webOn) item(key = "weboff") {
                    Text2("Изключени са. Включват се от Настройки → Уеб сайтове.", 12, C.dim, FontWeight.Normal, 2, Modifier.padding(start = 4.d))
                } else if (web.isEmpty()) item(key = "webnone") {
                    Text2("Няма адреси (или още се зарежда информацията за филма).", 12, C.dim, FontWeight.Normal, 2, Modifier.padding(start = 4.d))
                } else {
                    item(key = "webnote") {
                        Text2(
                            "Рекламите се блокират. Щом сайтът пусне видеото, предавам го на вградения плеър със субтитри. Ако не успее - гледаш в самата страница.",
                            11, C.dim, FontWeight.Normal, 3, Modifier.padding(start = 4.d, bottom = 2.d)
                        )
                    }
                    itemsIndexed(web, key = { i, s -> "w$i" + s.host }) { i, s ->
                        PickRow(
                            left = "WEB", title = s.host,
                            sub = if (i == 0) "основен" else "резервен " + i,
                            right = "▶", rightColor = C.accent,
                            focusRequester = if (torrents.isEmpty() && i == 0) firstFocus else null
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
private fun PickRow(
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
