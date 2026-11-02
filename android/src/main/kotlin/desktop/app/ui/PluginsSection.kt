package desktop.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Surface
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.semantics.contentDescription
import coil3.compose.SubcomposeAsyncImage
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import desktop.api.PluginDto
import desktop.api.PluginSettingsDto
import desktop.api.PluginSourceDto
import desktop.api.PrefEntry
import desktop.app.data.Api
import desktop.plugins.SettingField
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

enum class PluginFilter(val label: String) { All("All plugins"), Updates("Updates"), Issues("Needs attention") }

/** A plugin that failed to load or link, as the desktop's plugin list marks it. */
fun PluginDto.hasIssue(): Boolean = status.equals("error", ignoreCase = true) || error != null || linkage.isNotEmpty() || runtimeLinkage != null

fun PluginDto.hasUpdate(): Boolean = available != null && version != null && available!! > version!!

/** Which plugins a search box and a filter chip leave visible. */
fun filterPlugins(all: List<PluginDto>, query: String, filter: PluginFilter): List<PluginDto> {
    val q = query.trim().lowercase()
    return all.filter { p ->
        (q.isEmpty() || p.name.lowercase().contains(q) || p.providers.any { it.lowercase().contains(q) }) &&
            when (filter) {
                PluginFilter.All -> true
                PluginFilter.Updates -> p.hasUpdate()
                PluginFilter.Issues -> p.hasIssue()
            }
    }.sortedBy { it.name.lowercase() }
}

/** The plugins list's state and the writes it makes, so the lazy list and its detail sheet share one source of truth. */
class PluginsController(private val api: Api, private val scope: CoroutineScope, private val tell: (String) -> Unit) {
    var plugins by mutableStateOf<Load<List<PluginDto>>>(Load.Loading)
    var source by mutableStateOf<PluginSourceDto?>(null)
    var query by mutableStateOf("")
    var filter by mutableStateOf(PluginFilter.All)
    var selected by mutableStateOf<String?>(null)
    var busy by mutableStateOf(false)

    suspend fun load() {
        if (plugins is Load.Failed) plugins = Load.Loading
        plugins = runCatching { Load.Ok(api.plugins()) }.getOrElse { Load.Failed(it.userMessage()) }
        source = runCatching { api.pluginSource() }.getOrNull()
    }

    private fun run(failure: String, block: suspend () -> Unit) = scope.launch {
        busy = true
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            tell("$failure: ${e.userMessage()}")
        } finally {
            busy = false
        }
    }

    fun setEnabled(p: PluginDto, enabled: Boolean) = run("Couldn't ${if (enabled) "enable" else "disable"} ${p.name}") {
        api.setPluginEnabled(p.name, enabled)
        load()
    }

    fun checkUpdates() = run("Couldn't check for updates") {
        source = api.checkPluginUpdates()
        load()
        val n = source?.updates?.size ?: 0
        tell(if (n == 0) "Everything is up to date" else "$n ${if (n == 1) "update" else "updates"} available")
    }

    fun update(names: List<String>?) = run("Couldn't update") {
        val result = api.updatePlugins(names)
        source = result.source
        load()
        tell(
            if (result.failed.isEmpty()) "Updated"
            else "Couldn't update ${result.failed.keys.joinToString(", ")}: ${result.failed.values.first()}",
        )
    }

    fun rollback(name: String) = run("Couldn't roll back $name") {
        source = api.rollbackPlugin(name)
        load()
        tell("Rolled back $name")
    }
}

fun LazyListScope.pluginItems(c: PluginsController, onRetry: () -> Unit) {
    item { PluginSource(c, onRetry) }
    when (val loaded = c.plugins) {
        Load.Loading -> item { Loading() }
        is Load.Failed -> item { ErrorBox(loaded.message, onRetry = onRetry) }
        is Load.Ok -> {
            val all = loaded.value
            if (all.isEmpty()) {
                item {
                    Text(
                        "No plugins installed. Add a repository in the desktop settings to get started.",
                        style = MaterialTheme.typography.bodyMedium, color = Palette.muted, modifier = Modifier.padding(vertical = 12.dp),
                    )
                }
                return
            }
            val updates = all.count { it.hasUpdate() }
            val issues = all.count { it.hasIssue() }
            val repo = c.source?.takeIf { it.mode == "repo" }
            item {
                OutlinedTextField(
                    c.query, { c.query = it },
                    placeholder = { Text("Search plugins or providers") },
                    leadingIcon = { Icon(Icons.Filled.Search, null) },
                    trailingIcon = { if (c.query.isNotEmpty()) IconButton(onClick = { c.query = "" }, modifier = Modifier.tvFocusIndicator()) { Icon(Icons.Filled.Close, "Clear search") } },
                    singleLine = true,
                    shape = RoundedCornerShape(8.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        unfocusedContainerColor = Palette.surface2,
                        focusedContainerColor = Palette.surface2,
                        unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
                    ),
                    modifier = (Modifier.fillMaxWidth().semantics { contentDescription = "Search plugins or providers" }).tvFocusIndicator(),
                )
            }
            item {
                PluginFilterMenu(c, updates, issues)
                Row(Modifier.fillMaxWidth().padding(top = 20.dp, bottom = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    val count = filterPlugins(all, c.query, c.filter).size
                    Text(if (count == all.size) "${all.size} plugins" else "$count of ${all.size} plugins", style = MaterialTheme.typography.bodySmall, color = Palette.muted)
                    Text("${all.count { it.status.equals("loaded", true) }} loaded", style = MaterialTheme.typography.bodySmall, color = Palette.muted)
                }
                if (repo != null && updates > 0) {
                    Button(onClick = { c.update(null) }, enabled = !c.busy, modifier = Modifier.heightIn(min = 48.dp).tvFocusIndicator()) {
                        Text("Update $updates ${if (updates == 1) "plugin" else "plugins"}")
                    }
                }
            }
            val shown = filterPlugins(all, c.query, c.filter)
            if (shown.isEmpty()) item {
                Text("No plugin matches.", style = MaterialTheme.typography.bodyMedium, color = Palette.muted, modifier = Modifier.padding(vertical = 12.dp))
            }
            items(shown, key = { "plugin-" + it.name }) { p -> PluginRow(p, onOpen = { c.selected = p.name }, onToggle = { c.setEnabled(p, it) }, enabled = !c.busy) }
        }
    }
}

@Composable
private fun PluginSource(c: PluginsController, onRefresh: () -> Unit) {
    val source = c.source
    val repo = source?.mode == "repo"
    var expanded by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedButton(
            onClick = { if (repo) c.checkUpdates() else onRefresh() },
            enabled = !c.busy && c.plugins !is Load.Loading,
            shape = RoundedCornerShape(8.dp),
            colors = androidx.compose.material3.ButtonDefaults.outlinedButtonColors(containerColor = Palette.surface2, contentColor = Palette.text),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            modifier = Modifier.heightIn(min = 48.dp).tvFocusIndicator(),
        ) {
            if (c.busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            else Icon(Icons.Outlined.Refresh, null, Modifier.size(18.dp))
            Text(if (repo) "Check for updates" else "Refresh", Modifier.padding(start = 10.dp))
        }
        HorizontalDivider(Modifier.padding(top = 8.dp, bottom = 12.dp), color = MaterialTheme.colorScheme.outlineVariant)
        Text("Plugin source", style = MaterialTheme.typography.labelLarge)
        Surface(color = Palette.surface2, contentColor = Palette.text, shape = RoundedCornerShape(8.dp), border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
            Text(
                when { source == null -> "Source unavailable"; repo -> source.repoName ?: "Online repo (published plugins)"; else -> "Local extensions repo (built with gradle)" },
                Modifier.fillMaxWidth().padding(14.dp), style = MaterialTheme.typography.bodyMedium,
            )
        }
        TextButton(onClick = { expanded = !expanded }, contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp), modifier = Modifier.tvFocusIndicator()) {
            Text(if (repo) "Repository details" else "Local build details", color = Palette.muted)
            Icon(if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, null, Modifier.padding(start = 8.dp).size(18.dp), tint = Palette.muted)
        }
        if (expanded) {
            source?.let {
                Text(if (repo) it.repoUrl else it.devPath, style = MaterialTheme.typography.bodySmall, color = Palette.muted)
            }
            Text("Plugins run on your desktop. Change the source or rebuild local plugins from desktop settings.", style = MaterialTheme.typography.bodySmall, color = Palette.muted)
        }
        source?.error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Palette.danger) }
        HorizontalDivider(Modifier.padding(top = 8.dp, bottom = 14.dp), color = MaterialTheme.colorScheme.outlineVariant)
    }
}

@Composable
private fun PluginFilterMenu(c: PluginsController, updates: Int, issues: Int) {
    var expanded by remember { mutableStateOf(false) }
    Box(Modifier.padding(top = 12.dp)) {
        Surface(
            onClick = { expanded = true }, color = Palette.surface2, contentColor = Palette.text, shape = RoundedCornerShape(8.dp),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Filter plugins: ${c.filter.label}" },
        ) {
            Row(Modifier.heightIn(min = 48.dp).padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(c.filter.label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                Icon(Icons.Outlined.ExpandMore, null, Modifier.size(18.dp), tint = Palette.muted)
            }
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            PluginFilter.entries.forEach { filter ->
                val count = when (filter) { PluginFilter.All -> null; PluginFilter.Updates -> updates; PluginFilter.Issues -> issues }
                DropdownMenuItem(text = { Text(filter.label + (count?.let { " ($it)" } ?: "")) }, onClick = { c.filter = filter; expanded = false }, modifier = Modifier.tvFocusIndicator())
            }
        }
    }
}

private fun pluginSummary(p: PluginDto): String = listOfNotNull(
    p.version?.let { "v$it" },
    p.available?.takeIf { p.hasUpdate() }?.let { "v$it available" },
    p.providers.filterNot { it.equals(p.name, true) }.takeIf { it.isNotEmpty() }?.joinToString(", "),
    "NSFW".takeIf { p.nsfw },
    p.status.lowercase().replaceFirstChar { it.uppercase() }.takeIf { !p.status.equals("loaded", ignoreCase = true) },
).joinToString(" · ")

@Composable
private fun PluginRow(p: PluginDto, onOpen: () -> Unit, onToggle: (Boolean) -> Unit, enabled: Boolean) {
    Column {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 88.dp).tvFocusIndicator().clickable(role = Role.Button, onClickLabel = "Open ${p.name} details", onClick = onOpen).padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Box(Modifier.size(52.dp).clip(RoundedCornerShape(8.dp)).background(Palette.surface2), contentAlignment = Alignment.Center) {
                val fallback: @Composable () -> Unit = { Icon(Icons.Outlined.Extension, null, Modifier.size(28.dp), tint = Palette.muted) }
                val request = imageRequest(p.iconUrl)
                if (request == null) fallback() else SubcomposeAsyncImage(
                    model = request, contentDescription = null, modifier = Modifier.fillMaxSize().padding(4.dp),
                    contentScale = ContentScale.Fit, loading = { fallback() }, error = { fallback() },
                )
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(p.name, modifier = Modifier.weight(1f, fill = false), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Icon(Icons.Outlined.ExpandMore, null, Modifier.size(16.dp), tint = Palette.muted)
                }
                Text(
                    pluginSummary(p), style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    color = if (p.hasUpdate()) Palette.accent else if (p.hasIssue()) Palette.danger else Palette.muted,
                )
                if (p.error != null) Text(p.error!!, style = MaterialTheme.typography.bodySmall, color = Palette.danger, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Switch(
                p.enabled, onCheckedChange = onToggle, enabled = enabled,
                colors = SwitchDefaults.colors(
                    checkedTrackColor = Palette.accent, checkedThumbColor = Palette.text,
                    uncheckedTrackColor = Palette.surface2, uncheckedThumbColor = Palette.text, uncheckedBorderColor = Palette.muted,
                ),
                modifier = Modifier.semantics { contentDescription = "Enable ${p.name}"; stateDescription = if (p.enabled) "Enabled" else "Disabled" },
            )
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}

/** The details of one plugin: its switch, update and rollback, and the settings form it declares. */
@Composable
fun PluginSheet(c: PluginsController, api: Api, tell: (String) -> Unit) {
    val name = c.selected ?: return
    val p = (c.plugins as? Load.Ok)?.value?.firstOrNull { it.name == name }
    if (p == null) {
        c.selected = null
        return
    }
    val repo = c.source?.takeIf { it.mode == "repo" }
    AdaptiveSheet(
        onDismissRequest = { c.selected = null },
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = Gutter).padding(bottom = 32.dp)) {
            Text(p.name, style = MaterialTheme.typography.titleLarge)
            Text(pluginSummary(p).ifBlank { "Loaded" }, style = MaterialTheme.typography.bodySmall, color = Palette.muted)
            p.description?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = Palette.muted, modifier = Modifier.padding(top = 8.dp)) }
            if (p.error != null) Text(p.error!!, style = MaterialTheme.typography.bodySmall, color = Palette.danger, modifier = Modifier.padding(top = 8.dp))
            SwitchRow("Enabled", p.enabled) { c.setEnabled(p, it) }
            if (repo != null && (p.hasUpdate() || p.previous != null)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 8.dp)) {
                    if (p.hasUpdate()) Button(onClick = { c.update(listOf(p.name)) }, enabled = !c.busy, modifier = Modifier.heightIn(min = 48.dp).tvFocusIndicator()) { Text("Update to v${p.available}") }
                    if (p.previous != null) OutlinedButton(onClick = { c.rollback(p.name) }, enabled = !c.busy, modifier = Modifier.heightIn(min = 48.dp).tvFocusIndicator()) { Text("Roll back to v${p.previous}") }
                }
            }
            if (p.settingsSchema.isNotEmpty()) {
                Header("Settings")
                PluginSettingsForm(p, api, tell)
            } else if (p.settingsFiles.isNotEmpty()) {
                Text(
                    "This plugin keeps extra preferences that can be edited from the desktop settings.",
                    style = MaterialTheme.typography.bodySmall, color = Palette.muted, modifier = Modifier.padding(top = 8.dp),
                )
            }
        }
    }
}

private fun fieldId(f: SettingField) = "${f.file.orEmpty()}/${f.key}"
private fun prefType(f: SettingField) = when (f.type) { "boolean" -> "boolean"; "int" -> "int"; else -> "string" }

/** The plugin's declared settings as labelled fields, saved back through its preference files (as the web form does). */
@Composable
private fun PluginSettingsForm(p: PluginDto, api: Api, tell: (String) -> Unit) {
    var loaded by remember(p.name) { mutableStateOf<Load<PluginSettingsDto>>(Load.Loading) }
    val values = remember(p.name) { mutableStateMapOf<String, String>() }
    val revealed = remember(p.name) { mutableStateMapOf<String, Boolean>() }
    var saving by remember(p.name) { mutableStateOf(false) }
    var dirty by remember(p.name) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    fun fileOf(dto: PluginSettingsDto, f: SettingField) = f.file ?: dto.files.keys.firstOrNull() ?: p.name
    fun adopt(dto: PluginSettingsDto) {
        values.clear()
        for (f in p.settingsSchema) {
            val file = fileOf(dto, f)
            dto.files[file]?.firstOrNull { it.key == f.key }?.value?.let { values[fieldId(f)] = it }
        }
        dirty = false
    }
    LaunchedEffect(p.name) {
        loaded = runCatching { Load.Ok(api.pluginSettings(p.name).also(::adopt)) }.getOrElse { Load.Failed(it.userMessage()) }
    }

    when (val l = loaded) {
        Load.Loading -> Loading()
        is Load.Failed -> ErrorBox(l.message, onRetry = { loaded = Load.Loading; scope.launch { loaded = runCatching { Load.Ok(api.pluginSettings(p.name).also(::adopt)) }.getOrElse { Load.Failed(it.userMessage()) } } })
        is Load.Ok -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            p.settingsSchema.forEach { f ->
                val id = fieldId(f)
                val value = values[id].orEmpty()
                fun set(v: String) { values[id] = v; dirty = true }
                when (f.type) {
                    "boolean" -> SwitchRow(f.label, if (value.isEmpty()) f.default == "true" else value == "true", help = f.help) { set(it.toString()) }
                    "choice" -> Column {
                        Text(f.label)
                        f.help?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Palette.muted) }
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
                            items(f.options) { o -> FilterChip((value.ifEmpty { f.default.orEmpty() }) == o.value, { set(o.value) }, label = { Text(o.label) }, modifier = Modifier.heightIn(min = 48.dp).tvFocusIndicator()) }
                        }
                    }
                    else -> {
                        val secret = f.type == "secret"
                        val shown = revealed[id] == true
                        OutlinedTextField(
                            value, ::set,
                            label = { Text(f.label) },
                            supportingText = f.help?.let { h -> { Text(h) } },
                            placeholder = { Text(f.default?.let { "Default: $it" } ?: "Not set") },
                            singleLine = true,
                            visualTransformation = if (secret && !shown) PasswordVisualTransformation() else VisualTransformation.None,
                            keyboardOptions = KeyboardOptions(keyboardType = if (f.type == "int") KeyboardType.Number else KeyboardType.Text, autoCorrectEnabled = false),
                            trailingIcon = if (secret) ({
                                IconButton(onClick = { revealed[id] = !shown }, modifier = Modifier.tvFocusIndicator()) {
                                    Icon(if (shown) Icons.Filled.VisibilityOff else Icons.Filled.Visibility, if (shown) "Hide ${f.label}" else "Show ${f.label}")
                                }
                            }) else null,
                            modifier = Modifier.fillMaxWidth().tvFocusIndicator(),
                        )
                    }
                }
            }
            Button(
                enabled = dirty && !saving,
                modifier = Modifier.heightIn(min = 48.dp).tvFocusIndicator(),
                onClick = {
                    saving = true
                    scope.launch {
                        try {
                            val files = l.value.files.toMutableMap()
                            for (f in p.settingsSchema) {
                                val id = fieldId(f)
                                val file = fileOf(l.value, f)
                                val entries = files[file].orEmpty().filterNot { it.key == f.key }.toMutableList()
                                // Unset (or blanked) values are left out, so the plugin falls back to its default.
                                values[id]?.takeIf { it.isNotEmpty() }?.let { entries += PrefEntry(f.key, prefType(f), it) }
                                files[file] = entries
                            }
                            val saved = api.savePluginSettings(p.name, PluginSettingsDto(files.mapValues { (_, e) -> e.filter { it.key.isNotBlank() && !it.value.isNullOrEmpty() } }))
                            adopt(saved)
                            loaded = Load.Ok(saved)
                            tell("Saved ${p.name} settings")
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            tell("Couldn't save ${p.name} settings: ${e.userMessage()}")
                        } finally {
                            saving = false
                        }
                    }
                },
            ) { Text(if (saving) "Saving…" else "Save settings") }
        }
    }
}
