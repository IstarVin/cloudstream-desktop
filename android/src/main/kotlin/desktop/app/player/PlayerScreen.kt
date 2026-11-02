@file:OptIn(UnstableApi::class)

package desktop.app.player

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import android.app.Activity
import android.content.Context
import android.graphics.Color as AndroidColor
import android.media.AudioManager
import android.provider.Settings as SystemSettings
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AspectRatio
import androidx.compose.material.icons.filled.BrightnessMedium
import androidx.compose.material.icons.filled.ClosedCaption
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import androidx.compose.material.icons.filled.Source
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.automirrored.filled.VolumeUp
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
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
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.PlayerView
import desktop.app.data.PlayerPrefs
import desktop.app.ui.Palette
import desktop.app.ui.formatTime
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
enum class Sheet { Sources, Subtitles, Episodes, Speed }

@Composable
fun PlayerScreen(vm: PlayerViewModel, prefs: PlayerPrefs, inPip: Boolean, onResizeMode: (Int) -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    val activity = context as Activity
    val haptics = LocalHapticFeedback.current
    val player = vm.player
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
    LaunchedEffect(controls, lastInteraction, playing, sheet) {
        if (controls && playing && sheet == null) {
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

    val audio = remember { context.getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    val maxVolume = remember { audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).toFloat() }

    val handler = remember(player, prefs) {
        object : GestureHandler {
            var wasPlaying = false
            var holdFrom = 1f
            var lastScrubSeek = 0L
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
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
            }

            override fun onHoldEnd() {
                if (!holding) return
                player.playbackParameters = PlaybackParameters(holdFrom)
                holding = false
            }

            override fun onSeekStart() {
                val d = player.duration
                if (d == C.TIME_UNSET || d <= 0 || !player.isCurrentMediaItemSeekable) return
                wasPlaying = player.playWhenReady
                player.playWhenReady = false
                player.setSeekParameters(SeekParameters.CLOSEST_SYNC)
                scrubFrom = player.currentPosition
                scrubTarget = scrubFrom
            }

            override fun onSeek(fraction: Float) {
                if (scrubTarget == null) return
                scrubTarget = scrubPosition(scrubFrom, fraction, player.duration)
                // Seek to the nearest keyframe as the finger moves, so the picture follows the scrub.
                val now = System.currentTimeMillis()
                if (now - lastScrubSeek > 120) {
                    lastScrubSeek = now
                    player.seekTo(scrubTarget!!)
                }
            }

            override fun onSeekEnd() {
                val target = scrubTarget ?: return
                player.setSeekParameters(SeekParameters.EXACT)
                player.seekTo(target)
                player.playWhenReady = wasPlaying
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

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    useController = false
                    layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                    setShutterBackgroundColor(AndroidColor.BLACK)
                    setKeepContentOnPlayerReset(true)
                    this.player = player
                    subtitleView?.let { vm.engine.attach(it) }
                }
            },
            update = { view ->
                view.resizeMode = RESIZE_MODES[resizeIndex].first
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
                    setFractionalTextSize(SubtitleViewDefaults.TEXT_SIZE * prefs.subtitleScale)
                    // Clear of the control bar while it shows.
                    setBottomPaddingFraction(if (controls && !locked && !inPip) 0.2f else prefs.subtitleBottom)
                    setApplyEmbeddedStyles(true)
                }
            },
            modifier = Modifier.fillMaxSize().graphicsLayer { scaleX = pinch; scaleY = pinch },
        )

        if (!inPip) {
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
                    Button(onClick = { sheet = Sheet.Sources }) { Text("Sources") }
                    OutlinedButton(onClick = { vm.retry() }) { Text("Retry") }
                }
            }
        }

        if (!inPip) {
            Feedback(holding, prefs.holdSpeed, scrubTarget, scrubFrom, duration, tapSeek, level)

            AnimatedVisibility(controls && !locked, enter = fadeIn(), exit = fadeOut()) {
                Controls(
                    vm, s,
                    busy = busy && scrubTarget == null,
                    position = scrubTarget ?: position, duration = duration, buffered = buffered, playing = playing, speed = speed,
                    resizeLabel = RESIZE_MODES[resizeIndex].second,
                    onBack = onBack,
                    onSheet = { sheet = it; lastInteraction = System.currentTimeMillis() },
                    onLock = { locked = true; controls = true },
                    onResize = {
                        resizeIndex = (resizeIndex + 1) % RESIZE_MODES.size
                        onResizeMode(resizeIndex)
                    },
                    onInteract = { lastInteraction = System.currentTimeMillis() },
                    onScrub = { target, done ->
                        if (!done) {
                            if (scrubTarget == null) {
                                scrubFrom = player.currentPosition
                                player.setSeekParameters(SeekParameters.CLOSEST_SYNC)
                            }
                            scrubTarget = target
                            player.seekTo(target)
                        } else {
                            player.setSeekParameters(SeekParameters.EXACT)
                            scrubTarget?.let(player::seekTo)
                            scrubTarget = null
                        }
                        lastInteraction = System.currentTimeMillis()
                    },
                )
            }
            if (locked && controls) {
                IconButton(
                    onClick = { locked = false },
                    modifier = Modifier.align(Alignment.CenterStart).safeDrawingPadding().padding(16.dp).background(Color.Black.copy(alpha = 0.5f), CircleShape),
                ) { Icon(Icons.Filled.LockOpen, "Unlock", tint = Color.White) }
            }

            // Skip opening / ending, shown even without the controls.
            s.skip?.let { range ->
                Button(
                    onClick = { vm.skip(range) },
                    modifier = Modifier.align(Alignment.BottomEnd).safeDrawingPadding().padding(end = 24.dp, bottom = 96.dp),
                ) { Text("Skip ${range.label.lowercase()}") }
            }
            s.upNext?.let { n ->
                Column(
                    Modifier.align(Alignment.BottomEnd).safeDrawingPadding().padding(24.dp)
                        .background(Color.Black.copy(alpha = 0.8f), RoundedCornerShape(12.dp)).padding(16.dp),
                ) {
                    Text("Up next in $n", color = Color.White, fontWeight = FontWeight.SemiBold)
                    s.item?.let { it.episodes.getOrNull(it.index + 1) }?.let { e ->
                        Text(desktop.app.ui.episodeTitle(e, s.item!!.index + 1), color = Palette.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                        Button(onClick = { vm.next() }) { Text("Play now") }
                        TextButton(onClick = { vm.cancelUpNext() }) { Text("Cancel") }
                    }
                }
            }

            when (sheet) {
                Sheet.Sources -> SourcesPanel(vm, s, onDismiss = { sheet = null; vm.dismissPicker() })
                Sheet.Subtitles -> TracksPanel(vm, s, onDismiss = { sheet = null })
                Sheet.Episodes -> EpisodesPanel(vm, s, onDismiss = { sheet = null })
                Sheet.Speed -> SpeedPanel(player, onDismiss = { sheet = null })
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
                Icon(Icons.Filled.FastForward, null, tint = Color.White, modifier = Modifier.size(18.dp))
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
                Modifier.align(if (brightness) Alignment.CenterStart else Alignment.CenterEnd).padding(horizontal = 40.dp)
                    .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(20.dp)).padding(10.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(Modifier.width(6.dp).height(120.dp).background(Color.White.copy(alpha = 0.25f), RoundedCornerShape(3.dp)), contentAlignment = Alignment.BottomCenter) {
                    Box(Modifier.width(6.dp).fillMaxHeight(value).background(Palette.accent, RoundedCornerShape(3.dp)))
                }
                Spacer(Modifier.height(8.dp))
                Icon(if (brightness) Icons.Filled.BrightnessMedium else Icons.AutoMirrored.Filled.VolumeUp, null, tint = Color.White, modifier = Modifier.size(20.dp))
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
    onSheet: (Sheet) -> Unit,
    onLock: () -> Unit,
    onResize: () -> Unit,
    onInteract: () -> Unit,
    onScrub: (Long, Boolean) -> Unit,
) {
    val player = vm.player
    val item = s.item
    Box(
        Modifier.fillMaxSize().background(
            Brush.verticalGradient(0f to Color.Black.copy(alpha = 0.7f), 0.25f to Color.Transparent, 0.7f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.8f)),
        ),
    ) {
        // Top: back, title, source.
        Row(Modifier.fillMaxWidth().safeDrawingPadding().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Color.White) }
            Column(Modifier.weight(1f)) {
                Text(item?.details?.name.orEmpty(), color = Color.White, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val sub = listOfNotNull(item?.displayLabel, s.current?.let { "${it.source} · ${it.qualityLabel}" }).joinToString("  ·  ")
                if (sub.isNotEmpty()) Text(sub, color = Palette.muted, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            TextButton(onClick = onResize) {
                Icon(Icons.Filled.AspectRatio, null, tint = Color.White)
                Text(" $resizeLabel", color = Color.White)
            }
        }

        // Center: previous, play/pause, next.
        Row(Modifier.align(Alignment.Center), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(40.dp)) {
            RoundButton(Icons.Filled.SkipPrevious, "Previous episode", enabled = item?.hasPrev == true, size = 48) { vm.prev() }
            if (busy) {
                Box(Modifier.size(72.dp).semantics { contentDescription = "Loading" }, contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = Palette.accent)
                }
            } else RoundButton(
                if (s.status == "ended" && s.upNext == null) Icons.Filled.Replay else if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                if (playing) "Pause" else "Play",
                size = 72,
            ) {
                onInteract()
                when {
                    s.status == "ended" -> { player.seekTo(0); player.play() }
                    player.isPlaying -> player.pause()
                    else -> player.play()
                }
            }
            RoundButton(Icons.Filled.SkipNext, "Next episode", enabled = item?.hasNext == true, size = 48) { vm.next() }
        }

        // Bottom: seekbar and actions.
        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().safeDrawingPadding().padding(horizontal = 16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(formatTime(position / 1000.0), color = Color.White, style = MaterialTheme.typography.labelMedium)
                SeekBar(position, duration, buffered, s.skips, onScrub, Modifier.weight(1f).padding(horizontal = 12.dp))
                Text("−" + formatTime((duration - position).coerceAtLeast(0) / 1000.0), color = Color.White, style = MaterialTheme.typography.labelMedium)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                ActionButton(Icons.Filled.Lock, "Lock", onLock)
                ActionButton(Icons.Filled.Speed, "Speed ${formatSpeed(speed)}×") { onSheet(Sheet.Speed) }
                ActionButton(Icons.Filled.ClosedCaption, "Subtitles & audio") { onSheet(Sheet.Subtitles) }
                ActionButton(Icons.Filled.Source, "Sources") { onSheet(Sheet.Sources) }
                if (item != null && item.episodes.isNotEmpty()) ActionButton(Icons.AutoMirrored.Filled.PlaylistPlay, "Episodes") { onSheet(Sheet.Episodes) }
                if (item?.hasNext == true) ActionButton(Icons.Filled.SkipNext, "Next") { vm.next() }
            }
        }
    }
}

@Composable
private fun RoundButton(icon: ImageVector, label: String, enabled: Boolean = true, size: Int, onClick: () -> Unit) {
    IconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.size(size.dp).background(Color.Black.copy(alpha = 0.35f), CircleShape),
    ) { Icon(icon, label, tint = if (enabled) Color.White else Color.White.copy(alpha = 0.3f), modifier = Modifier.size((size * 0.6).dp)) }
}

@Composable
private fun ActionButton(icon: ImageVector, label: String, onClick: () -> Unit) {
    TextButton(onClick = onClick) {
        Icon(icon, null, tint = Color.White, modifier = Modifier.size(20.dp))
        Text(" $label", color = Color.White, style = MaterialTheme.typography.labelLarge)
    }
}

/** The seekbar: drags scrub live (keyframe seeks) and land exactly on release; AniSkip ranges are marked on it. */
@Composable
private fun SeekBar(position: Long, duration: Long, buffered: Long, skips: List<desktop.player.SkipRange>, onScrub: (Long, Boolean) -> Unit, modifier: Modifier) {
    val max = duration.coerceAtLeast(1L).toFloat()
    Box(modifier, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxWidth().height(4.dp)) {
            drawRect(Color.White.copy(alpha = 0.35f), size = size.copy(width = size.width * (buffered / max).coerceIn(0f, 1f)))
            skips.forEach { r ->
                val x0 = (r.start * 1000 / max).toFloat().coerceIn(0f, 1f) * size.width
                val x1 = (r.end * 1000 / max).toFloat().coerceIn(0f, 1f) * size.width
                drawRect(Palette.good.copy(alpha = 0.8f), topLeft = Offset(x0, 0f), size = size.copy(width = x1 - x0))
            }
        }
        Slider(
            value = position.toFloat().coerceIn(0f, max),
            onValueChange = { onScrub(it.toLong(), false) },
            onValueChangeFinished = { onScrub(position, true) },
            valueRange = 0f..max,
            enabled = duration > 0,
            colors = SliderDefaults.colors(thumbColor = Palette.accent, activeTrackColor = Palette.accent, inactiveTrackColor = Color.White.copy(alpha = 0.2f)),
        )
    }
}
