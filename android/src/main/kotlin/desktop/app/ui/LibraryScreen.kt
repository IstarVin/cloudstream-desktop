package desktop.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.unit.dp
import desktop.api.Bookmark
import desktop.api.HistoryEntry
import desktop.app.app
import kotlinx.coroutines.launch

val BOOKMARK_LISTS = listOf("watching" to "Watching", "planned" to "Plan to watch", "completed" to "Completed", "dropped" to "Dropped")

@Composable
fun LibraryScreen(onTitle: (String, String) -> Unit) {
    val api = LocalContext.current.app.api
    val scope = rememberCoroutineScope()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var history by remember { mutableStateOf<Load<List<HistoryEntry>>>(Load.Loading) }
    var bookmarks by remember { mutableStateOf<Load<List<Bookmark>>>(Load.Loading) }
    var list by rememberSaveable { mutableStateOf<String?>(null) }
    var confirmClear by remember { mutableStateOf(false) }

    fun reload() = scope.launch {
        history = runCatching { Load.Ok(api.history()) }.getOrElse { Load.Failed(it.userMessage()) }
        bookmarks = runCatching { Load.Ok(api.bookmarks()) }.getOrElse { Load.Failed(it.userMessage()) }
    }
    LaunchedEffect(Unit) { reload() }

    Column(Modifier.fillMaxSize()) {
        PrimaryTabRow(selectedTabIndex = tab) {
            Tab(tab == 0, { tab = 0 }, text = { Text("History") })
            Tab(tab == 1, { tab = 1 }, text = { Text("Bookmarks") })
        }
        val grid: @Composable (content: androidx.compose.foundation.lazy.grid.LazyGridScope.() -> Unit) -> Unit = { content ->
            LazyVerticalGrid(
                GridCells.Adaptive(110.dp),
                contentPadding = PaddingValues(16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier.fillMaxSize(),
                content = content,
            )
        }
        when (tab) {
            0 -> when (val h = history) {
                Load.Loading -> Loading()
                is Load.Failed -> ErrorBox(h.message, onRetry = { reload() })
                is Load.Ok -> if (h.value.isEmpty()) ErrorBox("Nothing watched yet.") else grid {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("${h.value.size} titles", color = Palette.muted, modifier = Modifier.weight(1f))
                            TextButton(onClick = { confirmClear = true }) { Text("Clear history") }
                        }
                    }
                    items(h.value, key = { it.provider + it.url }) { e ->
                        Box {
                            Poster(e.title, e.poster, width = null, subtitle = e.provider, onClick = { onTitle(e.provider, e.url) })
                            IconButton(
                                onClick = { scope.launch { runCatching { api.forget(e.provider, e.url) }; reload() } },
                                modifier = Modifier.align(Alignment.TopEnd),
                            ) { Icon(Icons.Filled.Close, "Forget ${e.title}") }
                        }
                    }
                }
            }
            else -> when (val b = bookmarks) {
                Load.Loading -> Loading()
                is Load.Failed -> ErrorBox(b.message, onRetry = { reload() })
                is Load.Ok -> grid {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(list == null, { list = null }, label = { Text("All") })
                            BOOKMARK_LISTS.forEach { (id, label) ->
                                val n = b.value.count { it.list == id }
                                if (n > 0) FilterChip(list == id, { list = id }, label = { Text("$label $n") })
                            }
                        }
                    }
                    val shown = b.value.filter { list == null || it.list == list }
                    if (shown.isEmpty()) item(span = { GridItemSpan(maxLineSpan) }) { ErrorBox("No bookmarks yet.") }
                    items(shown, key = { it.provider + it.url }) { e ->
                        Poster(e.title, e.poster, width = null,
                            subtitle = BOOKMARK_LISTS.firstOrNull { it.first == e.list }?.second, onClick = { onTitle(e.provider, e.url) })
                    }
                }
            }
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Clear history?") },
            text = { Text("This forgets every title and its progress, on the desktop too.", style = MaterialTheme.typography.bodyMedium) },
            confirmButton = {
                TextButton(onClick = {
                    confirmClear = false
                    scope.launch { runCatching { api.clearHistory() }; reload() }
                }) { Text("Clear") }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel") } },
        )
    }
}
