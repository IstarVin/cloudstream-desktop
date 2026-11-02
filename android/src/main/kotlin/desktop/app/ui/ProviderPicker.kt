package desktop.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.SubcomposeAsyncImage
import desktop.api.ProviderDto

@Composable
private fun SourceIcon(provider: ProviderDto?) {
    Box(
        Modifier.size(44.dp).clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh),
        contentAlignment = Alignment.Center,
    ) {
        val fallback: @Composable () -> Unit = {
            Icon(Icons.Filled.Layers, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(22.dp))
        }
        val request = imageRequest(provider?.iconUrl)
        if (request == null) fallback() else SubcomposeAsyncImage(
            model = request,
            contentDescription = null,
            modifier = Modifier.size(32.dp),
            loading = { fallback() },
            error = { fallback() },
        )
    }
}

@Composable
fun SourceSelector(current: ProviderDto?, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            SourceIcon(current)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(current?.name ?: "Choose a source", style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(
                    current?.let { "${sourceLanguage(it.lang)} · Change source" } ?: "Browse installed plugins",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(Icons.Filled.ExpandMore, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Category chips are independent toggles, just like the web source picker. */
@Composable
fun ProviderPicker(
    providers: List<ProviderDto>,
    selected: String?,
    categories: Set<String>,
    onCategories: (Set<String>) -> Unit,
    onDismiss: () -> Unit,
    onPick: (String) -> Unit,
    onManage: () -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    val filters = remember(providers) { ContentTypes.groups.filter { g -> providers.any { ContentTypes.matches(it.types, g.id) } } }
    val shown = remember(providers, categories, query) { filterSources(providers, categories, query) }
    val listState = rememberLazyListState()
    LaunchedEffect(query, categories) { listState.scrollToItem(0) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        sheetMaxWidth = 640.dp,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(Modifier.fillMaxHeight(0.92f)) {
            Row(
                Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Choose a source", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f).semantics { heading() })
                IconButton(onClick = onDismiss) { Icon(Icons.Filled.Close, "Close source picker") }
            }
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text("Find a source") },
                placeholder = { Text("Name, plugin or language") },
                leadingIcon = { Icon(Icons.Filled.Search, null) },
                trailingIcon = {
                    if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Filled.Close, "Clear search") }
                },
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
            )
            Spacer(Modifier.height(12.dp))
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 24.dp),
            ) {
                item(key = "filters") {
                    Column(Modifier.padding(horizontal = 8.dp)) {
                        if (filters.isNotEmpty()) {
                            Text("Content types", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                filters.forEach { group ->
                                    val active = group.id in categories
                                    FilterChip(
                                        selected = active,
                                        onClick = { onCategories(if (active) categories - group.id else categories + group.id) },
                                        label = { Text(group.label) },
                                        leadingIcon = if (active) ({ Icon(Icons.Filled.Check, null, Modifier.size(16.dp)) }) else null,
                                        modifier = Modifier.heightIn(min = 48.dp),
                                    )
                                }
                            }
                            Text("Select multiple. Leave empty to show every source.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.height(12.dp))
                        }
                        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "${shown.size} ${if (shown.size == 1) "source" else "sources"}${if (categories.isEmpty()) " available" else " matching"}",
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f),
                            )
                            if (categories.isNotEmpty() || query.isNotBlank()) TextButton(onClick = { query = ""; onCategories(emptySet()) }) { Text("Reset filters") }
                        }
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        Spacer(Modifier.height(8.dp))
                    }
                }
                items(shown, key = { it.name }) { provider ->
                    val active = provider.name == selected
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                            .background(if (active) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerLow)
                            .selectable(selected = active, role = Role.RadioButton, onClick = { onPick(provider.name) })
                            .padding(horizontal = 12.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        SourceIcon(provider)
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(provider.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text(
                                "${sourceLanguage(provider.lang)} · ${ContentTypes.labels(provider.types)}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 3,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        if (active) Icon(Icons.Filled.Check, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                    }
                }
                if (shown.isEmpty()) item(key = "empty") {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 28.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(if (providers.isEmpty()) "No home sources available" else "No matching sources", style = MaterialTheme.typography.titleMedium)
                        Text(
                            if (providers.isEmpty()) "Enable a plugin with a home page to start browsing."
                            else if (query.isNotBlank()) "Try another name, plugin or language, or reset your filters."
                            else "Try another content type, or reset your filters to see every source.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (providers.isEmpty()) TextButton(onClick = onManage) { Text("Manage plugins") }
                    }
                }
            }
        }
    }
}
