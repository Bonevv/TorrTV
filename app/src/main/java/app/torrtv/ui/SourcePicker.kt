package app.torrtv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.torrtv.core.formatSize
import app.torrtv.data.torrents.TorrentResult
import app.torrtv.data.torrents.PlaybackAdvisor
import app.torrtv.core.StreamFit
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
        st.torrents.filter { !it.isCam && it.hash != excludeHash }.take(20)
    }
    val web = remember(st.detail, st.season, st.episode, st.bgWebSources, st.apiWebSources) { st.webSources() }
    val webOn = Graph.prefs.webEnabled
    // По подразбиране са торентите; уеб сайтовете се избират със стрелките наляво/надясно.
    var tab by remember { mutableStateOf(0) }
    var bgOnly by remember { mutableStateOf(st.detail?.media?.let{Graph.bgWeb.has(it)}==true) }
    val capacity=remember(st,st.season,st.episode,st.episodes){st.playbackCapacity()}
    LaunchedEffect(st,tab,st.season,st.episode,st.episodeLoading){if(tab==1 && !st.episodeLoading)st.ensureWeb()}
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
                if (tab == 0) "Качеството, размерът и сийдърите помагат при избора. Новите резултати се добавят отдолу."
                else "Гледане онлайн, без торент. Надолу е списъкът, наляво - торентите.",
                11, C.dim, FontWeight.Normal, 2
            )
            Spacer(Modifier.height(8.d))
            if(tab==1 && webOn)Row(horizontalArrangement=Arrangement.spacedBy(8.d),verticalAlignment=Alignment.CenterVertically){
                Chip("Всички",!bgOnly,{bgOnly=false},compact=true)
                Chip("🇧🇬 БГ аудио (${web.count{it.bgAudio}})",bgOnly,{bgOnly=true},compact=true)
                if(st.webLoading)Spinner(14.d)
                Chip("Обнови",false,{st.ensureWeb(force=true)},compact=true)
            }

            LazyColumn(Modifier.heightIn(max = 270.d), verticalArrangement = Arrangement.spacedBy(4.d)) {
                if (tab == 0) {
                    if (torrents.isEmpty()) item(key = "none") {
                        Text2(
                            if (st.searching) "Търсене…" else "Няма други торенти. Пробвай уеб сайтовете (надясно).",
                            12, C.dim, FontWeight.Normal, 2, Modifier.padding(start = 4.d)
                        )
                    }
                    itemsIndexed(torrents, key = { _, t -> "t" + t.hash + t.source }) { _, t ->
                        val fit=PlaybackAdvisor.assess(t,capacity,Graph.transferRates.mbps(t.hash))
                        val note=when{fit.storage==StreamFit.Level.BAD->"недостатъчно място";fit.smooth==StreamFit.Level.BAD->"риск от буфериране";else->""}
                        PickRow(
                            left = t.quality, title = t.title,
                            sub = listOf(formatSize(t.size),t.source,note).filter{it.isNotBlank()}.joinToString(" · "),
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
                            "Българските сайтове запазват оригиналния си плеър и неговите контроли за аудио и субтитри.",
                            11, C.dim, FontWeight.Normal, 3, Modifier.padding(start = 4.d, bottom = 2.d)
                        )
                    }
                    if(bgOnly && web.none{it.bgAudio})item(key="no-bg"){
                        Text2(if(st.webLoading)"Проверяват се БГ версиите…" else "Няма потвърдена БГ версия за избрания филм или епизод.",12,C.dim,FontWeight.Normal,3)
                    }
                    itemsIndexed(web.filter{!bgOnly || it.bgAudio}, key = { _, s -> s.key }) { _, s ->
                        PickRow(
                            left = if (s.bgAudio) "🇧🇬" else "WEB", title = s.host,
                            sub = s.summary,
                            right = "▶", rightColor = C.accent
                        ) { onDismiss(); onWeb(web.indexOfFirst{it.key==s.key}) }
                    }
                }
            }
            Spacer(Modifier.height(10.d))
            PrimaryButton("Затвори", onDismiss, primary = false)
        }
    }
}

/** Uses already collected results; opening diagnostics never starts another site crawl. */
@Composable
fun SourceStatusDialog(st:DetailState,onDismiss:()->Unit) {
    val rows=st.torrentStatus.entries.map{"Торенти · ${it.key}" to it.value}+
        st.subStatus.entries.map{"Субтитри · ${it.key}" to it.value}
    val close=remember{FocusRequester()}
    LaunchedEffect(Unit){close.tryRequest()}
    Dialog(onDismissRequest=onDismiss,properties=DialogProperties(usePlatformDefaultWidth=false)){
        Column(Modifier.fillMaxWidth(0.68f).clip(RoundedCornerShape(18.d)).background(C.bg2)
            .border(1.d,C.line,RoundedCornerShape(18.d)).padding(20.d)){
            Text2("Състояние на източниците",20,C.text,FontWeight.Bold,1)
            Text2(st.detail?.media?.title.orEmpty(),13,C.accent2,FontWeight.Medium,2)
            if(st.type==app.torrtv.data.MediaType.TV)Text2("Сезон ${st.season} · Епизод ${st.episode}",12,C.dim,FontWeight.Medium,1)
            Spacer(Modifier.height(10.d))
            LazyColumn(Modifier.heightIn(max=240.d),verticalArrangement=Arrangement.spacedBy(8.d)){
                if(rows.isEmpty())item{Text2(if(st.searching || st.subSearching)"Проверяват се източниците…" else "Още няма резултати от проверка.",13,C.dim,FontWeight.Normal,2)}
                itemsIndexed(rows,key={_,r->r.first}){_,row->
                    var focused by remember{mutableStateOf(false)}
                    Column(Modifier.onFocusChanged{focused=it.isFocused}
                        .border(if(focused)2.d else 0.d,if(focused)C.ring else Color.Transparent,RoundedCornerShape(8.d))
                        .focusable().padding(6.d)){
                        Text2(row.first,12,C.text,FontWeight.Bold,1)
                        Text2(if(row.second.startsWith("✗"))app.torrtv.core.UiFailure.message(row.second) else "Резултати: ${row.second}",12,
                            if(row.second.startsWith("✗"))C.amber else C.green,FontWeight.Normal,3)
                    }
                }
                item(key="web"){
                    Text2(when{!st.webCheckedOrRequested->"Уеб: проверката започва при отваряне на уеб източниците";st.webLoading->"Уеб: проверяват се плеърите…";st.webError!=null->"Уеб: ${st.webError}";else->"Уеб: ${st.bgWebSources.size} намерени версии от сайтове"},12,C.dim,FontWeight.Normal,4)
                }
                item(key="speed"){
                    Text2("Последно измерване: "+(if(Graph.prefs.netMbps>0)"${Graph.prefs.netMbps.toInt()} Mbit/s" else "мрежата не е измерена"),12,C.dim,FontWeight.Normal,2)
                }
            }
            Spacer(Modifier.height(12.d))
            Row(horizontalArrangement=Arrangement.spacedBy(10.d)){
                PrimaryButton("Затвори",onDismiss,focusRequester=close)
                PrimaryButton("Провери отново",{st.refreshSources()},primary=false)
            }
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
