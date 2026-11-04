package com.istarvin.cloudstream.ui

import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeDown
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.CastConnected
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ClosedCaption
import androidx.compose.material.icons.filled.DesktopWindows
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Source
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import desktop.api.DeviceInfo
import desktop.api.DeviceKind
import desktop.api.LinkDto
import desktop.api.PlayRequest
import desktop.api.PlayerCommand
import desktop.api.SubtitleDto
import com.istarvin.cloudstream.app
import com.istarvin.cloudstream.data.PlayTargets
import com.istarvin.cloudstream.data.ServerSettings
import com.istarvin.cloudstream.data.error
import com.istarvin.cloudstream.data.link
import com.istarvin.cloudstream.data.subtitle
import com.istarvin.cloudstream.player.PlayerActivity
import com.istarvin.cloudstream.player.PlayerViewModel
import com.istarvin.cloudstream.player.sourceRecord
import desktop.links.LinkRanking
import desktop.player.PlayerState
import desktop.player.Track
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** The icon for a kind of player; null is this device. */
fun deviceIcon(kind: DeviceKind?, self: DeviceKind): ImageVector = when (kind ?: self) {
    DeviceKind.MPV -> Icons.Filled.DesktopWindows
    DeviceKind.WEB -> Icons.Filled.Language
    DeviceKind.TV -> Icons.Filled.Tv
    DeviceKind.PHONE -> Icons.Filled.PhoneAndroid
}

/**
 * Where Play plays, like the web UI's cast button: this device until another is picked, and it stays picked. Shown only
 * when another device takes casts; not on TV, which is a player rather than a remote.
 */
@Composable
fun CastButton(modifier: Modifier = Modifier, overImage: Boolean = false) {
    if (LocalTv.current) return
    val cast = LocalContext.current.app.cast
    val devices by cast.devices.collectAsState()
    val saved by cast.saved.collectAsState()
    val connected by cast.connected.collectAsState()
    val options = PlayTargets.options(devices, cast.id, cast.hereLabel)
    if (options.size < 2) return
    val effective = PlayTargets.effective(saved, devices, cast.id)
    val label = options.firstOrNull { it.id == effective }?.label ?: cast.hereLabel
    val remote = effective != PlayTargets.HERE
    var open by rememberSaveable { mutableStateOf(false) }
    val icon = if (remote) Icons.Filled.CastConnected else Icons.Filled.Cast
    if (overImage) FilledTonalIconButton(
        onClick = { open = true },
        modifier = modifier.padding(8.dp),
        colors = IconButtonDefaults.filledTonalIconButtonColors(containerColor = Palette.scrim, contentColor = if (remote) Palette.accent else Palette.onImage),
    ) { Icon(icon, "Play on: $label") }
    else IconButton(onClick = { open = true }, modifier = modifier.tvFocusIndicator()) {
        Icon(icon, "Play on: $label", tint = if (remote) Palette.accent else LocalContentColor.current)
    }
    if (open) PlayOnSheet(options, effective, connected, cast.kind, onPick = { cast.select(it); open = false }, onDismiss = { open = false })
}

@Composable
fun PlayOnSheet(
    options: List<PlayTargets.Option>,
    selected: String,
    connected: Boolean,
    self: DeviceKind,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
    title: String = "Play on",
) {
    AdaptiveSheet(onDismiss) {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
        options.forEach { o ->
            val on = o.id == selected
            Row(
                Modifier.fillMaxWidth().heightIn(min = 56.dp).tvFocusIndicator()
                    .clickable(enabled = !o.busy, role = Role.RadioButton) { onPick(o.id) }
                    .background(if (on) Palette.accent.copy(alpha = 0.14f) else Color.Transparent)
                    .padding(horizontal = 20.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                val color = when { on -> Palette.accent; o.busy -> Palette.muted; else -> Palette.text }
                Icon(deviceIcon(o.kind, self), null, tint = color)
                Text(o.label, Modifier.weight(1f), color = color, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (o.busy) Text("In use", style = MaterialTheme.typography.labelSmall, color = Palette.muted)
                if (on) Icon(Icons.Filled.Check, null, tint = Palette.accent)
            }
        }
        val note = when {
            !connected -> "Reconnecting to your other devices…"
            options.size < 2 -> "Other devices show up here once they're open and allow casting."
            options.any { it.busy } -> "Devices in use are playing for another device."
            else -> null
        }
        note?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Palette.muted, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) }
        Spacer(Modifier.navigationBarsPadding().height(16.dp))
    }
}

/** In the player: moves the episode, at its link, subtitles and position, to another device, then closes. */
@Composable
fun MoveToDeviceButton(vm: PlayerViewModel, onMoved: () -> Unit, onInteract: () -> Unit) {
    if (LocalTv.current) return
    val context = LocalContext.current
    val cast = context.app.cast
    val devices by cast.devices.collectAsState()
    val connected by cast.connected.collectAsState()
    val options = PlayTargets.options(devices, cast.id, cast.hereLabel)
    if (options.size < 2) return
    val scope = rememberCoroutineScope()
    var open by remember { mutableStateOf(false) }
    IconButton(onClick = { onInteract(); open = true }, modifier = Modifier.tvFocusIndicator()) { Icon(Icons.Filled.Cast, "Play on another device", tint = Color.White) }
    if (open) PlayOnSheet(options, PlayTargets.HERE, connected, cast.kind, title = "Continue on", onDismiss = { open = false }, onPick = { target ->
        open = false
        if (target == PlayTargets.HERE) return@PlayOnSheet
        val req = vm.handoff() ?: return@PlayOnSheet
        scope.launch {
            try {
                context.app.api.castPlay(target, req, cast.id)
                cast.select(target)
                vm.player.pause()
                onMoved()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Toast.makeText(context, "Couldn't play on ${cast.device(target)?.name ?: "that device"}: ${e.userMessage()}", Toast.LENGTH_LONG).show()
            }
        }
    })
}

private fun skipLabel(s: PlayerState): String? = s.skip?.let { "Skip " + when (it.type) { "op" -> "opening"; "ed" -> "ending"; "recap" -> "recap"; else -> it.type } }

/** The bar above the tabs while this device controls another's player; tapping it opens the remote. */
@Composable
fun NowPlayingBar(onTitle: (String, String) -> Unit) {
    val context = LocalContext.current
    val cast = context.app.cast
    val devices by cast.devices.collectAsState()
    val states by cast.states.collectAsState()
    val device = PlayTargets.controlled(devices, cast.id) ?: return
    val s = states[device.id]?.takeIf(PlayTargets::active) ?: return
    val scope = rememberCoroutineScope()
    var expanded by rememberSaveable { mutableStateOf(false) }
    val command: (String, PlayerCommand) -> Unit = { cmd, body ->
        scope.launch {
            try {
                context.app.api.castCommand(device.id, cmd, body)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Toast.makeText(context, "Couldn't reach ${device.name}: ${e.userMessage()}", Toast.LENGTH_SHORT).show()
            }
        }
    }
    val busy = s.status == "resolving"
    Surface(
        onClick = { expanded = true },
        color = Palette.surface2,
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp).tvFocusIndicator(),
    ) {
        Box {
            Row(Modifier.heightIn(min = 64.dp).padding(start = 8.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (s.poster != null) AsyncImage(imageRequest(s.poster), null, Modifier.size(40.dp, 52.dp).clip(RoundedCornerShape(6.dp)), contentScale = ContentScale.Crop)
                Column(Modifier.weight(1f).padding(start = 4.dp)) {
                    Text(s.title.orEmpty(), fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    val sub = if (busy || s.status == "error") s.message ?: "Loading…"
                    else listOfNotNull(device.name.takeIf { device.kind != DeviceKind.MPV }, s.episodeLabel, "${formatTime(s.position)} / ${formatTime(s.duration)}").joinToString(" · ")
                    Text(sub, style = MaterialTheme.typography.bodySmall, color = if (s.status == "error") Palette.danger else Palette.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                skipLabel(s)?.let { TextButton(onClick = { command("skip", PlayerCommand()) }) { Text(it) } }
                IconButton(onClick = { command("toggle", PlayerCommand()) }, enabled = !busy) {
                    Icon(if (s.paused) Icons.Filled.PlayArrow else Icons.Filled.Pause, if (s.paused) "Play" else "Pause")
                }
                if (s.hasNext) IconButton(onClick = { command("next", PlayerCommand()) }) { Icon(Icons.Filled.SkipNext, "Next episode") }
            }
            LinearProgressIndicator(
                progress = { if (s.duration > 0) (s.position / s.duration).toFloat().coerceIn(0f, 1f) else 0f },
                modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(3.dp),
                drawStopIndicator = {},
            )
        }
    }
    if (expanded) RemoteSheet(device, s, command, onTitle = { p, u -> expanded = false; onTitle(p, u) }, onDismiss = { expanded = false })
}

/** The full remote for the device this one plays on: what the web UI's now-playing remote offers. */
@Composable
private fun RemoteSheet(device: DeviceInfo, s: PlayerState, command: (String, PlayerCommand) -> Unit, onTitle: (String, String) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val cast = context.app.cast
    val isMpv = device.kind == DeviceKind.MPV
    val busy = s.status == "resolving"
    var sources by remember { mutableStateOf(false) }
    AdaptiveSheet(onDismiss, dragHandle = null) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(top = 20.dp, bottom = 16.dp).navigationBarsPadding(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val online = if (isMpv) s.mpvRunning else true
                Box(Modifier.size(8.dp).background(if (online) Palette.accent else Palette.muted, CircleShape))
                Text(if (isMpv) (if (s.mpvRunning) "Playing in mpv" else "mpv is closed") else "Playing on ${device.name}", style = MaterialTheme.typography.labelLarge, color = Palette.muted)
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                if (s.poster != null) AsyncImage(imageRequest(s.poster), null, Modifier.size(72.dp, 100.dp).clip(RoundedCornerShape(8.dp)), contentScale = ContentScale.Crop)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    val p = s.provider
                    val u = s.url
                    Text(
                        s.title ?: "Nothing playing", style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis,
                        modifier = if (p != null && u != null) Modifier.clickable(role = Role.Button, onClickLabel = "Open title") { onTitle(p, u) } else Modifier,
                    )
                    s.episodeLabel?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                    s.link?.let { Text("${it.source} · ${it.qualityLabel}${if (it.proxied) " · proxied" else ""}", style = MaterialTheme.typography.bodySmall, color = Palette.muted) }
                    s.message?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = if (s.status == "error") Palette.danger else Palette.muted) }
                    s.next?.let { n ->
                        val label = n.label ?: "next episode"
                        when (n.status) {
                            "resolving" -> "Next: $label · finding links…"
                            "ready" -> "Next: $label · ${n.links} ready"
                            "failed" -> "Next: $label · no links found"
                            else -> null
                        }
                    }?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Palette.muted) }
                }
            }

            var scrub by remember { mutableStateOf<Float?>(null) }
            val position = scrub ?: s.position.toFloat()
            Column {
                Slider(
                    value = position.coerceIn(0f, s.duration.toFloat().coerceAtLeast(1f)),
                    onValueChange = { scrub = it },
                    onValueChangeFinished = { scrub?.let { command("seek", PlayerCommand(seconds = it.toDouble())) }; scrub = null },
                    valueRange = 0f..s.duration.toFloat().coerceAtLeast(1f),
                    enabled = s.duration > 0,
                    thumb = { Box(Modifier.size(16.dp).background(if (s.duration > 0) Palette.accent else Palette.muted, CircleShape)) },
                    track = { state -> SliderDefaults.Track(state, modifier = Modifier.height(4.dp), enabled = s.duration > 0, thumbTrackGapSize = 0.dp, drawStopIndicator = null) },
                    modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Playback position" }.tvSliderNavigation().tvFocusIndicator(),
                )
                Row {
                    Text(formatTime(position.toDouble()), style = MaterialTheme.typography.bodySmall.merge(TabularNumbers), color = Palette.muted, modifier = Modifier.weight(1f))
                    Text("-" + formatTime((s.duration - position).coerceAtLeast(0.0)), style = MaterialTheme.typography.bodySmall.merge(TabularNumbers), color = Palette.muted)
                }
            }

            skipLabel(s)?.let { label ->
                OutlinedButton(shape = MaterialTheme.shapes.small, onClick = { command("skip", PlayerCommand()) }, modifier = Modifier.fillMaxWidth().tvFocusIndicator()) {
                    Icon(Icons.Filled.FastForward, null, Modifier.size(18.dp))
                    Text(label, Modifier.padding(start = 8.dp))
                }
            }

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { command("prev", PlayerCommand()) }, enabled = s.hasPrev, modifier = Modifier.tvFocusIndicator()) { Icon(Icons.Filled.SkipPrevious, "Previous episode") }
                IconButton(onClick = { command("seek", PlayerCommand(seconds = -10.0, relative = true)) }, modifier = Modifier.tvFocusIndicator()) { Icon(Icons.Filled.Replay10, "Back 10 seconds") }
                FilledIconButton(onClick = { command("toggle", PlayerCommand()) }, enabled = !busy, modifier = Modifier.size(64.dp).tvFocusIndicator()) {
                    if (busy) CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 2.dp, color = LocalContentColor.current)
                    else Icon(if (s.paused) Icons.Filled.PlayArrow else Icons.Filled.Pause, if (s.paused) "Play" else "Pause", Modifier.size(34.dp))
                }
                IconButton(onClick = { command("seek", PlayerCommand(seconds = 10.0, relative = true)) }, modifier = Modifier.tvFocusIndicator()) { Icon(Icons.Filled.Forward10, "Forward 10 seconds") }
                IconButton(onClick = { command("next", PlayerCommand()) }, enabled = s.hasNext, modifier = Modifier.tvFocusIndicator()) { Icon(Icons.Filled.SkipNext, "Next episode") }
            }

            Surface(color = Palette.surface2, shape = RoundedCornerShape(16.dp)) {
                Column {
                    TrackMenu("Audio", Icons.AutoMirrored.Filled.VolumeUp, s.audioTracks, offLabel = null) { command("track", PlayerCommand(type = "audio", id = it)) }
                    HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant)
                    TrackMenu("Subtitles", Icons.Filled.ClosedCaption, s.subTracks, offLabel = "Subtitles off") { command("track", PlayerCommand(type = "sub", id = it)) }
                }
            }

            var volume by remember { mutableStateOf<Float?>(null) }
            val maxVolume = if (isMpv) 130f else 100f
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.AutoMirrored.Filled.VolumeDown, null, tint = Palette.muted)
                Slider(
                    value = (volume ?: s.volume.toFloat()).coerceIn(0f, maxVolume),
                    onValueChange = { volume = it },
                    onValueChangeFinished = { volume?.let { command("volume", PlayerCommand(value = it.toDouble())) }; volume = null },
                    valueRange = 0f..maxVolume,
                    thumb = { Box(Modifier.size(16.dp).background(Palette.accent, CircleShape)) },
                    track = { state -> SliderDefaults.Track(state, modifier = Modifier.height(4.dp), thumbTrackGapSize = 0.dp, drawStopIndicator = null) },
                    modifier = Modifier.weight(1f).semantics { contentDescription = "Volume" }.tvSliderNavigation().tvFocusIndicator(),
                )
                Text("${(volume ?: s.volume.toFloat()).toInt()}", style = MaterialTheme.typography.bodySmall.merge(TabularNumbers), modifier = Modifier.width(32.dp))
            }

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(shape = MaterialTheme.shapes.small, onClick = { sources = true }, enabled = s.provider != null && s.data != null, modifier = Modifier.weight(1f).tvFocusIndicator(), contentPadding = PaddingValues(horizontal = 4.dp, vertical = 12.dp)) {
                    RemoteActionLabel(Icons.Filled.Source, "Sources")
                }
                val p = s.provider
                val u = s.url
                val d = s.data
                if (p != null && u != null && d != null) OutlinedButton(shape = MaterialTheme.shapes.small, onClick = {
                    // Carries on here from the same point and link, and plays here from now on.
                    command("stop", PlayerCommand())
                    cast.select(PlayTargets.HERE)
                    PlayerActivity.startCast(context, PlayRequest(p, u, d, linkId = s.link?.id, startAt = s.position.takeIf { it > 5 }))
                    onDismiss()
                }, modifier = Modifier.weight(1f).tvFocusIndicator(), contentPadding = PaddingValues(horizontal = 4.dp, vertical = 12.dp)) {
                    RemoteActionLabel(deviceIcon(null, cast.kind), "Play here")
                }
                OutlinedButton(shape = MaterialTheme.shapes.small, onClick = { command("stop", PlayerCommand()) }, modifier = Modifier.weight(1f).tvFocusIndicator(), contentPadding = PaddingValues(horizontal = 4.dp, vertical = 12.dp)) {
                    RemoteActionLabel(Icons.Filled.Stop, "Stop")
                }
            }
        }
    }
    if (sources) {
        val p = s.provider
        val d = s.data
        if (p != null && d != null) RemoteSourcesSheet(device, p, d, s, command, onDismiss = { sources = false })
    }
}

@Composable
private fun RemoteActionLabel(icon: ImageVector, label: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Icon(icon, null, Modifier.size(20.dp))
        Text(label, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

private fun trackLabel(t: Track): String = listOfNotNull(t.lang?.uppercase(), t.title).filter { it.isNotBlank() }.joinToString(" · ").ifEmpty { "Track ${t.id}" }

@Composable
private fun TrackMenu(label: String, icon: ImageVector, tracks: List<Track>, offLabel: String?, onPick: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val current = tracks.firstOrNull { it.selected }?.let(::trackLabel) ?: offLabel ?: "None"
    Box {
        Surface(
            onClick = { open = true }, enabled = tracks.isNotEmpty() || offLabel != null,
            color = Color.Transparent,
            modifier = Modifier.fillMaxWidth().tvFocusIndicator(),
        ) {
            Row(Modifier.heightIn(min = 64.dp).padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(icon, null, Modifier.size(18.dp), tint = Palette.muted)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(label, style = MaterialTheme.typography.labelMedium, color = Palette.muted)
                    Text(current, style = MaterialTheme.typography.bodyMedium, color = Palette.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                Icon(Icons.Outlined.ExpandMore, null, Modifier.size(18.dp), tint = Palette.muted)
            }
        }
        DropdownMenu(open, onDismissRequest = { open = false }) {
            if (offLabel != null) DropdownMenuItem(text = { Text(offLabel) }, onClick = { open = false; onPick("no") })
            tracks.forEach { t ->
                DropdownMenuItem(
                    text = { Text(trackLabel(t)) },
                    trailingIcon = if (t.selected) ({ Icon(Icons.Filled.Check, null) }) else null,
                    onClick = { open = false; onPick(t.id.toString()) },
                )
            }
        }
    }
}

/** The episode's links and subtitles, streamed as the desktop finds them; picking one switches the remote player to it. */
@Composable
private fun RemoteSourcesSheet(device: DeviceInfo, provider: String, data: String, s: PlayerState, command: (String, PlayerCommand) -> Unit, onDismiss: () -> Unit) {
    val api = LocalContext.current.app.api
    var links by remember { mutableStateOf(emptyList<LinkDto>()) }
    var subtitles by remember { mutableStateOf(emptyList<SubtitleDto>()) }
    var done by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var settings by remember { mutableStateOf(ServerSettings()) }
    // Bumped by the reload button; the server then runs the plugin's loadLinks again.
    var reloads by remember { mutableStateOf(0) }
    LaunchedEffect(provider, data) { runCatching { api.settings() }.onSuccess { settings = it } }
    LaunchedEffect(provider, data, reloads) {
        links = emptyList(); subtitles = emptyList(); error = null; done = false
        try {
            api.links(provider, data, fresh = reloads > 0).collect { ev ->
                when (ev.event) {
                    "link" -> links = links + ev.link()
                    "subtitle" -> subtitles = subtitles + ev.subtitle()
                    "error" -> error = ev.error()
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = e.userMessage()
        }
        done = true
    }
    val profile = settings.activeProfile
    val ranked = links.filter { it.playable }.sortedWith(LinkRanking.comparator(profile))
    AdaptiveSheet(onDismiss, dragHandle = null) {
        Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 16.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Sources", style = MaterialTheme.typography.titleLarge)
                Text(
                    listOfNotNull(s.episodeLabel ?: s.title, "on ${device.name}").joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall, color = Palette.muted,
                    maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
            }
            IconButton(onClick = { reloads++ }, enabled = done, modifier = Modifier.tvFocusIndicator()) {
                Icon(Icons.Filled.Refresh, "Reload links")
            }
            IconButton(onClick = onDismiss, modifier = Modifier.tvFocusIndicator()) {
                Icon(Icons.Filled.Close, "Close sources")
            }
        }
        if (!done) LinearProgressIndicator(Modifier.fillMaxWidth().height(2.dp))
        else HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        LazyColumn(
            Modifier.weight(1f, fill = false).heightIn(max = 560.dp),
            contentPadding = PaddingValues(start = 8.dp, end = 8.dp, top = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (!done && ranked.isEmpty()) item {
                Text("Finding sources…", style = MaterialTheme.typography.bodyMedium, color = Palette.muted, modifier = Modifier.padding(12.dp))
            }
            if (done && error != null && ranked.isNotEmpty()) item {
                Text(error.orEmpty(), style = MaterialTheme.typography.bodySmall, color = Palette.danger, modifier = Modifier.padding(12.dp))
            }
            if (done && ranked.isEmpty()) item {
                Text(error ?: "No playable links", color = if (error != null) Palette.danger else Palette.muted, modifier = Modifier.padding(12.dp))
            }
            items(ranked, key = { "l-" + it.id }) { l ->
                val failed = l.id in s.failedLinks
                SourceRow(
                    l.source,
                    listOfNotNull(l.name.takeIf { it != l.source }, sourceRecord(l), "priority ${profile.priority(l)}", "failed".takeIf { failed }).joinToString(" · "),
                    selected = l.id == s.link?.id, dim = failed, quality = l.qualityLabel,
                ) { command("source", PlayerCommand(linkId = l.id)); onDismiss() }
            }
            if (subtitles.isNotEmpty()) {
                item { HorizontalDivider(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), color = MaterialTheme.colorScheme.outlineVariant) }
                item { Text("Add subtitles", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) }
                items(subtitles, key = { "s-" + it.id }) { sub ->
                    SourceRow(sub.lang, null, selected = false, dim = false) { command("sub-add", PlayerCommand(id = sub.id)) }
                }
            }
            item { Spacer(Modifier.navigationBarsPadding().height(16.dp)) }
        }
    }
}

@Composable
private fun SourceRow(title: String, detail: String?, selected: Boolean, dim: Boolean, quality: String? = null, onClick: () -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    Row(
        Modifier.fillMaxWidth().clip(shape).tvFocusIndicator(shape)
            .background(if (selected) Palette.accent.copy(alpha = 0.14f) else Color.Transparent)
            .then(if (quality != null) Modifier.selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
                else Modifier.clickable(role = Role.Button, onClick = onClick))
            .heightIn(min = 56.dp).padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(title, modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, color = if (dim) Palette.muted else Palette.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (quality != null) Surface(
                    color = if (selected) Palette.accent else Palette.surface2,
                    contentColor = if (selected) Palette.accentInk else Palette.muted,
                    shape = RoundedCornerShape(4.dp),
                ) {
                    Text(quality, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
                }
                if (quality != null) Box(Modifier.size(20.dp), contentAlignment = Alignment.Center) {
                    if (selected) Icon(Icons.Filled.Check, null, Modifier.size(20.dp), tint = Palette.accent)
                }
            }
            if (!detail.isNullOrBlank()) Text(detail, style = MaterialTheme.typography.bodySmall, color = if (selected) Palette.text.copy(alpha = 0.8f) else Palette.muted, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}
