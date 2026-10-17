package app.torrtv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

object C {
    val bg = Color(0xFF080B12)
    val bg2 = Color(0xFF0F1420)
    val card = Color(0xFF171E2E)
    val cardHi = Color(0xFF263050)
    val text = Color(0xFFF3F5FA)
    val dim = Color(0xFF98A2B8)
    val accent = Color(0xFF7C6BFF)
    val accent2 = Color(0xFF22D3EE)
    val gold = Color(0xFFFFC857)
    val green = Color(0xFF3DDC84)
    val amber = Color(0xFFFFB020)
    val red = Color(0xFFFF5C6C)
    val ring = Color(0xFFFFFFFF)
    val grad: Brush = Brush.horizontalGradient(listOf(accent, accent2))
    val flat: Brush = Brush.horizontalGradient(listOf(card, card))
    val white: Brush = Brush.horizontalGradient(listOf(text, text))
}

/** Дизайнът е за ширина 960dp; всичко се мащабира според реалния екран на телевизора. */
@Immutable
class Dims(val k: Float)

val LocalDims = staticCompositionLocalOf { Dims(1f) }

val Int.d: Dp
    @Composable @ReadOnlyComposable get() = (this * LocalDims.current.k).dp

val Int.t: TextUnit
    @Composable @ReadOnlyComposable get() = (this * LocalDims.current.k).sp

@Composable
fun TorrTheme(content: @Composable () -> Unit) {
    val cfg = LocalConfiguration.current
    val k = (cfg.screenWidthDp / 960f).coerceIn(0.8f, 2.4f)
    CompositionLocalProvider(LocalDims provides Dims(k)) {
        MaterialTheme(
            colorScheme = darkColorScheme(
                primary = C.accent,
                background = C.bg,
                surface = C.bg2,
                onSurface = C.text,
                onBackground = C.text
            )
        ) {
            Box(Modifier.fillMaxSize().background(C.bg)) { content() }
        }
    }
}
