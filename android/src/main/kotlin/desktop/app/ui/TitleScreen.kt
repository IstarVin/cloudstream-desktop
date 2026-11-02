package desktop.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.text.HtmlCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import coil3.compose.AsyncImage
import desktop.api.BookmarkRequest
import desktop.api.Details
import desktop.api.Episode
import desktop.api.ProgressRequest
import desktop.app.app
import desktop.app.player.PlayerActivity
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

/** "S1E3 · Name", as the web UI labels episodes. */
fun episodeTitle(e: Episode, index: Int): String {
    val num = listOfNotNull(e.season?.let { "S$it" }, e.episode?.let { "E$it" }).joinToString("")
    return listOf(num, e.name.orEmpty()).filter { it.isNotBlank() }.joinToString(" · ").ifBlank { "Episode ${index + 1}" }
}

/** The episode to resume: the last one in progress, else the first unwatched after the last watched one. */
fun nextUp(eps: List<Episode>): Episode? {
    eps.lastOrNull { it.progress?.let { p -> !p.watched && p.position > 30 } == true }?.let { return it }
    val lastWatched = eps.indexOfLast { it.progress?.watched == true }
    return eps.getOrNull(lastWatched + 1) ?: eps.firstOrNull()
}

/** The dub an anime's episodes are shown in: the chosen one, else Subbed, else the first. */
fun activeDub(d: Details, chosen: String?): String? {
    val dubs = d.episodes.mapNotNull { it.dub }.distinct()
    return chosen?.takeIf { it in dubs } ?: dubs.firstOrNull { it == "Subbed" } ?: dubs.firstOrNull()
}

/** Wide enough to put the title's details and its episodes side by side. */
private val TwoPaneWidth = 840.dp

@Composable
fun TitleScreen(provider: String, url: String, onTitle: (String, String) -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    val api = context.app.api
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var details by remember { mutableStateOf<Load<Details>>(Load.Loading) }
    var dubChoice by rememberSaveable { mutableStateOf<String?>(null) }

    fun load(quiet: Boolean = false) = scope.launch {
        if (!quiet) details = Load.Loading
        details = runCatching { Load.Ok(api.details(provider, url)) }.getOrElse { if (quiet && details is Load.Ok) details else Load.Failed(it.userMessage()) }
    }
    /** Runs a write against the server, then refreshes; a failure is said out loud instead of silently doing nothing. */
    fun change(what: String, write: suspend () -> Unit) = scope.launch {
        try {
            write()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            snackbar.showSnackbar("Couldn't $what: ${e.userMessage()}")
        }
        load(quiet = true)
    }
    LaunchedEffect(provider, url) { load() }
    // Back from the player: progress and watched marks changed.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { if (details is Load.Ok) load(quiet = true) }

    Box(Modifier.fillMaxSize()) {
        when (val l = details) {
            Load.Loading -> Box(Modifier.fillMaxSize().statusBarsPadding()) { TitleLoading(); BackButton(onBack) }
            is Load.Failed -> Box(Modifier.fillMaxSize().statusBarsPadding()) {
                BackButton(onBack)
                ErrorBox(l.message, Modifier.align(Alignment.Center), onRetry = { load() })
            }
            is Load.Ok -> {
                val d = l.value
                val dub = activeDub(d, dubChoice)
                val episodes = d.episodes.filter { dub == null || it.dub == dub }
                TitleContent(
                    d, episodes, dub,
                    onBack = onBack,
                    onDub = { dubChoice = it },
                    onPlay = { data -> PlayerActivity.start(context, d, data) },
                    onTitle = onTitle,
                    onBookmark = { list ->
                        change("update your library") { api.setBookmark(BookmarkRequest(d.provider, d.url, d.name, d.poster, d.type, list)) }
                    },
                    onWatched = { e ->
                        change("mark the episode") { api.saveProgress(ProgressRequest(d.provider, d.url, e.data, watched = e.progress?.watched != true)) }
                    },
                )
            }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
    }
}

@Composable
private fun BackButton(onBack: () -> Unit, modifier: Modifier = Modifier) {
    FilledTonalIconButton(
        onClick = onBack,
        modifier = modifier.padding(8.dp),
        colors = IconButtonDefaults.filledTonalIconButtonColors(containerColor = Palette.scrim, contentColor = Palette.onImage),
    ) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
}

/** One entry of the title page's lazy lists, so the pane that holds the episodes can scroll to one by identity. */
private sealed interface Part {
    val key: String

    data object Hero : Part { override val key = "hero" }
    data object Actions : Part { override val key = "actions" }
    data object Tags : Part { override val key = "tags" }
    data object Plot : Part { override val key = "plot" }
    data object EpisodesHeader : Part { override val key = "episodes-header" }
    data class Ep(val index: Int, val episode: Episode) : Part { override val key get() = "ep-" + episode.data }
    data object Cast : Part { override val key = "cast" }
    data object Related : Part { override val key = "related" }
}

@Composable
private fun TitleContent(
    d: Details,
    episodes: List<Episode>,
    dub: String?,
    onBack: () -> Unit,
    onDub: (String) -> Unit,
    onPlay: (String) -> Unit,
    onTitle: (String, String) -> Unit,
    onBookmark: (String?) -> Unit,
    onWatched: (Episode) -> Unit,
) {
    val dubs = d.episodes.mapNotNull { it.dub }.distinct()
    val seasons = episodes.map { it.season }.distinct()
    val next = nextUp(episodes)
    var season by rememberSaveable(d.url, dub) { mutableStateOf(next?.season ?: seasons.firstOrNull()) }
    var filter by rememberSaveable { mutableStateOf("") }
    var plotExpanded by rememberSaveable { mutableStateOf(false) }
    var jumpTo by remember { mutableStateOf<String?>(null) }
    val plot = remember(d.plot) { d.plot?.let { HtmlCompat.fromHtml(it, HtmlCompat.FROM_HTML_MODE_COMPACT).toString().trim() }.orEmpty() }

    val shown = remember(episodes, seasons.size, season, filter) {
        episodes.withIndex().filter { (_, e) ->
            (seasons.size <= 1 || e.season == season) &&
                (filter.isBlank() || episodeTitle(e, 0).contains(filter, ignoreCase = true) || e.episode?.toString() == filter.trim())
        }
    }
    val hasEpisodes = episodes.isNotEmpty() || dubs.size > 1
    val summary = buildList {
        add(Part.Hero)
        add(Part.Actions)
        if (d.tags.isNotEmpty()) add(Part.Tags)
        if (plot.isNotBlank()) add(Part.Plot)
    }
    val episodeParts = buildList {
        if (hasEpisodes) {
            add(Part.EpisodesHeader)
            shown.forEach { (i, e) -> add(Part.Ep(i, e)) }
        }
    }
    val cast = if (d.actors.isNotEmpty()) listOf(Part.Cast) else emptyList()
    val related = if (d.recommendations.isNotEmpty()) listOf(Part.Related) else emptyList()

    @Composable
    fun Render(part: Part) {
        when (part) {
            Part.Hero -> Hero(d, onBack)
            Part.Actions -> Actions(d, episodes, next, onPlay, onBookmark)
            Part.Tags -> LazyRow(contentPadding = PaddingValues(horizontal = Gutter), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(d.tags) { t ->
                    Text(
                        t, style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.clip(MaterialTheme.shapes.small).background(Palette.surface2).padding(horizontal = 10.dp, vertical = 6.dp),
                    )
                }
            }
            Part.Plot -> Column(Modifier.padding(horizontal = Gutter, vertical = 12.dp)) {
                Text(plot, maxLines = if (plotExpanded) Int.MAX_VALUE else 4, overflow = TextOverflow.Ellipsis, color = Palette.muted)
                // Only offer the toggle when there is more to show than the preview.
                if (plot.length > 200) TextButton(onClick = { plotExpanded = !plotExpanded }) { Text(if (plotExpanded) "Show less" else "Show more") }
            }
            Part.EpisodesHeader -> EpisodesHeader(
                d, dubs, dub, seasons, season, filter, episodes.size, shown.size,
                canJump = next != null && episodes.size > 12,
                onDub = onDub,
                onSeason = { season = it },
                onFilter = { filter = it },
                onJump = {
                    next?.let { n ->
                        filter = ""
                        if (seasons.size > 1) season = n.season
                        jumpTo = n.data
                    }
                },
            )
            is Part.Ep -> EpisodeRow(part.episode, part.index, d, isNext = part.episode.data == next?.data, onPlay = { onPlay(part.episode.data) }, onWatched = { onWatched(part.episode) })
            Part.Cast -> Column {
                SectionTitle("Cast", Modifier.padding(start = Gutter, top = 20.dp, bottom = 8.dp))
                LazyRow(contentPadding = PaddingValues(horizontal = Gutter), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(d.actors) { a ->
                        Column(Modifier.width(80.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Box(Modifier.size(64.dp).clip(CircleShape).background(Palette.surface2)) {
                                AsyncImage(imageRequest(a.image), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                            }
                            Text(a.name, style = MaterialTheme.typography.labelMedium, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
                            a.role?.takeIf { it.isNotBlank() }?.let {
                                Text(it, style = MaterialTheme.typography.labelSmall, color = Palette.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }
            Part.Related -> Column {
                SectionTitle("More like this", Modifier.padding(start = Gutter, top = 20.dp, bottom = 8.dp))
                LazyRow(contentPadding = PaddingValues(horizontal = Gutter), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(d.recommendations, key = { it.provider + it.url }) { r ->
                        Poster(r.name, r.poster, headers = r.posterHeaders, onClick = { onTitle(r.provider, r.url) })
                    }
                }
            }
        }
    }

    @Composable
    fun Pane(parts: List<Part>, state: LazyListState, modifier: Modifier) {
        // A jump to the next episode waits for the season it needs to be showing.
        LaunchedEffect(jumpTo, parts) {
            val target = jumpTo ?: return@LaunchedEffect
            val index = parts.indexOfFirst { it is Part.Ep && it.episode.data == target }
            if (index >= 0) {
                state.animateScrollToItem(index)
                jumpTo = null
            }
        }
        LazyColumn(modifier, state = state, contentPadding = PaddingValues(bottom = 32.dp)) {
            items(parts, key = { it.key }) { Render(it) }
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        if (maxWidth >= TwoPaneWidth) {
            Row(Modifier.fillMaxSize()) {
                Pane(summary + cast, rememberLazyListState(), Modifier.width(380.dp).fillMaxHeight())
                Pane(episodeParts + related, rememberLazyListState(), Modifier.weight(1f).fillMaxHeight())
            }
        } else {
            Pane(summary + episodeParts + cast + related, rememberLazyListState(), Modifier.fillMaxSize())
        }
    }
}

@Composable
private fun Hero(d: Details, onBack: () -> Unit) {
    Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f)) {
        AsyncImage(imageRequest(d.background ?: d.poster, d.posterHeaders), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        // Fades into the page, so the title below reads against the page colour whatever the artwork is.
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.25f to Color.Transparent, 1f to Palette.bg)))
        Row(Modifier.align(Alignment.BottomStart).padding(horizontal = Gutter), verticalAlignment = Alignment.Bottom) {
            Box(Modifier.width(88.dp).aspectRatio(2f / 3f).clip(MaterialTheme.shapes.small).background(Palette.surface2)) {
                AsyncImage(imageRequest(d.poster, d.posterHeaders), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            }
            Column(Modifier.padding(start = 12.dp)) {
                val logo = imageRequest(d.logo)
                if (logo != null) AsyncImage(logo, d.name, Modifier.height(44.dp).fillMaxWidth(), contentScale = ContentScale.Fit, alignment = Alignment.CenterStart)
                else Text(d.name, style = MaterialTheme.typography.headlineSmall, maxLines = 3, overflow = TextOverflow.Ellipsis)
                val meta = listOfNotNull(d.year?.toString(), d.score?.let { "★ %.1f".format(it) }, d.contentRating, d.duration?.let { "$it min" }, d.showStatus)
                if (meta.isNotEmpty()) Text(meta.joinToString(" · "), color = Palette.muted, style = MaterialTheme.typography.bodySmall)
                Text(d.provider, color = Palette.muted, style = MaterialTheme.typography.bodySmall)
            }
        }
        // Last, so it draws over the artwork.
        BackButton(onBack)
    }
}

@Composable
private fun Actions(d: Details, episodes: List<Episode>, next: Episode?, onPlay: (String) -> Unit, onBookmark: (String?) -> Unit) {
    Row(Modifier.padding(horizontal = Gutter, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        val play = Modifier.weight(1f).heightIn(min = 48.dp)
        when {
            d.kind == "torrent" -> Text("Torrents aren't supported.", color = Palette.muted, modifier = Modifier.weight(1f))
            d.movieData != null && episodes.isEmpty() -> Button(onClick = { onPlay(d.movieData!!) }, play) {
                Icon(Icons.Filled.PlayArrow, null)
                val p = d.movieProgress
                Text(if (p != null && !p.watched && p.position > 30) "Resume ${formatTime(p.position)}" else if (d.kind == "live") "Watch live" else "Play", Modifier.padding(start = 4.dp))
            }
            next != null -> Button(onClick = { onPlay(next.data) }, play) {
                Icon(Icons.Filled.PlayArrow, null)
                val p = next.progress
                val label = episodeTitle(next.copy(name = null), episodes.indexOf(next))
                Text(if (p != null && !p.watched && p.position > 30) "Resume $label" else "Play $label", Modifier.padding(start = 4.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            else -> Spacer(Modifier.weight(1f))
        }
        BookmarkMenu(d.bookmarked, onBookmark)
    }
}

@Composable
private fun EpisodesHeader(
    d: Details,
    dubs: List<String>,
    dub: String?,
    seasons: List<Int?>,
    season: Int?,
    filter: String,
    total: Int,
    shown: Int,
    canJump: Boolean,
    onDub: (String) -> Unit,
    onSeason: (Int?) -> Unit,
    onFilter: (String) -> Unit,
    onJump: () -> Unit,
) {
    Column(Modifier.padding(horizontal = Gutter).padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SectionTitle("Episodes", Modifier.weight(1f))
            if (canJump) TextButton(onClick = onJump) { Text("Jump to next") }
        }
        if (dubs.size > 1) {
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                dubs.forEachIndexed { i, x ->
                    SegmentedButton(x == dub, { onDub(x) }, SegmentedButtonDefaults.itemShape(i, dubs.size)) { Text(x) }
                }
            }
        }
        if (seasons.size > 1) {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(seasons) { s ->
                    val name = d.seasons.firstOrNull { it.season == s }?.let { it.name ?: "Season ${it.displaySeason ?: s}" } ?: s?.let { "Season $it" } ?: "Other"
                    FilterChip(season == s, { onSeason(s) }, label = { Text(name) }, modifier = Modifier.heightIn(min = 48.dp))
                }
            }
        }
        if (total > 20) {
            OutlinedTextField(
                filter, onFilter,
                placeholder = { Text("Find an episode") },
                singleLine = true,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (filter.isNotBlank() && shown == 0) {
            Text("No episode matches “$filter”.", color = Palette.muted, modifier = Modifier.padding(vertical = 8.dp))
        }
    }
}

@Composable
private fun EpisodeRow(e: Episode, index: Int, d: Details, isNext: Boolean, onPlay: () -> Unit, onWatched: () -> Unit) {
    val p = e.progress
    val watched = p?.watched == true
    val title = episodeTitle(e, index)
    val meta = listOfNotNull(
        e.runtime?.takeIf { it > 0 }?.let { "$it min" },
        e.date?.takeIf { it > 0 }?.let { DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(it)) },
        p?.takeIf { !it.watched && it.position > 30 }?.let { remainingLabel(it.position, it.duration) },
    ).joinToString(" · ")
    Row(
        Modifier.fillMaxWidth().clickable(role = Role.Button, onClickLabel = "Play $title", onClick = onPlay).padding(start = Gutter, end = 4.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(Modifier.width(120.dp).aspectRatio(16f / 9f).clip(MaterialTheme.shapes.small).background(Palette.surface2)) {
            AsyncImage(imageRequest(e.poster ?: d.background ?: d.poster, d.posterHeaders), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            if (p != null && p.duration > 0 && !p.watched) {
                Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(3.dp).background(Color.Black.copy(alpha = 0.5f))) {
                    Box(Modifier.fillMaxWidth((p.position / p.duration).toFloat().coerceIn(0f, 1f)).height(3.dp).background(Palette.accent))
                }
            }
        }
        Column(Modifier.weight(1f).padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            if (isNext) Text("Up next", style = MaterialTheme.typography.labelSmall, color = Palette.accent)
            Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis, color = if (watched) Palette.muted else Palette.text)
            if (meta.isNotEmpty()) Text(meta, style = MaterialTheme.typography.bodySmall, color = Palette.muted, maxLines = 2)
            e.description?.takeIf { it.isNotBlank() }?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = Palette.muted, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        IconButton(onClick = onWatched, modifier = Modifier.semantics { stateDescription = if (watched) "Watched" else "Not watched" }) {
            if (watched) Icon(Icons.Filled.CheckCircle, "Mark $title unwatched", tint = Palette.good)
            else Icon(Icons.Outlined.CheckCircle, "Mark $title watched", tint = Palette.muted.copy(alpha = 0.7f))
        }
    }
}

@Composable
private fun BookmarkMenu(current: String?, onPick: (String?) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val label = BOOKMARK_LISTS.firstOrNull { it.first == current }?.second ?: "Save"
    Box {
        FilledTonalButton(onClick = { open = true }, Modifier.heightIn(min = 48.dp)) {
            if (current != null) Icon(Icons.Filled.Bookmark, null, tint = Palette.accent) else Icon(Icons.Filled.BookmarkBorder, null)
            Text(label, Modifier.padding(start = 8.dp), maxLines = 1)
        }
        DropdownMenu(open, { open = false }) {
            BOOKMARK_LISTS.forEach { (id, text) ->
                DropdownMenuItem(
                    text = { Text(text) },
                    onClick = { open = false; onPick(id) },
                    trailingIcon = { if (id == current) Icon(Icons.Filled.CheckCircle, "Current list", tint = Palette.accent) },
                )
            }
            if (current != null) DropdownMenuItem(text = { Text("Remove from library") }, onClick = { open = false; onPick(null) })
        }
    }
}
