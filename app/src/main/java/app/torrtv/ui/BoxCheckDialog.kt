package app.torrtv.ui

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.torrtv.core.BoxCheck
import app.torrtv.state.Graph

/** Показва се веднъж след първия вход: какъв е боксът и какво се препоръчва. Слабият бокс получава готова препоръка с един бутон. */
@Composable
fun BoxCheckDialog(ctx: Context, onDone: () -> Unit) {
    val r = remember { BoxCheck.run(ctx) }
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { first.tryRequest() }
    Dialog(onDismissRequest = onDone, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier.width(420.d).clip(RoundedCornerShape(20.d)).background(C.bg2)
                .border(1.d, C.line, RoundedCornerShape(20.d)).padding(horizontal = 24.d, vertical = 22.d),
            horizontalAlignment = Alignment.Start
        ) {
            Text2("Проверка на бокса", 18, C.text, FontWeight.Bold, 1)
            Spacer(Modifier.height(6.d))
            Text2(r.summary, 13, C.accent2, FontWeight.Medium, 2)
            Spacer(Modifier.height(10.d))
            r.advice.forEach {
                Text2(it, 13, C.dim, FontWeight.Normal, 5)
                Spacer(Modifier.height(6.d))
            }
            Spacer(Modifier.height(8.d))
            Row(horizontalArrangement = Arrangement.spacedBy(10.d)) {
                if (r.weak) {
                    PrimaryButton("Приеми", {
                        Graph.prefs.liteChoice = 1
                        Look.lite = true
                        Graph.prefs.remoteCtl = 2
                        onDone()
                    }, focusRequester = first)
                    PrimaryButton("Остави както е", onDone, primary = false)
                } else {
                    PrimaryButton("Добре", onDone, focusRequester = first)
                }
            }
        }
    }
}
