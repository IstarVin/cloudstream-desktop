package desktop.app

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performTextInput
import desktop.app.ui.RemoteTextField
import desktop.app.ui.SubtitleSize
import desktop.app.ui.ProviderPicker
import desktop.app.data.PlayerPrefs
import desktop.api.ProviderDto
import org.junit.Assert.assertFalse
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.activity.ComponentActivity
import androidx.core.view.WindowCompat
import android.view.WindowManager
import org.junit.Before
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import desktop.app.ui.AdaptiveSheet
import desktop.app.ui.AppTheme
import desktop.app.ui.PairScreen
import desktop.app.ui.Poster
import desktop.app.ui.tvFocusIndicator
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File

internal fun captureTvScreen(name: String) {
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    instrumentation.waitForIdleSync()
    android.os.SystemClock.sleep(350)
    val screenshot = instrumentation.uiAutomation.takeScreenshot()
    File(instrumentation.targetContext.cacheDir, "$name.png").outputStream().use {
        screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
    }
    screenshot.recycle()
}

@Composable
private fun TestTheme(content: @Composable () -> Unit) {
    AppTheme { Surface(Modifier.fillMaxSize(), content = content) }
}

class TvUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Before fun configureWindow() {
        compose.runOnUiThread {
            // Match MainActivity so IME insets are dispatched to Compose on older Android versions.
            WindowCompat.setDecorFitsSystemWindows(compose.activity.window, false)
            compose.activity.window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }
    }

    @Test fun textEntryRequiresOkAndBackDoesNotReopenKeyboard() = keyboardFlow(inSheet = false)

    @Test fun dialogKeyboardBackKeepsPickerOpenAndAllowsNavigation() = keyboardFlow(inSheet = true)

    @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
    private fun keyboardFlow(inSheet: Boolean) {
        val first = FocusRequester()
        var imeVisible = false
        var dismissed = false
        compose.setContent {
            TestTheme {
                val fields: @Composable () -> Unit = {
                    val visible = WindowInsets.isImeVisible
                    SideEffect { imeVisible = visible }
                    val text = remember { mutableStateOf("") }
                    Column {
                        RemoteTextField(text.value, { text.value = it }, label = { Text("Remote input") },
                            modifier = Modifier.focusRequester(first))
                        Button(onClick = {}) { Text("Next setting") }
                    }
                }
                if (inSheet) AdaptiveSheet(onDismissRequest = { dismissed = true }) { fields() }
                else fields()
            }
        }
        compose.runOnIdle { first.requestFocus() }
        compose.onNodeWithText("Remote input").assertIsFocused()
        compose.runOnIdle { assertFalse(imeVisible) }
        compose.onNodeWithText("Remote input").performKeyInput { pressKey(Key.DirectionDown) }
        compose.onNodeWithText("Next setting").assertIsFocused().performKeyInput { pressKey(Key.DirectionUp) }
        compose.onNodeWithText("Remote input").assertIsFocused().performKeyInput { pressKey(Key.DirectionCenter) }
        compose.waitUntil(5_000) { imeVisible }
        compose.onNodeWithText("Remote input").performTextInput("source")
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
        compose.waitUntil(5_000) { !imeVisible }
        compose.runOnIdle { assertFalse(dismissed) }
        compose.onNodeWithText("Remote input").assertIsFocused().performKeyInput { pressKey(Key.DirectionDown) }
        compose.onNodeWithText("Next setting").assertIsFocused().performKeyInput { pressKey(Key.DirectionUp) }
        compose.runOnIdle { assertFalse(imeVisible) }
        compose.onNodeWithText("Remote input").performKeyInput { pressKey(Key.DirectionCenter) }
        compose.waitUntil(5_000) { imeVisible }
    }

    @Test fun subtitleSliderAdjustsHorizontallyAndNavigatesVertically() {
        var saved = 1f
        compose.setContent {
            TestTheme {
                Column {
                    Button(onClick = {}) { Text("Before slider") }
                    SubtitleSize(PlayerPrefs()) { saved = it }
                    Button(onClick = {}) { Text("After slider") }
                }
            }
        }
        val slider = compose.onNodeWithContentDescription("Subtitle size")
        slider.performSemanticsAction(SemanticsActions.RequestFocus)
        slider.performKeyInput { pressKey(Key.DirectionRight) }
        compose.runOnIdle { assertEquals(1.05f, saved, 0.001f) }
        slider.performKeyInput { pressKey(Key.DirectionLeft) }
        compose.runOnIdle { assertEquals(1f, saved, 0.001f) }
        slider.performKeyInput { pressKey(Key.DirectionDown) }
        compose.onNodeWithText("After slider").assertIsFocused()
        compose.runOnIdle { assertEquals(1f, saved, 0.001f) }
        compose.onNodeWithText("After slider").performKeyInput { pressKey(Key.DirectionUp) }
        slider.assertIsFocused().performKeyInput { pressKey(Key.DirectionUp) }
        compose.onNodeWithText("Before slider").assertIsFocused()
        compose.runOnIdle { assertEquals(1f, saved, 0.001f) }
        captureTvScreen("tv-subtitle-size")
    }

    @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
    @Test fun sourcePickerOpensWithoutKeyboardAndAllowsRemoteSelection() {
        var imeVisible = false
        var picked: String? = null
        compose.setContent {
            TestTheme {
                val visible = WindowInsets.isImeVisible
                SideEffect { imeVisible = visible }
                ProviderPicker(
                    providers = listOf(ProviderDto("Example source", "Example plugin", "https://example.com", "en",
                        listOf("Movie", "Anime", "TvSeries"), false, true, null)),
                    selected = "Example source", categories = emptySet(), onCategories = {}, onDismiss = {},
                    onPick = { picked = it }, onManage = {},
                )
            }
        }
        compose.onNodeWithContentDescription("Find a source by name, plugin or language")
            .performSemanticsAction(SemanticsActions.RequestFocus)
        compose.runOnIdle { assertFalse(imeVisible) }
        captureTvScreen("tv-source-picker")
        compose.onNodeWithText("Example source").performSemanticsAction(SemanticsActions.RequestFocus)
        compose.onNodeWithText("Example source").performKeyInput { pressKey(Key.DirectionCenter) }
        compose.runOnIdle { assertEquals("Example source", picked) }
    }

    @Test fun pairingStartsWithManualEntryAndRemoteFocus() {
        compose.setContent { TestTheme { PairScreen(null) {} } }
        compose.onNodeWithText("Desktop address").assertIsFocused()
        compose.onNodeWithText("Token").assertExists()
        compose.onNodeWithText("Scan pairing code").assertDoesNotExist()
        compose.onNodeWithText("Connect").assertIsNotEnabled()
        captureTvScreen("tv-pairing")
    }

    @Test fun postersNavigateAndActivateWithDpad() {
        val first = FocusRequester()
        var opened: String? = null
        compose.setContent {
            TestTheme {
                Row {
                    Poster("First title", null, modifier = Modifier.focusRequester(first)) { opened = "first" }
                    Poster("Second title", null) { opened = "second" }
                }
            }
        }
        compose.runOnIdle { first.requestFocus() }
        compose.onNodeWithText("First title").assertIsFocused().performKeyInput { pressKey(Key.DirectionRight) }
        compose.onNodeWithText("Second title").assertIsFocused().performKeyInput { pressKey(Key.DirectionCenter) }
        compose.runOnIdle { assertEquals("second", opened) }
        captureTvScreen("tv-posters")
    }

    @Test fun tvPickerContainsFocusAndBackReturnsToTrigger() {
        val trigger = FocusRequester()
        compose.setContent {
            TestTheme {
                val open = remember { mutableStateOf(false) }
                Button(onClick = { open.value = true }, modifier = Modifier.focusRequester(trigger).tvFocusIndicator()) { Text("Open picker") }
                if (open.value) AdaptiveSheet(onDismissRequest = { open.value = false }) {
                    Button(onClick = {}, modifier = Modifier.tvFocusIndicator()) { Text("Only choice") }
                }
            }
        }
        compose.runOnIdle { trigger.requestFocus() }
        compose.onNodeWithText("Open picker").performKeyInput { pressKey(Key.DirectionCenter) }
        compose.onNodeWithText("Only choice").assertIsFocused().performKeyInput { pressKey(Key.DirectionLeft) }
        compose.onNodeWithText("Only choice").assertIsFocused()
        captureTvScreen("tv-picker")
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Only choice").fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithText("Only choice").assertDoesNotExist()
        compose.onNodeWithText("Open picker").assertIsFocused()
    }
}
