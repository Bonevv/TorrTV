package app.torrtv.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import app.torrtv.data.Media
import app.torrtv.state.HomeState
import app.torrtv.state.WeeklyRow
import kotlinx.coroutines.launch

private const val POSTER_W = 100

/** Ред "Избор на седмицата": плакати с едри номера от 1 до 10. */
@Composable
fun PicksRow(home: HomeState, row: WeeklyRow, onOpen: (Media) -> Unit) {
    val scope = rememberCoroutineScope()
    LaunchedEffect(row) { row.load() }
    // Докато няма друго, банерът показва първия избор.
    LaunchedEffect(row.items.size) {
        if (home.heroTarget == null && row.items.isNotEmpty()) home.heroTarget = row.items[0]
    }
    Column(Modifier.fillMaxWidth()) {
        SectionTitle(row.title, sub = row.range)
        when {
            row.items.isNotEmpty() -> LazyRow(
                state = row.listState,
                contentPadding = PaddingValues(horizontal = 36.d, vertical = 8.d),
                horizontalArrangement = Arrangement.spacedBy(10.d)
            ) {
                itemsIndexed(row.items, key = { _, m -> m.key }) { i, m ->
                    val fr = remember { FocusRequester() }
                    val fk = "${row.type.path}-pick|${m.key}"
                    if (home.focusKey == fk) {
                        LaunchedEffect(Unit) {
                            fr.tryRequest()
                            home.focusKey = null
                        }
                    }
                    RankedPoster(
                        i + 1, m,
                        onClick = {
                            home.focusKey = fk
                            onOpen(m)
                        },
                        onFocus = { home.heroTarget = m },
                        focusRequester = fr
                    )
                }
            }
            row.error != null -> ErrorLine(row.error ?: "", { scope.launch { row.retry() } })
            else -> Box(Modifier.fillMaxWidth().height(180.d), contentAlignment = Alignment.CenterStart) {
                Box(Modifier.padding(start = 36.d)) { Spinner() }
            }
        }
    }
}

/** Плакат с голям контурен номер отляво; плакатът застава върху края на цифрата. */
@Composable
private fun RankedPoster(
    rank: Int,
    media: Media,
    onClick: () -> Unit,
    onFocus: () -> Unit,
    focusRequester: FocusRequester
) {
    // колко от цифрата стърчи вляво от плаката
    val lead = if (rank >= 10) 74 else 36
    Box(Modifier.width((POSTER_W + lead).d).height((POSTER_W * 3 / 2 + 12).d)) {
        RankNumeral(rank, Modifier.align(Alignment.BottomStart))
        Poster(
            media, POSTER_W.d, onClick, Modifier.align(Alignment.BottomEnd),
            onFocus = onFocus, focusRequester = focusRequester
        )
    }
}

@Composable
private fun RankNumeral(rank: Int, modifier: Modifier) {
    // Дебелината на контура се мащабира с екрана, за да изглежда еднакво на всеки телевизор.
    val stroke = 2.2f * LocalDensity.current.density * LocalDims.current.k
    Text(
        rank.toString(),
        modifier = modifier.offset(y = 10.d),
        maxLines = 1,
        style = TextStyle(
            color = if (Look.lite) C.cardHi else C.accent.copy(alpha = 0.85f),
            fontSize = 96.t,
            fontWeight = FontWeight.Black,
            letterSpacing = (-6).t,
            drawStyle = if (Look.lite) null else Stroke(width = stroke, join = StrokeJoin.Round)
        )
    )
}
