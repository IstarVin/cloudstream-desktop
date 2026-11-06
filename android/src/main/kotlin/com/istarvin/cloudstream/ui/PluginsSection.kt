package com.istarvin.cloudstream.ui

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
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Build
import androidx.compose.material.icons.rounded.Extension
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
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
import androidx.compose.ui.semantics.heading
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
import com.istarvin.cloudstream.data.Api
import desktop.plugins.SettingField
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

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
    }.sortedWith(compareBy({ it.name.lowercase() }, { it.id }))
}

/**
 * Repo mode: [plugins] under each repo's label (its name, else its host), in the source's priority order; plugins from
 * no listed repo come last under "Other". Dev mode: one unlabelled group.
 */
fun groupByRepo(plugins: List<PluginDto>, source: PluginSourceDto?): List<Pair<String?, List<PluginDto>>> {
    if (source?.mode != "repo") return listOf(null to plugins)
    val repos = source.repos.ifEmpty { source.repoUrls.map { desktop.api.RepoDto(it) } }
    val known = repos.map { it.url }.toSet()
    val groups = repos.map { r ->
        (r.name ?: runCatching { java.net.URI(r.url).host }.getOrNull() ?: r.url) to plugins.filter { it.repo == r.url }
    } + ("Other" to plugins.filter { it.repo == null || it.repo !in known })
    return groups.filter { it.second.isNotEmpty() }
}

/** The plugins list's state and the writes it makes, so the lazy list and its detail sheet share one source of truth. */
class PluginsController(private val api: Api, private val scope: CoroutineScope, private val tell: (String) -> Unit) {
    var plugins by mutableStateOf<Load<List<PluginDto>>>(Load.Loading)
    var source by mutableStateOf<PluginSourceDto?>(null)
    var query by mutableStateOf("")
    var filter by mutableStateOf(PluginFilter.All)
    var selected by mutableStateOf<String?>(null)
    var selectedRepo by mutableStateOf<String?>(null)
    var busy by mutableStateOf(false)
    var addingRepo by mutableStateOf(false)
    /** The desktop is building or loading plugins. */
    var building by mutableStateOf(false)
    /** The last gradle build's output, while shown. */
    var buildLog by mutableStateOf<String?>(null)

    suspend fun load() {
        if (plugins is Load.Failed) plugins = Load.Loading
        plugins = runCatching { Load.Ok(api.plugins()) }.getOrElse { Load.Failed(it.userMessage()) }
        source = runCatching { api.pluginSource() }.getOrNull()
        building = runCatching { api.status().building }.getOrDefault(building)
    }

    /** Dev mode: rebuilds the local extensions repo with gradle, then reloads the list once the desktop is done. */
    fun rebuild() = run("Couldn't rebuild") {
        api.rebuildPlugins()
        building = true
        tell("Rebuilding plugins…")
        while (building) {
            kotlinx.coroutines.delay(1500)
            building = runCatching { api.status().building }.getOrDefault(false)
        }
        load()
        if (buildLog != null) buildLog = api.buildLog()
        tell("Rebuild finished")
    }

    fun toggleBuildLog() = run("Couldn't load the build log") {
        buildLog = if (buildLog == null) api.buildLog().ifBlank { "No build output yet." } else null
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
        api.setPluginEnabled(p.id, enabled)
        load()
    }

    /** Saves a new repo list; the desktop reloads plugins from it (and drops those of a removed repo), so reload once it's done. */
    private fun setRepos(urls: List<String>, failure: String, done: String) = run(failure) {
        api.updateSettings(JsonObject(mapOf("plugin_repo_urls" to JsonArray(urls.map(::JsonPrimitive)))))
        building = true
        tell(done)
        kotlinx.coroutines.delay(500)
        while (building) {
            building = runCatching { api.status().building }.getOrDefault(false)
            if (building) kotlinx.coroutines.delay(1500)
        }
        load()
    }

    fun addRepo(url: String, onAdded: () -> Unit) {
        val urls = source?.repoUrls.orEmpty()
        val u = url.trim()
        if (u.isEmpty() || u in urls) return
        if (!Regex("https?://\\S+|[A-Za-z0-9_-]+").matches(u)) { tell("Enter a repository URL or shortcode"); return }
        run("Couldn't add the repository") {
            addingRepo = true
            try {
                api.addRepository(u)
                load()
                onAdded()
            } finally {
                addingRepo = false
            }
        }
    }

    fun removeRepo(url: String) =
        setRepos(source?.repoUrls.orEmpty() - url, "Couldn't remove the repository", "Removing repository and its plugins…")

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
    if (c.source?.mode == "repo" && c.selectedRepo == null) {
        item { RepositoryList(c) }
        if (c.plugins is Load.Failed) item { ErrorBox((c.plugins as Load.Failed).message, onRetry = onRetry) }
        item { PluginSource(c, onRetry) }
        return
    }
    if (c.source?.mode == "repo") {
        item {
            Column(Modifier.padding(bottom = 12.dp)) {
                TextButton(
                    onClick = { c.selectedRepo = null; c.selected = null },
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
                    modifier = Modifier.tvFocusIndicator(),
                ) {
                    Icon(Icons.AutoMirrored.Rounded.ArrowBack, null, Modifier.size(18.dp))
                    Text("Repositories", Modifier.padding(start = 8.dp))
                }
                Text(
                    c.source?.repos?.firstOrNull { it.url == c.selectedRepo }?.name ?: runCatching { java.net.URI(c.selectedRepo).host }.getOrNull() ?: c.selectedRepo.orEmpty(),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(top = 8.dp).semantics { heading() },
                )
            }
        }
    } else item { PluginSource(c, onRetry) }
    when (val loaded = c.plugins) {
        Load.Loading -> item { Loading() }
        is Load.Failed -> item { ErrorBox(loaded.message, onRetry = onRetry) }
        is Load.Ok -> {
            val all = if (c.source?.mode == "repo") loaded.value.filter { it.repo == c.selectedRepo } else loaded.value
            if (all.isEmpty()) {
                item {
                    Text(
                        if (c.source?.mode == "repo") "No plugins found in this repository." else "No plugins found in this source. Refresh to try again.",
                        style = MaterialTheme.typography.bodyMedium, color = Palette.muted, modifier = Modifier.padding(vertical = 12.dp),
                    )
                }
                return
            }
            val updates = all.count { it.hasUpdate() }
            val issues = all.count { it.hasIssue() }
            val repo = c.source?.takeIf { it.mode == "repo" }
            item {
                RemoteTextField(
                    c.query, { c.query = it },
                    placeholder = { Text("Search plugins or providers") },
                    leadingIcon = { Icon(Icons.Rounded.Search, null) },
                    trailingIcon = { if (c.query.isNotEmpty()) IconButton(onClick = { c.query = "" }, modifier = Modifier.tvFocusIndicator()) { Icon(Icons.Rounded.Close, "Clear search") } },
                    singleLine = true,
                    shape = MaterialTheme.shapes.small,
                    colors = OutlinedTextFieldDefaults.colors(
                        unfocusedContainerColor = Palette.surface2,
                        focusedContainerColor = Palette.surface2,
                        unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
                    ),
                    modifier = (Modifier.fillMaxWidth().semantics { contentDescription = "Search plugins or providers" }),
                )
            }
            item {
                PluginFilters(c, updates, issues)
                Row(Modifier.fillMaxWidth().padding(top = 20.dp, bottom = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    val count = filterPlugins(all, c.query, c.filter).size
                    Text(if (count == all.size) "${all.size} plugins" else "$count of ${all.size} plugins", style = MaterialTheme.typography.bodySmall, color = Palette.muted)
                    Text("${all.count { it.status.equals("loaded", true) }} loaded", style = MaterialTheme.typography.bodySmall, color = Palette.muted)
                }
                if (repo != null && updates > 0) {
                    Button(shape = MaterialTheme.shapes.small, onClick = { c.update(all.filter { it.hasUpdate() }.map { it.id }) }, enabled = !c.busy, modifier = Modifier.heightIn(min = 48.dp).tvFocusIndicator()) {
                        Text("Update $updates ${if (updates == 1) "plugin" else "plugins"}")
                    }
                }
            }
            val shown = filterPlugins(all, c.query, c.filter)
            if (shown.isEmpty()) item {
                Column(Modifier.padding(vertical = 20.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("No plugins found", style = MaterialTheme.typography.titleSmall)
                    Text("Try a different search or filter.", style = MaterialTheme.typography.bodyMedium, color = Palette.muted)
                    TextButton(onClick = { c.query = ""; c.filter = PluginFilter.All }, modifier = Modifier.tvFocusIndicator()) { Text("Clear search and filters") }
                }
            }
            // Repo mode: a heading per repo, in priority order, so same-named plugins from two repos are told apart.
            for ((label, group) in groupByRepo(shown, if (c.selectedRepo != null) null else c.source)) {
                if (label != null) item(key = "repo-$label") {
                    Row(Modifier.fillMaxWidth().padding(top = 20.dp, bottom = 4.dp).semantics { heading() }, verticalAlignment = Alignment.CenterVertically) {
                        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("${group.size}", style = MaterialTheme.typography.bodySmall, color = Palette.muted)
                    }
                }
                items(group, key = { "plugin-" + it.id }) { p -> PluginRow(p, onOpen = { c.selected = p.id }, onToggle = { c.setEnabled(p, it) }, enabled = !c.busy) }
            }
        }
    }
}

@Composable
private fun RepositoryList(c: PluginsController) {
    var newRepo by remember { mutableStateOf("") }
    var removing by remember { mutableStateOf<String?>(null) }
    removing?.let { u ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { removing = null },
            title = { Text("Remove repository?") },
            text = { Text("Plugins installed from $u are removed too.") },
            confirmButton = { TextButton(onClick = { removing = null; c.removeRepo(u) }, modifier = Modifier.tvFocusIndicator()) { Text("Remove") } },
            dismissButton = { TextButton(onClick = { removing = null }, modifier = Modifier.tvFocusIndicator()) { Text("Cancel") } },
        )
    }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Repositories", Modifier.weight(1f).semantics { heading() }, style = MaterialTheme.typography.titleMedium)
            Text("${c.source?.repoUrls?.size ?: 0}", style = MaterialTheme.typography.bodySmall, color = Palette.muted)
        }
        val source = c.source
        source?.repoUrls?.forEach { u ->
            val info = source.repos.firstOrNull { it.url == u }
            val name = info?.name ?: runCatching { java.net.URI(u).host }.getOrNull() ?: u
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Row(Modifier.weight(1f).tvFocusIndicator().clickable { c.selectedRepo = u; c.query = ""; c.filter = PluginFilter.All }.padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(40.dp).clip(RoundedCornerShape(8.dp)).background(Palette.surface2), contentAlignment = Alignment.Center) {
                        val fallback: @Composable () -> Unit = { Icon(Icons.Rounded.Extension, null, Modifier.size(22.dp), tint = Palette.muted) }
                        val request = imageRequest(info?.iconUrl)
                        if (request == null) fallback() else SubcomposeAsyncImage(
                            model = request, contentDescription = null, modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Fit, loading = { fallback() }, error = { fallback() },
                        )
                    }
                    Column(Modifier.weight(1f).padding(start = 16.dp)) {
                        Text(name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        val count = (c.plugins as? Load.Ok)?.value?.count { it.repo == u }
                        Text(listOfNotNull(count?.let { "$it ${if (it == 1) "plugin" else "plugins"}" }, u).joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = Palette.muted, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        info?.error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Palette.danger) }
                    }
                    Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, Modifier.size(20.dp), tint = Palette.muted)
                }
                IconButton(onClick = { removing = u }, enabled = !c.busy && !c.building, modifier = Modifier.tvFocusIndicator()) {
                    Icon(Icons.Rounded.Delete, "Remove repository $name", Modifier.size(20.dp))
                }
            }
        }
        if (source?.repoUrls.isNullOrEmpty()) Text("Add a repository to browse its plugins.", style = MaterialTheme.typography.bodyMedium, color = Palette.muted, modifier = Modifier.padding(vertical = 8.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            RemoteTextField(newRepo, { newRepo = it }, Modifier.weight(1f), enabled = !c.busy && !c.building,
                label = { Text("Add repository") }, placeholder = { Text("Repository URL or shortcode") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = androidx.compose.ui.text.input.ImeAction.Done),
                keyboardActions = androidx.compose.foundation.text.KeyboardActions(onDone = { if (!c.busy && !c.building) c.addRepo(newRepo) { newRepo = "" } }))
            Button(onClick = { c.addRepo(newRepo) { newRepo = "" } }, enabled = newRepo.isNotBlank() && !c.busy && !c.building, modifier = Modifier.heightIn(min = 48.dp).tvFocusIndicator()) { Text(if (c.addingRepo) "Loading…" else "Add") }
        }
        if (c.addingRepo) Text("Loading repository…", style = MaterialTheme.typography.bodySmall, color = Palette.muted)
    }
}

@Composable
private fun PluginSource(c: PluginsController, onRefresh: () -> Unit) {
    val source = c.source
    val repo = source?.mode == "repo"
    var expanded by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(top = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedButton(shape = MaterialTheme.shapes.small,
            onClick = { if (repo) c.checkUpdates() else onRefresh() },
            enabled = !c.busy && c.plugins !is Load.Loading,
            colors = androidx.compose.material3.ButtonDefaults.outlinedButtonColors(containerColor = Palette.surface2, contentColor = Palette.text),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            modifier = Modifier.heightIn(min = 48.dp).tvFocusIndicator(),
        ) {
            if (c.busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            else Icon(Icons.Rounded.Refresh, null, Modifier.size(18.dp))
            Text(if (repo) "Check for updates" else "Refresh", Modifier.padding(start = 10.dp))
        }
        if (source != null && !repo) OutlinedButton(shape = MaterialTheme.shapes.small,
            onClick = { c.rebuild() },
            enabled = !c.busy && !c.building && source.devAvailable,
            colors = androidx.compose.material3.ButtonDefaults.outlinedButtonColors(containerColor = Palette.surface2, contentColor = Palette.text),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            modifier = Modifier.heightIn(min = 48.dp).tvFocusIndicator(),
        ) {
            if (c.building) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            else Icon(Icons.Rounded.Build, null, Modifier.size(18.dp))
            Text(if (c.building) "Building…" else "Rebuild", Modifier.padding(start = 10.dp))
        }
        if (!repo) {
            HorizontalDivider(Modifier.padding(top = 8.dp, bottom = 12.dp), color = MaterialTheme.colorScheme.outlineVariant)
            Text("Plugin source", style = MaterialTheme.typography.labelLarge)
            Surface(color = Palette.surface2, contentColor = Palette.text, shape = RoundedCornerShape(8.dp), border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
                Text(
                    if (source == null) "Source unavailable" else "Local extensions repo (built with gradle)",
                    Modifier.fillMaxWidth().padding(14.dp), style = MaterialTheme.typography.bodyMedium,
                )
            }
            TextButton(onClick = { expanded = !expanded }, contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp), modifier = Modifier.tvFocusIndicator()) {
                Text("Local build details", color = Palette.muted)
                Icon(if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, null, Modifier.padding(start = 8.dp).size(18.dp), tint = Palette.muted)
            }
            if (expanded) {
                source?.let {
                    Text(it.devPath, style = MaterialTheme.typography.bodySmall, color = Palette.muted)
                }
                Text("Plugins run on your desktop; switching between local and online plugins is done in desktop settings.", style = MaterialTheme.typography.bodySmall, color = Palette.muted)
                TextButton(onClick = { c.toggleBuildLog() }, contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp), modifier = Modifier.tvFocusIndicator()) {
                    Text(if (c.buildLog == null) "Show build log" else "Hide build log")
                }
                c.buildLog?.let { log ->
                    Surface(color = Palette.surface2, shape = RoundedCornerShape(8.dp)) {
                        Text(
                            log.takeLast(20_000),
                            Modifier.fillMaxWidth().heightIn(max = 360.dp).verticalScroll(rememberScrollState()).padding(12.dp),
                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace),
                            color = Palette.muted,
                        )
                    }
                }
            }
        }
        source?.error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Palette.danger) }
        if (!repo) HorizontalDivider(Modifier.padding(top = 8.dp, bottom = 14.dp), color = MaterialTheme.colorScheme.outlineVariant)
    }
}

@Composable
private fun PluginFilters(c: PluginsController, updates: Int, issues: Int) {
    LazyRow(
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(PluginFilter.entries) { filter ->
            val count = when (filter) { PluginFilter.All -> null; PluginFilter.Updates -> updates; PluginFilter.Issues -> issues }
            FilterChip(
                selected = c.filter == filter,
                onClick = { c.filter = filter },
                label = { Text(filter.label + (count?.let { " ($it)" } ?: "")) },
                modifier = Modifier.heightIn(min = 48.dp).tvFocusIndicator(),
            )
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
                val fallback: @Composable () -> Unit = { Icon(Icons.Rounded.Extension, null, Modifier.size(28.dp), tint = Palette.muted) }
                val request = imageRequest(p.iconUrl)
                if (request == null) fallback() else SubcomposeAsyncImage(
                    model = request, contentDescription = null, modifier = Modifier.fillMaxSize().padding(4.dp),
                    contentScale = ContentScale.Fit, loading = { fallback() }, error = { fallback() },
                )
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(p.name, modifier = Modifier.weight(1f, fill = false), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Icon(Icons.Rounded.ExpandMore, null, Modifier.size(16.dp), tint = Palette.muted)
                }
                Text(
                    pluginSummary(p), style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    color = if (p.hasIssue()) Palette.danger else if (p.hasUpdate()) Palette.accent else Palette.muted,
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
    val id = c.selected ?: return
    val p = (c.plugins as? Load.Ok)?.value?.firstOrNull { it.id == id }
    if (p == null) {
        c.selected = null
        return
    }
    val repo = c.source?.takeIf { it.mode == "repo" }
    AdaptiveSheet(
        onDismissRequest = { c.selected = null },
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        dragHandle = null,
    ) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(top = 16.dp, bottom = 24.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Column(Modifier.weight(1f).padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(p.name, style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
                    Text(pluginSummary(p).ifBlank { "Loaded" }, style = MaterialTheme.typography.bodySmall, color = Palette.muted)
                }
                IconButton(onClick = { c.selected = null }, modifier = Modifier.tvFocusIndicator()) {
                    Icon(Icons.Rounded.Close, "Close plugin details")
                }
            }
            p.description?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = Palette.muted, modifier = Modifier.padding(top = 16.dp, bottom = 8.dp)) }
            if (p.error != null) Text(p.error!!, style = MaterialTheme.typography.bodySmall, color = Palette.danger, modifier = Modifier.padding(top = 8.dp))
            SwitchRow("Enabled", p.enabled) { c.setEnabled(p, it) }
            if (repo != null && (p.hasUpdate() || p.previous != null)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 8.dp)) {
                    if (p.hasUpdate()) Button(shape = MaterialTheme.shapes.small, onClick = { c.update(listOf(p.id)) }, enabled = !c.busy, modifier = Modifier.heightIn(min = 48.dp).tvFocusIndicator()) { Text("Update to v${p.available}") }
                    if (p.previous != null) OutlinedButton(shape = MaterialTheme.shapes.small, onClick = { c.rollback(p.id) }, enabled = !c.busy, modifier = Modifier.heightIn(min = 48.dp).tvFocusIndicator()) { Text("Roll back to v${p.previous}") }
                }
            }
            if (p.settingsSchema.isNotEmpty()) {
                HorizontalDivider(modifier = Modifier.padding(top = 8.dp, bottom = 16.dp), color = MaterialTheme.colorScheme.outlineVariant)
                Text(
                    "Settings", style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 12.dp).semantics { heading() },
                )
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
    var loaded by remember(p.id) { mutableStateOf<Load<PluginSettingsDto>>(Load.Loading) }
    val values = remember(p.id) { mutableStateMapOf<String, String>() }
    val revealed = remember(p.id) { mutableStateMapOf<String, Boolean>() }
    var saving by remember(p.id) { mutableStateOf(false) }
    var dirty by remember(p.id) { mutableStateOf(false) }
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
    LaunchedEffect(p.id) {
        loaded = runCatching { Load.Ok(api.pluginSettings(p.id).also(::adopt)) }.getOrElse { Load.Failed(it.userMessage()) }
    }

    when (val l = loaded) {
        Load.Loading -> Loading()
        is Load.Failed -> ErrorBox(l.message, onRetry = { loaded = Load.Loading; scope.launch { loaded = runCatching { Load.Ok(api.pluginSettings(p.id).also(::adopt)) }.getOrElse { Load.Failed(it.userMessage()) } } })
        is Load.Ok -> Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            p.settingsSchema.forEach { f ->
                val id = fieldId(f)
                val value = values[id].orEmpty()
                fun set(v: String) { values[id] = v; dirty = true }
                when (f.type) {
                    "boolean" -> SwitchRow(f.label, if (value.isEmpty()) f.default == "true" else value == "true", help = f.help) { set(it.toString()) }
                    "choice" -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(f.label)
                        f.help?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Palette.muted) }
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
                            items(f.options) { o -> FilterChip((value.ifEmpty { f.default.orEmpty() }) == o.value, { set(o.value) }, label = { Text(o.label) }, modifier = Modifier.heightIn(min = 48.dp).tvFocusIndicator()) }
                        }
                    }
                    else -> {
                        val secret = f.type == "secret"
                        val shown = revealed[id] == true
                        RemoteTextField(
                            value, ::set,
                            label = { Text(f.label) },
                            supportingText = f.help?.let { h -> { Text(h) } },
                            placeholder = { Text(f.default?.let { "Default: $it" } ?: "Not set") },
                            singleLine = true,
                            visualTransformation = if (secret && !shown) PasswordVisualTransformation() else VisualTransformation.None,
                            keyboardOptions = KeyboardOptions(keyboardType = if (f.type == "int") KeyboardType.Number else KeyboardType.Text, autoCorrectEnabled = false),
                            trailingIcon = if (secret) ({
                                IconButton(onClick = { revealed[id] = !shown }, modifier = Modifier.tvFocusIndicator()) {
                                    Icon(if (shown) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility, if (shown) "Hide ${f.label}" else "Show ${f.label}")
                                }
                            }) else null,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
            Button(shape = MaterialTheme.shapes.small,
                enabled = dirty && !saving,
                modifier = Modifier.padding(top = 8.dp).fillMaxWidth().heightIn(min = 48.dp).tvFocusIndicator(),
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
                            val saved = api.savePluginSettings(p.id, PluginSettingsDto(files.mapValues { (_, e) -> e.filter { it.key.isNotBlank() && !it.value.isNullOrEmpty() } }))
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
