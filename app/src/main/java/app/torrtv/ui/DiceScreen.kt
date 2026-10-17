package app.torrtv.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
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

@Composable
fun DiceScreen(dice: DiceState, onOpen: (Media) -> Unit) {
    LaunchedEffect(Unit) { if (!dice.started) dice.roll() }
    Column(Modifier.fillMaxSize().padding(top = 6.d)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 36.d),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(20.d)
        ) {
            DiceView(dice.face, dice.rolling, 78.d)
            Column(Modifier.weight(1f)) {
                Text2("Какво да гледаме днес?", 22, C.text, FontWeight.ExtraBold, 1)
                Text2(
                    when {
                        dice.rolling -> "Зарът се върти…"
                        dice.error != null -> "⚠ ${dice.error}"
                        dice.pickedGenre != null -> "Зарът избра: ${dice.pickedGenre}  ·  5 предложения"
                        else -> "Избери жанр или хвърли зара"
                    },
                    13, if (dice.error != null) C.red else C.accent2, FontWeight.Medium, 2
                )
                Spacer(Modifier.height(8.d))
                Row(horizontalArrangement = Arrangement.spacedBy(8.d), verticalAlignment = Alignment.CenterVertically) {
                    PrimaryButton("🎲  Хвърли отново", { dice.roll() })
                    Chip("Филми", dice.type == MediaType.MOVIE, { dice.changeType(MediaType.MOVIE) })
                    Chip("Сериали", dice.type == MediaType.TV, { dice.changeType(MediaType.TV) })
                    Spacer(Modifier.width(10.d))
                    listOf(0 to "Всички оценки", 6 to "★ 6+", 7 to "★ 7+", 8 to "★ 8+").forEach { (v, l) ->
                        Chip(l, dice.minRating == v, { dice.changeRating(v) })
                    }
                }
            }
        }
        Spacer(Modifier.height(8.d))
        LazyRow(contentPadding = PaddingValues(horizontal = 36.d), horizontalArrangement = Arrangement.spacedBy(8.d)) {
            item(key = "surprise") { Chip("🎲 Изненада ме", dice.genre == null, { dice.changeGenre(null) }) }
            items(Genres.forType(dice.type), key = { it.id }) { g ->
                Chip(g.name, dice.genre == g.id, { dice.changeGenre(g.id) })
            }
        }
        Spacer(Modifier.height(10.d))

        Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.TopCenter) {
            if (dice.rolling) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Spinner(36.d) }
            } else {
                key(dice.rollId) {
                    Row(Modifier.padding(horizontal = 36.d), horizontalArrangement = Arrangement.spacedBy(12.d)) {
                        dice.results.forEachIndexed { i, m -> DiceCard(m, i, onOpen) }
                    }
                }
            }
        }
    }
}

@Composable
private fun DiceCard(m: Media, index: Int, onOpen: (Media) -> Unit) {
    val a = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        delay(index * 130L)
        a.animateTo(1f, tween(380))
    }
    Column(
        Modifier.width(158.d).graphicsLayer {
            alpha = a.value
            translationY = (1f - a.value) * 60f
        }
    ) {
        Poster(m, 158.d, { onOpen(m) })
        Spacer(Modifier.height(6.d))
        Text2(m.title, 12, C.text, FontWeight.Bold, 2)
        Text2(
            listOf(m.year, if (m.rating > 0) "★ " + String.format(Locale.US, "%.1f", m.rating) else "").filter { it.isNotBlank() }.joinToString("  ·  "),
            11, C.gold, FontWeight.Medium, 1
        )
    }
}
