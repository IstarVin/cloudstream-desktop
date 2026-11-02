@file:OptIn(UnstableApi::class)

package desktop.app.player

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.focusGroup
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.common.C
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import desktop.api.LinkDto
import desktop.api.SubtitleDto
import desktop.app.ui.Palette
import desktop.app.ui.LocalTv
import desktop.app.ui.tvFocusIndicator
import desktop.app.ui.episodeTitle

/** A panel over the right of the video, with a scrim that closes it. */
@Composable
private fun SidePanel(onDismiss: () -> Unit, widthFraction: Float = 0.5f, content: @Composable ColumnScope.() -> Unit) {
    BackHandler(onBack = onDismiss)
    val scrim = remember { MutableInteractionSource() }
    val panel = remember { MutableInteractionSource() }
    val tv = LocalTv.current
    val focus = remember { FocusRequester() }
    LaunchedEffect(tv) { if (tv) { withFrameNanos { }; focus.requestFocus() } }
    val body: @Composable () -> Unit = {
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.45f))
            .focusProperties { canFocus = false }.clickable(scrim, null, onClick = onDismiss)) {
            Column(
                Modifier.align(Alignment.CenterEnd).fillMaxHeight().fillMaxWidth(widthFraction)
                    .background(Palette.surface)
                    .focusProperties { canFocus = false }
                    .clickable(panel, null) {}
                    .focusRequester(focus).focusGroup()
                    // Only the edges the panel touches: the right one and the top and bottom.
                    .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Vertical + WindowInsetsSides.End))
                    .padding(vertical = 8.dp),
                content = content,
            )
        }
    }
    if (tv) Dialog(onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) { body() }
    else body()
}

@Composable
private fun PanelTitle(text: String, modifier: Modifier = Modifier) {
    Text(text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = Palette.text, modifier = modifier.padding(horizontal = 16.dp, vertical = 8.dp))
}

@Composable
private fun Choice(title: String, detail: String? = null, selected: Boolean, badges: List<Pair<String, Color>> = emptyList(), dim: Boolean = false, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().tvFocusIndicator().clickable(onClick = onClick)
            .background(if (selected) Palette.accent.copy(alpha = 0.14f) else Color.Transparent)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(title, color = if (dim) Palette.muted else Palette.text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                badges.forEach { (text, color) ->
                    Text(text, style = MaterialTheme.typography.labelSmall, color = color,
                        modifier = Modifier.background(color.copy(alpha = 0.15f), RoundedCornerShape(4.dp)).padding(horizontal = 5.dp, vertical = 1.dp))
                }
            }
            if (detail != null) Text(detail, style = MaterialTheme.typography.bodySmall, color = Palette.muted, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        if (selected) Icon(Icons.Filled.Check, null, tint = Palette.accent)
    }
}

fun sourceRecord(l: LinkDto): String {
    val total = l.successes + l.failures
    return if (total > 0) "worked ${l.successes} of $total" else "new"
}

/**
 * Cloudstream's source picker: links on the left (ranked as auto-play tries them, filling in while they resolve),
 * subtitles on the right, applied together.
 */
@Composable
fun SourcesPanel(vm: PlayerViewModel, s: Session, onDismiss: () -> Unit) {
    var showHidden by remember { mutableStateOf(false) }
    var link by remember { mutableStateOf(s.current?.id) }
    var subtitle by remember { mutableStateOf(if (s.subtitlesOff) OFF else s.subtitle) }
    val ranked = vm.ranked(s)
    val hidden = vm.hiddenCount(s)
    val links = if (showHidden) s.links.filter { it.playable }.sortedWith(desktop.links.LinkRanking.comparator(vm.profile)) else ranked
    val unplayable = s.links.filter { !it.playable }
    val best = ranked.firstOrNull()?.id

    SidePanel(onDismiss, widthFraction = 0.8f) {
        Row(Modifier.weight(1f)) {
            Column(Modifier.weight(1.4f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    PanelTitle("Sources", Modifier.weight(1f))
                    if (hidden > 0) TextButton(onClick = { showHidden = !showHidden }, modifier = Modifier.tvFocusIndicator()) {
                        Text(if (showHidden) "Hide $hidden" else "$hidden hidden by profile")
                    }
                }
                LazyColumn(Modifier.weight(1f)) {
                    items(links, key = { it.id }) { l ->
                        val badges = buildList {
                            if (l.id == s.current?.id) add("playing" to Palette.accent)
                            if (l.id == best) add("best" to Palette.good)
                            if (l.id in s.failed) add("failed" to Palette.danger)
                            if (l.drm) add("DRM" to Palette.muted)
                            if (l.proxied) add("proxied" to Palette.muted)
                        }
                        Choice(
                            "${l.source} · ${l.qualityLabel}",
                            detail = listOf(l.name.takeIf { it != l.source }, "priority ${vm.priority(l)}", sourceRecord(l), l.host.takeIf { it.isNotEmpty() })
                                .filterNotNull().joinToString(" · "),
                            selected = l.id == link,
                            badges = badges,
                            dim = l.id in s.failed,
                            onClick = { link = l.id },
                        )
                    }
                    if (!s.linksDone) item {
                        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(Modifier.padding(end = 12.dp).size(20.dp), strokeWidth = 2.dp)
                            Text("Resolving more links…", color = Palette.muted)
                        }
                    } else if (links.isEmpty()) item { Text("No playable links.", color = Palette.muted, modifier = Modifier.padding(16.dp)) }
                    if (unplayable.isNotEmpty()) {
                        item { Text("Can't play here", style = MaterialTheme.typography.labelMedium, color = Palette.muted, modifier = Modifier.padding(start = 16.dp, top = 12.dp)) }
                        items(unplayable, key = { "u" + it.id }) { l ->
                            Choice("${l.source} · ${l.qualityLabel}", detail = l.type.lowercase() + if (l.drm) " · DRM" else "", selected = false, dim = true) {}
                        }
                    }
                }
            }
            VerticalDivider()
            Column(Modifier.weight(1f)) {
                PanelTitle("Subtitles")
                LazyColumn(Modifier.weight(1f)) {
                    subtitleChoices(vm, s, selected = subtitle, onPick = { subtitle = it })
                }
            }
        }
        HorizontalDivider()
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = onDismiss, modifier = Modifier.tvFocusIndicator()) { Text("Cancel") }
            Button(onClick = {
                val chosen = s.links.firstOrNull { it.id == link }
                if (chosen != null && chosen.id != s.current?.id) {
                    val pos = vm.player.currentPosition / 1000.0
                    vm.play(chosen, from = pos.takeIf { it > 5 })
                }
                applySubtitle(vm, s, subtitle)
                onDismiss()
            }, modifier = Modifier.tvFocusIndicator()) { Text("Apply") }
        }
    }
}

private const val OFF = "off"
private const val EMBEDDED = "embedded:"

private fun applySubtitle(vm: PlayerViewModel, s: Session, choice: String?) {
    when {
        choice == null -> {}
        choice == OFF -> if (!s.subtitlesOff) vm.subtitlesOff()
        choice.startsWith(EMBEDDED) -> {
            val (g, i) = choice.removePrefix(EMBEDDED).split(':').map { it.toInt() }
            vm.player.currentTracks.groups.getOrNull(g)?.let { vm.selectEmbedded(it, i) }
        }
        choice != s.subtitle -> s.subtitles.firstOrNull { it.id == choice }?.let(vm::selectSubtitle)
    }
}

/** "Off", the stream's own text tracks, then the provider's subtitle files (this link's source first). */
private fun LazyListScope.subtitleChoices(vm: PlayerViewModel, s: Session, selected: String?, onPick: (String) -> Unit) {
    item { Choice("Off", selected = selected == OFF, onClick = { onPick(OFF) }) }
    val groups = vm.player.currentTracks.groups
    groups.forEachIndexed { g, group ->
        if (group.type != C.TRACK_TYPE_TEXT) return@forEachIndexed
        for (i in 0 until group.length) {
            val f = group.getTrackFormat(i)
            if (f.id?.contains("cs-sub-") == true) continue
            val key = "$EMBEDDED$g:$i"
            item(key = key) {
                Choice(f.label ?: f.language ?: "Track ${i + 1}", detail = "in the stream · ${f.sampleMimeType?.substringAfter('/') ?: ""}",
                    selected = selected == key || (selected == null && group.isTrackSelected(i)), onClick = { onPick(key) })
            }
        }
    }
    val current = s.current
    val ordered = if (current != null) desktop.player.SubtitlePreference.order(s.subtitles, current.source, current.name, true) { it.lang } else s.subtitles
    items(ordered, key = { it.id }) { sub: SubtitleDto ->
        Choice(sub.lang, detail = sub.format?.uppercase(), selected = selected == sub.id, onClick = { onPick(sub.id) })
    }
    if (!s.linksDone) item { Text("More may arrive while links resolve…", color = Palette.muted, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(16.dp)) }
}

/** Subtitles and audio tracks, picked immediately. */
@Composable
fun TracksPanel(vm: PlayerViewModel, s: Session, onDismiss: () -> Unit) {
    var tracks by remember { mutableStateOf(vm.player.currentTracks) }
    DisposableEffect(vm.player) {
        val listener = object : Player.Listener {
            override fun onTracksChanged(t: Tracks) { tracks = t }
        }
        vm.player.addListener(listener)
        onDispose { vm.player.removeListener(listener) }
    }
    SidePanel(onDismiss, widthFraction = 0.75f) {
        Row(Modifier.weight(1f)) {
            Column(Modifier.weight(1f)) {
                PanelTitle("Subtitles")
                LazyColumn {
                    subtitleChoices(vm, s, selected = if (s.subtitlesOff) OFF else s.subtitle) { choice ->
                        applySubtitle(vm, s, choice)
                        onDismiss()
                    }
                }
            }
            VerticalDivider()
            Column(Modifier.weight(1f)) {
                PanelTitle("Audio")
                LazyColumn {
                    tracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }.forEach { group ->
                        for (i in 0 until group.length) {
                            val f = group.getTrackFormat(i)
                            item {
                                Choice(
                                    f.label ?: f.language?.let { java.util.Locale.forLanguageTag(it).displayLanguage } ?: "Track ${i + 1}",
                                    detail = listOfNotNull(f.sampleMimeType?.substringAfter('/'), f.channelCount.takeIf { it > 0 }?.let { "$it ch" }).joinToString(" · "),
                                    selected = group.isTrackSelected(i),
                                ) { vm.selectAudio(group, i) }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun EpisodesPanel(vm: PlayerViewModel, s: Session, onDismiss: () -> Unit) {
    val item = s.item ?: return
    val state = rememberLazyListState(initialFirstVisibleItemIndex = (item.index - 2).coerceAtLeast(0))
    SidePanel(onDismiss, widthFraction = 0.5f) {
        PanelTitle("Episodes")
        LazyColumn(state = state) {
            items(item.episodes.size) { i ->
                val e = item.episodes[i]
                Choice(
                    episodeTitle(e, i),
                    detail = e.progress?.let { p -> if (p.watched) "✓ watched" else if (p.position > 30) "in progress" else null },
                    selected = i == item.index,
                    dim = e.progress?.watched == true,
                ) {
                    vm.episode(i)
                    onDismiss()
                }
            }
        }
    }
}

@Composable
fun SpeedPanel(player: Player, onDismiss: () -> Unit) {
    val speeds = listOf(0.25f, 0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f, 2.5f, 3f)
    SidePanel(onDismiss, widthFraction = 0.35f) {
        PanelTitle("Speed")
        LazyColumn {
            items(speeds) { v ->
                Choice("${formatSpeed(v)}×", selected = player.playbackParameters.speed == v) {
                    player.playbackParameters = PlaybackParameters(v)
                    onDismiss()
                }
            }
        }
    }
}
