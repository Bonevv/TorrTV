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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

object C {
    // Тъмна "кино" гама със златист акцент. Всичко в интерфейса се оцветява от тук.
    val bg = Color(0xFF07090E)
    val bg2 = Color(0xFF0E121A)
    val card = Color(0xFF141A26)
    val cardHi = Color(0xFF242E44)
    val text = Color(0xFFF4F1EA)
    val dim = Color(0xFF9AA3B6)
    val accent = Color(0xFFE9B44C)
    val accent2 = Color(0xFFF2D58F)
    val onAccent = Color(0xFF1A1305)
    val gold = Color(0xFFFFC857)
    val green = Color(0xFF3DDC84)
    val amber = Color(0xFFFFB020)
    val red = Color(0xFFFF5C6C)
    val ring = Color(0xFFF2D58F)
    val line = Color(0x1FFFFFFF)
    val grad: Brush = Brush.horizontalGradient(listOf(Color(0xFFF6CB6B), Color(0xFFD9962B)))
    val flat: Brush = Brush.horizontalGradient(listOf(card, card))
    val white: Brush = Brush.horizontalGradient(listOf(text, text))
}

/** Лек режим: изключва анимациите и ползва по-малки картинки. Състояние, за да се прилага веднага. */
object Look {
    var lite by androidx.compose.runtime.mutableStateOf(false)

    /**
     * Размерите на TMDB картинките. В лек режим плакатите се теглят по-малки (w185). Само размери, валидни и за
     * трите вида (плакат, фон, кадър), се сменят - другите остават, а пикселите се намаляват при декодирането.
     */
    fun img(path: String?, size: String): String? {
        val s = if (lite && size == "w342") "w185" else size
        return app.torrtv.data.Tmdb.img(path, s)
    }
}

/**
 * Дизайнът е за екран 960x540dp; всичко се мащабира според реалния екран на телевизора.
 * Мащабът е по по-тясната страна (ширина или височина): на по-широк екран (21:9) нищо не излиза долу
 * (панелът, диалозите и списъците са по височина), а на по-висок (4:3) просто има повече място встрани.
 */
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
    val k = uiScale(cfg.screenWidthDp, cfg.screenHeightDp)
    // Размерът на текста идва от екрана (k), а не от системната настройка за шрифт: оформлението е изчислено за него.
    val density = LocalDensity.current
    CompositionLocalProvider(LocalDims provides Dims(k), LocalDensity provides Density(density.density, 1f)) {
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
