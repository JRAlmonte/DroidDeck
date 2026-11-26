package com.droiddeck.launcher.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.InputModeManager
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalTestApi::class, androidx.compose.ui.ExperimentalComposeUiApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "w640dp-h480dp-land", application = android.app.Application::class)
class AnchoredListMenuTest {
    @get:Rule val compose = createRobolectricComposeRule()

    private val drawn = HashSet<Int>()
    private val picked = ArrayList<Int>()
    private var list by mutableStateOf(true)

    private val byTouch = object : InputModeManager {
        override val inputMode = InputMode.Touch
        override fun requestInputMode(inputMode: InputMode) = false
    }

    private fun show(count: Int, start: Int = 0, touch: Boolean = false) {
        compose.setContent {
            DroidDeckTheme {
                val menu = @androidx.compose.runtime.Composable {
                    Box {
                        if (list) AnchoredListMenu(true, onDismiss = {}, count = count, start = start, minWidth = 160.dp) { index, first ->
                            drawn += index
                            MenuItem("Row $index", index == start, focusRequester = first) { picked += index }
                        }
                        else AnchoredMenu(true, onDismiss = {}, compact = true, minWidth = 160.dp) { first ->
                            for (index in 0 until count) MenuItem("Row $index", index == start, focusRequester = first.takeIf { index == 0 }) { picked += index }
                        }
                    }
                }
                if (touch) CompositionLocalProvider(LocalInputModeManager provides byTouch) { menu() } else menu()
            }
        }
        compose.waitForIdle()
    }

    private fun press(key: Key, times: Int = 1) = repeat(times) { compose.onNode(isFocused()).performKeyInput { pressKey(key) } }

    private fun rows(count: Int) = (0 until count).map { compose.onNodeWithText("Row $it").getUnclippedBoundsInRoot() }

    @Test fun aShortListIsTheMenuItWasBefore() {
        list = false
        show(8, start = 5)
        val before = rows(8)
        list = true
        compose.waitForIdle()
        assertEquals((0 until 8).toSet(), drawn)
        assertEquals(before, rows(8))
        compose.onNodeWithText("Row 0").requestFocus().assertIsFocused()
        press(Key.DirectionDown, 7)
        compose.onNodeWithText("Row 7").assertIsFocused()
        press(Key.ButtonA)
        assertEquals(listOf(7), picked)
    }

    @Test fun aLongListHoldsOnlyTheRowsInViewAtAFixedWidth() {
        show(400)
        assertTrue(0 in drawn)
        assertTrue("${drawn.size} rows were drawn", drawn.size < 40)
        compose.onNodeWithText("Row 399").assertDoesNotExist()
        compose.onNodeWithText("Row 0").assertWidthIsEqualTo(248.dp)
        compose.onNodeWithText("Row 3").performClick()
        assertEquals(listOf(3), picked)
    }

    @Test fun aControllerWalksALongListPastTheRowsThatWereInView() {
        show(400)
        compose.onNodeWithText("Row 0").requestFocus().assertIsFocused()
        press(Key.DirectionDown, 60)
        compose.onNodeWithText("Row 60").assertIsFocused()
        press(Key.ButtonA)
        assertEquals(listOf(60), picked)
        compose.onNodeWithText("Row 0").assertIsNotDisplayed()
        press(Key.DirectionUp, 3)
        compose.onNodeWithText("Row 57").assertIsFocused()
        assertTrue("${drawn.size} rows were drawn", drawn.size < 120)
    }

    @Test fun aLongListOpenedByTouchStartsAtTheRowThatIsPicked() {
        show(400, start = 250, touch = true)
        compose.onNodeWithText("Row 250").assertExists()
        compose.onNodeWithText("Row 249").assertExists()
        compose.onNodeWithText("Row 0").assertDoesNotExist()
        assertTrue("${drawn.size} rows were drawn", drawn.size < 40)
    }

    @Test fun aLongListOpenedWithAControllerStartsAtItsFirstRow() {
        show(400, start = 250)
        compose.onNodeWithText("Row 0").assertExists()
        compose.onNodeWithText("Row 250").assertDoesNotExist()
    }
}
