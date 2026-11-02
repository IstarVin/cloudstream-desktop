package desktop.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
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
    var plugins by remember { mutableStateOf<List<PluginDto>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
    val player by prefs.player.collectAsState(PlayerPrefs())

    fun reload() = scope.launch {
        settings = runCatching { Load.Ok(api.settings()) }.getOrElse { Load.Failed(it.userMessage()) }
        plugins = runCatching { api.plugins() }.getOrDefault(emptyList())
    }
    LaunchedEffect(Unit) { reload() }

    fun save(vararg values: Pair<String, kotlinx.serialization.json.JsonElement>) = scope.launch {
        try {
            settings = Load.Ok(api.updateSettings(JsonObject(values.toMap())))
            error = null
        } catch (e: Exception) {
            error = e.userMessage()
        }
    }
    fun updatePlayer(change: (PlayerPrefs) -> PlayerPrefs) = scope.launch { prefs.updatePlayer(change) }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        item { Text("Settings", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold) }
        error?.let { item { Text(it, color = Palette.danger) } }

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

        if (plugins.isNotEmpty()) {
            item { Header("Plugins") }
            items(plugins, key = { it.name }) { p ->
                SwitchRow(
                    p.name, p.enabled,
                    help = listOfNotNull(p.version?.let { "v$it" }, p.status.lowercase(), p.error).joinToString(" · "),
                ) { enabled ->
                    scope.launch {
                        runCatching { api.setPluginEnabled(p.name, enabled) }.onFailure { error = it.userMessage() }
                        plugins = runCatching { api.plugins() }.getOrDefault(plugins)
                    }
                }
            }
        }

        item { Header("Desktop") }
        item { Text(api.server?.baseUrl.orEmpty(), color = Palette.muted) }
        item { OutlinedButton(onClick = onUnpair) { Text("Unpair") } }
    }
}

@Composable
private fun Header(text: String) {
    Column(Modifier.padding(top = 20.dp, bottom = 4.dp)) {
        Text(text, style = MaterialTheme.typography.titleSmall, color = Palette.accent)
        HorizontalDivider(Modifier.padding(top = 6.dp))
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, help: String? = null, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label)
            if (!help.isNullOrBlank()) Text(help, style = MaterialTheme.typography.bodySmall, color = Palette.muted)
        }
        Switch(checked, onChange)
    }
}

@Composable
private fun <T> ChoiceRow(label: String, options: List<T>, value: T, text: (T) -> String, onChange: (T) -> Unit) {
    Column(Modifier.padding(vertical = 6.dp)) {
        Text(label)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
            items(options) { o -> FilterChip(o == value, { onChange(o) }, label = { Text(text(o)) }) }
        }
    }
}

/** The seven Cloudstream source profiles: pick the active one and edit its source and quality priorities. */
@Composable
private fun ProfileEditor(settings: ServerSettings, onSave: (List<SourceProfile>, Int) -> Unit) {
    var active by remember(settings) { mutableIntStateOf(settings.sourceProfile) }
    var profiles by remember(settings) { mutableStateOf(settings.sourceProfiles) }
    var newSource by remember { mutableStateOf("") }
    val p = profiles.firstOrNull { it.id == active } ?: SourceProfile(active)
    fun edit(change: (SourceProfile) -> SourceProfile) {
        profiles = profiles.map { if (it.id == active) change(it) else it }
    }
    val dirty = profiles != settings.sourceProfiles || active != settings.sourceProfile

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(profiles) { pr -> FilterChip(pr.id == active, { active = pr.id }, label = { Text(pr.name) }) }
        }
        Text("A combined priority of ${SourceProfile.AUTO_PLAY_PRIORITY} or more starts playing right away.",
            style = MaterialTheme.typography.bodySmall, color = Palette.muted)
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
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(enabled = dirty, onClick = { onSave(profiles, active) }) { Text("Save profile") }
            TextButton(enabled = dirty, onClick = { profiles = settings.sourceProfiles; active = settings.sourceProfile }) { Text("Discard") }
        }
    }
}

@Composable
private fun Stepper(label: String, value: Int, modifier: Modifier = Modifier, onChange: (Int) -> Unit) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        IconButton(onClick = { onChange((value - 1).coerceAtLeast(-1000)) }) { Icon(Icons.Filled.Remove, "Lower") }
        Text("$value", Modifier.width(36.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        IconButton(onClick = { onChange((value + 1).coerceAtMost(1000)) }) { Icon(Icons.Filled.Add, "Raise") }
    }
}
