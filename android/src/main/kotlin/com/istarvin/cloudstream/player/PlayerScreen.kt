@file:OptIn(UnstableApi::class)

package com.istarvin.cloudstream.player

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import android.app.Activity
import android.content.Context
import android.graphics.Color as AndroidColor
import android.media.AudioManager
import android.provider.Settings as SystemSettings
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.focusable
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.AspectRatio
import androidx.compose.material.icons.rounded.BrightnessMedium
import androidx.compose.material.icons.rounded.FastForward
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.LockOpen
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.automirrored.rounded.PlaylistPlay
import androidx.compose.material.icons.rounded.Replay
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.Monitor
import androidx.compose.material.icons.rounded.ClosedCaption
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.C
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.CaptionStyleCompat
import com.istarvin.cloudstream.data.PlayerPrefs
import com.istarvin.cloudstream.ui.TabularNumbers
import com.istarvin.cloudstream.ui.MoveToDeviceButton
import com.istarvin.cloudstream.ui.Palette
import com.istarvin.cloudstream.ui.LocalTv
import com.istarvin.cloudstream.ui.tvSliderNavigation
import com.istarvin.cloudstream.ui.tvFocusIndicator
import com.istarvin.cloudstream.ui.formatTime
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sign

private val RESIZE_MODES = listOf(
    AspectRatioFrameLayout.RESIZE_MODE_FIT to "Fit",
    AspectRatioFrameLayout.RESIZE_MODE_FILL to "Stretch",
    AspectRatioFrameLayout.RESIZE_MODE_ZOOM to "Zoom",
)

/** Which sheet is open over the video. */
enum class Sheet { Sources, Subtitles, Episodes, Speed, VideoTracks }

@Composable
fun PlayerScreen(vm: PlayerViewModel, prefs: PlayerPrefs, inPip: Boolean, onResizeMode: (Int) -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    val activity = context as Activity
    val haptics = LocalHapticFeedback.current
    val player = vm.player
    val tv = LocalTv.current
    val windowFocused = LocalWindowInfo.current.isWindowFocused
    val remoteFocus = remember { FocusRequester() }
    val playFocus = remember { FocusRequester() }
    val errorFocus = remember { FocusRequester() }
    var consumedRemoteKey by remember { mutableStateOf<Int?>(null) }
    val s by vm.session.collectAsState()

    var controls by remember { mutableStateOf(true) }
    var locked by remember { mutableStateOf(false) }
    var sheet by remember { mutableStateOf<Sheet?>(null) }
    var resizeIndex by remember(prefs.resizeMode) { mutableIntStateOf(prefs.resizeMode.coerceIn(0, 2)) }
    var lastInteraction by remember { mutableLongStateOf(System.currentTimeMillis()) }

    // Polled player state for the seekbar and buttons.
    var position by remember { mutableLongStateOf(0L) }
    var duration by remember { mutableLongStateOf(0L) }
    var buffered by remember { mutableLongStateOf(0L) }
    var playing by remember { mutableStateOf(false) }
    var buffering by remember { mutableStateOf(false) }
    var speed by remember { mutableFloatStateOf(1f) }

    // Gesture feedback.
    var holding by remember { mutableStateOf(false) }
    var scrubTarget by remember { mutableStateOf<Long?>(null) }
    var scrubFrom by remember { mutableLongStateOf(0L) }
    var tapSeek by remember { mutableStateOf<Pair<Int, Int>?>(null) } // side to accumulated seconds
    var tapSeekAt by remember { mutableLongStateOf(0L) }
    var level by remember { mutableStateOf<Pair<Boolean, Float>?>(null) } // brightness? to level
    var pinch by remember { mutableFloatStateOf(1f) }

    LaunchedEffect(player) {
        while (true) {
            position = player.currentPosition
            duration = player.duration.takeIf { it != C.TIME_UNSET } ?: 0L
            buffered = player.bufferedPosition
            playing = player.isPlaying
            buffering = player.playbackState == androidx.media3.common.Player.STATE_BUFFERING
            speed = player.playbackParameters.speed
            delay(200)
        }
    }
    // Controls hide themselves while playing.
    LaunchedEffect(controls, lastInteraction, playing, sheet, scrubTarget) {
        if (controls && playing && sheet == null && scrubTarget == null) {
            delay(3500)
            controls = false
        }
    }
    LaunchedEffect(tapSeekAt) {
        delay(700)
        tapSeek = null
    }
    LaunchedEffect(s.pickSource) { if (s.pickSource) sheet = Sheet.Sources }
    LaunchedEffect(prefs.subtitleScale) { vm.engine.setAssScale(prefs.subtitleScale) }
    LaunchedEffect(tv, windowFocused, controls, sheet, s.status, buffering) {
        if (tv && windowFocused && sheet == null) {
            // The video surface owns input while controls are hidden; revealing them starts at Play.
            androidx.compose.runtime.withFrameNanos { }
            if (s.status == "error") errorFocus.requestFocus()
            else if (controls && !buffering && s.status !in listOf("resolving", "loading")) playFocus.requestFocus()
            else remoteFocus.requestFocus()
        }
    }
    BackHandler(enabled = tv && sheet == null && controls) { controls = false }

    val audio = remember { context.getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    val maxVolume = remember { audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).toFloat() }

    val scrubber = remember(player) { Scrubber(player) }
    DisposableEffect(scrubber) {
        // Leaving mid-scrub (stop, rotation) must not leave the player muted and paused.
        onDispose { scrubTarget?.let(scrubber::end) }
    }

    val handler = remember(player, prefs) {
        object : GestureHandler {
            var holdFrom = 1f
            var levelValue = 0f
            var brightness = false

            override fun onTap() {
                controls = !controls
                lastInteraction = System.currentTimeMillis()
            }

            override fun onMultiTap(side: Int) {
                val step = prefs.seekStep
                val prev = tapSeek?.takeIf { it.first == side }?.second ?: 0
                tapSeek = side to prev + step
                tapSeekAt = System.currentTimeMillis()
                val target = (player.currentPosition + side * step * 1000L).coerceIn(0L, player.duration.coerceAtLeast(0L))
                player.seekTo(target)
            }

            override fun onHoldStart() {
                if (!player.isPlaying) return
                holdFrom = player.playbackParameters.speed
                player.playbackParameters = PlaybackParameters(prefs.holdSpeed)
                holding = true
                controls = false
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
            }

            override fun onHoldEnd() {
                if (!holding) return
                player.playbackParameters = PlaybackParameters(holdFrom)
                holding = false
            }

            override fun onSeekStart() {
                if (!scrubber.begin()) return
                scrubFrom = player.currentPosition
                scrubTarget = scrubFrom
            }

            override fun onSeek(fraction: Float) {
                if (scrubTarget == null) return
                // A seek per touch event: scrubbing mode makes that cheap, and the picture follows the finger.
                val target = scrubPosition(scrubFrom, fraction, player.duration)
                scrubTarget = target
                scrubber.seek(target)
            }

            override fun onSeekEnd() {
                val target = scrubTarget ?: return
                scrubber.end(target)
                scrubTarget = null
            }

            override fun onVerticalStart(left: Boolean) {
                brightness = left
                levelValue = if (left) {
                    activity.window.attributes.screenBrightness.takeIf { it >= 0 }
                        ?: (SystemSettings.System.getInt(context.contentResolver, SystemSettings.System.SCREEN_BRIGHTNESS, 128) / 255f)
                } else audio.getStreamVolume(AudioManager.STREAM_MUSIC) / maxVolume
                level = left to levelValue
            }

            override fun onVertical(delta: Float) {
                levelValue = (levelValue + delta).coerceIn(0f, 1f)
                if (brightness) {
                    activity.window.attributes = activity.window.attributes.apply { screenBrightness = levelValue.coerceAtLeast(0.01f) }
                } else {
                    audio.setStreamVolume(AudioManager.STREAM_MUSIC, (levelValue * maxVolume).toInt(), 0)
                }
                level = brightness to levelValue
            }

            override fun onVerticalEnd() {
                level = null
            }

            override fun onPinch(scale: Float, done: Boolean) {
                if (!done) {
                    pinch = scale.coerceIn(0.7f, 2f)
                    return
                }
                pinch = 1f
                when {
                    scale > 1.12f -> resizeIndex = 2
                    scale < 0.9f -> resizeIndex = 0
                    else -> return
                }
                onResizeMode(resizeIndex)
            }
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)
        .onPreviewKeyEvent { event ->
            if (!tv || inPip) return@onPreviewKeyEvent false
            if (event.type == KeyEventType.KeyUp && consumedRemoteKey == event.nativeKeyEvent.keyCode) {
                consumedRemoteKey = null
                return@onPreviewKeyEvent true
            }
            if (event.type == KeyEventType.KeyDown) lastInteraction = System.currentTimeMillis()
            val action = remoteAction(event.nativeKeyEvent.keyCode, controls, sheet != null)
                ?: return@onPreviewKeyEvent false
            if (event.type == KeyEventType.KeyDown) {
                consumedRemoteKey = event.nativeKeyEvent.keyCode
                when (action) {
                    RemoteAction.ShowControls -> controls = true
                    RemoteAction.TogglePause -> if (player.isPlaying) player.pause() else player.play()
                    RemoteAction.Play -> player.play()
                    RemoteAction.Pause -> player.pause()
                    RemoteAction.SeekBack, RemoteAction.SeekForward -> {
                        val offset = prefs.seekStep * 1000L * if (action == RemoteAction.SeekBack) -1 else 1
                        player.seekTo(remoteSeekPosition(player.currentPosition, duration, offset))
                    }
                    RemoteAction.Previous -> vm.prev()
                    RemoteAction.Next -> vm.next()
                }
            }
            true
        }
        .focusRequester(remoteFocus)
        .focusable(tv && (!controls || buffering || s.status in listOf("resolving", "loading")))) {
        AndroidView(
            factory = { ctx ->
                ViewportPlayerView(ctx).apply {
                    useController = false
                    isFocusable = false
                    descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
                    layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                    setShutterBackgroundColor(AndroidColor.BLACK)
                    setKeepContentOnPlayerReset(true)
                    this.player = player
                    subtitleView?.let { vm.engine.attach(it) }
                }
            },
            update = { view ->
                view.resizeMode = RESIZE_MODES[resizeIndex].first
                view.setVideoScale(pinch)
                view.subtitleView?.apply {
                    setStyle(
                        CaptionStyleCompat(
                            AndroidColor.WHITE,
                            if (prefs.subtitleBackground) AndroidColor.argb(170, 0, 0, 0) else AndroidColor.TRANSPARENT,
                            AndroidColor.TRANSPARENT,
                            if (prefs.subtitleBackground) CaptionStyleCompat.EDGE_TYPE_NONE else CaptionStyleCompat.EDGE_TYPE_OUTLINE,
                            AndroidColor.BLACK,
                            null,
                        ),
                    )
                    setFractionalTextSize(SubtitleViewDefaults.TEXT_SIZE * prefs.subtitleScale, true)
                    // Clear of the control bar while it shows.
                    view.setSubtitleBottomPaddingFraction(if (controls && !locked && !inPip) 0.2f else prefs.subtitleBottom)
                    setApplyEmbeddedStyles(true)
                }
            },
            modifier = Modifier.fillMaxSize(),
        )

        if (!inPip && !tv) {
            Box(
                Modifier.fillMaxSize().playerGestures(handler, locked, prefs.swipeSeek) { left ->
                    if (left) prefs.brightnessGesture else prefs.volumeGesture
                },
            )
        }

        // Loading, resolving and errors.
        val busy = s.status == "resolving" || s.status == "loading" || buffering
        val controlsShown = controls && !locked && !inPip
        if (busy && scrubTarget == null) {
            Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                // With the controls up the spinner takes the play button's place (see Controls); the message sits below the row.
                if (!controlsShown) CircularProgressIndicator(color = Palette.accent)
                if (!inPip && s.status != "playing") s.message?.let {
                    Text(
                        it, color = Color.White,
                        modifier = Modifier.padding(top = if (controlsShown) 120.dp else 12.dp)
                            .background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(8.dp)).padding(horizontal = 12.dp, vertical = 6.dp),
                    )
                }
            }
        }
        if (s.status == "error" && !inPip) {
            Column(
                Modifier.align(Alignment.Center).background(Color.Black.copy(alpha = 0.75f), RoundedCornerShape(12.dp)).padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(s.message ?: "Playback failed", color = Color.White)
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(onClick = { sheet = Sheet.Sources }, modifier = Modifier.tvFocusIndicator()) { Text("Sources") }
                    OutlinedButton(onClick = { vm.retry() }, modifier = Modifier.focusRequester(errorFocus).tvFocusIndicator()) { Text("Retry") }
                }
            }
        }

        if (!inPip) {
            Feedback(holding, prefs.holdSpeed, scrubTarget, scrubFrom, duration, tapSeek, level)

            AnimatedVisibility(controls && !locked, enter = fadeIn(), exit = if (holding) ExitTransition.None else fadeOut()) {
                Controls(
                    vm, s,
                    busy = busy && scrubTarget == null,
                    position = scrubTarget ?: position, duration = duration, buffered = buffered, playing = playing, speed = speed,
                    resizeLabel = RESIZE_MODES[resizeIndex].second,
                    onBack = onBack,
                    playFocus = playFocus,
                    seekStep = prefs.seekStep,
                    onSheet = { sheet = it; lastInteraction = System.currentTimeMillis() },
                    onLock = { locked = true; controls = true },
                    onResize = {
                        resizeIndex = (resizeIndex + 1) % RESIZE_MODES.size
                        onResizeMode(resizeIndex)
                    },
                    onInteract = { lastInteraction = System.currentTimeMillis() },
                    onScrub = { target, done ->
                        if (!done) {
                            val started = scrubTarget != null || scrubber.begin().also { if (it) scrubFrom = player.currentPosition }
                            if (started) {
                                scrubTarget = target
                                scrubber.seek(target)
                            }
                        } else scrubTarget?.let {
                            scrubber.end(it)
                            scrubTarget = null
                        }
                        lastInteraction = System.currentTimeMillis()
                    },
                )
            }
            if (locked && controls) {
                IconButton(
                    onClick = { locked = false },
                    modifier = Modifier.align(Alignment.CenterStart).safeDrawingPadding().padding(16.dp).background(Color.Black.copy(alpha = 0.5f), CircleShape).tvFocusIndicator(),
                ) { Icon(Icons.Rounded.LockOpen, "Unlock", tint = Color.White) }
            }

            // Skip opening / ending, shown even without the controls.
            s.skip?.let { range ->
                Button(
                    onClick = { vm.skip(range) },
                    modifier = Modifier.align(Alignment.BottomEnd).safeDrawingPadding().padding(end = 24.dp, bottom = 96.dp).tvFocusIndicator(),
                ) { Text("Skip ${range.label.lowercase()}") }
            }
            s.upNext?.let { n ->
                Column(
                    Modifier.align(Alignment.BottomEnd).safeDrawingPadding().padding(24.dp)
                        .background(Color.Black.copy(alpha = 0.8f), RoundedCornerShape(12.dp)).padding(16.dp),
                ) {
                    Text("Up next in $n", color = Color.White, fontWeight = FontWeight.SemiBold)
                    s.item?.let { it.episodes.getOrNull(it.index + 1) }?.let { e ->
                        Text(com.istarvin.cloudstream.ui.episodeTitle(e, s.item!!.index + 1), color = Palette.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                        Button(onClick = { vm.next() }, modifier = Modifier.tvFocusIndicator()) { Text("Play now") }
                        TextButton(onClick = { vm.cancelUpNext() }, modifier = Modifier.tvFocusIndicator()) { Text("Cancel") }
                    }
                }
            }

            when (sheet) {
                Sheet.Sources -> SourcesPanel(vm, s, onDismiss = { sheet = null; vm.dismissPicker() })
                Sheet.Subtitles -> TracksPanel(vm, s, onDismiss = { sheet = null })
                Sheet.Episodes -> EpisodesPanel(vm, s, onDismiss = { sheet = null })
                Sheet.Speed -> SpeedPanel(player, onDismiss = { sheet = null })
                Sheet.VideoTracks -> VideoTracksPanel(vm, onDismiss = { sheet = null })
                null -> {}
            }
        }
    }
}

/** Media3's default caption size (fraction of the view height). */
private object SubtitleViewDefaults {
    const val TEXT_SIZE = 0.0533f
}

/**
 * Where a horizontal drag of [fraction] of the screen width lands. The curve is gentle near the start, so short
 * drags move a few seconds and a full-width drag moves up to three minutes (or the whole of a short video).
 */
fun scrubPosition(from: Long, fraction: Float, duration: Long): Long {
    val span = duration.coerceAtMost(180_000L).coerceAtLeast(30_000L)
    val offset = sign(fraction) * abs(fraction).coerceAtMost(1.2f).pow(1.6f) * span
    return (from + offset.toLong()).coerceIn(0L, duration.coerceAtLeast(0L))
}

@Composable
private fun Feedback(
    holding: Boolean,
    holdSpeed: Float,
    scrubTarget: Long?,
    scrubFrom: Long,
    duration: Long,
    tapSeek: Pair<Int, Int>?,
    level: Pair<Boolean, Float>?,
) {
    Box(Modifier.fillMaxSize().safeDrawingPadding()) {
        if (holding) {
            Row(
                Modifier.align(Alignment.TopCenter).padding(top = 24.dp).background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(20.dp))
                    .padding(horizontal = 14.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("${formatSpeed(holdSpeed)}×", color = Color.White, fontWeight = FontWeight.SemiBold)
                Icon(Icons.Rounded.FastForward, null, tint = Color.White, modifier = Modifier.size(18.dp))
            }
        }
        if (scrubTarget != null) {
            val delta = (scrubTarget - scrubFrom) / 1000.0
            Column(
                Modifier.align(Alignment.Center).background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(12.dp)).padding(horizontal = 20.dp, vertical = 10.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("${formatTime(scrubTarget / 1000.0)} / ${formatTime(duration / 1000.0)}", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                Text((if (delta >= 0) "+" else "−") + formatTime(abs(delta)), color = Palette.accent)
            }
        }
        tapSeek?.let { (side, seconds) ->
            Box(
                Modifier.align(if (side < 0) Alignment.CenterStart else Alignment.CenterEnd).padding(horizontal = 48.dp)
                    .size(96.dp).background(Color.White.copy(alpha = 0.18f), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Text((if (side < 0) "−" else "+") + "${seconds}s", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp)
            }
        }
        level?.let { (brightness, value) ->
            Column(
                Modifier.align(if (brightness) Alignment.CenterEnd else Alignment.CenterStart).padding(horizontal = 40.dp)
                    .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(20.dp)).padding(10.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(Modifier.width(6.dp).height(120.dp).background(Color.White.copy(alpha = 0.25f), RoundedCornerShape(3.dp)), contentAlignment = Alignment.BottomCenter) {
                    Box(Modifier.width(6.dp).fillMaxHeight(value).background(Palette.accent, RoundedCornerShape(3.dp)))
                }
                Spacer(Modifier.height(8.dp))
                Icon(if (brightness) Icons.Rounded.BrightnessMedium else Icons.AutoMirrored.Rounded.VolumeUp, null, tint = Color.White, modifier = Modifier.size(20.dp))
            }
        }
    }
}

fun formatSpeed(speed: Float) = if (speed % 1f == 0f) "%.0f".format(speed) else "%.2f".format(speed).trimEnd('0')

@Composable
private fun Controls(
    vm: PlayerViewModel,
    s: Session,
    busy: Boolean,
    position: Long,
    duration: Long,
    buffered: Long,
    playing: Boolean,
    speed: Float,
    resizeLabel: String,
    onBack: () -> Unit,
    playFocus: FocusRequester,
    seekStep: Int,
    onSheet: (Sheet) -> Unit,
    onLock: () -> Unit,
    onResize: () -> Unit,
    onInteract: () -> Unit,
    onScrub: (Long, Boolean) -> Unit,
) {
    val player = vm.player
    val item = s.item
    var tracks by remember(player) { mutableStateOf(player.currentTracks) }
    var videoSize by remember(player) { mutableStateOf(player.videoSize) }
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onTracksChanged(currentTracks: Tracks) { tracks = currentTracks }
            override fun onVideoSizeChanged(size: androidx.media3.common.VideoSize) { videoSize = size }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }
    val hasMultipleVideoTracks = tracks.groups.filter { it.type == C.TRACK_TYPE_VIDEO }
        .sumOf { group -> (0 until group.length).count { group.isTrackSupported(it) } } > 1
    Box(
        Modifier.fillMaxSize().background(
            Brush.verticalGradient(0f to Color.Black.copy(alpha = 0.65f), 0.22f to Color.Transparent, 0.62f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.8f)),
        ),
    ) {
        // Top: back, title, source.
        Row(
            Modifier.fillMaxWidth().safeDrawingPadding().padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack, modifier = Modifier.tvFocusIndicator()) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back", tint = Color.White) }
            Column(Modifier.weight(1f).padding(horizontal = 6.dp)) {
                Text(item?.details?.name.orEmpty(), color = Color.White, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val sub = listOfNotNull(
                    item?.displayLabel,
                    s.current?.let { "${it.source} ${it.qualityLabel}" },
                    videoSize.takeIf { it.width > 0 && it.height > 0 }?.let { "${it.width}×${it.height}" },
                ).joinToString(" · ")
                if (sub.isNotEmpty()) Text(sub, color = Color.White.copy(alpha = 0.8f), style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            MoveToDeviceButton(vm, onMoved = onBack, onInteract = onInteract)
            TextButton(
                onClick = { onInteract(); onResize() },
                modifier = Modifier.heightIn(min = 48.dp).tvFocusIndicator(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            ) {
                Icon(Icons.Rounded.AspectRatio, null, tint = Color.White, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text(resizeLabel, color = Color.White)
            }
        }

        // Center: previous, play/pause, next.
        if (s.status != "error") Row(Modifier.align(Alignment.Center), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(48.dp)) {
            RoundButton(Icons.Rounded.SkipPrevious, "Previous episode", enabled = item?.hasPrev == true, size = 56) { onInteract(); vm.prev() }
            if (busy) {
                Box(Modifier.size(72.dp).semantics { contentDescription = "Loading" }, contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = Palette.accent)
                }
            } else RoundButton(
                if (s.status == "ended" && s.upNext == null) Icons.Rounded.Replay else if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                if (s.status == "ended") "Replay" else if (playing) "Pause" else "Play",
                size = 72,
                prominent = true,
                modifier = Modifier.focusRequester(playFocus),
            ) {
                onInteract()
                when {
                    s.status == "ended" -> { player.seekTo(0); player.play() }
                    player.isPlaying -> player.pause()
                    else -> player.play()
                }
            }
            RoundButton(Icons.Rounded.SkipNext, "Next episode", enabled = item?.hasNext == true, size = 56) { onInteract(); vm.next() }
        }

        // The timeline gets the full width; stable time labels sit above it.
        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().safeDrawingPadding()
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(
                    formatTime(position / 1000.0), color = Color.White,
                    style = MaterialTheme.typography.labelMedium.merge(TabularNumbers),
                )
                Text(
                    "−" + formatTime((duration - position).coerceAtLeast(0) / 1000.0),
                    color = Color.White.copy(alpha = 0.8f),
                    style = MaterialTheme.typography.labelMedium.merge(TabularNumbers),
                )
            }
            SeekBar(position, duration, buffered, s.skips, onScrub, seekStep, Modifier.fillMaxWidth())
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                // Match the web toolbar: native utility at the left, playback settings at the right.
                // Narrow windows use named icon targets; larger windows show inline labels.
                val showLabels = maxWidth >= 700.dp || LocalTv.current
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    if (!LocalTv.current) {
                        IconButton(onClick = onLock, modifier = Modifier.tvFocusIndicator()) {
                            Icon(Icons.Rounded.Lock, "Lock controls", tint = Color.White, modifier = Modifier.size(20.dp))
                        }
                    }
                    Row(
                        Modifier.weight(1f).horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        ActionButton(Icons.Rounded.Speed, if (speed == 1f) "Speed" else "${formatSpeed(speed)}×", showLabel = showLabels) { onSheet(Sheet.Speed) }
                        ActionButton(Icons.Rounded.Layers, "Sources", showLabel = showLabels) { onSheet(Sheet.Sources) }
                        ActionButton(Icons.Rounded.ClosedCaption, "Audio & subs", showLabel = showLabels,
                            modifier = Modifier.semantics { contentDescription = "Subtitles & audio" }) { onSheet(Sheet.Subtitles) }
                        if (hasMultipleVideoTracks) {
                            ActionButton(Icons.Rounded.Monitor, "Quality", showLabel = showLabels) { onSheet(Sheet.VideoTracks) }
                        }
                        if (item != null && item.episodes.isNotEmpty()) {
                            ActionButton(Icons.AutoMirrored.Rounded.PlaylistPlay, "Episodes", showLabel = showLabels) { onSheet(Sheet.Episodes) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RoundButton(
    icon: ImageVector,
    label: String,
    size: Int,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    prominent: Boolean = false,
    onClick: () -> Unit,
) {
    IconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.size(size.dp).tvFocusIndicator().background(
            if (prominent) Color.Black.copy(alpha = 0.35f) else Color.Transparent, CircleShape,
        ),
    ) {
        Icon(
            icon, label,
            tint = Color.White.copy(alpha = if (enabled) 1f else 0.35f),
            modifier = Modifier.size(if (prominent) 36.dp else 24.dp),
        )
    }
}

@Composable
private fun ActionButton(icon: ImageVector, label: String, modifier: Modifier = Modifier, showLabel: Boolean = true, onClick: () -> Unit) {
    TextButton(
        onClick = onClick,
        modifier = modifier.heightIn(min = 48.dp).tvFocusIndicator(),
        shape = CircleShape,
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Icon(icon, if (showLabel) null else label, tint = Color.White, modifier = Modifier.size(20.dp))
            if (showLabel) Text(label, color = Color.White, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** The seekbar: drags scrub live (see [Scrubber]) and land exactly on release; AniSkip ranges are marked on it. */
@Composable
private fun SeekBar(position: Long, duration: Long, buffered: Long, skips: List<desktop.player.SkipRange>, onScrub: (Long, Boolean) -> Unit, seekStep: Int, modifier: Modifier) {
    val max = duration.coerceAtLeast(1L).toFloat()
    val tv = LocalTv.current
    // Keep Material's touch, keyboard and accessibility behavior with a quieter video timeline.
    Slider(
        value = if (duration > 0) position.toFloat().coerceIn(0f, max) else 0f,
        onValueChange = { onScrub(it.toLong(), false) },
        onValueChangeFinished = { onScrub(position, true) },
        valueRange = 0f..max,
        enabled = duration > 0,
        modifier = modifier.tvSliderNavigation().heightIn(min = 48.dp).semantics { contentDescription = "Playback position" }
            .onPreviewKeyEvent { event ->
                if (!tv || duration <= 0 || event.key !in listOf(Key.DirectionLeft, Key.DirectionRight)) return@onPreviewKeyEvent false
                if (event.type == KeyEventType.KeyDown) {
                    val offset = seekStep * 1000L * if (event.key == Key.DirectionLeft) -1 else 1
                    onScrub(remoteSeekPosition(position, duration, offset), false)
                } else if (event.type == KeyEventType.KeyUp) onScrub(position, true)
                true
            }.tvFocusIndicator(),
        thumb = {
            // Slider measures this slot at its touch-target height. Center the visible dot inside it.
            Box(Modifier.width(12.dp).heightIn(min = 48.dp), contentAlignment = Alignment.Center) {
                Box(Modifier.size(12.dp).background(if (duration > 0) Palette.accent else Color.White.copy(alpha = 0.4f), CircleShape))
            }
        },
        track = { state ->
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                Canvas(Modifier.fillMaxWidth().height(4.dp)) {
                    val center = size.height / 2
                    fun segment(start: Float, end: Float, color: Color) {
                        if (end > start) drawLine(
                            color, Offset(start * size.width, center), Offset(end * size.width, center),
                            strokeWidth = size.height, cap = StrokeCap.Round,
                        )
                    }
                    segment(0f, 1f, Color.White.copy(alpha = 0.25f))
                    segment(0f, if (duration > 0) (buffered / max).coerceIn(0f, 1f) else 0f, Color.White.copy(alpha = 0.45f))
                    segment(0f, (state.value / max).coerceIn(0f, 1f), Palette.accent)
                    skips.filter { duration > 0 }.forEach { range ->
                        segment(
                            (range.start * 1000 / max).toFloat().coerceIn(0f, 1f),
                            (range.end * 1000 / max).toFloat().coerceIn(0f, 1f),
                            Palette.good.copy(alpha = 0.85f),
                        )
                    }
                }
            }
        },
        colors = SliderDefaults.colors(thumbColor = Palette.accent, activeTrackColor = Palette.accent),
    )
}
