package app.torrtv.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Process
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.torrtv.state.Graph
import app.torrtv.torrent.StreamController.Stage
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.cos
import kotlin.math.sin

/** Иконите на панела са нарисувани с линии - няма нужда от файлове с картинки. */
internal enum class Glyph { HOME, MOVIES, SERIES, HEART, SEARCH, DICE, SETTINGS, POWER }

/** Позиция в панела: кой таб отваря (индексът в App), име и иконка. */
private class RailEntry(val tab: Int, val label: String, val glyph: Glyph)

private val entries = listOf(
    RailEntry(0, "Начало", Glyph.HOME),
    RailEntry(1, "Филми", Glyph.MOVIES),
    RailEntry(2, "Сериали", Glyph.SERIES),
    RailEntry(3, "Любими", Glyph.HEART),
    RailEntry(5, "Търсене", Glyph.SEARCH),
    RailEntry(4, "Зарче", Glyph.DICE),
    RailEntry(6, "Настройки", Glyph.SETTINGS)
)

/**
 * Съдържанието до панела е отделна група: нагоре и надолу фокусът не излиза от него. Без това "надолу" от
 * последния ред стига до бутона "Изход", защото той е най-близкото нещо отдолу. Наляво се влиза в панела както преди.
 */
@OptIn(ExperimentalComposeUiApi::class)
internal fun Modifier.stayInContent(): Modifier = this
    .focusProperties {
        exit = { dir ->
            if (dir == FocusDirection.Up || dir == FocusDirection.Down) FocusRequester.Cancel else FocusRequester.Default
        }
    }
    .focusGroup()

/** Страничен панел: лого, раздели и отдолу "Изход". */
@Composable
fun SideRail(
    selected: Int,
    settingsTab: Int,
    onSelect: (Int) -> Unit,
    focus: List<FocusRequester>,
    updateReady: Boolean,
    onExit: () -> Unit
) {
    // "Изход" е най-долу и затова е най-близкото нещо под съдържанието. Достъпен е само отвътре на панела.
    var railFocused by remember { mutableStateOf(false) }
    Column(
        Modifier
            .onFocusChanged { railFocused = it.hasFocus }
            .width(76.d)
            .fillMaxHeight()
            .background(Brush.verticalGradient(listOf(Color(0xFF0C1019), C.bg)))
            .padding(top = 12.d, bottom = 8.d),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("TORR", color = C.text, fontSize = 11.t, fontWeight = FontWeight.Light, letterSpacing = 2.t, maxLines = 1)
            Text("TV", color = C.accent, fontSize = 17.t, fontWeight = FontWeight.Black, letterSpacing = 1.t, maxLines = 1)
            Signature(large = false)
        }
        Spacer(Modifier.height(6.d))
        entries.forEach { e ->
            val upd = updateReady && e.tab == settingsTab
            RailItem(
                if (upd) "Обнови" else e.label, e.glyph, selected == e.tab, upd,
                { onSelect(e.tab) }, focus[e.tab]
            )
            Spacer(Modifier.height(2.d))
        }
        Spacer(Modifier.weight(1f))
        RailItem("Изход", Glyph.POWER, false, false, onExit, null, enabled = railFocused)
    }
}

private val allura = androidx.compose.ui.text.font.FontFamily(androidx.compose.ui.text.font.Font(app.torrtv.R.font.allura))

/** Подпис на автора: ръкописен шрифт (Allura, лиценз OFL), меко бяло, без сянка и блясък. */
@Composable
fun Signature(large: Boolean) {
    val c = Color(0xFFE5E1DA)
    Text("by V. Bonev", color = c, fontFamily = allura, fontSize = if (large) 26.t else 14.t, maxLines = 1, softWrap = false)
}

@Composable
private fun RailItem(
    label: String,
    glyph: Glyph,
    selected: Boolean,
    dot: Boolean,
    onClick: () -> Unit,
    focusRequester: FocusRequester?,
    enabled: Boolean = true
) {
    Focusable(
        onClick, Modifier.focusProperties { canFocus = enabled }.width(64.d).height(50.d), RoundedCornerShape(12.d), 1.05f,
        ring = false, focusRequester = focusRequester
    ) { f ->
        val fg = when {
            f -> C.text
            selected || dot -> C.accent
            else -> C.dim
        }
        Box(
            Modifier.fillMaxSize().background(
                when {
                    f -> Color(0x29FFFFFF)
                    selected -> Color(0x1AE9B44C)
                    else -> Color.Transparent
                }
            )
        )
        if (selected) {
            Box(
                Modifier.align(Alignment.CenterStart).width(3.d).height(24.d)
                    .clip(RoundedCornerShape(2.d)).background(C.accent)
            )
        }
        Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
            Box {
                GlyphIcon(glyph, fg, Modifier.size(20.d))
                if (dot) {
                    Box(
                        Modifier.align(Alignment.TopEnd).size(7.d).clip(RoundedCornerShape(50)).background(C.accent)
                    )
                }
            }
            Spacer(Modifier.height(3.d))
            Text(label, color = fg, fontSize = 9.t, fontWeight = FontWeight.SemiBold, maxLines = 1)
        }
    }
}

@Composable
internal fun GlyphIcon(glyph: Glyph, color: Color, modifier: Modifier) {
    Canvas(modifier) {
        val s = size.minDimension / 24f
        val line = Stroke(width = 1.9f * s, cap = StrokeCap.Round, join = StrokeJoin.Round)
        fun o(x: Float, y: Float) = Offset(x * s, y * s)
        fun seg(x1: Float, y1: Float, x2: Float, y2: Float) =
            drawLine(color, o(x1, y1), o(x2, y2), strokeWidth = 1.9f * s, cap = StrokeCap.Round)
        when (glyph) {
            Glyph.HOME -> {
                val p = Path()
                p.moveTo(4f * s, 11f * s)
                p.lineTo(12f * s, 4f * s)
                p.lineTo(20f * s, 11f * s)
                p.lineTo(20f * s, 20f * s)
                p.lineTo(4f * s, 20f * s)
                p.close()
                drawPath(p, color, style = line)
            }
            Glyph.MOVIES -> {
                drawRoundRect(color, o(3f, 5f), Size(18f * s, 14f * s), CornerRadius(2f * s), style = line)
                seg(7f, 5f, 7f, 19f)
                seg(17f, 5f, 17f, 19f)
                seg(3f, 10f, 7f, 10f)
                seg(3f, 14f, 7f, 14f)
                seg(17f, 10f, 21f, 10f)
                seg(17f, 14f, 21f, 14f)
            }
            Glyph.SERIES -> {
                drawRoundRect(color, o(3f, 5f), Size(18f * s, 12f * s), CornerRadius(2f * s), style = line)
                seg(9f, 21f, 15f, 21f)
                seg(12f, 17f, 12f, 21f)
            }
            Glyph.HEART -> {
                val p = Path()
                p.moveTo(12f * s, 20f * s)
                p.cubicTo(4f * s, 14.5f * s, 3f * s, 9f * s, 7f * s, 6.8f * s)
                p.cubicTo(9.2f * s, 5.6f * s, 11.3f * s, 6.4f * s, 12f * s, 8.2f * s)
                p.cubicTo(12.7f * s, 6.4f * s, 14.8f * s, 5.6f * s, 17f * s, 6.8f * s)
                p.cubicTo(21f * s, 9f * s, 20f * s, 14.5f * s, 12f * s, 20f * s)
                p.close()
                drawPath(p, color, style = line)
            }
            Glyph.SEARCH -> {
                drawCircle(color, radius = 6f * s, center = o(11f, 11f), style = line)
                seg(15.5f, 15.5f, 20.5f, 20.5f)
            }
            Glyph.DICE -> {
                drawRoundRect(color, o(4f, 4f), Size(16f * s, 16f * s), CornerRadius(4f * s), style = line)
                for ((x, y) in listOf(8.5f to 8.5f, 15.5f to 8.5f, 12f to 12f, 8.5f to 15.5f, 15.5f to 15.5f)) {
                    drawCircle(color, radius = 1.15f * s, center = o(x, y))
                }
            }
            Glyph.SETTINGS -> {
                drawCircle(color, radius = 3.3f * s, center = o(12f, 12f), style = line)
                for (i in 0 until 8) {
                    val a = i * Math.PI / 4
                    val c = cos(a).toFloat()
                    val n = sin(a).toFloat()
                    seg(12f + 6.2f * c, 12f + 6.2f * n, 12f + 8.6f * c, 12f + 8.6f * n)
                }
            }
            Glyph.POWER -> {
                seg(12f, 3.5f, 12f, 11f)
                drawArc(
                    color, -60f, 300f, false, o(4.2f, 4.7f), Size(15.6f * s, 15.6f * s), style = line
                )
            }
        }
    }
}

/** Въпрос преди изход - да не се затвори приложението от случайно натискане. */
@Composable
fun ExitDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val stay = remember { FocusRequester() }
    LaunchedEffect(Unit) { stay.tryRequest() }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier.width(340.d).clip(RoundedCornerShape(20.d)).background(C.bg2)
                .border(1.d, C.line, RoundedCornerShape(20.d)).padding(horizontal = 24.d, vertical = 22.d),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                "Сигурни ли сте, че искате да излезете?",
                color = C.text, fontSize = 17.t, fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center, lineHeight = 23.t
            )
            Spacer(Modifier.height(18.d))
            Row(horizontalArrangement = Arrangement.spacedBy(10.d)) {
                PrimaryButton("Да", onConfirm, primary = false)
                PrimaryButton("Не", onDismiss, focusRequester = stay)
            }
        }
    }
}

private tailrec fun Context.activity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.activity()
    else -> null
}

/**
 * Спира всичко: връзката за поток и торент процеса (с изтриване на свалените файлове), малкия сървър за
 * настройки, после затваря екрана и накрая и самия процес - така паметта се освобождава докрай.
 */
fun stopApp(ctx: Context) {
    val streaming = Graph.stream.ui.stage != Stage.IDLE
    Graph.stream.close()
    Graph.config.stop()
    ctx.activity()?.finishAndRemoveTask()
    // При активен поток торент процесът се убива и файловете се трият ~2 секунди след затварянето.
    Graph.scope.launch {
        delay(if (streaming) 2600L else 300L)
        Process.killProcess(Process.myPid())
    }
}
