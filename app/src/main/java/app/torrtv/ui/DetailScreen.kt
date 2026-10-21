package app.torrtv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.torrtv.core.StreamFit
import app.torrtv.core.Storage
import app.torrtv.core.formatSize
import app.torrtv.data.Detail
import app.torrtv.data.Media
import app.torrtv.data.MediaType
import app.torrtv.data.torrents.TorrentAggregator
import app.torrtv.data.torrents.TorrentResult
import app.torrtv.state.DetailState
import app.torrtv.state.Graph
import app.torrtv.state.Library
import app.torrtv.web.WebSources
import kotlinx.coroutines.launch
import java.util.Locale

fun seedColor(n: Int): Color = when {
    n < 0 -> C.dim
    n >= 20 -> C.green
    n >= 5 -> C.amber
    else -> C.red
}

// Разделите в страничния панел. Заглавието и бутоните горе са неподвижни и никога не се плъзгат.
private const val TAB_EPS = "eps"
private const val TAB_TOR = "tor"
private const val TAB_WEB = "web"
private const val TAB_ABOUT = "about"
private const val TAB_REC = "rec"

private val QUALITIES = listOf("all", "4K", "1080p", "720p", "480p", "SD")

/** Следващата стойност на филтъра по кръг; ако текущата я няма в списъка - първата. */
fun nextOption(options: List<String>, current: String): String =
    if (options.isEmpty()) current else options[(options.indexOf(current) + 1) % options.size]

@Composable
fun DetailScreen(st: DetailState, onOpen: (Media) -> Unit, onPlay: (TorrentResult, Long) -> Unit, sheetOpen: Boolean = false) {
    val d = st.detail
    val playFocus = remember { FocusRequester() }
    var quality by remember(st) { mutableStateOf("all") }
    var site by remember(st) { mutableStateOf("all") }
    var tab by remember(st) { mutableStateOf(if (st.type == MediaType.TV) TAB_EPS else TAB_TOR) }
    val ctx = androidx.compose.ui.platform.LocalContext.current

    LaunchedEffect(st) { if (st.detail == null) st.start() }
    // Фокусът отива на „Гледай“ - при отваряне и когато се върнем на тази страница от друго заглавие.
    LaunchedEffect(st, d != null) { if (d != null) playFocus.tryRequest() }
    // При „Продължи“ търсенето на торенти е отложено: пуска се, щом диалогът за пускане не е отворен.
    LaunchedEffect(d != null, sheetOpen) { if (d != null && !sheetOpen) st.ensureTorrents() }

    Box(Modifier.fillMaxSize()) {
        // фон
        Img(Look.img(d?.media?.backdropPath, "w780"), Modifier.fillMaxSize())
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xCC07090E), Color(0xF507090E), C.bg))))

        if (d == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                if (st.error != null) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text2("${st.error}", 14, C.red, FontWeight.Medium, 4)
                        Spacer(Modifier.height(12.d))
                        PrimaryButton("Опитай пак", { st.error = null; st.start() })
                    }
                } else Spinner(40.d)
            }
            return@Box
        }

        // Сайтовете, от които има резултати (с брой), най-богатите първи. Един торент може да е на няколко сайта.
        val siteList = remember(st.torrents) {
            val m = LinkedHashMap<String, Int>()
            for (t in st.torrents) {
                m[t.source] = (m[t.source] ?: 0) + 1
                for (o in t.alsoOn) m[o] = (m[o] ?: 0) + 1
            }
            m.entries.sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key }).map { it.key to it.value }
        }
        val siteSel = if (site != "all" && siteList.none { it.first == site }) "all" else site
        val qOptions = remember(st.torrents) {
            val present = st.torrents.map { it.quality }.toSet()
            QUALITIES.filter { it == "all" || it in present }
        }
        val qSel = if (quality in qOptions) quality else "all"
        val shown = remember(st.torrents, qSel, siteSel) {
            st.torrents.filter { (qSel == "all" || it.quality == qSel) && (siteSel == "all" || it.onSite(siteSel)) }.take(60)
        }
        val web = remember(st.detail, st.season, st.episode) { st.webSources() }
        val webOn = Graph.prefs.webEnabled
        // Свободно място на избраното място и измерени скорости - за етикетите „събира се“ и „гладко“.
        val place = remember { Storage.chosen(ctx, Graph.prefs.storageId) }
        val freeBytes = remember(place, st.torrents.size) { Storage.free(place) }
        val builtinPlayer = Graph.prefs.player == "builtin"
        val netMbps = Graph.prefs.netMbps.toDouble()
        val diskMBps = Graph.prefs.diskMBps.toDouble()
        val isTv = d.media.type == MediaType.TV
        val runtimeMin = if (isTv) st.episodes.firstOrNull { it.number == st.episode }?.runtime?.takeIf { it > 0 } ?: d.runtime else d.runtime
        val rowFit: (TorrentResult) -> Pair<StreamFit.Level, StreamFit.Level> = { t ->
            if (t.pack) StreamFit.Level.NONE to StreamFit.Level.NONE
            else StreamFit.storage(t.size, freeBytes) to StreamFit.smooth(t.size, runtimeMin, netMbps, diskMBps, t.seeders)
        }
        val best = st.torrents.filter { !it.isCam }.let { l ->
            l.filter { rowFit(it).first != StreamFit.Level.BAD }.ifEmpty { l }
        }.maxByOrNull {
            TorrentAggregator.score(it, builtinPlayer) -
                if (rowFit(it).second == StreamFit.Level.BAD) TorrentAggregator.SLOW_PENALTY else 0
        } ?: st.torrents.firstOrNull()
        // Започнато ли е вече? За сериал - само ако е избран същият епизод.
        val pr = Library.progressOf(d.media.key)?.takeIf {
            !it.nextUp && (d.media.type == MediaType.MOVIE || (it.season == st.season && it.episode == st.episode))
        }

        Column(Modifier.fillMaxSize()) {
            // Горната част е извън всеки списък: нищо не я плъзга, затова заглавието винаги се вижда.
            DetailHeader(st, d, best, pr, playFocus, onPlay) { tab = TAB_WEB }
            Box(Modifier.padding(horizontal = 36.d).fillMaxWidth().height(1.d).background(C.line))

            Row(
                Modifier.weight(1f).fillMaxWidth().padding(start = 36.d, end = 36.d, top = 12.d, bottom = 12.d),
                horizontalArrangement = Arrangement.spacedBy(18.d)
            ) {
                // Вляво: какво да се показва и филтри за торентите.
                val tabs = buildList {
                    if (isTv) add(Triple(TAB_EPS, "Епизоди", if (st.episodes.isNotEmpty()) "${st.episodes.size}" else ""))
                    add(Triple(TAB_TOR, "Торенти", if (st.torrents.isNotEmpty()) "${st.torrents.size}" else if (st.searching) "…" else "0"))
                    add(Triple(TAB_WEB, "Уеб сайтове", if (webOn && web.isNotEmpty()) "${web.size}" else ""))
                    add(Triple(TAB_ABOUT, "Описание", ""))
                    add(Triple(TAB_REC, "Подобни", ""))
                }
                val failedSubs = st.subStatus.entries.filter { it.value.startsWith("✗") }.map { it.key }.sorted()
                SidePanel {
                    items(tabs, key = { it.first }) { (id, label, count) -> SideTab(label, count, tab == id) { tab = id } }
                    if (tab == TAB_TOR && (qOptions.size > 1 || siteList.size > 1)) {
                        panelDivider("fdiv")
                        if (qOptions.size > 1) item(key = "fq") {
                            FilterButton(
                                "Качество",
                                if (qSel == "all") "Всички · ${st.torrents.size}" else "$qSel · ${st.torrents.count { it.quality == qSel }}"
                            ) { quality = nextOption(qOptions, qSel) }
                        }
                        if (siteList.size > 1) item(key = "fs") {
                            FilterButton(
                                "Сайт",
                                if (siteSel == "all") "Всички сайтове" else "$siteSel · ${siteList.firstOrNull { it.first == siteSel }?.second ?: 0}"
                            ) { site = nextOption(listOf("all") + siteList.map { it.first }, siteSel) }
                        }
                    }
                    if (st.subResults.isNotEmpty() || st.subStatus.isNotEmpty() || st.subSearching) {
                        item(key = "subs") {
                            // Подробностите по източници са в Настройки -> Разширени -> Диагностика; тук се показват само неуспелите.
                            Column(Modifier.padding(start = 6.d, top = 12.d, end = 4.d)) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.d)) {
                                    Text2(
                                        when {
                                            st.subSearching -> "Субтитри: търсят се…"
                                            st.subResults.isNotEmpty() -> "Субтитри: намерени ${st.subResults.size}"
                                            else -> "Субтитри: не са намерени"
                                        },
                                        11, C.dim, FontWeight.Medium, 1
                                    )
                                    if (st.subSearching) Spinner(13.d)
                                }
                                if (failedSubs.isNotEmpty()) {
                                    Spacer(Modifier.height(3.d))
                                    Text2("Не отговориха: " + failedSubs.joinToString(", "), 10, C.red, FontWeight.Normal, 2)
                                }
                            }
                        }
                    }
                }

                // Вдясно: съдържанието на избрания раздел.
                Box(Modifier.weight(1f).fillMaxHeight()) {
                    when (tab) {
                        TAB_EPS -> EpisodesPane(st, d)
                        TAB_WEB -> WebPane(st, web, webOn)
                        TAB_ABOUT -> AboutPane(d)
                        TAB_REC -> RecsPane(st, onOpen)
                        else -> TorrentsPane(
                            st, shown, qSel != "all" || siteSel != "all",
                            "Свободно " + app.torrtv.core.CacheManager.fmt(freeBytes) + " (" + place.label.lowercase() + ")" +
                                (if (netMbps > 0) " · мрежа ${netMbps.toInt()} Mbit/s" else ""),
                            rowFit, onPlay, qSel + "|" + siteSel
                        )
                    }
                }
            }
        }
    }
}

/** Заглавие, кратка информация и главните действия. Висотата е около 190 и не зависи от нищо отдолу. */
@Composable
private fun DetailHeader(
    st: DetailState, d: Detail, best: TorrentResult?, pr: Library.Progress?, playFocus: FocusRequester,
    onPlay: (TorrentResult, Long) -> Unit, onNoTorrents: () -> Unit
) {
    val isTv = d.media.type == MediaType.TV
    Row(
        Modifier.fillMaxWidth().padding(start = 36.d, end = 36.d, top = 20.d, bottom = 12.d),
        horizontalArrangement = Arrangement.spacedBy(20.d)
    ) {
        Img(
            Look.img(d.media.posterPath, "w342"),
            Modifier.width(106.d).height(159.d).clip(RoundedCornerShape(10.d)).border(1.d, C.line, RoundedCornerShape(10.d))
        )
        Column(Modifier.weight(1f)) {
            Text2(d.media.title, 26, C.text, FontWeight.ExtraBold, 2)
            Spacer(Modifier.height(4.d))
            Row(horizontalArrangement = Arrangement.spacedBy(10.d), verticalAlignment = Alignment.CenterVertically) {
                if (d.media.rating > 0) Pill("★ " + String.format(Locale.US, "%.1f", d.media.rating), Color(0x33FFC857), C.gold)
                val meta = listOf(
                    d.media.year,
                    if (d.runtime > 0) "${d.runtime} мин" else "",
                    if (isTv) (if (d.seasons.size == 1) "1 сезон" else "${d.seasons.size} сезона") else "",
                    if (isTv) d.status else ""
                ).filter { it.isNotBlank() }
                if (meta.isNotEmpty()) Text2(meta.joinToString("  ·  "), 13, C.dim, FontWeight.Medium, 1)
                if (d.genres.isNotEmpty()) Text2(d.genres.take(4).joinToString(" · ") { it.name }, 12, C.accent2, FontWeight.Medium, 1, Modifier.weight(1f))
            }
            Spacer(Modifier.height(6.d))
            Text2(d.media.overview.ifBlank { "Няма описание." }, 13, Color(0xFFD5DAE6), FontWeight.Normal, 2)
            Spacer(Modifier.height(10.d))
            Row(horizontalArrangement = Arrangement.spacedBy(10.d), verticalAlignment = Alignment.CenterVertically) {
                if (pr != null) {
                    PrimaryButton("▶  Продължи · " + Library.fmt(pr.posMs), { onPlay(pr.torrent(), pr.posMs) }, focusRequester = playFocus)
                }
                PrimaryButton(
                    when {
                        best != null && pr != null -> "От началото"
                        best != null -> "▶  Гледай"
                        st.searching -> "Търсене…"
                        else -> "Няма торенти - гледай от сайт"
                    },
                    {
                        if (best != null) onPlay(best, 0L)
                        else if (!st.searching) onNoTorrents() // няма торенти: показват се уеб сайтовете
                    },
                    primary = pr == null,
                    focusRequester = if (pr == null) playFocus else null
                )
                val fav = Library.isFavorite(d.media.key)
                Chip(if (fav) "♥ В любими" else "♡ Любими", fav, { Library.toggleFavorite(d.media) }, compact = true)
                val seen = Library.isWatched(d.media, if (isTv) st.season else null, if (isTv) st.episode else null)
                Chip(
                    when {
                        seen -> if (isTv) "✓ Гледан епизод" else "✓ Гледан"
                        isTv -> "Отметни епизода"
                        else -> "Отметни като гледан"
                    },
                    seen,
                    { Library.setWatched(d.media, if (isTv) st.season else null, if (isTv) st.episode else null, !seen) },
                    compact = true
                )
                Chip("↻ Търси пак", false, { st.loadTorrents(); st.loadSubs() }, compact = true)
            }
            // Един ред под бутоните: кой епизод е избран и кой торент ще се пусне.
            val few = pr == null && best != null && best.seeders in 0..4
            val epTag = if (isTv) "Сезон ${st.season} · Епизод ${st.episode}" else ""
            val hint = when {
                few -> "Най-добрият торент има само ${best!!.seeders} ${if (best.seeders == 1) "сийдър" else "сийдъра"} и може да буферира. Пробвай друг от списъка или „Уеб сайтове“."
                pr == null && best != null -> listOf(
                    epTag,
                    "Ще се пусне: " + listOf(
                        best.quality, formatSize(best.size),
                        if (best.seeders >= 0) "${best.seeders} сийдъра" else "", best.source
                    ).filter { it.isNotBlank() }.joinToString(" · ")
                ).filter { it.isNotBlank() }.joinToString("  ·  ")
                else -> epTag
            }
            if (hint.isNotBlank()) {
                Spacer(Modifier.height(6.d))
                Text2(hint, 11, if (few) C.amber else C.dim, FontWeight.Medium, 2)
            }
        }
    }
}

/** Ред от менюто вляво: избраният раздел има златна чертичка, фокусираният е светъл. */
@Composable
internal fun SideTab(label: String, count: String, selected: Boolean, onClick: () -> Unit) {
    Focusable(onClick, Modifier.fillMaxWidth(), RoundedCornerShape(10.d), 1f, ring = false) { f ->
        Row(
            Modifier.fillMaxWidth()
                .background(if (f) C.text else if (selected) Color(0x24FFFFFF) else Color.Transparent)
                .padding(start = 8.d, end = 10.d, top = 7.d, bottom = 7.d),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier.width(3.d).height(16.d).clip(RoundedCornerShape(2.d))
                    .background(if (!selected) Color.Transparent else if (f) C.bg else C.accent)
            )
            Spacer(Modifier.width(9.d))
            Text2(
                label, 14, if (f) C.bg else if (selected) C.text else Color(0xFFB9C0D0),
                if (selected) FontWeight.Bold else FontWeight.SemiBold, 1, Modifier.weight(1f)
            )
            if (count.isNotBlank()) Text2(count, 12, if (f) C.bg else if (selected) C.accent2 else C.dim, FontWeight.Bold, 1)
        }
    }
}

/** Филтър с една стойност: OK превключва към следващата. */
@Composable
internal fun FilterButton(label: String, value: String, onClick: () -> Unit) {
    Focusable(onClick, Modifier.fillMaxWidth(), RoundedCornerShape(10.d), 1f, ring = false) { f ->
        Row(
            Modifier.fillMaxWidth()
                .background(if (f) C.text else C.card)
                .then(if (f) Modifier else Modifier.border(1.d, C.line, RoundedCornerShape(10.d)))
                .padding(horizontal = 10.d, vertical = 8.d),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text2(label, 11, if (f) C.bg else C.dim, FontWeight.Medium, 1)
            Spacer(Modifier.width(8.d))
            Text2(value, 13, if (f) C.bg else C.text, FontWeight.SemiBold, 1, Modifier.weight(1f))
            Text2("›", 16, if (f) C.bg else C.accent, FontWeight.ExtraBold, 1)
        }
    }
}

@Composable
internal fun PaneTitle(text: String, sub: String = "") {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(3.d).height(16.d).clip(RoundedCornerShape(2.d)).background(C.accent))
        Spacer(Modifier.width(9.d))
        Text2(text, 17, C.text, FontWeight.Bold, 1)
        if (sub.isNotBlank()) {
            Spacer(Modifier.width(10.d))
            Text2(sub, 12, C.dim, FontWeight.Medium, 1)
        }
    }
}

@Composable
private fun TorrentsPane(
    st: DetailState, shown: List<TorrentResult>, filtered: Boolean, freeText: String,
    rowFit: (TorrentResult) -> Pair<StreamFit.Level, StreamFit.Level>, onPlay: (TorrentResult, Long) -> Unit, resetKey: String
) {
    // Нов списък (и връщане най-горе) при смяна на филтъра.
    val rows = remember(st, resetKey) { LazyListState() }
    val failed = st.torrentStatus.entries.filter { it.value.startsWith("✗") }.map { it.key }.sorted()
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(top = 4.d, bottom = 6.d), verticalAlignment = Alignment.CenterVertically) {
            PaneTitle(
                "Торенти",
                if (filtered) "${shown.size} от ${st.torrents.size}" else if (st.torrents.isNotEmpty()) "${st.torrents.size}" else ""
            )
            if (st.searching) {
                Spacer(Modifier.width(10.d))
                Spinner(16.d)
            }
            Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
                Text2(freeText, 11, C.dim, FontWeight.Normal, 1)
            }
        }
        // Източници, които не са отговорили (останалите се виждат във филтъра по сайт).
        if (failed.isNotEmpty()) {
            Text2("Не отговориха: " + failed.joinToString(", "), 11, C.red, FontWeight.Medium, 2, Modifier.padding(bottom = 6.d))
        }
        LazyColumn(
            Modifier.fillMaxWidth().weight(1f), state = rows,
            contentPadding = PaddingValues(horizontal = 6.d, vertical = 4.d)
        ) {
            if (shown.isEmpty()) {
                item(key = "none") {
                    Text2(
                        when {
                            st.searching -> "Търсене в източниците…"
                            st.torrents.isNotEmpty() -> "Няма торенти с този избор. Смени качеството или сайта."
                            else -> "Няма намерени торенти. Виж Настройки → Диагностика или пробвай „Уеб сайтове“."
                        },
                        13, C.dim, FontWeight.Normal, 2, Modifier.padding(vertical = 8.d)
                    )
                }
            }
            items(shown, key = { it.hash + it.source }) { t ->
                val fit = remember(t.hash, st.subResults) { st.subFit(t) }
                val (sf, sm) = rowFit(t)
                TorrentRow(t, fit, sf, sm) { onPlay(t, 0L) }
            }
        }
    }
}

@Composable
private fun EpisodesPane(st: DetailState, d: Detail) {
    Column(Modifier.fillMaxSize()) {
        Spacer(Modifier.height(2.d))
        LazyRow(contentPadding = PaddingValues(horizontal = 6.d, vertical = 4.d), horizontalArrangement = Arrangement.spacedBy(8.d)) {
            items(d.seasons, key = { it.number }) { s ->
                Chip(
                    if (s.number == 0) "Специални" else "Сезон ${s.number}",
                    s.number == st.season, { st.selectSeason(s.number) }
                )
            }
        }
        Spacer(Modifier.height(8.d))
        LazyRow(
            contentPadding = PaddingValues(horizontal = 6.d, vertical = 8.d),
            horizontalArrangement = Arrangement.spacedBy(12.d)
        ) {
            items(st.episodes, key = { it.number }) { e ->
                val sel = e.number == st.episode
                Focusable({ st.selectEpisode(e.number) }, Modifier.width(190.d), RoundedCornerShape(10.d), 1.06f) { f ->
                    Column(Modifier.background(if (sel) C.cardHi else C.card)) {
                        Box {
                            Img(Look.img(e.stillPath, "w300"), Modifier.fillMaxWidth().height(104.d))
                            Box(Modifier.padding(6.d)) { Pill("Еп. ${e.number}", Color(0xCC000000), if (sel) C.accent2 else C.text) }
                            if (Library.isWatched(d.media, st.season, e.number)) {
                                Box(Modifier.align(Alignment.TopEnd).padding(6.d)) { Pill("✓", Color(0xCC000000), C.green) }
                            }
                        }
                        Column(Modifier.padding(8.d)) {
                            Text2(e.name.ifBlank { "Епизод ${e.number}" }, 12, C.text, FontWeight.Bold, 1)
                            Text2(
                                if (f && e.overview.isNotBlank()) e.overview else listOf(e.airDate, if (e.runtime > 0) "${e.runtime} мин" else "").filter { it.isNotBlank() }.joinToString(" · "),
                                10, C.dim, FontWeight.Normal, 3
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun WebPane(st: DetailState, web: List<WebSources.Source>, webOn: Boolean) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    Column(Modifier.fillMaxSize()) {
        Box(Modifier.padding(top = 4.d, bottom = 8.d)) {
            PaneTitle("Уеб сайтове", if (webOn && web.isNotEmpty()) "${web.size}" else "")
        }
        when {
            !webOn -> Text2("Уеб сайтовете са изключени. Включват се от Настройки → Уеб сайтове.", 13, C.dim, FontWeight.Normal, 2)
            web.isEmpty() -> Text2("Няма адреси на сайтове (или филмът още се зарежда).", 13, C.dim, FontWeight.Normal, 2)
            else -> {
                Text2(
                    "Гледане онлайн, без торент. Рекламите се блокират. Когато видеото тръгне, можеш да го прехвърлиш във вградения плеър (със субтитри) или да го гледаш в самата страница.",
                    11, C.dim, FontWeight.Normal, 3, Modifier.padding(bottom = 6.d)
                )
                LazyColumn(
                    Modifier.fillMaxWidth().weight(1f),
                    contentPadding = PaddingValues(horizontal = 6.d, vertical = 4.d),
                    verticalArrangement = Arrangement.spacedBy(6.d)
                ) {
                    itemsIndexed(web, key = { i, s -> "w$i" + s.host }) { i, s ->
                        PickRow(
                            left = "WEB", title = s.host,
                            sub = if (i == 0) "основен" else "резервен " + i,
                            right = "▶", rightColor = C.accent
                        ) { app.torrtv.web.WebPlay.open(ctx, web, i, st.webRequest()) }
                    }
                }
            }
        }
    }
}

/** Пълното описание и актьорите (в заглавната част има само началото). */
@Composable
private fun AboutPane(d: Detail) {
    Column(Modifier.fillMaxSize()) {
        Box(Modifier.padding(top = 4.d, bottom = 10.d)) { PaneTitle("Описание") }
        if (d.tagline.isNotBlank()) {
            Text2("„${d.tagline}“", 14, C.accent2, FontWeight.Medium, 2)
            Spacer(Modifier.height(8.d))
        }
        Text2(d.media.overview.ifBlank { "Няма описание." }, 14, Color(0xFFD5DAE6), FontWeight.Normal, 6)
        if (d.cast.isNotEmpty()) {
            Spacer(Modifier.height(12.d))
            Text2("В ролите", 11, C.dim, FontWeight.Bold, 1)
            Spacer(Modifier.height(3.d))
            Text2(d.cast.take(8).joinToString(", "), 13, C.text, FontWeight.Medium, 2)
        }
        if (d.media.originalTitle.isNotBlank() && d.media.originalTitle != d.media.title) {
            Spacer(Modifier.height(10.d))
            Text2("Оригинално заглавие", 11, C.dim, FontWeight.Bold, 1)
            Spacer(Modifier.height(3.d))
            Text2(d.media.originalTitle, 13, C.text, FontWeight.Medium, 1)
        }
    }
}

/** Предложения: зареждат се при отваряне на раздела и дописват още при края на реда. */
@Composable
private fun RecsPane(st: DetailState, onOpen: (Media) -> Unit) {
    val recs = st.recs
    val scope = rememberCoroutineScope()
    val rowState = remember(st) { LazyListState() }
    LaunchedEffect(st) { if (!recs.started) recs.loadNext() }
    LaunchedEffect(st) {
        snapshotFlow {
            val li = rowState.layoutInfo
            (li.visibleItemsInfo.lastOrNull()?.index ?: 0) to li.totalItemsCount
        }.collect { (last, total) -> if (recs.started && last >= total - 5) recs.loadNext() }
    }
    Column(Modifier.fillMaxSize()) {
        Box(Modifier.padding(top = 4.d, bottom = 4.d)) { PaneTitle("Подобни заглавия") }
        if (recs.items.isEmpty()) {
            if (recs.error != null) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.d)) {
                    Text2(recs.error ?: "", 13, C.red, FontWeight.Normal, 3, Modifier.weight(1f))
                    PrimaryButton("Опитай пак", { scope.launch { recs.retry() } }, primary = false)
                }
            } else if (recs.started && recs.exhausted) Text2("Няма подобни заглавия.", 13, C.dim, FontWeight.Normal, 1)
            else Box(Modifier.height(120.d)) { Spinner() }
        } else {
            BoxWithConstraints(Modifier.fillMaxWidth().weight(1f)) {
                // Плакатите растат с височината на мястото (между 100 и 150), за да не остава празно отдолу.
                val pw = minOf(maxOf((maxHeight.value - 24.d.value) / 1.5f, 100.d.value), 150.d.value).dp
                LazyRow(
                    state = rowState,
                    contentPadding = PaddingValues(horizontal = 6.d, vertical = 12.d),
                    horizontalArrangement = Arrangement.spacedBy(12.d)
                ) {
                    items(recs.items, key = { it.key }) { m -> Poster(m, pw, { onOpen(m) }) }
                    if (recs.loading) item { Box(Modifier.width(60.d).height(pw * 1.5f), contentAlignment = Alignment.Center) { Spinner(22.d) } }
                }
            }
        }
    }
}

@Composable
fun TorrentRow(
    t: TorrentResult, fit: app.torrtv.data.subs.Fit, storage: StreamFit.Level, smooth: StreamFit.Level, onClick: () -> Unit
) {
    // Една от четирите колони вдясно: най-важното за това дали ще тръгне гладко.
    val status: Triple<String, Color, Color>? = when {
        storage == StreamFit.Level.BAD -> Triple("няма място", Color(0x33FF5C6C), C.red)
        smooth == StreamFit.Level.BAD -> Triple("ще буферира", Color(0x33FF5C6C), C.red)
        storage == StreamFit.Level.EDGE -> Triple("малко място", Color(0x33FFB020), C.amber)
        smooth == StreamFit.Level.EDGE -> Triple("на ръба", Color(0x33FFB020), C.amber)
        smooth == StreamFit.Level.GOOD -> Triple("гладко", Color(0x333DDC84), C.green)
        else -> null
    }
    // Звукът, който вграденият плеър не пуска, и записът от камера се виждат първи; обикновените белези са текст.
    val warn = t.tags.filter { it == "CAM" || it == "DTS" || it == "TrueHD" }.take(2)
    val plain = t.tags.filter { it !in warn }.take(2)
    Focusable(onClick, Modifier.fillMaxWidth().padding(vertical = 2.d), RoundedCornerShape(12.d), 1.012f) { f ->
        Row(
            Modifier.fillMaxWidth().background(if (f) C.cardHi else C.card).padding(horizontal = 12.d, vertical = 7.d),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.d)
        ) {
            // 1. Качество
            Box(Modifier.width(52.d), contentAlignment = Alignment.Center) {
                Pill(t.quality, if (t.quality == "4K" || t.quality == "1080p") C.accent else C.cardHi, if (t.quality == "4K" || t.quality == "1080p") C.onAccent else C.text)
            }
            // 2. Име, размер, сайт и белези на файла
            Column(Modifier.weight(1f)) {
                Text2(t.title, 13, C.text, FontWeight.SemiBold, 1)
                Spacer(Modifier.height(2.d))
                Row(horizontalArrangement = Arrangement.spacedBy(6.d), verticalAlignment = Alignment.CenterVertically) {
                    Text2(
                        (listOf(formatSize(t.size), t.source + (if (t.via.isNotBlank()) " през " + t.via else "")) + plain).joinToString(" · "),
                        11, C.dim, FontWeight.Normal, 1, Modifier.weight(1f, fill = false)
                    )
                    warn.forEach {
                        if (it == "CAM") Pill(it, Color(0x33FF5C6C), C.red) else Pill(it, Color(0x33FFB020), C.amber)
                    }
                    if (t.pack) Pill("пакет", Color(0x33FFB020), C.amber)
                }
            }
            // 3. Годност: гладко ли ще върви и пасват ли субтитрите
            Column(Modifier.width(116.d), horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(2.d)) {
                status?.let { Pill(it.first, it.second, it.third) }
                when (fit) {
                    app.torrtv.data.subs.Fit.EXACT -> Text2("✓ вероятно пасват", 11, C.green, FontWeight.Medium, 1)
                    app.torrtv.data.subs.Fit.SIMILAR -> Text2("~ подобни субтитри", 11, C.amber, FontWeight.Medium, 1)
                    else -> {}
                }
            }
            // 4. Сийдъри
            Row(Modifier.width(56.d), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(9.d).clip(CircleShape).background(seedColor(t.seeders)))
                Spacer(Modifier.width(6.d))
                Column(horizontalAlignment = Alignment.End) {
                    Text2(if (t.seeders < 0) "?" else "${t.seeders}", 16, seedColor(t.seeders), FontWeight.ExtraBold, 1)
                    Text2("сийдъра", 9, C.dim, FontWeight.Normal, 1)
                }
            }
        }
    }
}
