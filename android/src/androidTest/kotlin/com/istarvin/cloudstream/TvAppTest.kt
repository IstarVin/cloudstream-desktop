package com.istarvin.cloudstream

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.pressKey
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.istarvin.cloudstream.data.ServerConfig
import com.istarvin.cloudstream.ui.MainActivity
import org.junit.Rule
import org.junit.Test

class TvAppTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun railNavigatesWithRemoteWhenDesktopIsOffline() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.app
        val previous = app.api.server
        // A saved pairing remains navigable when its desktop cannot be reached.
        app.api.server = ServerConfig("http://127.0.0.1:1", "offline-ui-test")
        try {
            ActivityScenario.launch(MainActivity::class.java).use {
                compose.onNode(hasText("Home") and hasClickAction()).assertIsFocused().performKeyInput { pressKey(Key.DirectionDown) }
                compose.onNode(hasText("Search") and hasClickAction()).assertIsFocused().performKeyInput { pressKey(Key.DirectionCenter) }
                compose.onNodeWithText("Search movies, series, anime…").assertExists()
                compose.onNode(hasText("Search") and hasClickAction()).performKeyInput { pressKey(Key.DirectionDown) }
                compose.onNode(hasText("Library") and hasClickAction()).assertIsFocused().performKeyInput { pressKey(Key.DirectionDown) }
                compose.onNode(hasText("Settings") and hasClickAction()).assertIsFocused().performKeyInput { pressKey(Key.DirectionCenter) }
                compose.onNode(hasText("Plugins") and hasClickAction()).assertExists()
                captureTvScreen("tv-settings")
                compose.onNode(hasText("Settings") and hasClickAction()).performKeyInput { pressKey(Key.DirectionRight) }
                captureTvScreen("tv-settings-content-focus")
                compose.onNode(hasText("Playback") and hasClickAction()).performSemanticsAction(SemanticsActions.RequestFocus)
                compose.onNode(hasText("Playback") and hasClickAction()).performKeyInput { pressKey(Key.DirectionCenter) }
                compose.onNodeWithTag("settings-content")
                    .performScrollToNode(hasText("Remote seek interval"))
                compose.onNodeWithText("Remote seek interval").assertExists()
                compose.onNodeWithText("Swipe to seek").assertDoesNotExist()
            }
        } finally {
            app.api.server = previous
        }
    }
}
