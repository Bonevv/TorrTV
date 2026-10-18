package app.torrtv.ui

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.style.TextOverflow
import app.torrtv.data.Media
import app.torrtv.data.MediaType
import app.torrtv.data.Tmdb
import app.torrtv.state.Graph
import app.torrtv.state.HomeState
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale

@Composable
fun HeroBanner(m: Media?, kicker: String, isPick: Boolean, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth()) {
        Crossfade(targetState = m, animationSpec = tween(if (Look.lite) 0 else 350), label = "hero") { cur ->
            Box(Modifier.fillMaxSize()) {
                if (cur != null) {
                    Img(Look.img(cur.backdropPath ?: cur.posterPath, "w780"), Modifier.fillMaxSize())
                }
                Box(
                    Modifier.fillMaxSize().background(
                        Brush.horizontalGradient(listOf(Color(0xF507090E), Color(0x9907090E), Color.Transparent))
                    )
                )
                Box(
                    Modifier.fillMaxSize().background(
                        Brush.verticalGradient(listOf(Color.Transparent, Color(0x0007090E), C.bg))
                    )
                )
                if (cur != null) {
                    Column(
                        Modifier.align(Alignment.BottomStart).padding(start = 36.d, bottom = 14.d).fillMaxWidth(0.5f)
                    ) {
                        if (kicker.isNotBlank()) {
                            Text(
                                kicker, color = C.accent, fontSize = 10.t, fontWeight = FontWeight.Bold,
                                letterSpacing = 2.t, maxLines = 1
                            )
                            Spacer(Modifier.height(4.d))
                        }
                        Text2(cur.title, 32, C.text, FontWeight.ExtraBold, 2)
                        Spacer(Modifier.height(6.d))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            val parts = ArrayList<Pair<String, Color>>()
                            if (cur.rating > 0) parts.add("★ " + String.format(Locale.US, "%.1f", cur.rating) to C.gold)
                            if (cur.year.isNotBlank()) parts.add(cur.year to Color(0xFFC9CFDC))
                            if (isPick) parts.add((if (cur.type == MediaType.TV) "Сериал" else "Филм") to Color(0xFFC9CFDC))
                            val g = genreLine(cur)
                            if (g.isNotBlank()) parts.add(g to Color(0xFFC9CFDC))
                            parts.forEachIndexed { i, (t, c) ->
                                if (i > 0) {
                                    Spacer(Modifier.width(8.d))
                                    Box(Modifier.size(3.d).clip(RoundedCornerShape(50)).background(Color(0xFF6E778A)))
                                    Spacer(Modifier.width(8.d))
                                }
                                Text2(t, 12, c, if (i == 0 && cur.rating > 0) FontWeight.Bold else FontWeight.Medium, 1)
                            }
                        }
                        Spacer(Modifier.height(6.d))
                        Text2(cur.overview.ifBlank { "Няма описание." }, 12, Color(0xFFC4CAD8), FontWeight.Normal, 2)
                    }
                }
            }
        }
    }
}

@Composable
fun Text2(text: String, size: Int, color: Color, weight: FontWeight, lines: Int, modifier: Modifier = Modifier) {
    androidx.compose.material3.Text(
        text, color = color, fontSize = size.t, fontWeight = weight, maxLines = lines,
        overflow = TextOverflow.Ellipsis, modifier = modifier, lineHeight = (size * 13 / 10).t
    )
}

/** Надпис над банера: за избор на седмицата - класирането, иначе видът на заглавието. Вторият резултат: дали е избор. */
private fun kickerOf(home: HomeState, m: Media?): Pair<String, Boolean> {
    if (m == null) return "" to false
    for (r in home.picks) {
        val i = r.items.indexOfFirst { it.key == m.key }
        if (i >= 0) return "ИЗБОР НА СЕДМИЦАТА · №${i + 1}" to true
    }
    return (if (m.type == MediaType.TV) "СЕРИАЛ" else "ФИЛМ") to false
}

@Composable
fun HomeScreen(home: HomeState, onOpen: (Media) -> Unit, onResume: (app.torrtv.state.Library.Progress) -> Unit) {
    // Хероят се сменя със закъснение, за да не мига при бързо прескачане по плакатите.
    var shown by remember { mutableStateOf(home.heroTarget) }
    LaunchedEffect(home.heroTarget) {
        if (shown == null) shown = home.heroTarget else {
            delay(220)
            shown = home.heroTarget
        }
    }
    // Докато се гледа един ред, тези под него се зареждат отнапред (виж HomeState.warm).
    LaunchedEffect(Unit) {
        snapshotFlow {
            home.listState.layoutInfo.visibleItemsInfo.maxOfOrNull { v -> home.rows.indexOfFirst { it.id == v.key } } ?: -1
        }.collect { home.warm(it) }
    }
    Column(Modifier.fillMaxSize()) {
        val (kicker, isPick) = kickerOf(home, shown)
        HeroBanner(shown, kicker, isPick, Modifier.height(215.d))
        LazyColumn(Modifier.fillMaxWidth().weight(1f), state = home.listState) {
            if (app.torrtv.state.Library.progress.isNotEmpty()) {
                item(key = "continue") { ContinueRow(onResume, { home.heroTarget = it }) }
            }
            items(home.picks, key = { "pick-" + it.type.path }) { row ->
                PicksRow(home, row, onOpen)
            }
            if (app.torrtv.state.Library.favorites.isNotEmpty()) {
                item(key = "favs") {
                    MediaRow("Любими", app.torrtv.state.Library.favorites.take(20), onOpen, { home.heroTarget = it })
                }
            }
            items(home.rows, key = { it.id }) { row ->
                HomeRow(home, row, onOpen)
            }
            item { Spacer(Modifier.height(30.d)) }
        }
    }
}

@Composable
private fun HomeRow(home: HomeState, row: HomeState.Row, onOpen: (Media) -> Unit) {
    val data = row.data
    val scope = rememberCoroutineScope()
    LaunchedEffect(row) {
        if (data.items.isEmpty() && !data.loading && data.error == null) data.loadNext()
        snapshotFlow {
            val li = row.listState.layoutInfo
            (li.visibleItemsInfo.lastOrNull()?.index ?: 0) to li.totalItemsCount
        }.collect { (last, total) ->
            if (total > 0 && last >= total - 6) data.loadNext()
        }
    }
    LaunchedEffect(data.items.size) {
        if (home.heroTarget == null && data.items.isNotEmpty() && row.id == "trend") home.heroTarget = data.items[0]
    }
    Column(Modifier.fillMaxWidth()) {
        SectionTitle(row.title)
        if (data.items.isEmpty()) {
            if (data.error != null) {
                ErrorLine(data.error ?: "", { scope.launch { data.retry() } })
            } else if (data.exhausted) {
                Text2("Няма заглавия.", 12, C.dim, FontWeight.Normal, 1, Modifier.padding(horizontal = 36.d, vertical = 12.d))
            } else {
                Box(Modifier.fillMaxWidth().height(168.d), contentAlignment = Alignment.CenterStart) {
                    Box(Modifier.padding(start = 36.d)) { Spinner() }
                }
            }
        } else {
            LazyRow(
                state = row.listState,
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 36.d, vertical = 8.d),
                horizontalArrangement = Arrangement.spacedBy(12.d)
            ) {
                items(data.items, key = { it.key }) { m ->
                    val fr = remember { FocusRequester() }
                    val fk = "${row.id}|${m.key}"
                    if (home.focusKey == fk) {
                        LaunchedEffect(Unit) {
                            fr.tryRequest()
                            home.focusKey = null
                        }
                    }
                    Poster(
                        m, 112.d,
                        onClick = {
                            home.focusKey = fk
                            onOpen(m)
                        },
                        onFocus = { home.heroTarget = m },
                        focusRequester = fr
                    )
                }
                if (data.loading) item { Box(Modifier.width(60.d).height(168.d), contentAlignment = Alignment.Center) { Spinner(22.d) } }
            }
        }
    }
}
