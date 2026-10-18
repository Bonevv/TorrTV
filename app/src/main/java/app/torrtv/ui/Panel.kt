package app.torrtv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight

/**
 * Страничен панел с раздели и филтри. Един и същ вид има в Детайли, Филми/Сериали и Зарче, за да изглеждат
 * като едно приложение. Скролира сам, ако е по-дълъг от екрана.
 */
@Composable
internal fun SidePanel(
    modifier: Modifier = Modifier,
    state: LazyListState = rememberLazyListState(),
    content: LazyListScope.() -> Unit
) {
    val shape = RoundedCornerShape(16.d)
    LazyColumn(
        modifier.width(214.d).fillMaxHeight().clip(shape).background(Color(0xB30A0D14)).border(1.d, C.line, shape),
        state = state,
        contentPadding = PaddingValues(8.d),
        verticalArrangement = Arrangement.spacedBy(2.d),
        content = content
    )
}

/** Малък надпис над група в панела. */
internal fun LazyListScope.panelCaption(key: String, text: String) {
    item(key = key) {
        Text2(text, 11, C.dim, FontWeight.Bold, 1, Modifier.padding(start = 10.d, top = 2.d, bottom = 4.d))
    }
}

/** Тънка линия между групите в панела. */
internal fun LazyListScope.panelDivider(key: String) {
    item(key = key) {
        Box(Modifier.padding(horizontal = 6.d, vertical = 6.d).fillMaxWidth().height(1.d).background(C.line))
    }
}
