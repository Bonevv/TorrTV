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
import app.torrtv.core.formatSize
import app.torrtv.data.Media
import app.torrtv.data.MediaType
import app.torrtv.data.Tmdb
import app.torrtv.data.torrents.TorrentResult
import app.torrtv.state.DetailState
import app.torrtv.state.Graph
import kotlinx.coroutines.launch
import java.util.Locale

fun seedColor(n: Int): Color = when {
    n >= 20 -> C.green
    n >= 5 -> C.amber
    else -> C.red
}

@Composable
fun DetailScreen(st: DetailState, onOpen: (Media) -> Unit, onPlay: (TorrentResult) -> Unit) {
    val d = st.detail
    val scope = rememberCoroutineScope()
    var quality by remember(st) { mutableStateOf("all") }
    val playFocus = remember { FocusRequester() }
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
        Img(Tmdb.img(d?.media?.backdropPath, "w1280"), Modifier.fillMaxSize(), 1280, 720)
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xCC080B12), Color(0xF5080B12), C.bg))))

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

        val shown = remember(st.torrents, quality) {
            val l = if (quality == "all") st.torrents else st.torrents.filter { it.quality == quality }
            l.take(60)
        }

        LazyColumn(Modifier.fillMaxSize(), state = listState, contentPadding = PaddingValues(bottom = 40.d)) {
            item {
                Row(Modifier.fillMaxWidth().padding(start = 36.d, end = 36.d, top = 28.d), horizontalArrangement = Arrangement.spacedBy(22.d)) {
                    Img(Tmdb.img(d.media.posterPath, "w342"), Modifier.width(150.d).height(225.d).clip(RoundedCornerShape(12.d)), 342, 513)
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
                        Row(horizontalArrangement = Arrangement.spacedBy(10.d)) {
                            val best = st.torrents.filter { !it.isCam }.maxByOrNull { score(it) } ?: st.torrents.firstOrNull()
                            PrimaryButton(
                                if (best != null) "▶  Гледай най-доброто" else if (st.searching) "Търсене…" else "Няма торенти",
                                { best?.let(onPlay) },
                                focusRequester = playFocus
                            )
                            PrimaryButton("↻ Търси пак", { st.loadTorrents(); st.loadSubs() }, primary = false)
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
                                        Img(Tmdb.img(e.stillPath, "w300"), Modifier.fillMaxWidth().height(104.d), 300, 169)
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
                        if (d.media.type == MediaType.TV) "Торенти · С${st.season} Е${st.episode}" else "Торенти"
                    )
                    if (st.searching) Spinner(18.d)
                }
                LazyRow(contentPadding = PaddingValues(horizontal = 36.d), horizontalArrangement = Arrangement.spacedBy(8.d)) {
                    val present = st.torrents.map { it.quality }.toSet()
                    items(listOf("all", "4K", "1080p", "720p", "480p", "SD").filter { it == "all" || it in present }, key = { it }) { q ->
                        Chip(if (q == "all") "Всички (${st.torrents.size})" else q, quality == q, { quality = q })
                    }
                }
                if (st.torrentStatus.isNotEmpty()) {
                    Spacer(Modifier.height(6.d))
                    Row(Modifier.padding(horizontal = 36.d), horizontalArrangement = Arrangement.spacedBy(6.d)) {
                        st.torrentStatus.entries.sortedBy { it.key }.forEach { (k, v) ->
                            Pill("$k: $v", if (v.startsWith("✗")) Color(0x33FF5C6C) else C.card, if (v.startsWith("✗")) C.red else C.dim)
                        }
                    }
                }
                Spacer(Modifier.height(8.d))
            }

            if (shown.isEmpty()) {
                item {
                    Text2(
                        if (st.searching) "Търся в източниците…" else "Няма намерени торенти. Провери „Настройки → Диагностика“.",
                        13, C.dim, FontWeight.Normal, 2, Modifier.padding(horizontal = 36.d, vertical = 8.d)
                    )
                }
            }
            items(shown, key = { it.hash + it.source }) { t -> TorrentRow(t) { onPlay(t) } }

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
                SectionTitle("✨  Предложения след това")
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
fun TorrentRow(t: TorrentResult, onClick: () -> Unit) {
    Focusable(onClick, Modifier.fillMaxWidth().padding(horizontal = 36.d, vertical = 3.d), RoundedCornerShape(10.d), 1.015f) { f ->
        Row(
            Modifier.fillMaxWidth().background(if (f) C.cardHi else C.card).padding(horizontal = 12.d, vertical = 8.d),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.d)
        ) {
            Box(Modifier.width(50.d), contentAlignment = Alignment.Center) {
                Pill(t.quality, if (t.quality == "4K" || t.quality == "1080p") C.accent else C.cardHi, C.text)
            }
            Column(Modifier.weight(1f)) {
                Text2(t.title, 13, C.text, FontWeight.SemiBold, 1)
                Row(horizontalArrangement = Arrangement.spacedBy(6.d), verticalAlignment = Alignment.CenterVertically) {
                    Text2(formatSize(t.size) + " · " + t.source, 11, C.dim, FontWeight.Normal, 1)
                    t.tags.take(4).forEach { Pill(it, Color(0x22FFFFFF), if (it == "CAM") C.red else C.dim) }
                    if (t.pack) Pill("пакет", Color(0x33FFB020), C.amber)
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(9.d).clip(CircleShape).background(seedColor(t.seeders)))
                Spacer(Modifier.width(6.d))
                Column(horizontalAlignment = Alignment.End) {
                    Text2("${t.seeders}", 16, seedColor(t.seeders), FontWeight.ExtraBold, 1)
                    Text2("сийдъра", 9, C.dim, FontWeight.Normal, 1)
                }
            }
        }
    }
}
