package app.torrtv.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import app.torrtv.data.Remote
import app.torrtv.state.Graph
import kotlinx.coroutines.launch

/** Цифрата, която прави клавишът от дистанционното (или null). */
private fun digitOf(k: Key): Char? = when (k) {
    Key.Zero, Key.NumPad0 -> '0'
    Key.One, Key.NumPad1 -> '1'
    Key.Two, Key.NumPad2 -> '2'
    Key.Three, Key.NumPad3 -> '3'
    Key.Four, Key.NumPad4 -> '4'
    Key.Five, Key.NumPad5 -> '5'
    Key.Six, Key.NumPad6 -> '6'
    Key.Seven, Key.NumPad7 -> '7'
    Key.Eight, Key.NumPad8 -> '8'
    Key.Nine, Key.NumPad9 -> '9'
    else -> null
}

/**
 * Вход с номер и ПИН (когато приложението е свързано с услугата за настройки). Цифрите се избират от
 * екранната клавиатура с бутоните на дистанционното; цифрите на самото дистанционно също работят.
 */
@Composable
fun LoginScreen() {
    val sync = Graph.sync
    val scope = rememberCoroutineScope()
    var step by remember { mutableStateOf(0) } // 0 - номер, 1 - ПИН
    var num by remember { mutableStateOf("") }
    var pin by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf(sync.notice) }
    val first = remember { FocusRequester() }
    // Входът може да се направи и от телефона: страницата работи, докато е отворен този екран.
    DisposableEffect(Unit) {
        Graph.config.start()
        onDispose { Graph.config.stop() }
    }
    val addr = remember(Graph.config.port) { Graph.config.addresses().firstOrNull() }
    LaunchedEffect(Unit) {
        sync.clearNotice()
        first.tryRequest()
    }

    fun digit(c: Char) {
        if (busy) return
        msg = ""
        if (step == 0) num = Remote.appendDigit(num, c, 8) else pin = Remote.appendDigit(pin, c, 8)
    }

    fun erase() {
        if (busy) return
        msg = ""
        if (step == 0) num = num.dropLast(1) else pin = pin.dropLast(1)
    }

    fun next() {
        if (busy) return
        if (step == 0) {
            if (num.length < 3) msg = "Въведи номера си." else {
                step = 1
                msg = ""
            }
            return
        }
        if (pin.length < 4) {
            msg = "ПИН-ът е поне 4 цифри."
            return
        }
        busy = true
        msg = ""
        scope.launch {
            val r = sync.login(num, pin)
            busy = false
            if (!r.ok) {
                msg = Remote.message(r).orEmpty()
                // Грешни данни: започва се отначало, за да се поправи и номерът. При проблем с връзката ПИН-ът остава.
                if (r.err == Remote.Err.BAD || r.err == Remote.Err.LOCKED) {
                    pin = ""
                    step = 0
                }
            }
        }
    }

    BackHandler(enabled = step == 1 && !busy) {
        step = 0
        pin = ""
        msg = ""
    }

    Box(
        Modifier.fillMaxSize().onPreviewKeyEvent { e ->
            if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
            val d = digitOf(e.key)
            when {
                d != null -> {
                    digit(d)
                    true
                }
                e.key == Key.Backspace || e.key == Key.Delete -> {
                    erase()
                    true
                }
                else -> false
            }
        },
        contentAlignment = Alignment.Center
    ) {
        Column(Modifier.width(400.d), horizontalAlignment = Alignment.CenterHorizontally) {
            Text2("Вход", 28, C.text, FontWeight.Bold, 1)
            Spacer(Modifier.height(4.d))
            Text2(if (step == 0) "Въведи номера си" else "Въведи ПИН", 15, C.accent2, FontWeight.Medium, 1)
            Spacer(Modifier.height(12.d))
            val shape = RoundedCornerShape(14.d)
            Box(
                Modifier.fillMaxWidth().height(56.d).clip(shape).background(C.card).border(1.d, C.line, shape),
                contentAlignment = Alignment.Center
            ) {
                val cur = if (step == 0) num else "●".repeat(pin.length)
                val shown = cur.toCharArray().joinToString(" ")
                if (shown.isEmpty()) Text2(if (step == 0) "номер" else "ПИН", 16, C.dim, FontWeight.Medium, 1)
                else Text2(shown, 26, C.text, FontWeight.Bold, 1)
            }
            Box(Modifier.fillMaxWidth().height(34.d), contentAlignment = Alignment.Center) {
                if (busy) Spinner() else if (msg.isNotEmpty()) Text2(msg, 13, C.red, FontWeight.Medium, 2)
            }
            Column(verticalArrangement = Arrangement.spacedBy(10.d)) {
                for (row in listOf("123", "456", "789")) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.d)) {
                        for (c in row) PadKey(c.toString(), { digit(c) }, Modifier.weight(1f), focusRequester = if (c == '1') first else null)
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.d)) {
                    PadKey("Изтрий", { erase() }, Modifier.weight(1f), small = true)
                    PadKey("0", { digit('0') }, Modifier.weight(1f))
                    PadKey(if (step == 0) "Напред" else "Вход", { next() }, Modifier.weight(1f), accent = true, small = true)
                }
            }
            Spacer(Modifier.height(14.d))
            Text2("Номера и ПИН-а ти дава този, който ти даде приложението.", 12, C.dim, FontWeight.Normal, 2)
            if (addr != null && Graph.config.port != 0) {
                Spacer(Modifier.height(8.d))
                Text2("Или от телефона: http://$addr:${Graph.config.port}  ПИН ${Graph.config.pin}", 12, C.accent2, FontWeight.Medium, 2)
            }
        }
    }
}

@Composable
private fun PadKey(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    accent: Boolean = false,
    small: Boolean = false,
    focusRequester: FocusRequester? = null
) {
    Focusable(onClick, modifier.height(50.d), RoundedCornerShape(14.d), 1.06f, focusRequester = focusRequester) { f ->
        Box(
            Modifier.fillMaxSize().background(if (accent) C.accent else if (f) C.cardHi else C.card),
            contentAlignment = Alignment.Center
        ) {
            Text2(label, if (small) 16 else 22, if (accent) C.onAccent else C.text, FontWeight.Bold, 1)
        }
    }
}
