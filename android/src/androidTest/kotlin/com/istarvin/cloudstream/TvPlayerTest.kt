@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package com.istarvin.cloudstream

import androidx.annotation.OptIn
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.pressKey
import androidx.lifecycle.ViewModelProvider
import androidx.media3.exoplayer.source.SilenceMediaSource
import androidx.test.platform.app.InstrumentationRegistry
import com.istarvin.cloudstream.data.PlayerPrefs
import com.istarvin.cloudstream.player.PlayerScreen
import com.istarvin.cloudstream.player.PlayerViewModel
import com.istarvin.cloudstream.player.Session
import com.istarvin.cloudstream.ui.AppTheme
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class TvPlayerTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun remoteCanSeekRevealControlsAndClosePlaybackPicker() {
        lateinit var vm: PlayerViewModel
        compose.runOnUiThread {
            vm = ViewModelProvider(compose.activity)[PlayerViewModel::class.java]
            // Local silent media exercises the real player without a server or external stream.
            @Suppress("UNCHECKED_CAST")
            (vm.session as MutableStateFlow<Session>).value = Session(status = "playing")
            vm.player.setMediaSource(SilenceMediaSource.Factory().setDurationUs(60_000_000L).createMediaSource())
            vm.player.prepare()
        }
        compose.setContent { AppTheme { PlayerScreen(vm, PlayerPrefs(), false, {}, {}) } }
        compose.waitUntil(5_000) { compose.onAllNodesWithContentDescription("Play").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("Play").assertIsFocused()
        compose.onNodeWithText("Lock").assertDoesNotExist()
        captureTvScreen("tv-player")

        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        compose.waitUntil(5_000) { compose.onAllNodesWithContentDescription("Play").fetchSemanticsNodes().isEmpty() }
        compose.onRoot().performKeyInput { pressKey(Key.DirectionRight) }
        compose.runOnIdle { assertEquals(10_000L, vm.player.currentPosition) }
        compose.onRoot().performKeyInput { pressKey(Key.DirectionCenter) }
        compose.onNodeWithContentDescription("Play").assertIsFocused()
        compose.runOnIdle { assertEquals(false, vm.player.playWhenReady) }

        compose.onNodeWithText("Speed").performSemanticsAction(SemanticsActions.RequestFocus)
        compose.onNodeWithText("Speed").performKeyInput { pressKey(Key.DirectionCenter) }
        compose.onNodeWithText("0.25×").assertIsFocused()
        captureTvScreen("tv-player-speed")
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        compose.waitUntil(5_000) { compose.onAllNodesWithContentDescription("Play").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("Play").assertIsFocused()
    }
}
