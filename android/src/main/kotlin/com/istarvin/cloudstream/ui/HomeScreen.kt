package com.istarvin.cloudstream.ui

import android.app.Application
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.material.icons.filled.Search
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import desktop.api.ContinueEntry
import desktop.api.HomePage
import desktop.api.HomeSection
import desktop.api.ProviderDto
import desktop.api.StatusDto
import com.istarvin.cloudstream.app
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class HomeViewModel(application: Application) : AndroidViewModel(application) {
    private val api = application.app.api
    private val prefs = application.app.prefs

    val providers = MutableStateFlow<Load<List<ProviderDto>>>(Load.Loading)
    val provider = MutableStateFlow<String?>(null)
    val categories = MutableStateFlow<Set<String>>(emptySet())
    private var sectionJob: Job? = null
    private val pageJobs = mutableListOf<Job>()
    val sections = MutableStateFlow<Load<List<HomeSection>>>(Load.Loading)
    val pages = mutableStateMapOf<Int, Load<HomePage>>()
    val continueWatching = MutableStateFlow<List<ContinueEntry>>(emptyList())
    val status = MutableStateFlow<StatusDto?>(null)
    val refreshing = MutableStateFlow(false)

    init {
        viewModelScope.launch { categories.value = ContentTypes.normalize(prefs.homeCategories.first()) }
        load()
        // Plugins build in the background after the server starts; show the progress and reload when they're in.
        viewModelScope.launch {
            while (true) {
                val s = runCatching { api.status() }.getOrNull()
                val wasBuilding = status.value?.building == true
                status.value = s
                if (s != null && wasBuilding && !s.building) load()
                delay(if (s?.building == true) 4000 else 30_000)
            }
        }
    }

    fun load() = viewModelScope.launch {
        refreshing.value = true
        try {
            val list = api.providers()
            providers.value = Load.Ok(list)
            val saved = prefs.homeProvider.first()
            val withHome = list.filter { it.hasMainPage }
            val chosen = withHome.firstOrNull { it.name == saved } ?: withHome.firstOrNull()
            choose(chosen?.name, remember = false)
        } catch (e: Exception) {
            providers.value = Load.Failed(e.userMessage())
        }
        runCatching { continueWatching.value = api.continueWatching() }
        refreshing.value = false
    }

    fun choose(name: String?, remember: Boolean = true) {
        sectionJob?.cancel()
        pageJobs.forEach { it.cancel() }
        pageJobs.clear()
        provider.value = name
        pages.clear()
        if (name == null) {
            sections.value = Load.Ok(emptyList())
            return
        }
        if (remember) viewModelScope.launch { prefs.setHomeProvider(name) }
        sections.value = Load.Loading
        sectionJob = viewModelScope.launch {
            sections.value = try {
                Load.Ok(api.homeSections(name))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Load.Failed(e.userMessage())
            }
        }
    }

    fun setCategories(ids: Set<String>) {
        categories.value = ContentTypes.normalize(ids)
        viewModelScope.launch { prefs.setHomeCategories(categories.value) }
    }

    fun loadSection(index: Int) {
        val p = provider.value ?: return
        if (pages[index] is Load.Ok || pages[index] == Load.Loading) return
        pages[index] = Load.Loading
        pageJobs += viewModelScope.launch {
            pages[index] = try {
                Load.Ok(api.home(p, index))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Load.Failed(e.userMessage())
            }
        }
    }
}

@Composable
fun HomeScreen(onTitle: (String, String) -> Unit, onSection: (String, Int, String, String) -> Unit, onManage: () -> Unit, onSearch: (String) -> Unit = {}) {
    val vm: HomeViewModel = viewModel()
    val providers by vm.providers.collectAsState()
    val provider by vm.provider.collectAsState()
    val sections by vm.sections.collectAsState()
    val cont by vm.continueWatching.collectAsState()
    val status by vm.status.collectAsState()
    val refreshing by vm.refreshing.collectAsState()
    val categories by vm.categories.collectAsState()
    val tv = LocalTv.current
    var query by rememberSaveable { mutableStateOf("") }
    var picking by rememberSaveable { mutableStateOf(false) }

    @Composable
    fun HomeSearch(modifier: Modifier) {
        RemoteTextField(
            value = query, onValueChange = { query = it },
            placeholder = { Text("Search titles…", maxLines = 1, overflow = TextOverflow.Ellipsis) },
            leadingIcon = { Icon(Icons.Filled.Search, null, tint = Palette.muted) },
            trailingIcon = {
                Button(shape = MaterialTheme.shapes.small, onClick = { onSearch(query.trim()) }, modifier = Modifier.padding(end = 6.dp).tvFocusIndicator(), contentPadding = PaddingValues(horizontal = 14.dp)) { Text("Go") }
            },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { onSearch(query.trim()) }),
            modifier = modifier,
        )
    }

    HomeRefreshContainer(isRefreshing = refreshing, onRefresh = { vm.load() }) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
            item {
                ScreenHeading("Home", subtitle = "Your next watch starts here.") {
                    if (tv) {
                        IconButton(onClick = { vm.load() }, enabled = !refreshing, modifier = Modifier.tvFocusIndicator()) {
                            Icon(Icons.Filled.Refresh, "Refresh home")
                        }
                    }
                    if (LocalCatalogLayout.current.expanded) HomeSearch(Modifier.width(320.dp))
                    CastButton()
                }
                if (!LocalCatalogLayout.current.expanded) HomeSearch(Modifier.fillMaxWidth().padding(horizontal = Gutter).padding(bottom = 20.dp))
                Surface(
                    Modifier.fillMaxWidth().padding(horizontal = Gutter),
                    color = Palette.surface, shape = MaterialTheme.shapes.medium,
                    border = BorderStroke(1.dp, Palette.line),
                ) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SourceSelector(
                            current = (providers as? Load.Ok)?.value?.firstOrNull { it.name == provider },
                            onClick = { picking = true },
                            modifier = if (LocalCatalogLayout.current.expanded) Modifier.width(360.dp) else Modifier.weight(1f),
                        )
                        if (LocalCatalogLayout.current.expanded) {
                            Spacer(Modifier.weight(1f))
                            TextButton(onClick = onManage, modifier = Modifier.tvFocusIndicator(), colors = ButtonDefaults.textButtonColors(contentColor = Palette.muted)) { Text("Manage plugins") }
                        } else IconButton(onClick = onManage, modifier = Modifier.tvFocusIndicator()) { Icon(Icons.Outlined.Extension, "Manage plugins", tint = Palette.muted) }
                    }
                }
            }
            if (tv && refreshing) {
                item { LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = Gutter)) }
            }
            status?.takeIf { it.building }?.let { s ->
                item {
                    Column(Modifier.padding(horizontal = Gutter, vertical = 4.dp).semantics { liveRegion = LiveRegionMode.Polite }, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Loading plugins… ${s.loaded} of ${s.plugins} ready", style = MaterialTheme.typography.bodySmall, color = Palette.muted)
                        LinearProgressIndicator(progress = { if (s.plugins > 0) s.loaded / s.plugins.toFloat() else 0f }, modifier = Modifier.fillMaxWidth())
                    }
                }
            }
            if (cont.isNotEmpty()) {
                item {
                    Text(
                        "Continue watching",
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.padding(start = Gutter, end = Gutter, top = 20.dp, bottom = 12.dp).semantics { heading() },
                    )
                }
                item {
                    LazyRow(contentPadding = PaddingValues(horizontal = Gutter), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        items(cont, key = { it.provider + it.url }) { c ->
                            ContinueWatchingCard(c, onClick = { onTitle(c.provider, c.url) })
                        }
                    }
                }
            }
            when (val p = providers) {
                is Load.Failed -> item { ErrorBox(p.message, onRetry = { vm.load() }) }
                Load.Loading -> item { CatalogLoading() }
                is Load.Ok -> when (val s = sections) {
                    Load.Loading -> item { CatalogLoading() }
                    is Load.Failed -> item { ErrorBox(s.message, onRetry = { vm.choose(provider) }) }
                    is Load.Ok -> {
                        if (s.value.isEmpty()) item {
                            EmptyState(
                                Icons.Filled.VideoLibrary,
                                if (provider == null) "Start with a source" else "No collections available",
                                if (provider == null) "Enable a plugin with a home page to start browsing." else "This source has no home collections right now. Try another source.",
                                actionLabel = if (provider == null) "Manage plugins" else "Change source",
                                onAction = if (provider == null) onManage else ({ picking = true }),
                            )
                        }
                        items(s.value, key = { "section-${it.index}" }) { section ->
                            HomeSectionRows(vm, section, onTitle = onTitle, onMore = { row -> onSection(provider!!, section.index, row, section.name) })
                        }
                    }
                }
            }
        }
    }

    if (picking) {
        ProviderPicker(
            providers = (providers as? Load.Ok)?.value?.filter { it.hasMainPage }.orEmpty(),
            selected = provider,
            categories = categories,
            onCategories = vm::setCategories,
            onManage = { picking = false; onManage() },
            onDismiss = { picking = false },
            onPick = {
                picking = false
                vm.choose(it)
            },
        )
    }
}

/** Focus-driven scrolling on TV must never enter the touch refresh gesture handler. */
@Composable
internal fun HomeRefreshContainer(isRefreshing: Boolean, onRefresh: () -> Unit, content: @Composable () -> Unit) {
    if (LocalTv.current) {
        Box(Modifier.fillMaxSize()) { content() }
    } else {
        PullToRefreshBox(isRefreshing = isRefreshing, onRefresh = onRefresh, modifier = Modifier.fillMaxSize()) {
            content()
        }
    }
}

/** Landscape artwork, metadata and an edge progress track, matching the web shelf. */
@Composable
private fun ContinueWatchingCard(entry: ContinueEntry, onClick: () -> Unit) {
    val progress = if (entry.duration.isFinite() && entry.duration > 0 && entry.position.isFinite()) {
        (entry.position / entry.duration).toFloat().coerceIn(0f, 1f)
    } else null
    val remaining = if (progress != null) remainingLabel(entry.position, entry.duration) else null
    Poster(
        title = entry.title, poster = entry.poster,
        width = LocalCatalogLayout.current.landscapeWidth, aspect = 16f / 9f,
        subtitle = listOfNotNull(entry.provider, entry.episodeLabel, remaining).joinToString(" · "),
        progress = progress, onClick = onClick,
    )
}

@Composable
private fun HomeSectionRows(vm: HomeViewModel, section: HomeSection, onTitle: (String, String) -> Unit, onMore: (String) -> Unit) {
    LaunchedEffect(section.index, vm.provider.collectAsState().value) { vm.loadSection(section.index) }
    when (val page = vm.pages[section.index]) {
        null, Load.Loading -> Column(Modifier.padding(Gutter)) {
            SectionTitle(section.name)
            PosterPlaceholders()
        }
        is Load.Failed -> Column(Modifier.padding(Gutter)) {
            SectionTitle(section.name)
            Text(page.message, color = Palette.muted, style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = { vm.loadSection(section.index) }, modifier = Modifier.tvFocusIndicator()) { Text("Retry") }
        }
        is Load.Ok -> page.value.rows.filter { it.items.isNotEmpty() }.forEach { row ->
            Column(Modifier.padding(top = 32.dp)) {
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(start = Gutter, end = Gutter, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(row.name, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f).semantics { heading() })
                    Spacer(Modifier.width(8.dp))
                    TextButton(colors = ButtonDefaults.textButtonColors(contentColor = Palette.muted), onClick = { onMore(row.name) }, modifier = (Modifier.heightIn(min = 48.dp).semantics { contentDescription = "See all ${row.name}" }).tvFocusIndicator()) {
                        Text("See all")
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, modifier = Modifier.size(18.dp))
                    }
                }
                LazyRow(contentPadding = PaddingValues(horizontal = Gutter), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    items(row.items, key = { it.provider + it.url }) { item ->
                        Poster(
                            item.name, item.poster,
                            headers = item.posterHeaders,
                            width = if (row.horizontal) LocalCatalogLayout.current.landscapeWidth else LocalCatalogLayout.current.posterWidth,
                            aspect = if (row.horizontal) 16f / 9f else 2f / 3f,
                            subtitle = item.year?.toString(),
                            badge = item.quality,
                            onClick = { onTitle(item.provider, item.url) },
                        )
                    }
                }
            }
        }
    }
}
