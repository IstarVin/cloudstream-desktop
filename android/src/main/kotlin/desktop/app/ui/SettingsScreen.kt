package desktop.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.FormatListNumbered
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material.icons.outlined.Computer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import desktop.api.Quality
import desktop.api.StatusDto
import desktop.app.app
import desktop.app.data.PlayerPrefs
import desktop.app.data.ServerSettings
import desktop.app.data.json
import desktop.player.SourceProfile
import kotlin.math.roundToInt
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.encodeToJsonElement

private val QUALITY_LABELS = mapOf(
    Quality.Unknown to "Auto / unknown", Quality.P144 to "144p", Quality.P240 to "240p", Quality.P360 to "360p",
    Quality.P480 to "480p", Quality.P720 to "720p", Quality.P1080 to "1080p", Quality.P1440 to "1440p", Quality.P2160 to "2160p / 4K",
)

private enum class SettingsTab(val label: String, val icon: ImageVector) {
    Playback("Playback", Icons.Outlined.PlayArrow),
    Priority("Source priority", Icons.Outlined.FormatListNumbered),
    Content("Content", Icons.Outlined.Shield),
    Browser("Browser", Icons.Outlined.Language),
    Plugins("Plugins", Icons.Outlined.Layers),
    Desktop("Connection", Icons.Outlined.Computer),
}

@Composable
fun SettingsScreen(onUnpair: () -> Unit) {
    val context = LocalContext.current
    val tv = LocalTv.current
    val api = context.app.api
    val prefs = context.app.prefs
    val scope = rememberCoroutineScope()
    var settings by remember { mutableStateOf<Load<ServerSettings>>(Load.Loading) }
    var status by remember { mutableStateOf<Load<StatusDto>>(Load.Loading) }
    var confirmUnpair by remember { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    val player by prefs.player.collectAsState(PlayerPrefs())
    val savedTab by prefs.settingsTab.collectAsState(initial = null)
    val tab = SettingsTab.entries.firstOrNull { it.name == savedTab } ?: SettingsTab.Plugins
    fun selectTab(tab: SettingsTab) { scope.launch { prefs.setSettingsTab(tab.name) } }
    val phoneScroll = rememberLazyListState()
    val desktopScroll = rememberLazyListState()
    val priorityScroll = rememberLazyListState()
    val contentScroll = rememberLazyListState()
    val pluginsScroll = rememberLazyListState()
    val browserScroll = rememberLazyListState()
    val currentSettings = (settings as? Load.Ok)?.value
    // Keep profile drafts while changing tabs, and while unrelated settings are saved.
    val profileDraft = remember(currentSettings?.sourceProfiles, currentSettings?.sourceProfile) {
        currentSettings?.let { ProfileDraft(it) }
    }
    fun tell(message: String) { scope.launch { snackbar.showSnackbar(message) } }
    val plugins = remember { PluginsController(api, scope, ::tell) }

    fun reload() = scope.launch {
        if (settings is Load.Failed) settings = Load.Loading
        status = Load.Loading
        launch { settings = runCatching { Load.Ok(api.settings()) }.getOrElse { Load.Failed(it.userMessage()) } }
        launch { status = runCatching { Load.Ok(api.status()) }.getOrElse { Load.Failed(it.userMessage()) } }
        launch { plugins.load() }
    }
    LaunchedEffect(Unit) { reload() }

    fun save(vararg values: Pair<String, JsonElement>) = scope.launch {
        try {
            settings = Load.Ok(api.updateSettings(JsonObject(values.toMap())))
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            tell("Couldn't save changes: ${e.userMessage()}")
        }
    }
    fun updatePlayer(change: (PlayerPrefs) -> PlayerPrefs) = scope.launch { prefs.updatePlayer(change) }

    Scaffold(snackbarHost = { SnackbarHost(snackbar) }, containerColor = MaterialTheme.colorScheme.background) { padding ->
        BoxWithConstraints(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
            val wide = maxWidth >= 760.dp
            Column(Modifier.widthIn(max = 1120.dp).fillMaxSize()) {
                Column(
                    Modifier.fillMaxWidth().padding(horizontal = 24.dp)
                        .padding(top = if (tv) 12.dp else 20.dp, bottom = 20.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text("Settings", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.semantics { heading() })
                    Text("Make Cloudstream work your way.", style = MaterialTheme.typography.bodyMedium, color = Palette.muted)
                }
                if (!wide) SettingsCategories(tab, vertical = false, onSelect = ::selectTab)
                Row(Modifier.weight(1f).fillMaxWidth()) {
                    if (wide) SettingsCategories(tab, vertical = true, onSelect = ::selectTab)
                    LazyColumn(
                        Modifier.weight(1f).fillMaxSize().testTag("settings-content"),
                        state = when (tab) {
                            SettingsTab.Playback -> phoneScroll
                            SettingsTab.Priority -> priorityScroll
                            SettingsTab.Content -> contentScroll
                            SettingsTab.Desktop -> desktopScroll
                            SettingsTab.Plugins -> pluginsScroll
                            SettingsTab.Browser -> browserScroll
                        },
                        contentPadding = PaddingValues(start = 24.dp, end = 24.dp, top = 24.dp, bottom = 32.dp),
                    ) {
                        when (tab) {
                            SettingsTab.Playback -> {
                                item { SettingsSectionTitle("Playback", "Customize playback on this device and your desktop.") }
                                item { SettingsNote("Saved on this device. Changes apply to its video player.") }
                                item { Header("Casting", first = true) }
                                item { CastingSettings() }
                                if (!tv) {
                                    item { Header("Gestures") }
                                    item { SwitchRow("Swipe to seek", player.swipeSeek, help = "Swipe left or right across the video.") { v -> updatePlayer { it.copy(swipeSeek = v) } } }
                                    item { SwitchRow("Brightness gesture", player.brightnessGesture, help = "Swipe up or down on the left edge.") { v -> updatePlayer { it.copy(brightnessGesture = v) } } }
                                    item { SwitchRow("Volume gesture", player.volumeGesture, help = "Swipe up or down on the right edge.") { v -> updatePlayer { it.copy(volumeGesture = v) } } }
                                } else item { Header("Remote") }
                                item { ChoiceRow(if (tv) "Remote seek interval" else "Double-tap seek interval", listOf(5, 10, 15, 30), player.seekStep, { "${it}s" }) { v -> updatePlayer { it.copy(seekStep = v) } } }
                                if (!tv) item { ChoiceRow("Hold-to-play speed", listOf(1.5f, 2f, 3f), player.holdSpeed, { "${it}×" }) { v -> updatePlayer { it.copy(holdSpeed = v) } } }
                                item { Header("Subtitles") }
                                item { SubtitleSize(player) { v -> updatePlayer { it.copy(subtitleScale = v) } } }
                                item { SwitchRow("Dark subtitle background", player.subtitleBackground, help = "Applies to SRT and VTT subtitles.") { v -> updatePlayer { it.copy(subtitleBackground = v) } } }
                                item { Header("Playback") }
                                item { SwitchRow("Picture-in-picture", player.pictureInPicture, help = "Keep the video playing when you leave the app.") { v -> updatePlayer { it.copy(pictureInPicture = v) } } }
                                item {
                                    SwitchRow(
                                        "Direct video links",
                                        player.directLinks,
                                        help = "Stream straight from the video host when possible. Links that need the desktop still go through it.",
                                    ) { v -> updatePlayer { it.copy(directLinks = v) } }
                                }
                                item { Header("Desktop playback") }
                                when (val s = settings) {
                                    Load.Loading -> item { Loading() }
                                    is Load.Failed -> item { ErrorBox(s.message, onRetry = { reload() }) }
                                    is Load.Ok -> {
                                        val v = s.value
                                        item { SwitchRow("Auto-play the best link", v.autoplayBest) { save("autoplay_best" to JsonPrimitive(it)) } }
                                        item { SwitchRow("Auto-play next episode", v.autoplayNext) { save("autoplay_next" to JsonPrimitive(it)) } }
                                        item {
                                            ChoiceRow("Openings and endings", listOf("prompt", "auto", "off"), v.skipSegments,
                                                { mapOf("prompt" to "Offer to skip", "auto" to "Skip", "off" to "Off").getValue(it) }) { save("skip_segments" to JsonPrimitive(it)) }
                                        }
                                        item { Header("Desktop mpv") }
                                        item {
                                            SwitchRow("Send every stream through the proxy", v.proxyAll, help = "Only needed when a site rejects mpv's direct requests.") {
                                                save("proxy_all" to JsonPrimitive(it))
                                            }
                                        }
                                        item {
                                            TextSettingRow(
                                                "Extra mpv options", v.mpvExtraArgs.joinToString(" "),
                                                help = "Applied the next time mpv starts, e.g. --profile=gpu-hq --fullscreen",
                                                placeholder = "--fullscreen",
                                            ) { text -> save("mpv_extra_args" to JsonArray(text.split(Regex("\\s+")).filter { it.isNotBlank() }.map(::JsonPrimitive))) }
                                        }
                                    }
                                }
                            }
                            SettingsTab.Priority, SettingsTab.Content -> {
                                item { SettingsSectionTitle(tab.label, if (tab == SettingsTab.Priority) "Choose which sources and qualities play first." else "Choose what appears in Cloudstream.", manualSave = tab == SettingsTab.Priority) }
                                when (val s = settings) {
                                    Load.Loading -> item { Loading() }
                                    is Load.Failed -> item { ErrorBox(s.message, onRetry = { reload() }) }
                                    is Load.Ok -> {
                                        val v = s.value
                                        if (tab == SettingsTab.Content) {
                                            item { SwitchRow("Show NSFW content", v.showNsfw) { save("show_nsfw" to JsonPrimitive(it)) } }
                                        } else {
                                            item {
                                                profileDraft?.let { draft ->
                                                    ProfileEditor(v, draft) { profiles, active ->
                                                        save("source_profiles" to json.encodeToJsonElement(profiles), "source_profile" to JsonPrimitive(active))
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                            SettingsTab.Desktop -> {
                                item { SettingsSectionTitle("Connection", "Manage your paired desktop and shared settings.") }
                                item { DesktopStatus(api.server?.baseUrl.orEmpty(), status, onRetry = { reload() }) }
                                item {
                                    Column(Modifier.padding(top = 28.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                                        TextButton(
                                            onClick = { confirmUnpair = true },
                                            colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                                            modifier = Modifier.heightIn(min = 48.dp).tvFocusIndicator(),
                                        ) { Text("Unpair this device") }
                                    }
                                }
                            }
                            SettingsTab.Browser -> {
                                item { SettingsSectionTitle("Browser", "Handle sites that need a real browser on the desktop.") }
                                when (val s = settings) {
                                    Load.Loading -> item { Loading() }
                                    is Load.Failed -> item { ErrorBox(s.message, onRetry = { reload() }) }
                                    // The desktop stops a running browser, so the next request starts it in the new mode.
                                    is Load.Ok -> item { BrowserSettings(s.value) { mode -> save("chromium_mode" to JsonPrimitive(mode)) } }
                                }
                            }
                            SettingsTab.Plugins -> {
                                item { SettingsSectionTitle("Plugins", "Manage the plugins that bring your providers to Cloudstream.") }
                                pluginItems(plugins, onRetry = { reload() })
                            }
                        }
                    }
                }
            }
        }
    }

    PluginSheet(plugins, api, ::tell)

    if (confirmUnpair) {
        AlertDialog(
            onDismissRequest = { confirmUnpair = false },
            title = { Text("Unpair this device?") },
            text = { Text("You'll need to scan the pairing code on the desktop again to reconnect. Your history and library stay on the desktop.") },
            confirmButton = { TextButton(onClick = { confirmUnpair = false; onUnpair() }, modifier = Modifier.tvFocusIndicator()) { Text("Unpair") } },
            dismissButton = { TextButton(onClick = { confirmUnpair = false }, modifier = Modifier.tvFocusIndicator()) { Text("Cancel") } },
        )
    }
}

@Composable
private fun SettingsCategories(tab: SettingsTab, vertical: Boolean, onSelect: (SettingsTab) -> Unit) {
    val scroll = rememberLazyListState(initialFirstVisibleItemIndex = if (vertical) 0 else tab.ordinal)
    LaunchedEffect(tab, vertical) {
        if (!vertical) scroll.animateScrollToItem(tab.ordinal)
    }
    if (vertical) {
        LazyColumn(
            state = scroll,
            modifier = Modifier.width(232.dp).fillMaxSize()
                .background(MaterialTheme.colorScheme.surfaceContainerLow).selectableGroup(),
            contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(SettingsTab.entries, key = { it.name }) { destination ->
                SettingsCategory(destination, destination == tab, Modifier.fillMaxWidth()) { onSelect(destination) }
            }
        }
    } else {
        LazyRow(
            state = scroll,
            modifier = Modifier.fillMaxWidth().selectableGroup(),
            contentPadding = PaddingValues(horizontal = 24.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(SettingsTab.entries, key = { it.name }) { destination ->
                SettingsCategory(destination, destination == tab) { onSelect(destination) }
            }
        }
        HorizontalDivider(Modifier.padding(top = 12.dp), color = MaterialTheme.colorScheme.outlineVariant)
    }
}

@Composable
private fun SettingsCategory(destination: SettingsTab, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = MaterialTheme.shapes.small,
        color = if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
        contentColor = if (selected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.tvFocusIndicator().semantics { role = Role.Tab; this.selected = selected },
    ) {
        Row(
            Modifier.heightIn(min = 48.dp).padding(horizontal = 12.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(destination.icon, null, Modifier.size(20.dp))
            Text(destination.label, style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Composable
private fun SettingsSectionTitle(title: String, description: String, manualSave: Boolean = false) {
    Column(Modifier.padding(bottom = 20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() })
        Text(description, style = MaterialTheme.typography.bodyMedium, color = Palette.muted)
        Text(
            if (manualSave) "Save profile changes with the Save profile button." else "Changes save automatically",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SettingsNote(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(bottom = 12.dp),
    )
}

@Composable
internal fun SubtitleSize(player: PlayerPrefs, onChange: (Float) -> Unit) {
    var scale by remember(player.subtitleScale) { mutableFloatStateOf(player.subtitleScale) }
    val percent = (scale * 100).roundToInt()
    Column(Modifier.padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Subtitle size", Modifier.weight(1f))
            Text("$percent%", style = MaterialTheme.typography.labelLarge.merge(TabularNumbers), color = MaterialTheme.colorScheme.primary)
        }
        Box(
            Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .heightIn(min = 100.dp).padding(16.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                "This is how subtitles will look.",
                style = MaterialTheme.typography.bodyLarge.copy(
                    fontSize = (16 * scale).sp,
                    lineHeight = (24 * scale).sp,
                    shadow = if (player.subtitleBackground) null else Shadow(Color.Black, Offset(1f, 2f), 3f),
                ),
                color = Color.White,
                textAlign = TextAlign.Center,
                modifier = if (player.subtitleBackground) Modifier.background(Color.Black).padding(horizontal = 4.dp, vertical = 2.dp) else Modifier,
            )
        }
        Slider(
            value = scale,
            onValueChange = { scale = (it * 20).roundToInt() / 20f },
            onValueChangeFinished = { onChange(scale) },
            valueRange = 0.6f..2f,
            steps = 27,
            modifier = (Modifier.semantics { contentDescription = "Subtitle size"; stateDescription = "$percent percent" }).tvSliderNavigation().tvFocusIndicator(),
        )
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(if (LocalTv.current) "Left / right to adjust · Up / down to move" else "60–200%", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(enabled = scale != 1f, onClick = { scale = 1f; onChange(1f) }, modifier = Modifier.tvFocusIndicator()) { Text("Reset size") }
        }
    }
}

@Composable
private fun DesktopStatus(address: String, status: Load<StatusDto>, onRetry: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(address, style = MaterialTheme.typography.bodyLarge)
        when (status) {
            Load.Loading -> Text("Checking connection…", style = MaterialTheme.typography.bodySmall, color = Palette.muted)
            is Load.Failed -> Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Not reachable. ${status.message}", style = MaterialTheme.typography.bodySmall, color = Palette.danger, modifier = Modifier.weight(1f, fill = false))
                TextButton(onClick = onRetry, modifier = Modifier.tvFocusIndicator()) { Text("Retry") }
            }
            is Load.Ok -> Text(
                "Connected · ${status.value.loaded} of ${status.value.plugins} plugins loaded" + if (status.value.building) " (still building)" else "",
                style = MaterialTheme.typography.bodySmall,
                color = Palette.good,
            )
        }
    }
}

/** Section spacing and a divider group controls without nesting them in cards. */
@Composable
internal fun Header(text: String, first: Boolean = false) {
    Column(Modifier.fillMaxWidth().padding(top = if (first) 8.dp else 24.dp)) {
        if (!first) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Text(
            text,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = if (first) 0.dp else 16.dp, bottom = 8.dp).semantics { heading() },
        )
    }
}

@Composable
internal fun SwitchRow(label: String, checked: Boolean, help: String? = null, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 64.dp).clip(MaterialTheme.shapes.small).tvFocusIndicator()
            .toggleable(value = checked, role = Role.Switch, onValueChange = onChange)
            .padding(horizontal = if (LocalTv.current) 12.dp else 0.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(label)
            if (!help.isNullOrBlank()) Text(help, style = MaterialTheme.typography.bodySmall, color = Palette.muted)
        }
        Switch(checked, onCheckedChange = null)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun <T> ChoiceRow(label: String, options: List<T>, value: T, text: (T) -> String, onChange: (T) -> Unit) {
    Column(Modifier.padding(vertical = 8.dp)) {
        Text(label)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp).selectableGroup()) {
            // Wrap choices so every value is discoverable at narrow widths and larger font sizes.
            options.forEach { o ->
                FilterChip(
                    selected = o == value,
                    onClick = { onChange(o) },
                    label = { Text(text(o)) },
                    modifier = (Modifier.semantics { role = Role.RadioButton }).tvFocusIndicator(),
                )
            }
        }
    }
}

private class ProfileDraft(settings: ServerSettings) {
    var active by mutableIntStateOf(settings.sourceProfile)
    var profiles by mutableStateOf(settings.sourceProfiles)
    var newSource by mutableStateOf("")
    var expanded by mutableStateOf(false)
}

/** The seven Cloudstream source profiles: pick the active one and edit its source and quality priorities. */
@Composable
private fun ProfileEditor(settings: ServerSettings, draft: ProfileDraft, onSave: (List<SourceProfile>, Int) -> Unit) {
    var active by draft::active
    var profiles by draft::profiles
    var newSource by draft::newSource
    var expanded by draft::expanded
    val p = profiles.firstOrNull { it.id == active } ?: SourceProfile(active)
    fun edit(change: (SourceProfile) -> SourceProfile) {
        profiles = profiles.map { if (it.id == active) change(it) else it }
    }
    val dirty = profiles != settings.sourceProfiles || active != settings.sourceProfile

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(profiles) { pr -> FilterChip(pr.id == active, { active = pr.id }, label = { Text(pr.name) }, modifier = Modifier.heightIn(min = 48.dp).tvFocusIndicator()) }
        }
        Text("A combined priority of ${SourceProfile.AUTO_PLAY_PRIORITY} or more starts playing right away.",
            style = MaterialTheme.typography.bodySmall, color = Palette.muted)
        TextButton(onClick = { expanded = !expanded }, modifier = Modifier.tvFocusIndicator()) {
            Text(if (expanded) "Hide priorities and filters" else "Edit priorities and filters")
            Icon(if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, null)
        }
        if (expanded) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Qualities", style = MaterialTheme.typography.labelLarge)
                Quality.entries.forEach { q ->
                    Stepper(QUALITY_LABELS.getValue(q), p.qualityPriorities[q.value] ?: q.defaultPriority) { n ->
                        edit { it.copy(qualityPriorities = it.qualityPriorities + (q.value to n)) }
                    }
                }
                Text("Sources", style = MaterialTheme.typography.labelLarge)
                p.sourcePriorities.toSortedMap().forEach { (source, n) ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Stepper(source, n, Modifier.weight(1f)) { v -> edit { it.copy(sourcePriorities = it.sourcePriorities + (source to v)) } }
                        IconButton(onClick = { edit { it.copy(sourcePriorities = it.sourcePriorities - source) } }, modifier = Modifier.tvFocusIndicator()) { Icon(Icons.Filled.Delete, "Remove $source") }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RemoteTextField(newSource, { newSource = it }, placeholder = { Text("Source name, exactly as listed") }, singleLine = true, modifier = Modifier.weight(1f))
                    TextButton(enabled = newSource.isNotBlank(), onClick = {
                        edit { it.copy(sourcePriorities = it.sourcePriorities + (newSource.trim() to 1)) }
                        newSource = ""
                    }, modifier = Modifier.tvFocusIndicator()) { Text("Add") }
                }
                SwitchRow("Hide failed sources", p.hideFailedSources) { v -> edit { it.copy(hideFailedSources = v) } }
                SwitchRow("Hide negative-priority sources", p.hideNegativeSources) { v -> edit { it.copy(hideNegativeSources = v) } }
            }
        }
        if (dirty) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(enabled = dirty, onClick = { onSave(profiles, active) }, modifier = Modifier.tvFocusIndicator()) { Text("Save profile") }
            TextButton(enabled = dirty, onClick = { profiles = settings.sourceProfiles; active = settings.sourceProfile }, modifier = Modifier.tvFocusIndicator()) { Text("Discard") }
        }
    }
}

@Composable
private fun Stepper(label: String, value: Int, modifier: Modifier = Modifier, onChange: (Int) -> Unit) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        IconButton(onClick = { onChange((value - 1).coerceAtLeast(-1000)) }, enabled = value > -1000, modifier = Modifier.tvFocusIndicator()) { Icon(Icons.Filled.Remove, "Lower $label priority") }
        Text("$value", Modifier.widthIn(min = 48.dp), style = MaterialTheme.typography.bodyMedium.copy(fontFeatureSettings = "tnum"), textAlign = TextAlign.Center)
        IconButton(onClick = { onChange((value + 1).coerceAtMost(1000)) }, enabled = value < 1000, modifier = Modifier.tvFocusIndicator()) { Icon(Icons.Filled.Add, "Raise $label priority") }
    }
}
