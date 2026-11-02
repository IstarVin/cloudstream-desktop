package desktop.app.ui

import android.app.Application
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class SearchViewModel(application: Application) : AndroidViewModel(application) {
    private val api = application.app.api
    val results = MutableStateFlow<List<Pair<String, List<SearchItem>>>>(emptyList())
    val errors = MutableStateFlow<List<Pair<String, String>>>(emptyList())
    val searching = MutableStateFlow(false)
    var lastQuery = ""
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
                        "error" -> errors.update { it + ((ev.provider() ?: "?") to (ev.error() ?: "failed")) }
                    }
                }
            } catch (e: Exception) {
                if (e !is kotlinx.coroutines.CancellationException) errors.update { it + ("Search" to e.userMessage()) }
            } finally {
                searching.value = false
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

    LaunchedEffect(initial) { if (initial.isNotBlank() && initial != vm.lastQuery) vm.search(initial) }

    Column(Modifier.fillMaxSize()) {
        OutlinedTextField(
            query, { query = it },
            placeholder = { Text("Search all providers") },
            leadingIcon = { Icon(Icons.Filled.Search, null) },
            trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Filled.Clear, "Clear") } },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { keyboard?.hide(); vm.search(query) }),
            modifier = Modifier.fillMaxWidth().padding(16.dp),
        )
        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            item { FilterChip(type == null, { type = null }, label = { Text("All") }) }
            items(TYPE_GROUPS.keys.toList()) { t -> FilterChip(type == t, { type = if (type == t) null else t }, label = { Text(t) }) }
        }
        if (searching) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
        val visible = results.map { (p, items) -> p to items.filter { type == null || it.type in TYPE_GROUPS.getValue(type!!) } }
            .filter { it.second.isNotEmpty() }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
            items(visible, key = { it.first }) { (provider, items) ->
                Column(Modifier.padding(top = 16.dp)) {
                    SectionTitle(provider, Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
                    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        items(items, key = { it.url }) { item ->
                            Poster(item.name, item.poster, headers = item.posterHeaders,
                                subtitle = listOfNotNull(item.year?.toString(), item.type).joinToString(" · "),
                                badge = item.quality, onClick = { onTitle(item.provider, item.url) })
                        }
                    }
                }
            }
            if (!searching && vm.lastQuery.isNotEmpty() && visible.isEmpty()) {
                item { ErrorBox(if (results.isEmpty()) "Nothing found for “${vm.lastQuery}”." else "No results of this type.") }
            }
            if (errors.isNotEmpty()) {
                item { SectionTitle("Failed", Modifier.padding(start = 16.dp, top = 24.dp)) }
                items(errors, key = { "err-" + it.first }) { (provider, error) ->
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(provider)
                            Text(error, style = MaterialTheme.typography.bodySmall, color = Palette.muted, maxLines = 2)
                        }
                        IconButton(onClick = { vm.search(vm.lastQuery, only = provider) }) { Icon(Icons.Filled.Refresh, "Retry $provider") }
                    }
                }
            }
        }
    }
}
