package com.istarvin.cloudstream.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.TrendingUp
import androidx.compose.material.icons.rounded.Cancel
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.LiveTv
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SmartDisplay
import androidx.compose.material.icons.rounded.Theaters
import androidx.compose.material.icons.rounded.Tv
import androidx.compose.material.icons.rounded.VideoLibrary
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.SubcomposeAsyncImage
import desktop.api.ProviderDto

@Composable
private fun SourceIcon(provider: ProviderDto?, size: Dp = 40.dp) {
    Box(
        Modifier.size(size).clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh),
        contentAlignment = Alignment.Center,
    ) {
        val fallback: @Composable () -> Unit = {
            Icon(Icons.Rounded.Layers, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(22.dp))
        }
        val request = imageRequest(provider?.iconUrl)
        if (request == null) fallback() else SubcomposeAsyncImage(
            model = request,
            contentDescription = null,
            modifier = Modifier.size(size - 8.dp),
            loading = { fallback() },
            error = { fallback() },
        )
    }
}

@Composable
fun SourceSelector(current: ProviderDto?, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        onClick = onClick,
        shape = MaterialTheme.shapes.medium,
        color = Palette.surface2,
        contentColor = Palette.text,
        modifier = modifier.heightIn(min = 48.dp).tvFocusIndicator().semantics { contentDescription = "Source: ${current?.name ?: "none chosen"}. Change source" },
    ) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            SourceIcon(current, size = 32.dp)
            Column(Modifier.weight(1f)) {
                Text(current?.name ?: "Choose a source", style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    current?.let { sourceLanguage(it.lang) + " · Change source" } ?: "Browse installed plugins",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
            Icon(Icons.Rounded.ExpandMore, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
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
    // Keep persisted selections visible even if the installed sources have changed.
    val filters = remember(providers, categories) {
        ContentTypes.groups.filter { g -> g.id in categories || providers.any { ContentTypes.matches(it.types, g.id) } }
    }
    val shown = remember(providers, categories, query) { filterSources(providers, categories, query) }
    val listState = rememberLazyListState()
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val hasFilters = categories.isNotEmpty() || query.isNotBlank()
    val resetFilters = { query = ""; onCategories(emptySet()) }
    LaunchedEffect(query, categories) { listState.scrollToItem(0) }

    AdaptiveSheet(
        onDismissRequest = onDismiss,
        sheetGesturesEnabled = false,
        dragHandle = null,
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(Modifier.fillMaxWidth().heightIn(max = 720.dp)) {
            Row(
                Modifier.fillMaxWidth().padding(start = 20.dp, top = 12.dp, end = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Choose a source", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f).semantics { heading() })
                IconButton(onClick = onDismiss, modifier = Modifier.tvFocusIndicator()) { Icon(Icons.Rounded.Close, "Close source picker") }
            }
            RemoteTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text("Find a source") },
                placeholder = { Text("Name, plugin or language") },
                leadingIcon = { Icon(Icons.Rounded.Search, null) },
                trailingIcon = {
                    if (query.isNotEmpty()) IconButton(onClick = { query = "" }, modifier = Modifier.tvFocusIndicator()) {
                        Icon(Icons.Rounded.Cancel, "Clear search", modifier = Modifier.size(20.dp))
                    }
                },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = {
                    keyboard?.hide()
                    focusManager.clearFocus()
                }),
                shape = MaterialTheme.shapes.small,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                    unfocusedBorderColor = Palette.line,
                ),
                modifier = (Modifier.fillMaxWidth().padding(horizontal = 20.dp)
                    .semantics { contentDescription = "Find a source by name, plugin or language" }),
            )
            Spacer(Modifier.height(if (LocalTv.current) 12.dp else 20.dp))
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f, fill = false).fillMaxWidth().selectableGroup(),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 24.dp),
            ) {
                item(key = "filters") {
                    Column(Modifier.padding(horizontal = 8.dp)) {
                        if (filters.isNotEmpty()) {
                            if (!LocalTv.current) Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.padding(bottom = 4.dp),
                            ) {
                                Icon(Icons.Rounded.Layers, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
                                Text("Content types", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            if (LocalTv.current) {
                                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    items(filters, key = { it.id }) { group ->
                                        SourceCategoryChip(group, categories, onCategories)
                                    }
                                }
                            } else {
                                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    filters.forEach { group -> SourceCategoryChip(group, categories, onCategories) }
                                }
                            }
                            Row(
                                Modifier.padding(top = 8.dp, bottom = if (LocalTv.current) 4.dp else 16.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Icon(Icons.Rounded.Info, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
                                Text("Select multiple. Leave empty to show every source.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        Row(
                            Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(top = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Icon(Icons.Rounded.Groups, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
                            Text(
                                "${shown.size} ${if (shown.size == 1) "source" else "sources"}${if (categories.isEmpty() && query.isBlank()) " available" else " matching"}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f).semantics { liveRegion = LiveRegionMode.Polite },
                            )
                            if (hasFilters) TextButton(onClick = resetFilters, contentPadding = PaddingValues(horizontal = 8.dp), modifier = Modifier.tvFocusIndicator()) {
                                Icon(Icons.Rounded.RestartAlt, null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.size(6.dp))
                                Text("Reset filters", style = MaterialTheme.typography.labelMedium)
                            }
                        }
                    }
                }
                items(shown, key = { it.name }, contentType = { "source" }) { provider ->
                    SourceRow(provider, selected = provider.name == selected) {
                        keyboard?.hide()
                        onPick(provider.name)
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
                        if (providers.isEmpty()) TextButton(onClick = onManage, modifier = Modifier.tvFocusIndicator()) { Text("Manage plugins") }
                        else TextButton(onClick = resetFilters, modifier = Modifier.tvFocusIndicator()) { Text("Reset filters") }
                    }
                }
            }
        }
    }
}

@Composable
private fun SourceRow(provider: ProviderDto, selected: Boolean, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Surface(
        modifier = Modifier.fillMaxWidth().tvFocusIndicator().clip(MaterialTheme.shapes.large)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick),
        shape = MaterialTheme.shapes.large,
        color = colors.surfaceContainerHigh,
        border = BorderStroke(1.dp, if (selected) colors.primary else colors.outlineVariant),
    ) {
        Row(
            Modifier.background(
                Brush.horizontalGradient(
                    if (selected) listOf(
                        colors.primary.copy(alpha = 0.16f).compositeOver(colors.surfaceContainerLow),
                        colors.primary.copy(alpha = 0.04f).compositeOver(colors.surfaceContainerLow),
                    ) else listOf(colors.surfaceContainerHigh, colors.surfaceContainerLow),
                ),
            ).padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SourceIcon(provider, size = 48.dp)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    provider.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    "${sourceLanguage(provider.lang)} · ${ContentTypes.labels(provider.types)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            // Reserve the selection indicator's space so long names wrap consistently.
            Box(
                Modifier.size(20.dp)
                    .background(if (selected) colors.primary else Color.Transparent, CircleShape)
                    .then(if (selected) Modifier else Modifier.border(1.5.dp, colors.outline, CircleShape)),
                contentAlignment = Alignment.Center,
            ) {
                if (selected) Icon(Icons.Rounded.Check, null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(16.dp))
            }
        }
    }
}

private fun contentTypeIcon(id: String): ImageVector = when (id) {
    "anime" -> Icons.Rounded.PlayArrow
    "movies" -> Icons.Rounded.Movie
    "series" -> Icons.Rounded.Tv
    "drama" -> Icons.Rounded.Theaters
    "cartoons" -> Icons.Rounded.SmartDisplay
    "documentaries" -> Icons.Rounded.VideoLibrary
    "live" -> Icons.AutoMirrored.Rounded.TrendingUp
    "torrent" -> Icons.Rounded.Download
    "adult" -> Icons.Rounded.Person
    else -> Icons.Rounded.LiveTv
}

@Composable
private fun SourceCategoryChip(group: ContentTypes.Group, categories: Set<String>, onCategories: (Set<String>) -> Unit) {
    val active = group.id in categories
    // Material keeps a 48dp touch target around the compact chip.
    // The label and width stay stable when selection changes.
    FilterChip(
        selected = active,
        onClick = { onCategories(if (active) categories - group.id else categories + group.id) },
        label = { Text(group.label, style = MaterialTheme.typography.labelMedium) },
        leadingIcon = { Icon(contentTypeIcon(group.id), null, modifier = Modifier.size(16.dp)) },
        shape = MaterialTheme.shapes.large,
        colors = FilterChipDefaults.filterChipColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            labelColor = MaterialTheme.colorScheme.onSurface,
            iconColor = MaterialTheme.colorScheme.onSurfaceVariant,
            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
            selectedLabelColor = MaterialTheme.colorScheme.onSurface,
            selectedLeadingIconColor = MaterialTheme.colorScheme.primary,
        ),
        border = FilterChipDefaults.filterChipBorder(
            enabled = true,
            selected = active,
            borderColor = MaterialTheme.colorScheme.outlineVariant,
            selectedBorderColor = MaterialTheme.colorScheme.primary,
            selectedBorderWidth = 1.dp,
        ),
        modifier = Modifier.tvFocusIndicator(),
    )
}
