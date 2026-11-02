package desktop.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.History
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import desktop.api.Bookmark
import desktop.api.HistoryEntry
import desktop.app.app
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

val BOOKMARK_LISTS = listOf("watching" to "Watching", "planned" to "Plan to watch", "completed" to "Completed", "dropped" to "Dropped")

@Composable
fun LibraryScreen(onTitle: (String, String) -> Unit, onBrowse: () -> Unit) {
    val api = LocalContext.current.app.api
    val scope = rememberCoroutineScope()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var history by remember { mutableStateOf<Load<List<HistoryEntry>>>(Load.Loading) }
    var bookmarks by remember { mutableStateOf<Load<List<Bookmark>>>(Load.Loading) }
    var list by rememberSaveable { mutableStateOf<String?>(null) }
    var confirmClear by remember { mutableStateOf(false) }
    var changingHistory by remember { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    val historyGrid = rememberLazyGridState()
    val bookmarkGrid = rememberLazyGridState()

    fun reload() = scope.launch {
        if (history is Load.Failed) history = Load.Loading
        if (bookmarks is Load.Failed) bookmarks = Load.Loading
        launch { history = runCatching { Load.Ok(api.history()) }.getOrElse { Load.Failed(it.userMessage()) } }
        launch { bookmarks = runCatching { Load.Ok(api.bookmarks()) }.getOrElse { Load.Failed(it.userMessage()) } }
    }
    fun changeHistory(action: suspend () -> Unit) = scope.launch {
        changingHistory = true
        try {
            action()
            reload().join()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            changingHistory = false
            snackbar.showSnackbar("Couldn't update history: ${e.userMessage()}")
        } finally {
            changingHistory = false
        }
    }
    LaunchedEffect(Unit) { reload() }
    LaunchedEffect(list) { bookmarkGrid.scrollToItem(0) }
    LaunchedEffect(bookmarks) {
        val saved = (bookmarks as? Load.Ok)?.value
        if (saved != null && list != null && saved.none { it.list == list }) list = null
    }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            ScreenHeading("Library")
            PrimaryTabRow(selectedTabIndex = tab) {
                Tab(tab == 0, { tab = 0 }, text = { Text("History") })
                Tab(tab == 1, { tab = 1 }, text = { Text("Bookmarks") })
            }
            val grid: @Composable (content: androidx.compose.foundation.lazy.grid.LazyGridScope.() -> Unit) -> Unit = { content ->
                LazyVerticalGrid(
                    GridCells.Adaptive(100.dp),
                    state = if (tab == 0) historyGrid else bookmarkGrid,
                    contentPadding = PaddingValues(20.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    modifier = Modifier.fillMaxSize(),
                    content = content,
                )
            }
            when (tab) {
                0 -> when (val h = history) {
                    Load.Loading -> CatalogLoading()
                    is Load.Failed -> ErrorBox(h.message, onRetry = { reload() })
                    is Load.Ok -> if (h.value.isEmpty()) grid {
                        item(span = { GridItemSpan(maxLineSpan) }) {
                            EmptyState(Icons.Filled.History, "Nothing watched yet", "Titles you play will appear here, ready to pick up where you left off.", actionLabel = "Browse titles", onAction = onBrowse)
                        }
                    } else grid {
                        item(span = { GridItemSpan(maxLineSpan) }) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("${h.value.size} ${if (h.value.size == 1) "title" else "titles"}", style = MaterialTheme.typography.bodySmall, color = Palette.muted, modifier = Modifier.weight(1f))
                                TextButton(onClick = { confirmClear = true }, enabled = !changingHistory) { Text("Clear history") }
                            }
                        }
                        items(h.value, key = { it.provider + it.url }) { e ->
                            Box {
                                Poster(e.title, e.poster, width = null, subtitle = e.provider, onClick = { onTitle(e.provider, e.url) })
                                FilledIconButton(
                                    onClick = { changeHistory { api.forget(e.provider, e.url) } },
                                    enabled = !changingHistory,
                                    colors = IconButtonDefaults.filledIconButtonColors(containerColor = Color.Black.copy(alpha = 0.72f), contentColor = Color.White),
                                    modifier = Modifier.align(Alignment.TopEnd).padding(2.dp),
                                ) { Icon(Icons.Filled.Close, "Forget ${e.title}") }
                            }
                        }
                    }
                }
                else -> when (val b = bookmarks) {
                    Load.Loading -> CatalogLoading()
                    is Load.Failed -> ErrorBox(b.message, onRetry = { reload() })
                    is Load.Ok -> grid {
                        item(span = { GridItemSpan(maxLineSpan) }) {
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                item { FilterChip(list == null, { list = null }, label = { Text("All") }, modifier = Modifier.heightIn(min = 48.dp)) }
                                BOOKMARK_LISTS.forEach { (id, label) ->
                                    val n = b.value.count { it.list == id }
                                    if (n > 0) item(key = id) { FilterChip(list == id, { list = id }, label = { Text("$label $n") }, modifier = Modifier.heightIn(min = 48.dp)) }
                                }
                            }
                        }
                        val shown = b.value.filter { list == null || it.list == list }
                        if (shown.isEmpty()) item(span = { GridItemSpan(maxLineSpan) }) {
                            EmptyState(Icons.Filled.BookmarkBorder, "No bookmarks yet", "Save a title to a list from its detail page to find it here.", actionLabel = "Browse titles", onAction = onBrowse)
                        }
                        items(shown, key = { it.provider + it.url }) { e ->
                            Poster(e.title, e.poster, width = null,
                                subtitle = BOOKMARK_LISTS.firstOrNull { it.first == e.list }?.second, onClick = { onTitle(e.provider, e.url) })
                        }
                    }
                }
            }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Clear history?") },
            text = { Text("This forgets every title and its progress, on the desktop too.", style = MaterialTheme.typography.bodyMedium) },
            confirmButton = {
                TextButton(onClick = {
                    confirmClear = false
                    changeHistory { api.clearHistory() }
                }) { Text("Clear") }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel") } },
        )
    }
}
