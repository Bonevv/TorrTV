package app.torrtv.ui

import android.app.Application
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import app.torrtv.data.*
import app.torrtv.state.Graph
import app.torrtv.state.Paged
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28],application=Application::class,qualifiers="w960dp-h540dp-land")
@LooperMode(LooperMode.Mode.PAUSED)
@GraphicsMode(GraphicsMode.Mode.LEGACY)
@OptIn(ExperimentalTestApi::class)
class TvNavigationTest {
    @get:Rule val ui=createComposeRule()
    @Before fun setup(){Graph.app=ApplicationProvider.getApplicationContext()}

    @Test fun remoteOkActivatesOnceAndDownMovesToTheNextButton() {
        var clicked=0
        ui.setContent{TorrTheme{Column{
            PrimaryButton("Първи",{clicked++})
            PrimaryButton("Втори",{})
        }}}
        val first=ui.onNodeWithText("Първи")
        first.performSemanticsAction(SemanticsActions.RequestFocus)
        first.performKeyInput{pressKey(Key.DirectionCenter)}
        ui.runOnIdle{assertEquals(1,clicked)}
        first.performKeyInput{pressKey(Key.DirectionDown)}
        ui.onNodeWithText("Втори").assertIsFocused()
    }

    @Test fun returningFromDetailsRestoresTheSamePosterAndScrollPosition() {
        val details=mutableStateOf(false);val focus=mutableStateOf<String?>(null)
        val state=LazyGridState()
        val cards=(1..40).map{n->Media(n,MediaType.MOVIE,"Movie $n","Movie $n","",null,null,"2020",7.0,20,emptyList(),"en")}
        val paged=Paged<Media>(100,{Page(cards,1,1)}){it.key}
        ui.setContent{TorrTheme{
            if(details.value)PrimaryButton("Назад",{details.value=false})
            else PosterGrid(paged,state,{focus.value},{focus.value=it},{details.value=true})
        }}
        ui.waitUntil{paged.items.size==40}
        ui.onNodeWithContentDescription("Movie 2 · 2020").performSemanticsAction(SemanticsActions.RequestFocus)
        ui.onNodeWithContentDescription("Movie 2 · 2020").performKeyInput{pressKey(Key.Enter)}
        ui.onNodeWithText("Назад").assertExists().performClick()
        ui.onNodeWithContentDescription("Movie 2 · 2020").assertIsFocused()
        ui.runOnIdle{assertEquals(0,state.firstVisibleItemIndex)}
    }
}
