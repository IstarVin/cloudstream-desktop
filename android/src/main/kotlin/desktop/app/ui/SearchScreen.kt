package desktop.app.ui

import android.app.Application
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import desktop.api.SearchItem
import desktop.app.app
import desktop.app.data.error
import desktop.app.data.provider
import desktop.app.data.results
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class SearchViewModel(application: Application) : AndroidViewModel(application) {
    private val api = application.app.api
    val results = MutableStateFlow<List<Pair<String, List<SearchItem>>>>(emptyList())
    val errors = MutableStateFlow<List<Pair<String, String>>>(emptyList())
    val searching = MutableStateFlow(false)
    var lastQuery by mutableStateOf("")
        private set
    private var job: Job? = null

    /** Searches every provider, or only [only] (a retry); results arrive per provider over SSE. */
    fun search(q: String, only: String? = null) {
        val query = q.trim()
        if (query.isEmpty()) return
        job?.cancel()
        lastQuery = query
        if (only == null) {
            results.value = emptyList()
            errors.value = emptyList()
        } else {
            errors.update { e -> e.filterNot { it.first == only } }
        }
        searching.value = true
        job = viewModelScope.launch {
            try {
                api.search(query, only?.let(::listOf)).collect { ev ->
                    when (ev.event) {
                        "results" -> ev.results().let { r ->
                            if (r.items.isNotEmpty()) results.update { it.filterNot { p -> p.first == r.provider } + (r.provider to r.items) }
                        }
                        "error" -> {
                            val provider = ev.provider() ?: "Search"
                            errors.update { it.filterNot { error -> error.first == provider } + (provider to (ev.error() ?: "Search failed")) }
                        }
                    }
                }
            } catch (e: Exception) {
                if (e !is kotlinx.coroutines.CancellationException) errors.update { it.filterNot { error -> error.first == "Search" } + ("Search" to e.userMessage()) }
            } finally {
                if (currentCoroutineContext().isActive) searching.value = false
            }
        }
    }
}

private val TYPE_GROUPS = linkedMapOf(
    "Movies" to setOf("Movie", "AnimeMovie"),
    "Series" to setOf("TvSeries", "AsianDrama", "Cartoon", "Documentary"),
    "Anime" to setOf("Anime", "OVA", "AnimeMovie"),
    "Live" to setOf("Live"),
)

@Composable
fun SearchScreen(initial: String, onTitle: (String, String) -> Unit) {
    val vm: SearchViewModel = viewModel()
    var query by rememberSaveable { mutableStateOf(initial.ifEmpty { vm.lastQuery }) }
    var type by rememberSaveable { mutableStateOf<String?>(null) }
    val results by vm.results.collectAsState()
    val errors by vm.errors.collectAsState()
    val searching by vm.searching.collectAsState()
    val keyboard = LocalSoftwareKeyboardController.current
    fun submit() {
        if (query.isNotBlank()) {
            keyboard?.hide()
            vm.search(query)
        }
    }

    LaunchedEffect(initial) { if (initial.isNotBlank() && initial != vm.lastQuery) vm.search(initial) }

    Column(Modifier.fillMaxSize()) {
        ScreenHeading("Search")
        OutlinedTextField(
            query, { query = it },
            label = { Text("Search all sources") },
            placeholder = { Text("Movies, series, anime…") },
            leadingIcon = { Icon(Icons.Filled.Search, null) },
            trailingIcon = {
                Row {
                    if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Filled.Clear, "Clear search text") }
                    IconButton(onClick = ::submit, enabled = query.isNotBlank()) {
                        Icon(Icons.AutoMirrored.Filled.ArrowForward, "Submit search", tint = if (query.isNotBlank()) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f))
                    }
                }
            },
            singleLine = true,
            shape = RoundedCornerShape(12.dp),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { submit() }),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp),
        )
        LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(listOf("All") + TYPE_GROUPS.keys) { label ->
                val value = label.takeUnless { it == "All" }
                FilterChip(
                    selected = type == value,
                    onClick = { type = value },
                    label = { Text(label) },
                    leadingIcon = if (type == value) ({ Icon(Icons.Filled.Check, null, Modifier.size(16.dp)) }) else null,
                    modifier = Modifier.heightIn(min = 48.dp),
                )
            }
        }
        if (searching) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
        val visible = results.map { (p, items) -> p to items.filter { type == null || it.type in TYPE_GROUPS.getValue(type!!) } }
            .filter { it.second.isNotEmpty() }
        if (vm.lastQuery.isNotEmpty()) {
            val count = visible.sumOf { it.second.size }
            Text(
                if (searching) "Searching for “${vm.lastQuery}”… · $count found"
                else "$count ${if (count == 1) "result" else "results"} for “${vm.lastQuery}”",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp).semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
            if (searching && visible.isEmpty()) item { CatalogLoading() }
            if (!searching && vm.lastQuery.isEmpty()) item {
                EmptyState(Icons.Filled.Search, "Find your next watch", "Search your installed sources, then narrow the results by content type.")
            }
            items(visible, key = { it.first }) { (provider, items) ->
                Column(Modifier.padding(top = 20.dp)) {
                    SectionTitle(provider, Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
                    LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        items(items, key = { it.url }) { item ->
                            Poster(item.name, item.poster, headers = item.posterHeaders,
                                subtitle = listOfNotNull(item.year?.toString(), item.type).joinToString(" · "),
                                badge = item.quality, onClick = { onTitle(item.provider, item.url) })
                        }
                    }
                }
            }
            if (!searching && vm.lastQuery.isNotEmpty() && visible.isEmpty()) {
                if (results.isNotEmpty()) item {
                    EmptyState(Icons.Filled.Search, "No results of this type", "Try another content type to see more of your results.", actionLabel = "Show all results", onAction = { type = null })
                } else if (errors.isEmpty()) item {
                    EmptyState(Icons.Filled.Search, "No titles found", "Nothing matched “${vm.lastQuery}”. Try another title or a different spelling.")
                }
            }
            if (errors.isNotEmpty()) {
                item { SectionTitle("Sources unavailable", Modifier.padding(start = 20.dp, top = 24.dp, bottom = 8.dp)) }
                items(errors, key = { "err-" + it.first }) { (provider, error) ->
                    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(provider)
                            Text(error, style = MaterialTheme.typography.bodySmall, color = Palette.muted, maxLines = 2)
                        }
                        IconButton(onClick = { vm.search(vm.lastQuery, only = provider.takeUnless { it == "Search" }) }, enabled = !searching) { Icon(Icons.Filled.Refresh, "Retry $provider") }
                    }
                }
            }
        }
    }
}
