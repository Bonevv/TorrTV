package app.torrtv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.torrtv.data.Media
import app.torrtv.state.Library

/** Помни къде е бил фокусът в "Любими" при връщане от детайлите. */
object FavUi {
    val grid = LazyGridState()
    var focusKey: String? = null
}

/** Ред "Продължи гледане" в началото: картичка с лента колко е изгледано. */
@Composable
fun ContinueRow(onResume: (Library.Progress) -> Unit, onFocusMedia: (Media) -> Unit,
    state:LazyListState=rememberLazyListState(),getFocusKey:()->String?={null},setFocusKey:(String?)->Unit={}) {
    val list = Library.progress.toList()
    if (list.isEmpty()) return
    Column(Modifier.fillMaxWidth()) {
        SectionTitle("Продължи гледане")
        LazyRow(
            state=state,
            contentPadding = PaddingValues(horizontal = 36.d, vertical = 8.d),
            horizontalArrangement = Arrangement.spacedBy(12.d)
        ) {
            items(list, key = { it.media.key }) { p ->
                val fr=remember{FocusRequester()};val key="continue|${p.media.key}"
                if(getFocusKey()==key)LaunchedEffect(Unit){fr.tryRequest();setFocusKey(null)}
                ContinueCard(p, {setFocusKey(key);onResume(p)}, { onFocusMedia(p.media) },fr)
            }
            item(key = "clear") {
                Box(Modifier.height(112.d), contentAlignment = Alignment.Center) {
                    Chip("Изчисти", false, { Library.clearProgress() })
                }
            }
        }
    }
}

@Composable
fun ContinueCard(p: Library.Progress, onClick: () -> Unit, onFocus: () -> Unit,focusRequester:FocusRequester?=null) {
    val m = p.media
    Column(Modifier.width(200.d)) {
        Focusable(onClick, Modifier.fillMaxWidth().height(112.d), RoundedCornerShape(12.d), 1.025f, onFocus = onFocus,focusRequester=focusRequester) { f ->
            Img(
                if (m.backdropPath != null) Look.img(m.backdropPath, "w300") else Look.img(m.posterPath, "w342"),
                Modifier.fillMaxSize(),fade=false
            )
            Box(
                Modifier.fillMaxSize().background(
                    Brush.verticalGradient(listOf(Color.Transparent, Color(0xE6000000)), startY = 120f)
                )
            )
            // голям знак за пускане
            Box(Modifier.align(Alignment.Center)) {
                Box(
                    Modifier.background(if (f) C.accent else Color(0x99000000), RoundedCornerShape(50))
                        .padding(horizontal = 14.d, vertical = 6.d)
                ) { Text2("▶", 16, if (f) C.onAccent else C.text, FontWeight.Bold, 1) }
            }
            Text2(
                m.title, 12, C.text, FontWeight.Bold, 1,
                Modifier.align(Alignment.BottomStart).padding(start = 9.d, end = 9.d, bottom = 10.d)
            )
            // лентата за изгледаното (при „Следва“ още няма какво да се покаже)
            if (!p.nextUp) {
                Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(4.d).background(Color(0x66FFFFFF))) {
                    Box(Modifier.fillMaxHeight().fillMaxWidth(p.fraction.coerceIn(0.03f, 1f)).background(C.accent))
                }
            }
        }
        Spacer(Modifier.height(5.d))
        Text2(p.label(), 11, C.dim, FontWeight.Medium, 1, Modifier.padding(start = 2.d))
        if(!p.nextUp && p.durMs>p.posMs)Text2("Остават ${(p.durMs-p.posMs+59_999)/60_000} мин",10,C.accent2,FontWeight.Normal,1,Modifier.padding(start=2.d))
    }
}

/** Общ ред с плакати (за "Любими" на началния екран). */
@Composable
fun MediaRow(title: String, list: List<Media>, onOpen: (Media) -> Unit, onFocusMedia: (Media) -> Unit,
    state:LazyListState=rememberLazyListState(),getFocusKey:()->String?={null},setFocusKey:(String?)->Unit={}) {
    if (list.isEmpty()) return
    Column(Modifier.fillMaxWidth()) {
        SectionTitle(title)
        LazyRow(
            state=state,
            contentPadding = PaddingValues(horizontal = 36.d, vertical = 8.d),
            horizontalArrangement = Arrangement.spacedBy(12.d)
        ) {
            items(list, key = { it.key }) { m ->
                val fr=remember{FocusRequester()};val key="homefav|${m.key}"
                if(getFocusKey()==key)LaunchedEffect(Unit){fr.tryRequest();setFocusKey(null)}
                Poster(m, 112.d, {setFocusKey(key);onOpen(m)}, onFocus = { onFocusMedia(m) },focusRequester=fr)
            }
        }
    }
}

/** Таб "Любими": всичко, отбелязано с ♥, в мрежа. */
@Composable
fun FavoritesScreen(onOpen: (Media) -> Unit) {
    val favs = Library.favorites.toList()
    if (favs.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(horizontal = 80.d)) {
                Text2("♡", 44, C.dim, FontWeight.Light, 1)
                Spacer(Modifier.height(8.d))
                Text2("Още нямаш любими.", 17, C.text, FontWeight.Bold, 1)
                Spacer(Modifier.height(4.d))
                Text2("Отвори филм или сериал и избери „Добави в любими“ - ще го намериш тук.", 13, C.dim, FontWeight.Normal, 2)
            }
        }
        return
    }
    Column(Modifier.fillMaxSize()) {
        SectionTitle("Любими", sub = if (favs.size == 1) "1 заглавие" else "${favs.size} заглавия")
        BoxWithConstraints(Modifier.fillMaxWidth().weight(1f)) {
            // Плакатите запълват реда точно - същото като във Филми, Сериали и Търсене.
            val (cols, w) = gridGeometry(maxWidth.value, 36.d.value, 12.d.value, 108.d.value)
            val pw = w.dp
            LazyVerticalGrid(
                modifier=Modifier.stayInContent(),
                columns = GridCells.Fixed(cols),
                state = FavUi.grid,
                contentPadding = PaddingValues(start = 36.d, end = 36.d, top = 6.d, bottom = 40.d),
                horizontalArrangement = Arrangement.spacedBy(12.d),
                verticalArrangement = Arrangement.spacedBy(14.d)
            ) {
                items(favs, key = { it.key },contentType={"poster"}) { m ->
                    val fr = remember { FocusRequester() }
                    if (FavUi.focusKey == m.key) {
                        LaunchedEffect(Unit) {
                            fr.tryRequest()
                            FavUi.focusKey = null
                        }
                    }
                    Poster(m, pw, { FavUi.focusKey = m.key; onOpen(m) }, focusRequester = fr)
                }
            }
        }
    }
}
