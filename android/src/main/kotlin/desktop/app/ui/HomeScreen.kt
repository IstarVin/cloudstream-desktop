package desktop.app.ui

import android.app.Application
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import coil3.compose.AsyncImage
import desktop.api.ContinueEntry
import desktop.api.HomePage
import desktop.api.HomeSection
import desktop.api.ProviderDto
import desktop.api.StatusDto
import desktop.app.app
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class HomeViewModel(application: Application) : AndroidViewModel(application) {
    private val api = application.app.api
    private val prefs = application.app.prefs

    val providers = MutableStateFlow<Load<List<ProviderDto>>>(Load.Loading)
    val provider = MutableStateFlow<String?>(null)
    val typeFilter = MutableStateFlow("all")
    val sections = MutableStateFlow<Load<List<HomeSection>>>(Load.Loading)
    val pages = mutableStateMapOf<Int, Load<HomePage>>()
    val continueWatching = MutableStateFlow<List<ContinueEntry>>(emptyList())
    val status = MutableStateFlow<StatusDto?>(null)
    val refreshing = MutableStateFlow(false)

    init {
        viewModelScope.launch { typeFilter.value = prefs.homeType.first() }
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
        provider.value = name
        pages.clear()
        if (name == null) {
            sections.value = Load.Ok(emptyList())
            return
        }
        if (remember) viewModelScope.launch { prefs.setHomeProvider(name) }
        sections.value = Load.Loading
        viewModelScope.launch {
            sections.value = try {
                Load.Ok(api.homeSections(name))
            } catch (e: Exception) {
                Load.Failed(e.userMessage())
            }
        }
    }

    fun setType(id: String) {
        typeFilter.value = id
        viewModelScope.launch { prefs.setHomeType(id) }
    }

    fun loadSection(index: Int) {
        val p = provider.value ?: return
        if (pages[index] is Load.Ok) return
        pages[index] = Load.Loading
        viewModelScope.launch {
            pages[index] = try {
                Load.Ok(api.home(p, index))
            } catch (e: Exception) {
                Load.Failed(e.userMessage())
            }
        }
    }
}

@Composable
fun HomeScreen(onTitle: (String, String) -> Unit, onSection: (String, Int, String, String) -> Unit) {
    val vm: HomeViewModel = viewModel()
    val providers by vm.providers.collectAsState()
    val provider by vm.provider.collectAsState()
    val sections by vm.sections.collectAsState()
    val cont by vm.continueWatching.collectAsState()
    val status by vm.status.collectAsState()
    val refreshing by vm.refreshing.collectAsState()
    val typeFilter by vm.typeFilter.collectAsState()
    var picking by remember { mutableStateOf(false) }

    PullToRefreshBox(isRefreshing = refreshing, onRefresh = { vm.load() }, modifier = Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
            item {
                Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Home", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    val current = (providers as? Load.Ok)?.value?.firstOrNull { it.name == provider }
                    Row(
                        Modifier.clip(RoundedCornerShape(20.dp)).background(Palette.surface2).clickable { picking = true }
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        current?.iconUrl?.let {
                            AsyncImage(imageRequest(it), null, Modifier.size(20.dp).clip(CircleShape))
                            Spacer(Modifier.size(8.dp))
                        }
                        Text(provider ?: "Choose provider", maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Icon(Icons.Filled.ArrowDropDown, null)
                    }
                }
            }
            status?.takeIf { it.building }?.let { s ->
                item {
                    Text(
                        "Loading plugins… ${s.loaded} of ${s.plugins} ready",
                        color = Palette.muted,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                }
            }
            if (cont.isNotEmpty()) {
                item { SectionTitle("Continue watching", Modifier.padding(start = 16.dp, top = 8.dp, bottom = 8.dp)) }
                item {
                    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        items(cont, key = { it.provider + it.url }) { c ->
                            Poster(
                                c.title, c.poster,
                                subtitle = c.episodeLabel,
                                progress = if (c.duration > 0) (c.position / c.duration).toFloat() else null,
                                onClick = { onTitle(c.provider, c.url) },
                            )
                        }
                    }
                }
            }
            when (val p = providers) {
                is Load.Failed -> item { ErrorBox(p.message, onRetry = { vm.load() }) }
                Load.Loading -> item { Loading() }
                is Load.Ok -> when (val s = sections) {
                    Load.Loading -> item { Loading() }
                    is Load.Failed -> item { ErrorBox(s.message, onRetry = { vm.choose(provider) }) }
                    is Load.Ok -> {
                        if (s.value.isEmpty() && provider == null) item { ErrorBox("No provider has a home page.") }
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
            typeFilter = typeFilter,
            onType = vm::setType,
            onDismiss = { picking = false },
            onPick = {
                picking = false
                vm.choose(it)
            },
        )
    }
}

@Composable
private fun HomeSectionRows(vm: HomeViewModel, section: HomeSection, onTitle: (String, String) -> Unit, onMore: (String) -> Unit) {
    LaunchedEffect(section.index, vm.provider.collectAsState().value) { vm.loadSection(section.index) }
    when (val page = vm.pages[section.index]) {
        null, Load.Loading -> Column(Modifier.padding(16.dp)) {
            SectionTitle(section.name)
            Spacer(Modifier.height(8.dp))
            Box(Modifier.fillMaxWidth().height(180.dp).clip(RoundedCornerShape(10.dp)).background(Palette.surface))
        }
        is Load.Failed -> Column(Modifier.padding(16.dp)) {
            SectionTitle(section.name)
            Text(page.message, color = Palette.muted, style = MaterialTheme.typography.bodySmall)
        }
        is Load.Ok -> page.value.rows.filter { it.items.isNotEmpty() }.forEach { row ->
            Column(Modifier.padding(top = 12.dp)) {
                Row(
                    Modifier.fillMaxWidth().clickable { onMore(row.name) }.padding(horizontal = 16.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    SectionTitle(row.name, Modifier.weight(1f))
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "See all")
                }
                LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(row.items, key = { it.provider + it.url }) { item ->
                        Poster(
                            item.name, item.poster,
                            headers = item.posterHeaders,
                            width = if (row.horizontal) 220.dp else 120.dp,
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

/** The web UI's provider picker: search by name, plugin or language, filter by content type. */
@Composable
fun ProviderPicker(
    providers: List<ProviderDto>,
    selected: String?,
    typeFilter: String,
    onType: (String) -> Unit,
    onDismiss: () -> Unit,
    onPick: (String) -> Unit,
) {
    var query by remember { mutableStateOf("") }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 16.dp)) {
            OutlinedTextField(query, { query = it }, placeholder = { Text("Search providers") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 8.dp)) {
                item { FilterChip(typeFilter == "all", { onType("all") }, label = { Text("All") }) }
                items(ContentTypes.groups.filter { g -> providers.any { ContentTypes.matches(it.types, g.id) } }) { g ->
                    FilterChip(typeFilter == g.id, { onType(g.id) }, label = { Text(g.label) })
                }
            }
        }
        val q = query.trim().lowercase()
        val shown = providers.filter { p ->
            ContentTypes.matches(p.types, typeFilter) &&
                (q.isEmpty() || listOf(p.name, p.plugin, p.lang).any { it.lowercase().contains(q) })
        }
        LazyColumn(Modifier.fillMaxWidth().height(480.dp)) {
            items(shown, key = { it.name }) { p ->
                Row(
                    Modifier.fillMaxWidth().clickable { onPick(p.name) }.padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.size(32.dp).clip(CircleShape).background(Palette.surface2), contentAlignment = Alignment.Center) {
                        val req = imageRequest(p.iconUrl)
                        if (req != null) AsyncImage(req, null, Modifier.size(32.dp)) else Text(p.name.take(1))
                    }
                    Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                        Text(p.name)
                        Text(
                            "${p.lang.uppercase()} · ${ContentTypes.labels(p.types)}",
                            style = MaterialTheme.typography.bodySmall, color = Palette.muted, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (p.name == selected) Icon(Icons.Filled.Check, null, tint = Palette.accent)
                }
            }
            if (shown.isEmpty()) item { TextButton(onClick = { query = ""; onType("all") }) { Text("Clear filters") } }
        }
    }
}
