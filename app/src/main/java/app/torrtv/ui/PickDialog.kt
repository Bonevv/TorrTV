package app.torrtv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
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

/** Един избор от списък: отваря се с OK, избира се с OK, а Назад го затваря. Заменя цъкането през всяка опция поред. */
class PickOption(val key: String, val label: String, val count: String = "")

@Composable
fun PickDialog(title: String, options: List<PickOption>, selected: String, onPick: (String) -> Unit, onDismiss: () -> Unit) {
    val start = options.indexOfFirst { it.key == selected }.coerceAtLeast(0)
    val first = remember { FocusRequester() }
    val list = rememberLazyListState()
    LaunchedEffect(Unit) {
        list.scrollToItem((start - 2).coerceAtLeast(0))
        first.tryRequest()
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier.width(380.d).clip(RoundedCornerShape(20.d)).background(C.bg2)
                .border(1.d, C.line, RoundedCornerShape(20.d)).padding(horizontal = 18.d, vertical = 18.d)
        ) {
            Text2(title, 17, C.text, FontWeight.Bold, 1)
            Spacer(Modifier.height(10.d))
            LazyColumn(Modifier.heightIn(max = 300.d), state = list, verticalArrangement = Arrangement.spacedBy(4.d)) {
                itemsIndexed(options, key = { _, o -> o.key }) { i, o ->
                    val on = o.key == selected
                    Focusable(
                        { onPick(o.key) }, Modifier.fillMaxWidth(), RoundedCornerShape(10.d), 1.02f, ring = false,
                        focusRequester = if (i == start) first else null
                    ) { f ->
                        Row(
                            Modifier.fillMaxWidth().background(if (f) C.text else C.card).padding(horizontal = 12.d, vertical = 10.d),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(Modifier.width(14.d), contentAlignment = Alignment.CenterStart) {
                                if (on) Text2("✓", 14, if (f) C.bg else C.accent, FontWeight.ExtraBold, 1)
                            }
                            Text2(o.label, 14, if (f) C.bg else C.text, if (on) FontWeight.Bold else FontWeight.Medium, 1, Modifier.weight(1f))
                            if (o.count.isNotBlank()) Text2(o.count, 12, if (f) C.bg else C.dim, FontWeight.Medium, 1)
                        }
                    }
                }
            }
        }
    }
}
