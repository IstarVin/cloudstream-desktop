package desktop.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import desktop.api.HomePage
import desktop.api.HomeRow
import desktop.app.app
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** Merges a home section's pages into rows, dropping repeats (`web/src/lib/home.ts`). */
object HomePages {
    fun merge(pages: List<HomePage>): List<HomeRow> {
        val rows = LinkedHashMap<String, HomeRow>()
        val seen = HashMap<String, MutableSet<Pair<String, String>>>()
        for (page in pages) for (row in page.rows) {
            val keys = seen.getOrPut(row.name) { mutableSetOf() }
            val fresh = row.items.filter { keys.add(it.provider to it.url) }
            rows[row.name] = rows[row.name]?.let { it.copy(items = it.items + fresh) } ?: row.copy(items = fresh)
        }
        return rows.values.toList()
    }

    /** Keep a missing collection empty instead of substituting an unrelated row. */
    fun rowItems(rows: List<HomeRow>, name: String) = rows.firstOrNull { it.name == name }?.items.orEmpty()

    /** Whether to ask for another page: the provider says so and the last page added something new. */
    fun hasMore(pages: List<HomePage>): Boolean {
        val last = pages.lastOrNull() ?: return true
        if (!last.hasNext) return false
        val before = merge(pages.dropLast(1)).associate { r -> r.name to r.items.map { it.provider to it.url }.toSet() }
        return last.rows.any { row -> row.items.any { (it.provider to it.url) !in before[row.name].orEmpty() } }
    }
}

@Composable
fun SectionScreen(provider: String, section: Int, row: String, name: String, onTitle: (String, String) -> Unit, onBack: () -> Unit) {
    val api = LocalContext.current.app.api
    val scope = rememberCoroutineScope()
    val pages = remember { mutableStateListOf<HomePage>() }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val grid = rememberLazyGridState()

    fun more() {
        if (loading || !HomePages.hasMore(pages)) return
        loading = true
        scope.launch {
            try {
                pages.add(api.home(provider, section, pages.size + 1))
                error = null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = e.userMessage()
            } finally {
                loading = false
            }
        }
    }

    LaunchedEffect(Unit) { if (pages.isEmpty()) more() }
    val rows = remember(pages.toList()) { HomePages.merge(pages) }
    val items = HomePages.rowItems(rows, row)
    val nearEnd by remember { derivedStateOf { (grid.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0) >= grid.layoutInfo.totalItemsCount - 6 } }
    LaunchedEffect(nearEnd, pages.size) { if (nearEnd && error == null) more() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(row.ifBlank { name }) },
                navigationIcon = { IconButton(onClick = onBack, modifier = Modifier.tvFocusIndicator()) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            )
        },
    ) { padding ->
        LazyVerticalGrid(
            GridCells.Adaptive(if (LocalTv.current) 140.dp else 100.dp),
            state = grid,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = Gutter, end = Gutter, top = padding.calculateTopPadding() + 8.dp, bottom = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            items(items, key = { it.provider + it.url }) { item ->
                Poster(item.name, item.poster, headers = item.posterHeaders, width = null, subtitle = item.year?.toString(),
                    badge = item.quality, onClick = { onTitle(item.provider, item.url) })
            }
            if (loading) item(span = { GridItemSpan(maxLineSpan) }) { Loading() }
            error?.let { item(span = { GridItemSpan(maxLineSpan) }) { ErrorBox(it, onRetry = { error = null; more() }) } }
            if (!loading && error == null && pages.isNotEmpty() && items.isEmpty()) item(span = { GridItemSpan(maxLineSpan) }) {
                EmptyState(Icons.Filled.VideoLibrary, "No titles available", "This collection is empty right now. Go back to browse another collection.", actionLabel = "Back to browsing", onAction = onBack)
            }
        }
    }
}
