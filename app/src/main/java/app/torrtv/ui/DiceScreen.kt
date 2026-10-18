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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
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

/** Страничният панел на зара за един: вид, минимален рейтинг и жанрове. */
@Composable
private fun DicePanel(
    type: MediaType, onType: (MediaType) -> Unit, rating: Int, onRating: (Int) -> Unit,
    genres: List<Genre>, genre: Int?, onGenre: (Int?) -> Unit
) {
    SidePanel {
        item(key = "tm") { SideTab("Филми", "", type == MediaType.MOVIE) { onType(MediaType.MOVIE) } }
        item(key = "ts") { SideTab("Сериали", "", type == MediaType.TV) { onType(MediaType.TV) } }
        panelDivider("rdiv")
        item(key = "rt") {
            FilterButton("Рейтинг", ratingLabel(rating)) { onRating(RATINGS[(RATINGS.indexOf(rating) + 1) % RATINGS.size]) }
        }
        panelDivider("gdiv")
        item(key = "g0") { SideTab("Изненада ме", "", genre == null) { onGenre(null) } }
        items(genres, key = { "g" + it.id }) { g -> SideTab(g.name, "", genre == g.id) { onGenre(g.id) } }
    }
}

/** Плавно появяване на карта: избледняване и леко изплуване отдолу, всяка следваща малко по-късно. */
@Composable
private fun appear(index: Int): Modifier {
    val a = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        delay(index * 130L)
        a.animateTo(1f, tween(380))
    }
    return Modifier.graphicsLayer {
        alpha = a.value
        translationY = (1f - a.value) * 60f
    }
}

private fun metaLine(m: Media): String =
    listOf(m.year, if (m.rating > 0) "★ " + String.format(Locale.US, "%.1f", m.rating) else "")
        .filter { it.isNotBlank() }.joinToString("  ·  ")

@Composable
fun DiceScreen(dice: DiceState, onOpen: (Media) -> Unit) {
    if (dice.pairMode) {
        PairDiceScreen(dice, onOpen)
        return
    }
    LaunchedEffect(Unit) { if (!dice.started) dice.roll() }
    Row(Modifier.fillMaxSize().padding(start = 36.d, end = 36.d, bottom = 14.d), horizontalArrangement = Arrangement.spacedBy(18.d)) {
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
                    // Отстъпи: 6 от двете страни на реда, 4 пролуки по 12; отдолу заглавие, година и малко въздух.
                    val cw = diceCardWidth(maxWidth.value, maxHeight.value, 6.d.value, 12.d.value, 72.d.value, 70.d.value).dp
                    key(dice.rollId) {
                        Row(Modifier.padding(horizontal = 6.d, vertical = 8.d), horizontalArrangement = Arrangement.spacedBy(12.d)) {
                            dice.results.forEachIndexed { i, m -> DiceCard(m, i, cw, onOpen, 12, 11) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DiceCard(m: Media, index: Int, width: Dp, onOpen: (Media) -> Unit, titleSize: Int, metaSize: Int) {
    Column(Modifier.width(width).then(appear(index))) {
        Poster(m, width, { onOpen(m) })
        Spacer(Modifier.height(5.d))
        Text2(m.title, titleSize, C.text, FontWeight.Bold, 2)
        Text2(metaLine(m), metaSize, C.gold, FontWeight.Medium, 1)
    }
}

/**
 * „Зарче за двама“: двама избират по жанр (от панела вляво), зарът подава по две заглавия от всеки,
 * а монета решава чий жанр печели.
 */
@Composable
private fun PairDiceScreen(dice: DiceState, onOpen: (Media) -> Unit) {
    val pd = dice.pair
    val genres = Genres.forType(pd.type)
    fun gName(id: Int?) = genres.firstOrNull { it.id == id }?.name ?: ""
    val nameA = gName(pd.genreA)
    val nameB = gName(pd.genreB)
    // Кой избира жанр в момента: 0 - никой, 1 - първият, 2 - вторият.
    var picking by remember { mutableStateOf(0) }
    val rollFocus = remember { FocusRequester() }
    // Влизането от „За двама“ изтрива бутона, на който беше фокусът: започваме от „Първият“.
    val firstFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { firstFocus.tryRequest() }
    // Щом и двамата са избрали, фокусът отива на „Хвърли“.
    var toRoll by remember { mutableStateOf(false) }
    LaunchedEffect(toRoll) {
        if (toRoll) {
            delay(250)
            rollFocus.tryRequest()
            toRoll = false
        }
    }
    val hasResults = pd.resultsA.isNotEmpty() || pd.resultsB.isNotEmpty()

    Row(Modifier.fillMaxSize().padding(start = 36.d, end = 36.d, bottom = 14.d), horizontalArrangement = Arrangement.spacedBy(18.d)) {
        SidePanel {
            item(key = "tm") { SideTab("Филми", "", pd.type == MediaType.MOVIE) { pd.changeType(MediaType.MOVIE) } }
            item(key = "ts") { SideTab("Сериали", "", pd.type == MediaType.TV) { pd.changeType(MediaType.TV) } }
            panelDivider("d1")
            item(key = "pa") { PlayerButton("Първият", nameA, firstFocus) { picking = 1 } }
            item(key = "pb") { PlayerButton("Вторият", nameB, null) { picking = 2 } }
            panelDivider("d2")
            item(key = "mode") { FilterButton("Режим", "За двама") { dice.pairMode = false } }
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
                            pd.winner >= 0 -> "Монетата реши: ${if (pd.winner == 0) nameA else nameB}. Изберете едно от двете заглавия."
                            pd.ready -> "Натиснете „Хвърли“."
                            else -> "Всеки избира жанр. Зарът подава по две заглавия от всеки, а монета решава чий жанр печели."
                        },
                        13, if (pd.error != null) C.red else C.accent2, FontWeight.Medium, 2
                    )
                    Spacer(Modifier.height(6.d))
                    PrimaryButton(
                        when {
                            pd.ready -> if (hasResults) "Хвърли отново" else "Хвърли"
                            pd.genreA == null -> "Първият избира жанр"
                            else -> "Вторият избира жанр"
                        },
                        { if (pd.ready) pd.roll() else picking = if (pd.genreA == null) 1 else 2 },
                        primary = pd.ready, focusRequester = rollFocus
                    )
                }
            }
            Spacer(Modifier.height(12.d))
            BoxWithConstraints(Modifier.fillMaxWidth().weight(1f)) {
                // Две страни и монета помежду им. Отстъпи: 6 от двете страни, 18 между трите части,
                // 10 между двете заглавия в страна, монета 46; отгоре надпис на страната, отдолу заглавие и година.
                val coin = 46.d.value
                val cw = pairCardWidth(
                    maxWidth.value, maxHeight.value, 6.d.value, 18.d.value, 10.d.value, coin, 48.d.value, 46.d.value, 56.d.value
                ).dp
                // Монетата е по средата на плакатите (не на целия стълб), за да не скача, когато дойдат заглавията.
                val coinTop = maxOf(44.d.value + (cw.value * 1.5f - coin) / 2f, 0f).dp
                key(pd.rollId) {
                    Row(Modifier.padding(horizontal = 6.d), horizontalArrangement = Arrangement.spacedBy(18.d), verticalAlignment = Alignment.Top) {
                        PairSide("Първият", nameA, pd.resultsA, cw, pd.winner == 0, pd.winner >= 0, onOpen)
                        Column(Modifier.padding(top = coinTop)) { CoinView(pd.winner, pd.coinSpinning) }
                        PairSide("Вторият", nameB, pd.resultsB, cw, pd.winner == 1, pd.winner >= 0, onOpen)
                    }
                }
            }
        }
    }

    if (picking != 0) {
        val first = picking == 1
        GenrePicker(
            if (first) "Първият избира жанр" else "Вторият избира жанр", genres, if (first) pd.genreA else pd.genreB,
            onPick = { id ->
                val otherSet = if (first) pd.genreB != null else pd.genreA != null
                if (first) pd.pickA(id) else pd.pickB(id)
                picking = 0
                if (otherSet) toRoll = true
            },
            onDismiss = { picking = 0 }
        )
    }
}

/** Бутон в панела за един от двамата: отгоре кой е, отдолу избраният жанр (или покана да избере). */
@Composable
private fun PlayerButton(who: String, genre: String, focusRequester: FocusRequester?, onClick: () -> Unit) {
    val set = genre.isNotBlank()
    Focusable(onClick, Modifier.fillMaxWidth(), RoundedCornerShape(10.d), 1f, ring = false, focusRequester = focusRequester) { f ->
        Row(
            Modifier.fillMaxWidth()
                .background(if (f) C.text else C.card)
                .then(if (f) Modifier else Modifier.border(1.d, if (set) C.accent else C.line, RoundedCornerShape(10.d)))
                .padding(horizontal = 10.d, vertical = 7.d),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text2(who, 11, if (f) C.bg else C.dim, FontWeight.Medium, 1)
                Text2(
                    if (set) genre else "Избери жанр", 14,
                    if (f) C.bg else if (set) C.text else C.accent2, FontWeight.Bold, 1
                )
            }
            Text2("›", 16, if (f) C.bg else C.accent, FontWeight.ExtraBold, 1)
        }
    }
}

/** Прозорец за избор на жанр: плочки в три колони. Фокусът започва от избрания жанр (или от първия). */
@Composable
private fun GenrePicker(title: String, genres: List<Genre>, selected: Int?, onPick: (Int) -> Unit, onDismiss: () -> Unit) {
    val start = remember { FocusRequester() }
    LaunchedEffect(Unit) { start.tryRequest() }
    val startIdx = genres.indexOfFirst { it.id == selected }.coerceAtLeast(0)
    val maxH = (LocalConfiguration.current.screenHeightDp * 0.6f).dp
    val cols = 3
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier.fillMaxWidth(0.66f).clip(RoundedCornerShape(20.d)).background(C.bg2)
                .border(1.d, C.line, RoundedCornerShape(20.d)).padding(20.d)
        ) {
            Text2(title, 18, C.text, FontWeight.ExtraBold, 1)
            Spacer(Modifier.height(8.d))
            // Ако екранът е нисък, плочките скролират; малкият отстъп пази увеличената плочка да не се отрязва.
            Column(Modifier.heightIn(max = maxH).verticalScroll(rememberScrollState())) {
                Column(Modifier.padding(6.d), verticalArrangement = Arrangement.spacedBy(8.d)) {
                    genres.chunked(cols).forEachIndexed { r, row ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.d)) {
                            row.forEachIndexed { c, g ->
                                GenreTile(
                                    g.name, selected == g.id, { onPick(g.id) }, Modifier.weight(1f),
                                    if (r * cols + c == startIdx) start else null
                                )
                            }
                            repeat(cols - row.size) { Spacer(Modifier.weight(1f)) }
                        }
                    }
                }
            }
            Spacer(Modifier.height(8.d))
            PrimaryButton("Затвори", onDismiss, primary = false)
        }
    }
}

@Composable
private fun GenreTile(text: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier, focusRequester: FocusRequester?) {
    Focusable(onClick, modifier, RoundedCornerShape(10.d), 1.04f, ring = false, focusRequester = focusRequester) { f ->
        Box(
            Modifier.fillMaxWidth()
                .background(if (f) C.text else if (selected) C.accent else C.card)
                .then(if (!f && !selected) Modifier.border(1.d, C.line, RoundedCornerShape(10.d)) else Modifier)
                .padding(horizontal = 10.d, vertical = 9.d),
            contentAlignment = Alignment.Center
        ) {
            Text2(text, 13, if (f) C.bg else if (selected) C.onAccent else C.text, FontWeight.SemiBold, 1)
        }
    }
}

/** Една от двете страни: кой е, неговият жанр и двете заглавия (докато ги няма - празни места). */
@Composable
private fun PairSide(
    who: String, genre: String, items: List<Media>, cw: Dp, win: Boolean, decided: Boolean, onOpen: (Media) -> Unit
) {
    val dim = decided && !win
    // Фиксираната широчина пази дългото име на жанра да не избута монетата.
    val sideW = (cw.value * 2 + 10.d.value).dp
    Column(Modifier.width(sideW).graphicsLayer { alpha = if (dim) 0.45f else 1f }) {
        Text2(who, 11, C.dim, FontWeight.Medium, 1)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.d)) {
            Text2(
                genre.ifBlank { "Жанр не е избран" }, 16,
                if (win) C.gold else if (genre.isBlank()) C.dim else C.text, FontWeight.ExtraBold, 1
            )
            if (win) Pill("печели", Color(0x33FFC857), C.gold)
        }
        Spacer(Modifier.height(6.d))
        Row(horizontalArrangement = Arrangement.spacedBy(10.d)) {
            if (items.isEmpty()) {
                EmptySlot(cw)
                EmptySlot(cw)
            } else {
                items.forEachIndexed { i, m -> DiceCard(m, i, cw, onOpen, 11, 10) }
            }
        }
    }
}

/** Празно място на плакат: показва къде ще дойдат заглавията. */
@Composable
private fun EmptySlot(cw: Dp) {
    Box(
        Modifier.width(cw).height(cw * 1.5f).clip(RoundedCornerShape(12.d))
            .background(Color(0x14FFFFFF)).border(1.d, C.line, RoundedCornerShape(12.d)),
        contentAlignment = Alignment.Center
    ) {
        Text2("?", 24, C.dim, FontWeight.Bold, 1)
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
                else -> "?"
            },
            18, Color(0xFF1A1305), FontWeight.ExtraBold, 1
        )
    }
}
