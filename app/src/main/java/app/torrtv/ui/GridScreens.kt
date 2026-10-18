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
import androidx.compose.runtime.LaunchedEffect
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
import app.torrtv.data.Genres
import app.torrtv.data.Media
import app.torrtv.state.BrowseState
import app.torrtv.state.Paged
import app.torrtv.state.SearchState
import kotlinx.coroutines.launch

/** Мрежа с плакати и безкрайно зареждане (до тавана на Paged). */
@Composable
fun PosterGrid(
    paged: Paged<Media>,
    gridState: LazyGridState,
    getFocusKey: () -> String?,
    setFocusKey: (String?) -> Unit,
    onOpen: (Media) -> Unit,
    modifier: Modifier = Modifier
) {
    val scope = rememberCoroutineScope()
    LaunchedEffect(paged) {
        if (!paged.started) paged.loadNext()
        snapshotFlow {
            val li = gridState.layoutInfo
            (li.visibleItemsInfo.lastOrNull()?.index ?: 0) to li.totalItemsCount
        }.collect { (last, total) -> if (total > 0 && last >= total - 12) paged.loadNext() }
    }
    Box(modifier.fillMaxSize()) {
        LazyVerticalGrid(
            columns = GridCells.Adaptive(118.d),
            state = gridState,
            contentPadding = PaddingValues(start = 36.d, end = 36.d, top = 10.d, bottom = 40.d),
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
                Poster(m, 112.d, { setFocusKey(m.key); onOpen(m) }, focusRequester = fr)
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

@Composable
fun BrowseScreen(st: BrowseState, onOpen: (Media) -> Unit) {
    val sorts = listOf("Популярни", "Най-нови", "Най-високо оценени")
    Column(Modifier.fillMaxSize()) {
        LazyRow(
            contentPadding = PaddingValues(horizontal = 36.d, vertical = 4.d),
            horizontalArrangement = Arrangement.spacedBy(8.d)
        ) {
            items(sorts.size, key = { "s$it" }) { i -> Chip("↕ " + sorts[i], st.sort == i, { st.apply(st.genre, i) }) }
            item(key = "anime") { Chip("🎌 Аниме", st.anime, { st.apply(st.genre, st.sort, !st.anime) }) }
        }
        LazyRow(
            contentPadding = PaddingValues(horizontal = 36.d, vertical = 4.d),
            horizontalArrangement = Arrangement.spacedBy(8.d)
        ) {
            item(key = "all") { Chip("Всички жанрове", st.genre == null, { st.apply(null, st.sort) }) }
            items(Genres.forType(st.type), key = { it.id }) { g -> Chip(g.name, st.genre == g.id, { st.apply(g.id, st.sort) }) }
        }
        PosterGrid(st.paged, st.gridState, { st.focusKey }, { st.focusKey = it }, onOpen)
    }
}

@Composable
fun SearchScreen(st: SearchState, onOpen: (Media) -> Unit) {
    val kb = LocalSoftwareKeyboardController.current
    val inputFocus = remember { FocusRequester() }
    Column(Modifier.fillMaxSize()) {
        Focusable({ inputFocus.requestFocus(); kb?.show() }, Modifier.padding(horizontal = 36.d, vertical = 8.d).fillMaxWidth(), RoundedCornerShape(14.d), 1.01f) { f ->
            Row(
                Modifier.fillMaxWidth().background(if (f) C.cardHi else C.card).padding(horizontal = 16.d, vertical = 12.d),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text2("🔍", 16, C.dim, FontWeight.Normal, 1)
                Spacer(Modifier.padding(horizontal = 6.d))
                BasicTextField(
                    value = st.query,
                    onValueChange = { st.onQuery(it) },
                    singleLine = true,
                    textStyle = TextStyle(color = C.text, fontSize = 18.t),
                    cursorBrush = SolidColor(C.accent2),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { kb?.hide() }),
                    modifier = Modifier.fillMaxWidth().focusRequesterCompat(inputFocus),
                    decorationBox = { inner ->
                        if (st.query.isEmpty()) Text2("Търси филм или сериал…  (натисни OK за клавиатура)", 16, C.dim, FontWeight.Normal, 1)
                        inner()
                    }
                )
            }
        }
        val p = st.paged
        if (p == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text2("Въведи поне 2 букви.", 14, C.dim, FontWeight.Medium, 1)
            }
        } else {
            PosterGrid(p, st.gridState, { st.focusKey }, { st.focusKey = it }, onOpen)
        }
    }
}

private fun Modifier.focusRequesterCompat(fr: FocusRequester): Modifier =
    this.then(Modifier.focusRequester(fr))
