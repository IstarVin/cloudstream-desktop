@file:OptIn(UnstableApi::class)

package desktop.app.player

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import android.app.PictureInPictureParams
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.util.Rational
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.material3.Surface
import androidx.compose.ui.graphics.Color
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.session.MediaSession
import desktop.api.Details
import desktop.app.app
import desktop.app.data.PlayerPrefs
import desktop.app.ui.AppTheme
import desktop.app.ui.ErrorBox
import desktop.app.ui.userMessage
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** Full-screen ExoPlayer for one title; finishing it returns to the title page. */
class PlayerActivity : ComponentActivity() {
    private val vm: PlayerViewModel by viewModels()
    private var session: MediaSession? = null
    private val inPip = mutableStateOf(false)
    private val loadError = mutableStateOf<String?>(null)
    private var prefs = PlayerPrefs()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        hideSystemBars()

        session = runCatching { MediaSession.Builder(this, vm.player).build() }.getOrNull()
        vm.player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) = updatePip()
            override fun onVideoSizeChanged(videoSize: VideoSize) = updatePip()
        })

        val provider = intent.getStringExtra(EXTRA_PROVIDER)!!
        val url = intent.getStringExtra(EXTRA_URL)!!
        val data = intent.getStringExtra(EXTRA_DATA)!!
        lifecycleScope.launch {
            prefs = app.prefs.player.first()
            val details = handoff?.takeIf { it.provider == provider && it.url == url }
                ?: runCatching { app.api.details(provider, url) }.getOrElse {
                    loadError.value = it.userMessage()
                    return@launch
                }
            handoff = null
            vm.start(details, data, null)
        }

        setContent {
            AppTheme {
                val p by app.prefs.player.collectAsState(prefs)
                val error by loadError
                if (error != null) {
                    Surface(Modifier.fillMaxSize(), color = Color.Black) { ErrorBox(error!!, onRetry = { recreate() }) }
                } else {
                    PlayerScreen(
                        vm, p, inPip.value,
                        onResizeMode = { mode -> lifecycleScope.launch { app.prefs.updatePlayer { it.copy(resizeMode = mode) } } },
                        onBack = { finish() },
                    )
                }
            }
        }
    }

    private fun hideSystemBars() {
        WindowInsetsControllerCompat(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    private fun pipParams(): PictureInPictureParams {
        val size = vm.player.videoSize
        val ratio = if (size.width > 0 && size.height > 0) {
            Rational(size.width, size.height).let { r ->
                // Android refuses ratios outside 1:2.39 .. 2.39:1.
                when {
                    r.toFloat() > 2.39f -> Rational(239, 100)
                    r.toFloat() < 1 / 2.39f -> Rational(100, 239)
                    else -> r
                }
            }
        } else Rational(16, 9)
        return PictureInPictureParams.Builder().setAspectRatio(ratio).apply {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                setAutoEnterEnabled(prefs.pictureInPicture && vm.player.isPlaying)
                setSeamlessResizeEnabled(true)
            }
        }.build()
    }

    private fun updatePip() {
        if (packageManager.hasSystemFeature("android.software.picture_in_picture")) runCatching { setPictureInPictureParams(pipParams()) }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        // Before Android 12 there's no auto-enter; go into PiP by hand when the user leaves mid-play.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S && prefs.pictureInPicture && vm.player.isPlaying) {
            runCatching { enterPictureInPictureMode(pipParams()) }
        }
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        inPip.value = isInPictureInPictureMode
    }

    override fun onStop() {
        super.onStop()
        vm.onStop()
        // In PiP the activity stops when the PiP window is dismissed; keep playing only while it's visible.
        if (!isInPictureInPictureMode) vm.player.pause()
    }

    override fun onDestroy() {
        session?.release()
        session = null
        super.onDestroy()
    }

    companion object {
        private const val EXTRA_PROVIDER = "provider"
        private const val EXTRA_URL = "url"
        private const val EXTRA_DATA = "data"

        /** Details of the title being opened; too big for an Intent with long episode lists, so passed in memory. */
        private var handoff: Details? = null

        fun start(context: Context, details: Details, data: String) {
            handoff = details
            context.startActivity(
                Intent(context, PlayerActivity::class.java)
                    .putExtra(EXTRA_PROVIDER, details.provider)
                    .putExtra(EXTRA_URL, details.url)
                    .putExtra(EXTRA_DATA, data),
            )
        }
    }
}
