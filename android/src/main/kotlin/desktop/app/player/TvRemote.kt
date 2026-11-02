package desktop.app.player

import android.view.KeyEvent

internal enum class RemoteAction { ShowControls, TogglePause, Play, Pause, SeekBack, SeekForward, Previous, Next }

/** Visible controls keep normal spatial D-pad navigation; hidden controls provide playback shortcuts. */
internal fun remoteAction(keyCode: Int, controlsVisible: Boolean, panelOpen: Boolean): RemoteAction? {
    if (panelOpen) return null
    return when (keyCode) {
        KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_SPACE -> RemoteAction.TogglePause
        KeyEvent.KEYCODE_MEDIA_PLAY -> RemoteAction.Play
        KeyEvent.KEYCODE_MEDIA_PAUSE -> RemoteAction.Pause
        KeyEvent.KEYCODE_MEDIA_REWIND -> RemoteAction.SeekBack
        KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> RemoteAction.SeekForward
        KeyEvent.KEYCODE_MEDIA_PREVIOUS -> RemoteAction.Previous
        KeyEvent.KEYCODE_MEDIA_NEXT -> RemoteAction.Next
        KeyEvent.KEYCODE_DPAD_LEFT -> if (!controlsVisible) RemoteAction.SeekBack else null
        KeyEvent.KEYCODE_DPAD_RIGHT -> if (!controlsVisible) RemoteAction.SeekForward else null
        KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
        KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> if (!controlsVisible) RemoteAction.ShowControls else null
        else -> null
    }
}

internal fun remoteSeekPosition(position: Long, duration: Long, offset: Long): Long =
    (position + offset).coerceIn(0L, duration.takeIf { it > 0 } ?: Long.MAX_VALUE)
