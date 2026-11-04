package com.istarvin.cloudstream.player

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TvRemoteTest {
    @Test fun `visible controls retain spatial navigation and button activation`() {
        listOf(KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_UP,
            KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER).forEach {
            assertNull(remoteAction(it, controlsVisible = true, panelOpen = false))
        }
    }

    @Test fun `hidden controls can be revealed or seeked with the remote`() {
        assertEquals(RemoteAction.SeekBack, remoteAction(KeyEvent.KEYCODE_DPAD_LEFT, false, false))
        assertEquals(RemoteAction.SeekForward, remoteAction(KeyEvent.KEYCODE_DPAD_RIGHT, false, false))
        listOf(KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_ENTER).forEach {
            assertEquals(RemoteAction.ShowControls, remoteAction(it, false, false))
        }
    }

    @Test fun `media transport keys work with or without the overlay`() {
        val keys = mapOf(
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE to RemoteAction.TogglePause,
            KeyEvent.KEYCODE_MEDIA_PLAY to RemoteAction.Play,
            KeyEvent.KEYCODE_MEDIA_PAUSE to RemoteAction.Pause,
            KeyEvent.KEYCODE_MEDIA_REWIND to RemoteAction.SeekBack,
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD to RemoteAction.SeekForward,
            KeyEvent.KEYCODE_MEDIA_PREVIOUS to RemoteAction.Previous,
            KeyEvent.KEYCODE_MEDIA_NEXT to RemoteAction.Next,
        )
        keys.forEach { (key, action) ->
            assertEquals(action, remoteAction(key, false, false))
            assertEquals(action, remoteAction(key, true, false))
            assertNull(remoteAction(key, false, panelOpen = true))
        }
        assertNull(remoteAction(KeyEvent.KEYCODE_DPAD_CENTER, false, panelOpen = true))
        assertNull(remoteAction(KeyEvent.KEYCODE_BACK, false, false))
        assertNull(remoteAction(KeyEvent.KEYCODE_VOLUME_UP, false, false))
    }

    @Test fun `remote seeks stop at the beginning and end and allow unknown duration`() {
        assertEquals(0L, remoteSeekPosition(5_000, 60_000, -10_000))
        assertEquals(60_000L, remoteSeekPosition(55_000, 60_000, 10_000))
        assertEquals(25_000L, remoteSeekPosition(15_000, 60_000, 10_000))
        assertEquals(25_000L, remoteSeekPosition(15_000, 0, 10_000))
    }
}
