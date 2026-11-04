package com.istarvin.cloudstream.ui

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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import desktop.api.HomePage
import desktop.api.HomeRow
import com.istarvin.cloudstream.app
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

/** Shared pagination state; callers launch requests in their owning ViewModel's scope. */
internal class HomeSectionPager {
    val pages = mutableStateListOf<HomePage>()
    var loading by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    val rows get() = HomePages.merge(pages)
    val hasMore get() = HomePages.hasMore(pages)

    suspend fun more(fetch: suspend (Int) -> HomePage) {
        if (loading || !hasMore) return
        loading = true
        error = null
        try {
            pages.add(fetch(pages.size + 1))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = e.userMessage()
        } finally {
            loading = false
        }
    }
}

class SectionViewModel(application: Application) : AndroidViewModel(application) {
    internal val pager = HomeSectionPager()

    fun more(provider: String, section: Int) {
        viewModelScope.launch { pager.more { page -> getApplication<Application>().app.api.home(provider, section, page) } }
    }
}

@Composable
fun SectionScreen(provider: String, section: Int, row: String, name: String, onTitle: (String, String) -> Unit, onBack: () -> Unit) {
    // Navigation scopes this ViewModel to the section's back-stack entry, so opening a
    // title retains its pages. The saveable grid state can then restore the same position.
    val vm: SectionViewModel = viewModel()
    val pager = vm.pager
    val pages = pager.pages
    val loading = pager.loading
    val error = pager.error
    val grid = rememberLazyGridState()
    fun more() = vm.more(provider, section)

    LaunchedEffect(Unit) { if (pages.isEmpty() && error == null) more() }
    val rows = remember(pages.toList()) { pager.rows }
    val items = HomePages.rowItems(rows, row)
    val nearEnd by remember { derivedStateOf { (grid.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0) >= grid.layoutInfo.totalItemsCount - 6 } }
    LaunchedEffect(nearEnd, pages.size) { if (nearEnd && error == null) more() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(row.ifBlank { name }) },
                navigationIcon = { IconButton(onClick = onBack, modifier = Modifier.tvFocusIndicator()) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                actions = { CastButton() },
            )
        },
    ) { padding ->
        LazyVerticalGrid(
            GridCells.Adaptive(LocalCatalogLayout.current.gridMinWidth),
            state = grid,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = Gutter, end = Gutter, top = padding.calculateTopPadding() + 8.dp, bottom = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            items(items, key = { it.provider + it.url }) { item ->
                Poster(item.name, item.poster, headers = item.posterHeaders, width = null, subtitle = item.year?.toString(),
                    badge = item.quality, onClick = { onTitle(item.provider, item.url) })
            }
            if (loading) item(span = { GridItemSpan(maxLineSpan) }) { Loading() }
            error?.let { item(span = { GridItemSpan(maxLineSpan) }) { ErrorBox(it, onRetry = { more() }) } }
            if (!loading && error == null && pages.isNotEmpty() && items.isEmpty()) item(span = { GridItemSpan(maxLineSpan) }) {
                EmptyState(Icons.Filled.VideoLibrary, "No titles available", "This collection is empty right now. Go back to browse another collection.", actionLabel = "Back to browsing", onAction = onBack)
            }
        }
    }
}
