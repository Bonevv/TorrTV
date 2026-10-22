package app.torrtv.ui

import app.torrtv.data.MediaType
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.torrtv.data.Genres
import app.torrtv.data.Platforms
import app.torrtv.data.Media
import app.torrtv.state.BrowseState
import app.torrtv.state.Graph
import app.torrtv.state.Paged
import app.torrtv.state.SearchState
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Мрежа с плакати и безкрайно зареждане (до тавана на Paged). Колоните се смятат от свободната широчина
 * (виж gridGeometry), така че на всеки екран редът е пълен и нищо не е отрязано.
 */
@Composable
fun PosterGrid(
    paged: Paged<Media>,
    gridState: LazyGridState,
    getFocusKey: () -> String?,
    setFocusKey: (String?) -> Unit,
    onOpen: (Media) -> Unit,
    modifier: Modifier = Modifier,
    sidePad: Dp = 36.d,
    topPad: Dp = 10.d,
    minW: Dp = 108.d
) {
    val scope = rememberCoroutineScope()
    LaunchedEffect(paged) {
        if (!paged.started) paged.loadNext()
        snapshotFlow {
            val li = gridState.layoutInfo
            (li.visibleItemsInfo.lastOrNull()?.index ?: 0) to li.totalItemsCount
        }.collect { (last, total) -> if (total > 0 && last >= total - 12) paged.loadNext() }
    }
    BoxWithConstraints(modifier.fillMaxSize()) {
        val (cols, w) = gridGeometry(maxWidth.value, sidePad.value, 12.d.value, minW.value)
        val pw = w.dp
        LazyVerticalGrid(
            columns = GridCells.Fixed(cols),
            state = gridState,
            contentPadding = PaddingValues(start = sidePad, end = sidePad, top = topPad, bottom = 40.d),
            horizontalArrangement = Arrangement.spacedBy(12.d),
            verticalArrangement = Arrangement.spacedBy(14.d)
        ) {
            items(paged.items, key = { it.key }) { m ->
                val fr = remember { FocusRequester() }
                if (getFocusKey() == m.key) {
                    LaunchedEffect(Unit) {
                        fr.tryRequest()
                        setFocusKey(null)
                    }
                }
                Poster(m, pw, { setFocusKey(m.key); onOpen(m) }, focusRequester = fr)
            }
            if (paged.loading) item(span = { GridItemSpan(maxLineSpan) }) {
                Box(Modifier.fillMaxWidth().height(50.d), contentAlignment = Alignment.Center) { Spinner() }
            }
            if (paged.error != null) item(span = { GridItemSpan(maxLineSpan) }) {
                ErrorLine(paged.error ?: "", { scope.launch { paged.retry() } })
            }
        }
        if (paged.items.isEmpty() && paged.started && !paged.loading && paged.error == null) {
            Text2("Няма резултати.", 14, C.dim, FontWeight.Medium, 1, Modifier.align(Alignment.Center))
        }
    }
}

/** Филми, Сериали и Аниме: вляво панел с подредба и жанрове, вдясно мрежа с плакати. */
@Composable
fun BrowseScreen(st: BrowseState, onOpen: (Media) -> Unit) {
    val sorts = listOf("Популярни", "Най-нови", "Най-високо оценени")
    // Пролуката е малка, защото мрежата има свой отстъп (12): блясъкът на фокусирания плакат не се отрязва.
    Row(Modifier.fillMaxSize().padding(start = 36.d, end = 36.d, bottom = 14.d), horizontalArrangement = Arrangement.spacedBy(6.d)) {
        SidePanel {
            if (st.forceAnime) {
                item(key = "tm") { SideTab("Филми", "", st.type == MediaType.MOVIE) { st.changeType(MediaType.MOVIE) } }
                item(key = "ts") { SideTab("Сериали", "", st.type == MediaType.TV) { st.changeType(MediaType.TV) } }
                panelDivider("tdiv")
            }
            items(sorts.size, key = { "s$it" }) { i -> SideTab(sorts[i], "", st.sort == i) { st.apply(st.genre, i) } }
            if (!st.forceAnime) {
                panelDivider("pdiv")
                item(key = "pall") { SideTab("Всички платформи", "", st.provider == null) { st.pickProvider(null) } }
                items(Platforms.all, key = { "p" + it.id }) { pl -> SideTab(pl.name, "", st.provider == pl.id) { st.pickProvider(pl.id) } }
                if (st.provider != null) item(key = "jw") { Text2("Данни: JustWatch", 10, C.dim, FontWeight.Normal, 1, Modifier.padding(start = 14.d, top = 2.d)) }
            }
            panelDivider("gdiv")
            item(key = "all") { SideTab("Всички жанрове", "", st.genre == null) { st.apply(null, st.sort) } }
            val genres = if (st.forceAnime) Genres.forAnime(st.type) else Genres.forType(st.type)
            items(genres, key = { "g" + it.id }) { g -> SideTab(g.name, "", st.genre == g.id) { st.apply(g.id, st.sort) } }
        }
        PosterGrid(
            st.paged, st.gridState, { st.focusKey }, { st.focusKey = it }, onOpen,
            Modifier.weight(1f), sidePad = 12.d, topPad = 0.d, minW = 100.d
        )
    }
}

@Composable
fun SearchScreen(st: SearchState, onOpen: (Media) -> Unit) {
    val kb = LocalSoftwareKeyboardController.current
    val inputFocus = remember { FocusRequester() }
    // Страницата за телефона работи и докато е отворено търсенето: оттам може да се напише думата.
    DisposableEffect(Unit) {
        Graph.config.start()
        onDispose { Graph.config.stop() }
    }
    Column(Modifier.fillMaxSize()) {
        Focusable({ inputFocus.requestFocus(); kb?.show() }, Modifier.padding(horizontal = 36.d, vertical = 8.d).fillMaxWidth(), RoundedCornerShape(14.d), 1.01f) { f ->
            Row(
                Modifier.fillMaxWidth().background(if (f) C.cardHi else C.card).padding(horizontal = 16.d, vertical = 12.d),
                verticalAlignment = Alignment.CenterVertically
            ) {
                GlyphIcon(Glyph.SEARCH, C.dim, Modifier.size(20.d))
                Spacer(Modifier.padding(horizontal = 6.d))
                BasicTextField(
                    value = st.query,
                    onValueChange = { st.onQuery(it) },
                    singleLine = true,
                    textStyle = TextStyle(color = C.text, fontSize = 18.t),
                    cursorBrush = SolidColor(C.accent2),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { kb?.hide(); st.commit() }),
                    modifier = Modifier.fillMaxWidth().focusRequesterCompat(inputFocus),
                    decorationBox = { inner ->
                        if (st.query.isEmpty()) Text2("Търси филм или сериал (OK отваря клавиатурата)", 16, C.dim, FontWeight.Normal, 1)
                        inner()
                    }
                )
            }
        }
        val p = st.paged
        if (p == null) {
            Column(Modifier.fillMaxSize().padding(horizontal = 36.d, vertical = 14.d)) {
                if (st.recent.isNotEmpty()) {
                    Text2("Последни търсения", 13, C.dim, FontWeight.Bold, 1)
                    Spacer(Modifier.height(6.d))
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.d)) {
                        items(st.recent.toList(), key = { it }) { s -> Chip(s, false, { st.pick(s) }) }
                        item(key = "\u0001clear") { Chip("Изчисти", false, { st.clearRecent() }, compact = true) }
                    }
                    Spacer(Modifier.height(20.d))
                }
                PhoneSearchHint()
                Spacer(Modifier.height(20.d))
                Text2("Въведи поне 2 букви.", 13, C.dim, FontWeight.Medium, 1)
            }
        } else {
            PosterGrid(p, st.gridState, { st.focusKey }, { st.focusKey = it }, { st.commit(); onOpen(it) })
        }
    }
}

/** Как да се търси от телефона: адресът на страницата и ПИН-ът. */
@Composable
private fun PhoneSearchHint() {
    val tick by produceState(0) {
        while (true) {
            delay(1500)
            value += 1
        }
    }
    val addrs = remember(tick / 4) { Graph.config.addresses() }
    val port = Graph.config.port
    Text2("Или пиши от телефона", 13, C.dim, FontWeight.Bold, 1)
    Spacer(Modifier.height(4.d))
    if (addrs.isEmpty()) {
        Text2("Телевизорът не е свързан към мрежа.", 12, C.dim, FontWeight.Normal, 1)
    } else if (port != 0) {
        Text2("Отвори на телефона (в същата Wi-Fi мрежа): http://${addrs.first()}:$port", 15, C.accent2, FontWeight.Bold, 2)
        Text2("ПИН: ${Graph.config.pin}", 15, C.gold, FontWeight.Bold, 1)
    }
}

private fun Modifier.focusRequesterCompat(fr: FocusRequester): Modifier =
    this.then(Modifier.focusRequester(fr))
