package app.torrtv.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import app.torrtv.data.Genres
import app.torrtv.data.Media
import app.torrtv.data.MediaType
import app.torrtv.data.Tmdb
import coil.compose.AsyncImage
import coil.request.ImageRequest
import kotlinx.coroutines.delay
import java.util.Locale

/** Основният градивен елемент за управление с дистанционно: фокус = уголемяване + рамка. */
@Composable
fun Focusable(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(12.d),
    scale: Float = 1.06f,
    ring: Boolean = true,
    onFocus: () -> Unit = {},
    focusRequester: FocusRequester? = null,
    content: @Composable BoxScope.(Boolean) -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    val lite = Look.lite
    val s by animateFloatAsState(if (focused && !lite) scale else 1f, tween(if (lite) 0 else 140), label = "focusScale")
    val interaction = remember { MutableInteractionSource() }
    val base = if (focusRequester != null) modifier.focusRequester(focusRequester) else modifier
    val ringMod = if (focused && ring) Modifier.border(if (lite) 3.d else 2.d, C.ring, shape) else Modifier
    // Мека златиста светлина около избрания плакат (не в лек режим - там е излишна тежест).
    val glow = if (focused && ring && !lite) Modifier.shadow(18.d, shape, clip = false, ambientColor = C.accent, spotColor = C.accent) else Modifier
    Box(
        base
            .zIndex(if (focused) 2f else 0f)
            .graphicsLayer {
                scaleX = s
                scaleY = s
            }
            .onFocusChanged {
                focused = it.isFocused
                if (it.isFocused) onFocus()
            }
            .then(glow)
            .clip(shape)
            .then(ringMod)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
    ) { content(focused) }
}

suspend fun FocusRequester.tryRequest(): Boolean {
    repeat(4) {
        try {
            requestFocus()
            return true
        } catch (e: Exception) {
            delay(90)
        }
    }
    return false
}

@Composable
fun Chip(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onFocus: () -> Unit = {},
    focusRequester: FocusRequester? = null,
    compact: Boolean = false
) {
    Focusable(onClick, modifier, RoundedCornerShape(50), 1.08f, ring = false, onFocus = onFocus, focusRequester = focusRequester) { f ->
        Box(
            Modifier
                .background(
                    when {
                        f -> C.text
                        selected -> C.accent
                        else -> C.card
                    }
                )
                .then(if (!f && !selected) Modifier.border(1.d, C.line, RoundedCornerShape(50)) else Modifier)
                .padding(horizontal = if (compact) 11.d else 14.d, vertical = 7.d),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text, color = if (f) C.bg else if (selected) C.onAccent else C.text, fontSize = if (compact) 12.t else 13.t,
                fontWeight = FontWeight.SemiBold, maxLines = 1
            )
        }
    }
}

@Composable
fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    primary: Boolean = true,
    onFocus: () -> Unit = {},
    focusRequester: FocusRequester? = null,
    big: Boolean = false
) {
    Focusable(onClick, modifier, RoundedCornerShape(50), 1.07f, ring = false, onFocus = onFocus, focusRequester = focusRequester) { f ->
        Box(
            Modifier
                .background(if (f) C.white else if (primary) C.grad else C.flat)
                .padding(horizontal = if (big) 32.d else 22.d, vertical = if (big) 14.d else 10.d),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text, color = if (f) C.bg else if (primary) C.onAccent else C.text, fontSize = if (big) 18.t else 14.t,
                fontWeight = FontWeight.Bold, maxLines = 1
            )
        }
    }
}

@Composable
fun Pill(text: String, bg: Color = C.card, fg: Color = C.dim) {
    Box(
        Modifier.clip(RoundedCornerShape(50)).background(bg).padding(horizontal = 9.d, vertical = 3.d),
        contentAlignment = Alignment.Center
    ) {
        Text(text, color = fg, fontSize = 11.t, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

@Composable
fun Spinner(size: Dp = 26.d) {
    CircularProgressIndicator(color = C.accent, strokeWidth = 3.d, modifier = Modifier.size(size))
}

/**
 * Картинка от TMDB. Размерът за декодиране не се задава - Coil го взема от мястото в оформлението, така че
 * плакат от 200 пиксела не държи в паметта картинка от 342 (а над размера на файла никога не се уголемява).
 */
@Composable
fun Img(url: String?, modifier: Modifier, scale: ContentScale = ContentScale.Crop) {
    Box(modifier.background(C.card)) {
        if (url != null) {
            val ctx = LocalContext.current
            val lite = Look.lite
            val req = remember(url, lite) {
                // В лек режим: без плавно появяване.
                ImageRequest.Builder(ctx).data(url).allowRgb565(true)
                    .crossfade(if (lite) 0 else 220).build()
            }
            AsyncImage(model = req, contentDescription = null, contentScale = scale, modifier = Modifier.fillMaxSize())
        }
    }
}

@Composable
fun RatingBadge(rating: Double, modifier: Modifier = Modifier) {
    if (rating <= 0.0) return
    Row(
        modifier.clip(RoundedCornerShape(50)).background(Color(0xCC000000)).padding(horizontal = 7.d, vertical = 2.d),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("★", color = C.gold, fontSize = 11.t)
        Spacer(Modifier.width(3.d))
        Text(String.format(Locale.US, "%.1f", rating), color = C.text, fontSize = 11.t, fontWeight = FontWeight.Bold)
    }
}

@Composable
fun Poster(
    media: Media,
    width: Dp,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onFocus: () -> Unit = {},
    focusRequester: FocusRequester? = null
) {
    Focusable(
        onClick, modifier.width(width).height(width * 1.5f), RoundedCornerShape(12.d), 1.09f,
        onFocus = onFocus, focusRequester = focusRequester
    ) { f ->
        Img(Look.img(media.posterPath, "w342"), Modifier.fillMaxSize())
        if (!f) Box(Modifier.fillMaxSize().border(1.d, C.line, RoundedCornerShape(12.d)))
        RatingBadge(media.rating, Modifier.align(Alignment.TopEnd).padding(5.d))
        if (media.type == MediaType.TV) {
            Box(Modifier.align(Alignment.TopStart).padding(5.d)) { Pill("СЕРИАЛ", Color(0xCC000000), C.accent2) }
        }
        if (f) {
            Box(
                Modifier.fillMaxSize().background(
                    Brush.verticalGradient(listOf(Color.Transparent, Color(0xE6000000)), startY = 150f)
                )
            )
            Column(Modifier.align(Alignment.BottomStart).padding(8.d)) {
                Text(
                    media.title, color = C.text, fontSize = 12.t, fontWeight = FontWeight.Bold,
                    maxLines = 3, overflow = TextOverflow.Ellipsis
                )
                if (media.year.isNotBlank()) Text(media.year, color = C.dim, fontSize = 10.t)
            }
        }
    }
}

fun genreLine(m: Media, max: Int = 3): String =
    m.genreIds.mapNotNull { Genres.name(it) }.take(max).joinToString(" · ")

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier, sub: String = "") {
    Row(
        modifier.padding(horizontal = 36.d, vertical = 8.d),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.width(3.d).height(17.d).clip(RoundedCornerShape(2.d)).background(C.accent))
        Spacer(Modifier.width(9.d))
        Text(
            text, color = C.text, fontSize = 17.t, fontWeight = FontWeight.Bold,
            letterSpacing = 0.3.sp, maxLines = 1
        )
        if (sub.isNotBlank()) {
            Spacer(Modifier.width(10.d))
            Text(sub, color = C.dim, fontSize = 12.t, fontWeight = FontWeight.Medium, maxLines = 1)
        }
    }
}

@Composable
fun ErrorLine(message: String, onRetry: (() -> Unit)?, modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().padding(horizontal = 36.d, vertical = 6.d),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.d)
    ) {
        Text(message, color = C.red, fontSize = 13.t, modifier = Modifier.weight(1f), maxLines = 3)
        if (onRetry != null) PrimaryButton("Опитай пак", onRetry, primary = false)
    }
}
