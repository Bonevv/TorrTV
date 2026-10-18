package app.torrtv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
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
import app.torrtv.core.StreamFit
import app.torrtv.core.Storage
import app.torrtv.core.formatSize
import app.torrtv.data.Media
import app.torrtv.data.MediaType
import app.torrtv.data.Tmdb
import app.torrtv.data.torrents.TorrentResult
import app.torrtv.state.DetailState
import app.torrtv.state.Graph
import app.torrtv.state.Library
import kotlinx.coroutines.launch
import java.util.Locale

fun seedColor(n: Int): Color = when {
    n < 0 -> C.dim
    n >= 20 -> C.green
    n >= 5 -> C.amber
    else -> C.red
}

@Composable
fun DetailScreen(st: DetailState, onOpen: (Media) -> Unit, onPlay: (TorrentResult, Long) -> Unit) {
    val d = st.detail
    val scope = rememberCoroutineScope()
    var quality by remember(st) { mutableStateOf("all") }
    val playFocus = remember { FocusRequester() }
    // 0 = торенти, 1 = уеб сайтове (двата бутона над списъка); site = филтър по торент сайт
    var tab by remember(st) { mutableStateOf(0) }
    var site by remember(st) { mutableStateOf("all") }
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val listState = remember(st) { androidx.compose.foundation.lazy.LazyListState() }

    LaunchedEffect(st) { if (st.detail == null) st.start() }
    LaunchedEffect(d != null) { if (d != null) playFocus.tryRequest() }
    LaunchedEffect(st) {
        // Автоматично зареждане на предложения, когато потребителят стигне до края
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }
            .collect { if (it >= 3 && !st.recs.started) st.recs.loadNext() }
    }

    Box(Modifier.fillMaxSize()) {
        // фон
        Img(Look.img(d?.media?.backdropPath, "w780"), Modifier.fillMaxSize(), 780, 439)
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xCC07090E), Color(0xF507090E), C.bg))))

        if (d == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                if (st.error != null) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text2("⚠ ${st.error}", 14, C.red, FontWeight.Medium, 4)
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
        val shown = remember(st.torrents, quality, siteSel) {
            st.torrents.filter { (quality == "all" || it.quality == quality) && (siteSel == "all" || it.onSite(siteSel)) }.take(60)
        }
        val web = remember(st.detail, st.season, st.episode) { st.webSources() }
        val webOn = Graph.prefs.webEnabled
        // Свободно място на избраното място и измерени скорости - за етикетите „събира се“ и „гладко“.
        val place = remember { Storage.chosen(ctx, Graph.prefs.storageId) }
        val freeBytes = remember(place, st.torrents.size) { Storage.free(place) }
        val netMbps = Graph.prefs.netMbps.toDouble()
        val diskMBps = Graph.prefs.diskMBps.toDouble()
        val runtimeMin = if (d.media.type == MediaType.TV) st.episodes.firstOrNull { it.number == st.episode }?.runtime?.takeIf { it > 0 } ?: d.runtime else d.runtime
        fun rowFit(t: TorrentResult): Pair<StreamFit.Level, StreamFit.Level> =
            if (t.pack) StreamFit.Level.NONE to StreamFit.Level.NONE
            else StreamFit.storage(t.size, freeBytes) to StreamFit.smooth(t.size, runtimeMin, netMbps, diskMBps, t.seeders)

        LazyColumn(Modifier.fillMaxSize(), state = listState, contentPadding = PaddingValues(bottom = 40.d)) {
            item {
                Row(Modifier.fillMaxWidth().padding(start = 36.d, end = 36.d, top = 28.d), horizontalArrangement = Arrangement.spacedBy(22.d)) {
                    Img(Look.img(d.media.posterPath, "w342"), Modifier.width(150.d).height(225.d).clip(RoundedCornerShape(12.d)), 342, 513)
                    Column(Modifier.weight(1f)) {
                        Text2(d.media.title, 30, C.text, FontWeight.ExtraBold, 2)
                        if (d.media.originalTitle.isNotBlank() && d.media.originalTitle != d.media.title)
                            Text2(d.media.originalTitle, 13, C.dim, FontWeight.Normal, 1)
                        if (d.tagline.isNotBlank()) {
                            Spacer(Modifier.height(4.d))
                            Text2("„${d.tagline}“", 13, C.accent2, FontWeight.Medium, 2)
                        }
                        Spacer(Modifier.height(8.d))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.d), verticalAlignment = Alignment.CenterVertically) {
                            if (d.media.rating > 0) Pill("★ " + String.format(Locale.US, "%.1f", d.media.rating), Color(0x33FFC857), C.gold)
                            if (d.media.year.isNotBlank()) Pill(d.media.year)
                            if (d.runtime > 0) Pill("${d.runtime} мин")
                            if (d.status.isNotBlank() && d.media.type == MediaType.TV) Pill(d.status)
                            if (d.media.type == MediaType.TV) Pill("${d.seasons.size} сез.")
                        }
                        Spacer(Modifier.height(6.d))
                        if (d.genres.isNotEmpty()) Text2(d.genres.joinToString(" · ") { it.name }, 12, C.accent2, FontWeight.Medium, 1)
                        Spacer(Modifier.height(6.d))
                        Text2(d.media.overview.ifBlank { "Няма описание." }, 13, Color(0xFFD5DAE6), FontWeight.Normal, 5)
                        if (d.cast.isNotEmpty()) {
                            Spacer(Modifier.height(6.d))
                            Text2("В ролите: " + d.cast.take(6).joinToString(", "), 12, C.dim, FontWeight.Normal, 2)
                        }
                        Spacer(Modifier.height(12.d))
                        val best = st.torrents.filter { !it.isCam }.let { l ->
                            l.filter { rowFit(it).first != StreamFit.Level.BAD }.ifEmpty { l }
                        }.maxByOrNull { score(it) - if (rowFit(it).second == StreamFit.Level.BAD) 1500 else 0 } ?: st.torrents.firstOrNull()
                        // Започнато ли е вече? За сериал - само ако е избран същият епизод.
                        val pr = Library.progressOf(d.media.key)?.takeIf {
                            d.media.type == MediaType.MOVIE || (it.season == st.season && it.episode == st.episode)
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(12.d), verticalAlignment = Alignment.CenterVertically) {
                            if (pr != null) {
                                PrimaryButton(
                                    "▶  Продължи · " + Library.fmt(pr.posMs),
                                    { onPlay(pr.torrent(), pr.posMs) },
                                    focusRequester = playFocus, big = true
                                )
                            }
                            PrimaryButton(
                                when {
                                    best != null && pr != null -> "От началото"
                                    best != null -> "▶  Гледай"
                                    st.searching -> "Търсене…"
                                    else -> "🌐  Няма торенти - гледай от сайт"
                                },
                                {
                                    if (best != null) onPlay(best, 0L)
                                    else if (!st.searching) {
                                        // Няма торенти: показваме уеб сайтовете и слизаме до тях.
                                        tab = 1
                                        scope.launch { listState.animateScrollToItem(if (d.media.type == MediaType.TV) 2 else 1) }
                                    }
                                },
                                primary = pr == null,
                                focusRequester = if (pr == null) playFocus else null,
                                big = pr == null
                            )
                        }
                        Spacer(Modifier.height(8.d))
                        Row(horizontalArrangement = Arrangement.spacedBy(10.d)) {
                            val fav = Library.isFavorite(d.media.key)
                            PrimaryButton(
                                if (fav) "♥ В любими" else "♡ В любими",
                                { Library.toggleFavorite(d.media) },
                                primary = false
                            )
                            PrimaryButton("↻ Търси пак", { st.loadTorrents(); st.loadSubs() }, primary = false)
                        }
                        Spacer(Modifier.height(5.d))
                        if (best != null && best.seeders in 0..4 && pr == null) {
                            Text2(
                                "⚠ Най-добрият торент има само ${best.seeders} сийдъра - може да се буферира бавно. Ако е така, избери друг торент или „Уеб сайтове“ по-долу.",
                                11, C.amber, FontWeight.Medium, 2
                            )
                        } else {
                            Text2(
                                "„Гледай“ избира торент с най-много сийдъри и добро качество. По-долу избираш друг торент или уеб сайт.",
                                11, C.dim, FontWeight.Normal, 2
                            )
                        }
                    }
                }
            }

            if (d.media.type == MediaType.TV) {
                item {
                    SectionTitle("Сезони")
                    LazyRow(contentPadding = PaddingValues(horizontal = 36.d), horizontalArrangement = Arrangement.spacedBy(8.d)) {
                        items(d.seasons, key = { it.number }) { s ->
                            Chip(
                                if (s.number == 0) "Специални" else "Сезон ${s.number}",
                                s.number == st.season, { st.selectSeason(s.number) }
                            )
                        }
                    }
                    Spacer(Modifier.height(10.d))
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 36.d, vertical = 6.d),
                        horizontalArrangement = Arrangement.spacedBy(12.d)
                    ) {
                        items(st.episodes, key = { it.number }) { e ->
                            val sel = e.number == st.episode
                            Focusable({ st.selectEpisode(e.number) }, Modifier.width(190.d), RoundedCornerShape(10.d), 1.06f) { f ->
                                Column(Modifier.background(if (sel) C.cardHi else C.card)) {
                                    Box {
                                        Img(Look.img(e.stillPath, "w300"), Modifier.fillMaxWidth().height(104.d), 300, 169)
                                        Box(Modifier.padding(6.d)) { Pill("Еп. ${e.number}", Color(0xCC000000), if (sel) C.accent2 else C.text) }
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

            item {
                Row(Modifier.fillMaxWidth().padding(end = 36.d), verticalAlignment = Alignment.CenterVertically) {
                    SectionTitle(
                        if (d.media.type == MediaType.TV) "Откъде да гледаш · С${st.season} Е${st.episode}" else "Откъде да гледаш"
                    )
                    if (st.searching && tab == 0) Spinner(18.d)
                }
                // Двата бутона: щом натиснеш единия, списъкът отдолу се сменя.
                Row(
                    Modifier.padding(horizontal = 36.d), horizontalArrangement = Arrangement.spacedBy(10.d),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Chip("🧲  Торенти (${st.torrents.size})", tab == 0, { tab = 0 })
                    Chip("🌐  Уеб сайтове (${web.size})", tab == 1, { tab = 1 })
                }
                Spacer(Modifier.height(4.d))
                Text2(
                    "Свободно: ${app.torrtv.core.CacheManager.fmt(freeBytes)} (${place.label.lowercase()}) · файлове до около " +
                        app.torrtv.core.CacheManager.fmt(StreamFit.usableBytes(freeBytes)) +
                        (if (netMbps > 0) " · мрежа ${netMbps.toInt()} Mbit/s" else ""),
                    11, C.dim, FontWeight.Normal, 1, Modifier.padding(horizontal = 36.d)
                )
                Spacer(Modifier.height(6.d))
                if (tab == 0) {
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 36.d), horizontalArrangement = Arrangement.spacedBy(8.d),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        item(key = "qlabel") { Text2("Качество", 11, C.dim, FontWeight.Bold, 1) }
                        val present = st.torrents.map { it.quality }.toSet()
                        items(listOf("all", "4K", "1080p", "720p", "480p", "SD").filter { it == "all" || it in present }, key = { it }) { q ->
                            Chip(if (q == "all") "Всички (${st.torrents.size})" else q, quality == q, { quality = q })
                        }
                    }
                    if (siteList.size >= 2) {
                        Spacer(Modifier.height(6.d))
                        LazyRow(
                            contentPadding = PaddingValues(horizontal = 36.d), horizontalArrangement = Arrangement.spacedBy(8.d),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            item(key = "slabel") { Text2("Сайт", 11, C.dim, FontWeight.Bold, 1) }
                            item(key = "sall") { Chip("Всички сайтове", siteSel == "all", { site = "all" }, compact = true) }
                            items(siteList, key = { "s" + it.first }) { (name, n) ->
                                Chip("$name · $n", siteSel == name, { site = name }, compact = true)
                            }
                        }
                    }
                    // Източници, които не са отговорили (останалите се виждат във филтъра по сайт).
                    val failed = st.torrentStatus.entries.filter { it.value.startsWith("✗") }.sortedBy { it.key }
                    if (failed.isNotEmpty()) {
                        Spacer(Modifier.height(6.d))
                        Row(Modifier.padding(horizontal = 36.d), horizontalArrangement = Arrangement.spacedBy(6.d)) {
                            failed.forEach { (k, v) -> Pill("$k: $v", Color(0x33FF5C6C), C.red) }
                        }
                    }
                }
                Spacer(Modifier.height(8.d))
            }

            if (tab == 1) {
                if (!webOn) {
                    item(key = "weboff") {
                        Text2(
                            "Уеб сайтовете са изключени. Включват се от Настройки → Уеб сайтове.",
                            13, C.dim, FontWeight.Normal, 2, Modifier.padding(horizontal = 36.d, vertical = 8.d)
                        )
                    }
                } else if (web.isEmpty()) {
                    item(key = "webnone") {
                        Text2(
                            "Няма адреси (или още се зарежда информацията за филма).",
                            13, C.dim, FontWeight.Normal, 2, Modifier.padding(horizontal = 36.d, vertical = 8.d)
                        )
                    }
                } else {
                    item(key = "webnote") {
                        Text2(
                            "Гледане онлайн, без торент. Рекламите се блокират. Щом сайтът пусне видеото, можеш да го прехвърлиш във вградения плеър със субтитри. Ако не, гледаш в самата страница.",
                            11, C.dim, FontWeight.Normal, 3, Modifier.padding(horizontal = 36.d, vertical = 2.d)
                        )
                    }
                    itemsIndexed(web, key = { i, s -> "w$i" + s.host }) { i, s ->
                        Box(Modifier.padding(horizontal = 36.d, vertical = 3.d)) {
                            PickRow(
                                left = "WEB", title = s.host,
                                sub = if (i == 0) "основен" else "резервен " + i,
                                right = "▶", rightColor = C.accent
                            ) { app.torrtv.web.WebPlay.open(ctx, web, i, st.webRequest()) }
                        }
                    }
                }
            } else {
                if (shown.isEmpty()) {
                    item(key = "none") {
                        Text2(
                            when {
                                st.searching -> "Търся в източниците…"
                                st.torrents.isNotEmpty() -> "Няма торенти с този избор. Смени качеството или сайта."
                                else -> "Няма намерени торенти. Провери „Настройки → Диагностика“ или пробвай „Уеб сайтове“."
                            },
                            13, C.dim, FontWeight.Normal, 2, Modifier.padding(horizontal = 36.d, vertical = 8.d)
                        )
                    }
                }
                items(shown, key = { it.hash + it.source }) { t ->
                    val fit = remember(t.hash, st.subResults) { st.subFit(t) }
                    val (sf, sm) = rowFit(t)
                    TorrentRow(t, fit, sf, sm) { onPlay(t, 0L) }
                }
            }

            item {
                if (st.subResults.isNotEmpty() || st.subStatus.isNotEmpty()) {
                    Spacer(Modifier.height(10.d))
                    Row(Modifier.padding(horizontal = 36.d), horizontalArrangement = Arrangement.spacedBy(6.d), verticalAlignment = Alignment.CenterVertically) {
                        Text2("Субтитри:", 12, C.dim, FontWeight.Bold, 1)
                        st.subStatus.entries.sortedBy { it.key }.forEach { (k, v) ->
                            Pill("$k: $v", if (v.startsWith("✗")) Color(0x33FF5C6C) else C.card, if (v.startsWith("✗")) C.red else C.dim)
                        }
                        if (st.subSearching) Spinner(16.d)
                    }
                }
            }

            // Предложения: зареждат се сами при стигане до тук и дописват още при края на реда
            item {
                val recs = st.recs
                val rowState = remember(st) { androidx.compose.foundation.lazy.LazyListState() }
                LaunchedEffect(st) {
                    snapshotFlow {
                        val li = rowState.layoutInfo
                        (li.visibleItemsInfo.lastOrNull()?.index ?: 0) to li.totalItemsCount
                    }.collect { (last, total) -> if (recs.started && last >= total - 5) recs.loadNext() }
                }
                Spacer(Modifier.height(18.d))
                SectionTitle("Предложения след това")
                if (recs.items.isEmpty()) {
                    if (recs.error != null) ErrorLine(recs.error ?: "", { scope.launch { recs.retry() } })
                    else if (recs.started && recs.exhausted) Text2("Няма предложения.", 12, C.dim, FontWeight.Normal, 1, Modifier.padding(horizontal = 36.d))
                    else Box(Modifier.padding(horizontal = 36.d).height(120.d)) { Spinner() }
                } else {
                    LazyRow(
                        state = rowState,
                        contentPadding = PaddingValues(horizontal = 36.d, vertical = 8.d),
                        horizontalArrangement = Arrangement.spacedBy(12.d)
                    ) {
                        items(recs.items, key = { it.key }) { m -> Poster(m, 112.d, { onOpen(m) }) }
                        if (recs.loading) item { Box(Modifier.width(60.d).height(168.d), contentAlignment = Alignment.Center) { Spinner(22.d) } }
                    }
                }
            }
        }
    }
}

private fun score(t: TorrentResult): Int {
    val q = when (t.quality) { "4K" -> 3; "1080p" -> 4; "720p" -> 2; else -> 0 }
    val seeds = minOf(t.seeders, 200)
    return (if (t.seeders >= 5) q * 1000 else 0) + seeds * 3
}

@Composable
fun TorrentRow(
    t: TorrentResult, fit: app.torrtv.data.subs.Fit, storage: StreamFit.Level, smooth: StreamFit.Level, onClick: () -> Unit
) {
    Focusable(onClick, Modifier.fillMaxWidth().padding(horizontal = 36.d, vertical = 3.d), RoundedCornerShape(10.d), 1.015f) { f ->
        Row(
            Modifier.fillMaxWidth().background(if (f) C.cardHi else C.card).padding(horizontal = 12.d, vertical = 8.d),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.d)
        ) {
            Box(Modifier.width(50.d), contentAlignment = Alignment.Center) {
                Pill(t.quality, if (t.quality == "4K" || t.quality == "1080p") C.accent else C.cardHi, if (t.quality == "4K" || t.quality == "1080p") C.onAccent else C.text)
            }
            Column(Modifier.weight(1f)) {
                Text2(t.title, 13, C.text, FontWeight.SemiBold, 1)
                Row(horizontalArrangement = Arrangement.spacedBy(6.d), verticalAlignment = Alignment.CenterVertically) {
                    Text2(formatSize(t.size) + " · " + t.source + (if (t.via.isNotBlank()) " · през " + t.via else ""), 11, C.dim, FontWeight.Normal, 1)
                    // Звукът, който вграденият плеър не пуска, и записът от камера се виждат първи.
                    t.tags.sortedBy { if (it == "DTS" || it == "TrueHD" || it == "CAM") 0 else 1 }.take(3).forEach {
                        when (it) {
                            "CAM" -> Pill(it, Color(0x33FF5C6C), C.red)
                            "DTS", "TrueHD" -> Pill("$it ⚠", Color(0x33FFB020), C.amber)
                            else -> Pill(it, Color(0x22FFFFFF), C.dim)
                        }
                    }
                    if (t.pack) Pill("пакет", Color(0x33FFB020), C.amber)
                    when (storage) {
                        StreamFit.Level.BAD -> Pill("✗ не се събира", Color(0x33FF5C6C), C.red)
                        StreamFit.Level.EDGE -> Pill("⚠ малко място", Color(0x33FFB020), C.amber)
                        else -> {}
                    }
                    when (smooth) {
                        StreamFit.Level.GOOD -> Pill("▶ гладко", Color(0x333DDC84), C.green)
                        StreamFit.Level.EDGE -> Pill("▶ на ръба", Color(0x33FFB020), C.amber)
                        StreamFit.Level.BAD -> Pill("▶ ще буферира", Color(0x33FF5C6C), C.red)
                        else -> {}
                    }
                    when (fit) {
                        app.torrtv.data.subs.Fit.EXACT -> Pill("💬 ✓ точни субтитри", Color(0x333DDC84), C.green)
                        app.torrtv.data.subs.Fit.SIMILAR -> Pill("💬 ~ подобни субтитри", Color(0x33FFB020), C.amber)
                        else -> {}
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
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
