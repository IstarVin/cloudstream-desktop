package desktop.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import desktop.api.PluginDto
import desktop.api.Quality
import desktop.app.app
import desktop.app.data.PlayerPrefs
import desktop.app.data.ServerSettings
import desktop.app.data.json
import desktop.player.SourceProfile
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.encodeToJsonElement

private val QUALITY_LABELS = mapOf(
    Quality.Unknown to "Auto / unknown", Quality.P144 to "144p", Quality.P240 to "240p", Quality.P360 to "360p",
    Quality.P480 to "480p", Quality.P720 to "720p", Quality.P1080 to "1080p", Quality.P1440 to "1440p", Quality.P2160 to "2160p / 4K",
)

@Composable
fun SettingsScreen(onUnpair: () -> Unit) {
    val context = LocalContext.current
    val api = context.app.api
    val prefs = context.app.prefs
    val scope = rememberCoroutineScope()
    var settings by remember { mutableStateOf<Load<ServerSettings>>(Load.Loading) }
    var plugins by remember { mutableStateOf<Load<List<PluginDto>>>(Load.Loading) }
    var error by remember { mutableStateOf<String?>(null) }
    val snackbar = remember { SnackbarHostState() }
    val player by prefs.player.collectAsState(PlayerPrefs())

    fun reload() = scope.launch {
        if (settings is Load.Failed) settings = Load.Loading
        if (plugins is Load.Failed) plugins = Load.Loading
        launch { settings = runCatching { Load.Ok(api.settings()) }.getOrElse { Load.Failed(it.userMessage()) } }
        launch { plugins = runCatching { Load.Ok(api.plugins()) }.getOrElse { Load.Failed(it.userMessage()) } }
    }
    LaunchedEffect(Unit) { reload() }
    LaunchedEffect(error) {
        error?.let { snackbar.showSnackbar("Couldn't save changes: $it"); error = null }
    }

    fun save(vararg values: Pair<String, kotlinx.serialization.json.JsonElement>) = scope.launch {
        try {
            settings = Load.Ok(api.updateSettings(JsonObject(values.toMap())))
            error = null
        } catch (e: Exception) {
            error = e.userMessage()
        }
    }
    fun updatePlayer(change: (PlayerPrefs) -> PlayerPrefs) = scope.launch { prefs.updatePlayer(change) }

    Scaffold(snackbarHost = { SnackbarHost(snackbar) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding), horizontalAlignment = Alignment.CenterHorizontally) {
            ScreenHeading("Settings", Modifier.widthIn(max = 840.dp).fillMaxWidth())
            LazyColumn(Modifier.weight(1f).widthIn(max = 840.dp).fillMaxWidth(), contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 20.dp, end = 20.dp, bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                item { Header("Player (this phone)") }
                item { SwitchRow("Swipe left/right to seek", player.swipeSeek) { v -> updatePlayer { it.copy(swipeSeek = v) } } }
                item { SwitchRow("Swipe on the left for brightness", player.brightnessGesture) { v -> updatePlayer { it.copy(brightnessGesture = v) } } }
                item { SwitchRow("Swipe on the right for volume", player.volumeGesture) { v -> updatePlayer { it.copy(volumeGesture = v) } } }
                item { ChoiceRow("Double-tap seeks", listOf(5, 10, 15, 30), player.seekStep, { "${it}s" }) { v -> updatePlayer { it.copy(seekStep = v) } } }
                item { ChoiceRow("Hold to speed up", listOf(1.5f, 2f, 3f), player.holdSpeed, { "${it}×" }) { v -> updatePlayer { it.copy(holdSpeed = v) } } }
                item {
                    Column {
                        Text("Subtitle size ${"%.0f".format(player.subtitleScale * 100)}%")
                        Slider(player.subtitleScale, { v -> updatePlayer { it.copy(subtitleScale = v) } }, valueRange = 0.6f..2f, steps = 13)
                    }
                }
                item { SwitchRow("Subtitles on a dark box (SRT/VTT)", player.subtitleBackground) { v -> updatePlayer { it.copy(subtitleBackground = v) } } }
                item { SwitchRow("Picture-in-picture when leaving", player.pictureInPicture) { v -> updatePlayer { it.copy(pictureInPicture = v) } } }
                item {
                    SwitchRow(
                        "Direct links when possible",
                        player.directLinks,
                        help = "Fetch video straight from its host instead of through the desktop. Links that need the desktop still go through it.",
                    ) { v -> updatePlayer { it.copy(directLinks = v) } }
                }

                when (val s = settings) {
                    Load.Loading -> item { Loading() }
                    is Load.Failed -> item { ErrorBox(s.message, onRetry = { reload() }) }
                    is Load.Ok -> {
                        val v = s.value
                        item { Header("Playback (shared with the desktop)") }
                        item { SwitchRow("Auto-play the best link", v.autoplayBest) { save("autoplay_best" to JsonPrimitive(it)) } }
                        item { SwitchRow("Play the next episode automatically", v.autoplayNext) { save("autoplay_next" to JsonPrimitive(it)) } }
                        item {
                            ChoiceRow("Wait for links", listOf(1, 3, 5), v.autoplayMinLinks, { "$it" }) { save("autoplay_min_links" to JsonPrimitive(it)) }
                        }
                        item {
                            ChoiceRow("Openings and endings", listOf("prompt", "auto", "off"), v.skipSegments,
                                { mapOf("prompt" to "Offer to skip", "auto" to "Skip", "off" to "Off").getValue(it) }) { save("skip_segments" to JsonPrimitive(it)) }
                        }
                        item { SwitchRow("Show NSFW content", v.showNsfw) { save("show_nsfw" to JsonPrimitive(it)) } }
                        item { Header("Source priority") }
                        item {
                            ProfileEditor(v) { profiles, active ->
                                save("source_profiles" to json.encodeToJsonElement(profiles), "source_profile" to JsonPrimitive(active))
                            }
                        }
                    }
                }

                item { Header("Plugins") }
                when (val loaded = plugins) {
                    Load.Loading -> item { Loading() }
                    is Load.Failed -> item { ErrorBox(loaded.message, onRetry = { reload() }) }
                    is Load.Ok -> {
                        if (loaded.value.isEmpty()) item { Text("No plugins installed. Add a repository in the desktop settings to get started.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 12.dp)) }
                        items(loaded.value, key = { it.name }) { p ->
                            SwitchRow(
                                p.name, p.enabled,
                                help = listOfNotNull(p.version?.let { "v$it" }, p.status.lowercase(), p.error).joinToString(" · "),
                            ) { enabled ->
                                scope.launch {
                                    runCatching { api.setPluginEnabled(p.name, enabled) }.onFailure { error = it.userMessage() }
                                    plugins = runCatching { Load.Ok(api.plugins()) }.getOrDefault(plugins)
                                }
                            }
                        }
                    }
                }

                item { Header("Desktop") }
                item { Text(api.server?.baseUrl.orEmpty(), color = Palette.muted) }
                item { OutlinedButton(onClick = onUnpair) { Text("Unpair") } }
            }
        }
    }
}

@Composable
private fun Header(text: String) {
    Column(Modifier.padding(top = 24.dp, bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Text(text, style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, help: String? = null, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 64.dp).toggleable(value = checked, role = Role.Switch, onValueChange = onChange).padding(vertical = 12.dp),
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

@Composable
private fun <T> ChoiceRow(label: String, options: List<T>, value: T, text: (T) -> String, onChange: (T) -> Unit) {
    Column(Modifier.padding(vertical = 6.dp)) {
        Text(label)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
            items(options) { o -> FilterChip(o == value, { onChange(o) }, label = { Text(text(o)) }, modifier = Modifier.heightIn(min = 48.dp)) }
        }
    }
}

/** The seven Cloudstream source profiles: pick the active one and edit its source and quality priorities. */
@Composable
private fun ProfileEditor(settings: ServerSettings, onSave: (List<SourceProfile>, Int) -> Unit) {
    var active by remember(settings) { mutableIntStateOf(settings.sourceProfile) }
    var profiles by remember(settings) { mutableStateOf(settings.sourceProfiles) }
    var newSource by remember { mutableStateOf("") }
    var expanded by rememberSaveable { mutableStateOf(false) }
    val p = profiles.firstOrNull { it.id == active } ?: SourceProfile(active)
    fun edit(change: (SourceProfile) -> SourceProfile) {
        profiles = profiles.map { if (it.id == active) change(it) else it }
    }
    val dirty = profiles != settings.sourceProfiles || active != settings.sourceProfile

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(profiles) { pr -> FilterChip(pr.id == active, { active = pr.id }, label = { Text(pr.name) }, modifier = Modifier.heightIn(min = 48.dp)) }
        }
        Text("A combined priority of ${SourceProfile.AUTO_PLAY_PRIORITY} or more starts playing right away.",
            style = MaterialTheme.typography.bodySmall, color = Palette.muted)
        TextButton(onClick = { expanded = !expanded }) {
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
                        IconButton(onClick = { edit { it.copy(sourcePriorities = it.sourcePriorities - source) } }) { Icon(Icons.Filled.Delete, "Remove $source") }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(newSource, { newSource = it }, placeholder = { Text("Source name, exactly as listed") }, singleLine = true, modifier = Modifier.weight(1f))
                    TextButton(enabled = newSource.isNotBlank(), onClick = {
                        edit { it.copy(sourcePriorities = it.sourcePriorities + (newSource.trim() to 1)) }
                        newSource = ""
                    }) { Text("Add") }
                }
                SwitchRow("Hide failed sources", p.hideFailedSources) { v -> edit { it.copy(hideFailedSources = v) } }
                SwitchRow("Hide negative-priority sources", p.hideNegativeSources) { v -> edit { it.copy(hideNegativeSources = v) } }
            }
        }
        if (dirty) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(enabled = dirty, onClick = { onSave(profiles, active) }) { Text("Save profile") }
            TextButton(enabled = dirty, onClick = { profiles = settings.sourceProfiles; active = settings.sourceProfile }) { Text("Discard") }
        }
    }
}

@Composable
private fun Stepper(label: String, value: Int, modifier: Modifier = Modifier, onChange: (Int) -> Unit) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        IconButton(onClick = { onChange((value - 1).coerceAtLeast(-1000)) }, enabled = value > -1000) { Icon(Icons.Filled.Remove, "Lower $label priority") }
        Text("$value", Modifier.widthIn(min = 48.dp), style = MaterialTheme.typography.bodyMedium.copy(fontFeatureSettings = "tnum"), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        IconButton(onClick = { onChange((value + 1).coerceAtMost(1000)) }, enabled = value < 1000) { Icon(Icons.Filled.Add, "Raise $label priority") }
    }
}
