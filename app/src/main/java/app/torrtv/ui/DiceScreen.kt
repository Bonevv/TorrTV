package app.torrtv.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.border
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import app.torrtv.data.Genre
import app.torrtv.data.Genres
import app.torrtv.data.Media
import app.torrtv.data.MediaType
import app.torrtv.state.DiceState
import kotlinx.coroutines.delay
import java.util.Locale

@Composable
fun DiceView(face: Int, rolling: Boolean, size: androidx.compose.ui.unit.Dp) {
    val tr = rememberInfiniteTransition(label = "dice")
    val rot by tr.animateFloat(0f, 360f, infiniteRepeatable(tween(650, easing = LinearEasing), RepeatMode.Restart), label = "rot")
    val bounce by tr.animateFloat(0.88f, 1.08f, infiniteRepeatable(tween(325), RepeatMode.Reverse), label = "bounce")
    var shownFace by remember { mutableStateOf(face) }
    LaunchedEffect(rolling, face) {
        if (rolling) {
            while (true) {
                shownFace = (1..6).random()
                delay(110)
            }
        } else shownFace = face
    }
    Canvas(
        Modifier.size(size).graphicsLayer {
            rotationZ = if (rolling) rot else -8f
            scaleX = if (rolling) bounce else 1f
            scaleY = if (rolling) bounce else 1f
        }
    ) {
        val w = this.size.width
        drawRoundRect(
            Brush.linearGradient(listOf(Color(0xFF8E7DFF), Color(0xFF3FB6E8)), Offset.Zero, Offset(w, w)),
            size = Size(w, w), cornerRadius = CornerRadius(w * 0.22f)
        )
        drawRoundRect(
            Color(0x33FFFFFF), topLeft = Offset(w * 0.04f, w * 0.04f), size = Size(w * 0.92f, w * 0.92f),
            cornerRadius = CornerRadius(w * 0.19f), style = androidx.compose.ui.graphics.drawscope.Stroke(w * 0.02f)
        )
        val a = 0.27f; val b = 0.5f; val c = 0.73f
        val pts: List<Pair<Float, Float>> = when (shownFace) {
            1 -> listOf(b to b)
            2 -> listOf(a to a, c to c)
            3 -> listOf(a to a, b to b, c to c)
            4 -> listOf(a to a, c to a, a to c, c to c)
            5 -> listOf(a to a, c to a, b to b, a to c, c to c)
            else -> listOf(a to a, c to a, a to b, c to b, a to c, c to c)
        }
        pts.forEach { (x, y) -> drawCircle(Color.White, radius = w * 0.075f, center = Offset(w * x, w * y)) }
    }
}

private val RATINGS = listOf(0, 6, 7, 8)

private fun ratingLabel(v: Int) = if (v == 0) "Всички" else "★ $v+"

/** Страничният панел: вид, минимален рейтинг и (по желание) списък с жанрове. Скролира сам, ако е по-дълъг. */
@Composable
private fun DicePanel(
    type: MediaType, onType: (MediaType) -> Unit, rating: Int?, onRating: (Int) -> Unit,
    genres: List<Genre>?, genre: Int?, onGenre: (Int?) -> Unit, footer: (@Composable () -> Unit)? = null
) {
    LazyColumn(
        Modifier.width(214.d).fillMaxHeight()
            .clip(RoundedCornerShape(16.d)).background(Color(0xB30A0D14)).border(1.d, C.line, RoundedCornerShape(16.d)),
        contentPadding = PaddingValues(8.d),
        verticalArrangement = Arrangement.spacedBy(2.d)
    ) {
        item(key = "tm") { SideTab("Филми", "", type == MediaType.MOVIE) { onType(MediaType.MOVIE) } }
        item(key = "ts") { SideTab("Сериали", "", type == MediaType.TV) { onType(MediaType.TV) } }
        if (rating != null) {
            item(key = "rdiv") { Box(Modifier.padding(horizontal = 6.d, vertical = 6.d).fillMaxWidth().height(1.d).background(C.line)) }
            item(key = "rt") {
                FilterButton("Рейтинг", ratingLabel(rating)) { onRating(RATINGS[(RATINGS.indexOf(rating) + 1) % RATINGS.size]) }
            }
        }
        if (genres != null) {
            item(key = "gdiv") { Box(Modifier.padding(horizontal = 6.d, vertical = 6.d).fillMaxWidth().height(1.d).background(C.line)) }
            item(key = "g0") { SideTab("Изненада ме", "", genre == null) { onGenre(null) } }
            items(genres, key = { "g" + it.id }) { g -> SideTab(g.name, "", genre == g.id) { onGenre(g.id) } }
        }
        if (footer != null) {
            item(key = "pdiv") { Box(Modifier.padding(horizontal = 6.d, vertical = 6.d).fillMaxWidth().height(1.d).background(C.line)) }
            item(key = "foot") { footer() }
        }
    }
}

@Composable
fun DiceScreen(dice: DiceState, onOpen: (Media) -> Unit) {
    if (dice.pairMode) {
        PairDiceScreen(dice, onOpen)
        return
    }
    LaunchedEffect(Unit) { if (!dice.started) dice.roll() }
    Row(Modifier.fillMaxSize().padding(start = 36.d, end = 36.d, top = 14.d, bottom = 12.d), horizontalArrangement = Arrangement.spacedBy(18.d)) {
        DicePanel(
            dice.type, { dice.changeType(it) }, dice.minRating, { dice.changeRating(it) },
            Genres.forType(dice.type), dice.genre, { dice.changeGenre(it) }
        )
        Column(Modifier.weight(1f).fillMaxHeight()) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(18.d)) {
                DiceView(dice.face, dice.rolling, 72.d)
                Column(Modifier.weight(1f)) {
                    Text2("Какво да гледаме днес?", 22, C.text, FontWeight.ExtraBold, 1)
                    Text2(
                        when {
                            dice.rolling -> "Зарът се върти…"
                            dice.error != null -> "${dice.error}"
                            dice.pickedGenre != null -> "Зарът избра: ${dice.pickedGenre}  ·  5 предложения"
                            else -> "Избери жанр или хвърли зара"
                        },
                        13, if (dice.error != null) C.red else C.accent2, FontWeight.Medium, 2
                    )
                    Spacer(Modifier.height(8.d))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.d), verticalAlignment = Alignment.CenterVertically) {
                        PrimaryButton("Хвърли отново", { dice.roll() })
                        PrimaryButton("За двама", { dice.pairMode = true }, primary = false)
                    }
                }
            }
            Spacer(Modifier.height(10.d))
            BoxWithConstraints(Modifier.fillMaxWidth().weight(1f)) {
                if (dice.rolling) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Spinner(36.d) }
                } else {
                    // Широчината на плаката се смята от свободното място: не излиза извън екрана и не е по-висока от него.
                    val cw = maxOf(minOf((maxWidth - 12.d * 4) / 5, (maxHeight - 56.d) / 1.5f), 70.d)
                    key(dice.rollId) {
                        Row(Modifier.padding(horizontal = 6.d, vertical = 8.d), horizontalArrangement = Arrangement.spacedBy(12.d)) {
                            dice.results.forEachIndexed { i, m -> DiceCard(m, i, cw, onOpen) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DiceCard(m: Media, index: Int, width: Dp, onOpen: (Media) -> Unit) {
    val a = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        delay(index * 130L)
        a.animateTo(1f, tween(380))
    }
    Column(
        Modifier.width(width).graphicsLayer {
            alpha = a.value
            translationY = (1f - a.value) * 60f
        }
    ) {
        Poster(m, width, { onOpen(m) })
        Spacer(Modifier.height(6.d))
        Text2(m.title, 12, C.text, FontWeight.Bold, 2)
        Text2(
            listOf(m.year, if (m.rating > 0) "★ " + String.format(Locale.US, "%.1f", m.rating) else "").filter { it.isNotBlank() }.joinToString("  ·  "),
            11, C.gold, FontWeight.Medium, 1
        )
    }
}

/** „Зарче за двама“: двама избират по жанр, зарът подава по две заглавия от всеки, а монета решава чий жанр печели. */
@Composable
private fun PairDiceScreen(dice: DiceState, onOpen: (Media) -> Unit) {
    val pd = dice.pair
    val genres = Genres.forType(pd.type)
    fun gName(id: Int?) = genres.firstOrNull { it.id == id }?.name ?: ""
    Row(Modifier.fillMaxSize().padding(start = 36.d, end = 36.d, top = 14.d, bottom = 12.d), horizontalArrangement = Arrangement.spacedBy(18.d)) {
        DicePanel(pd.type, { pd.changeType(it) }, null, {}, null, null, {}) {
            FilterButton("Режим", "За двама") { dice.pairMode = false }
        }
        Column(Modifier.weight(1f).fillMaxHeight()) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(18.d)) {
                DiceView(pd.face, pd.rolling, 60.d)
                Column(Modifier.weight(1f)) {
                    Text2("Зарче за двама", 22, C.text, FontWeight.ExtraBold, 1)
                    Text2(
                        when {
                            pd.rolling -> "Зарът се върти…"
                            pd.error != null -> "${pd.error}"
                            pd.coinSpinning -> "Монетата се върти…"
                            pd.winner >= 0 -> "Монетата реши: ${gName(if (pd.winner == 0) pd.genreA else pd.genreB)}. Изберете едно от двете заглавия."
                            pd.ready -> "Натиснете „Хвърли“."
                            else -> "Първият и вторият избират по един жанр. Зарът подава по две заглавия от всеки, а монета решава чий жанр печели."
                        },
                        13, if (pd.error != null) C.red else C.accent2, FontWeight.Medium, 2
                    )
                    Spacer(Modifier.height(6.d))
                    PrimaryButton(if (pd.ready) "Хвърли" else "Изберете жанрове", { if (pd.ready) pd.roll() }, primary = pd.ready)
                }
            }
            Spacer(Modifier.height(6.d))
            PairGenreRow("Първият избира жанр", genres, pd.genreA) { pd.pickA(it) }
            Spacer(Modifier.height(4.d))
            PairGenreRow("Вторият избира жанр", genres, pd.genreB) { pd.pickB(it) }
            Spacer(Modifier.height(8.d))
            BoxWithConstraints(Modifier.fillMaxWidth().weight(1f)) {
                if (pd.rolling) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Spinner(36.d) }
                } else if (pd.resultsA.isNotEmpty() || pd.resultsB.isNotEmpty()) {
                    // Две страни и монета помежду им: всяка страна дели свободната широчина на две заглавия.
                    val cw = maxOf(minOf((maxWidth - 46.d - 18.d * 2 - 10.d * 2) / 4, (maxHeight - 76.d) / 1.5f), 60.d)
                    key(pd.rollId) {
                        Row(
                            Modifier.padding(horizontal = 6.d),
                            horizontalArrangement = Arrangement.spacedBy(18.d),
                            verticalAlignment = Alignment.Top
                        ) {
                            PairSide(gName(pd.genreA), pd.resultsA, cw, pd.winner == 0, pd.winner >= 0, onOpen)
                            Column(Modifier.padding(top = 30.d)) { CoinView(pd.winner, pd.coinSpinning) }
                            PairSide(gName(pd.genreB), pd.resultsB, cw, pd.winner == 1, pd.winner >= 0, onOpen)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PairGenreRow(label: String, genres: List<Genre>, selected: Int?, onPick: (Int) -> Unit) {
    Text2(label, 12, C.dim, FontWeight.Bold, 1, Modifier.padding(vertical = 3.d))
    LazyRow(contentPadding = PaddingValues(horizontal = 4.d, vertical = 3.d), horizontalArrangement = Arrangement.spacedBy(8.d)) {
        items(genres, key = { it.id }) { g -> Chip(g.name, selected == g.id, { onPick(g.id) }, compact = true) }
    }
}

@Composable
private fun PairSide(title: String, items: List<Media>, cw: Dp, win: Boolean, decided: Boolean, onOpen: (Media) -> Unit) {
    val dim = decided && !win
    Column(Modifier.graphicsLayer { alpha = if (dim) 0.45f else 1f }) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.d)) {
            Text2(title, 15, if (win) C.gold else C.text, FontWeight.ExtraBold, 1)
            if (win) Pill("печели", Color(0x33FFC857), C.gold)
        }
        Spacer(Modifier.height(6.d))
        Row(horizontalArrangement = Arrangement.spacedBy(10.d)) {
            items.forEach { m ->
                Column(Modifier.width(cw)) {
                    Poster(m, cw, { onOpen(m) })
                    Spacer(Modifier.height(4.d))
                    Text2(m.title, 11, C.text, FontWeight.Bold, 2)
                    Text2(
                        listOf(m.year, if (m.rating > 0) "★ " + String.format(Locale.US, "%.1f", m.rating) else "").filter { it.isNotBlank() }.joinToString("  ·  "),
                        10, C.gold, FontWeight.Medium, 1
                    )
                }
            }
        }
    }
}

/** Монетата: докато се върти, показва "?", после стрелка към страната, която печели. */
@Composable
private fun CoinView(winner: Int, spinning: Boolean) {
    val rot = if (spinning) {
        val tr = rememberInfiniteTransition(label = "coin")
        val r by tr.animateFloat(0f, 360f, infiniteRepeatable(tween(520, easing = LinearEasing), RepeatMode.Restart), label = "coinrot")
        r
    } else 0f
    Box(
        Modifier.size(46.d).graphicsLayer { rotationX = rot }.clip(CircleShape)
            .background(Brush.linearGradient(listOf(Color(0xFFFFE08A), Color(0xFFD9962B)))),
        contentAlignment = Alignment.Center
    ) {
        Text2(
            when {
                spinning -> "?"
                winner == 0 -> "◀"
                winner == 1 -> "▶"
                else -> ""
            },
            18, Color(0xFF1A1305), FontWeight.ExtraBold, 1
        )
    }
}
