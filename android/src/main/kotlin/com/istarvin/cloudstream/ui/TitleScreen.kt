package com.istarvin.cloudstream.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.text.HtmlCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import coil3.compose.AsyncImage
import coil3.compose.SubcomposeAsyncImage
import coil3.compose.SubcomposeAsyncImageContent
import desktop.api.BookmarkRequest
import desktop.api.Details
import desktop.api.Episode
import desktop.api.ProgressRequest
import com.istarvin.cloudstream.app
import com.istarvin.cloudstream.data.PlayTargets
import com.istarvin.cloudstream.player.PlayerActivity
import desktop.api.PlayRequest
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

private const val EpisodesPerPage = 20

@Composable
fun TitleScreen(provider: String, url: String, onTitle: (String, String) -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    val api = context.app.api
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var details by remember(provider, url) { mutableStateOf<Load<Details>>(Load.Loading) }
    var dubChoice by rememberSaveable(provider, url) { mutableStateOf<String?>(null) }

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
    /** Plays here, or on the device picked with the cast button, as the web UI does. */
    fun play(d: Details, data: String) {
        val cast = context.app.cast
        val target = cast.effective()
        if (target == PlayTargets.HERE) return PlayerActivity.start(context, d, data)
        val name = cast.device(target)?.name ?: "the other device"
        scope.launch {
            try {
                api.castPlay(target, PlayRequest(d.provider, d.url, data), cast.id)
                snackbar.showSnackbar("Playing on $name")
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                snackbar.showSnackbar("Couldn't play on $name: ${e.userMessage()}")
            }
        }
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
                    onPlay = { data -> play(d, data) },
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

/** One entry of the title page's lazy list, allowing episodes to be scrolled to by identity. */
private sealed interface Part {
    val key: String

    data object Hero : Part { override val key = "hero" }
    data object Actions : Part { override val key = "actions" }
    data object Tags : Part { override val key = "tags" }
    data object Plot : Part { override val key = "plot" }
    data object EpisodesHeader : Part { override val key = "episodes-header" }
    data object EpisodesPagination : Part { override val key = "episodes-pagination" }
    data object EpisodesEmpty : Part { override val key = "episodes-empty" }
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
    var season by rememberSaveable(d.provider, d.url, dub) { mutableStateOf(next?.season ?: seasons.firstOrNull()) }
    var filter by rememberSaveable(d.provider, d.url, dub) { mutableStateOf("") }
    var page by rememberSaveable(d.provider, d.url, dub, season, filter) { mutableIntStateOf(0) }
    var jumpTo by remember { mutableStateOf<String?>(null) }
    var scrollToEpisodes by remember { mutableStateOf(false) }
    val plot = remember(d.plot) { d.plot?.let { HtmlCompat.fromHtml(it, HtmlCompat.FROM_HTML_MODE_COMPACT).toString().trim() }.orEmpty() }

    val shown = remember(episodes, seasons.size, season, filter) {
        val query = filter.trim()
        episodes.withIndex().filter { (index, e) ->
            (seasons.size <= 1 || e.season == season) &&
                (query.isBlank() || episodeTitle(e, index).contains(query, ignoreCase = true) || e.episode?.toString() == query)
        }
    }
    val pageCount = ((shown.size + EpisodesPerPage - 1) / EpisodesPerPage).coerceAtLeast(1)
    val currentPage = page.coerceIn(0, pageCount - 1)
    val pageEpisodes = shown.drop(currentPage * EpisodesPerPage).take(EpisodesPerPage)
    LaunchedEffect(jumpTo, shown) {
        val target = jumpTo ?: return@LaunchedEffect
        val index = shown.indexOfFirst { it.value.data == target }
        if (index >= 0) page = index / EpisodesPerPage
    }
    fun changePage(value: Int) {
        jumpTo = null
        page = value.coerceIn(0, pageCount - 1)
        scrollToEpisodes = true
    }
    val hasEpisodes = episodes.isNotEmpty() || dubs.size > 1 || d.kind in listOf("series", "anime")
    val summary = buildList {
        add(Part.Hero)
        add(Part.Actions)
        if (d.tags.isNotEmpty()) add(Part.Tags)
        if (plot.isNotBlank()) add(Part.Plot)
    }
    val episodeParts = buildList {
        if (hasEpisodes) {
            add(Part.EpisodesHeader)
            pageEpisodes.forEach { (i, e) -> add(Part.Ep(i, e)) }
            if (shown.isEmpty()) add(Part.EpisodesEmpty)
            if (pageCount > 1) add(Part.EpisodesPagination)
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
            Part.Plot -> Overview(plot, d.provider + d.url)
            Part.EpisodesHeader -> Column {
                EpisodesHeader(
                    d, dubs, dub, seasons, season, filter, episodes,
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
                if (pageCount > 1) EpisodePagination(currentPage, pageCount, ::changePage)
            }
            Part.EpisodesPagination -> EpisodePagination(currentPage, pageCount, ::changePage)
            Part.EpisodesEmpty -> EmptyState(
                Icons.Filled.Search,
                if (filter.isNotBlank()) "No matching episodes" else "No episodes available",
                if (filter.isNotBlank()) "Try another episode number or name." else "This source hasn't listed any episodes for this selection yet.",
                actionLabel = if (filter.isNotBlank()) "Clear search" else null,
                onAction = if (filter.isNotBlank()) ({ filter = "" }) else null,
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
                LazyRow(contentPadding = PaddingValues(horizontal = Gutter), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    items(d.recommendations, key = { it.provider + it.url }) { r ->
                        Poster(r.name, r.poster, headers = r.posterHeaders, onClick = { onTitle(r.provider, r.url) })
                    }
                }
            }
        }
    }

    @Composable
    fun Pane(parts: List<Part>, state: LazyListState, modifier: Modifier) {
        // Wait for the requested episode's season and page before scrolling.
        LaunchedEffect(jumpTo, scrollToEpisodes, parts) {
            val target = jumpTo
            val index = when {
                target != null -> parts.indexOfFirst { it is Part.Ep && it.episode.data == target }
                scrollToEpisodes -> parts.indexOf(Part.EpisodesHeader)
                else -> return@LaunchedEffect
            }
            if (index >= 0) {
                state.animateScrollToItem(index)
                jumpTo = null
                scrollToEpisodes = false
            }
        }
        LazyColumn(modifier, state = state, contentPadding = PaddingValues(bottom = 32.dp)) {
            items(parts, key = { it.key }) { Render(it) }
        }
    }

    Box(Modifier.fillMaxSize()) {
        Pane(summary + episodeParts + cast + related, rememberLazyListState(), Modifier.widthIn(max = 760.dp).fillMaxSize().align(Alignment.TopCenter))
    }
}

@Composable
private fun Overview(plot: String, identity: String) {
    var expanded by rememberSaveable(identity) { mutableStateOf(false) }
    var overflowing by remember(plot) { mutableStateOf(false) }
    Column(Modifier.padding(horizontal = Gutter).padding(top = 20.dp, bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionTitle("Overview")
        Text(
            plot, style = MaterialTheme.typography.bodyMedium,
            maxLines = if (expanded) Int.MAX_VALUE else 3,
            overflow = TextOverflow.Ellipsis, color = Palette.muted,
            onTextLayout = { if (!expanded) overflowing = it.hasVisualOverflow },
        )
        if (overflowing || expanded) TextButton(
            onClick = { expanded = !expanded },
            contentPadding = PaddingValues(horizontal = 0.dp),
            modifier = (Modifier.heightIn(min = 48.dp).semantics { stateDescription = if (expanded) "Expanded" else "Collapsed" }).tvFocusIndicator(),
        ) {
            Text(if (expanded) "Show less" else "Show more")
            Icon(if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, null, Modifier.padding(start = 4.dp).size(18.dp))
        }
    }
}

@Composable
private fun Hero(d: Details, onBack: () -> Unit) {
    val shortWindow = LocalWindowInfo.current.containerSize.height < with(LocalDensity.current) { 480.dp.roundToPx() }
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val artworkHeight = if (shortWindow) (maxWidth * 0.3f).coerceIn(112.dp, 144.dp) else (maxWidth * 0.5f).coerceIn(168.dp, 240.dp)
        Box(Modifier.fillMaxWidth().height(artworkHeight)) {
            AsyncImage(imageRequest(d.background ?: d.poster, d.posterHeaders), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0f to Palette.scrim.copy(alpha = 0.15f), 0.45f to Palette.bg.copy(alpha = 0.4f), 1f to Palette.bg)))
        }
        Column(Modifier.fillMaxWidth().padding(top = artworkHeight - 64.dp).padding(horizontal = Gutter)) {
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Box(Modifier.width(if (shortWindow) 80.dp else 96.dp).aspectRatio(2f / 3f).clip(MaterialTheme.shapes.medium).background(Palette.surface2)) {
                    AsyncImage(imageRequest(d.poster, d.posterHeaders), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    val logo = imageRequest(d.logo)
                    val title: @Composable () -> Unit = {
                        Text(d.name, style = MaterialTheme.typography.headlineMedium.copy(lineBreak = LineBreak.Heading), modifier = Modifier.semantics { heading() })
                    }
                    if (logo != null) SubcomposeAsyncImage(
                        logo, d.name, Modifier.fillMaxWidth().semantics { heading() },
                        contentScale = ContentScale.Fit, alignment = Alignment.CenterStart,
                        loading = { title() }, error = { title() },
                        success = { SubcomposeAsyncImageContent(Modifier.fillMaxWidth().height(44.dp)) },
                    ) else title()
                    Text(d.provider, color = Palette.muted, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
            FlowRow(
                Modifier.padding(top = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                d.score?.let { score ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Icon(Icons.Filled.Star, "Rating", tint = Palette.muted, modifier = Modifier.size(16.dp))
                        Text("%.1f".format(score), style = MaterialTheme.typography.labelMedium)
                    }
                }
                listOfNotNull(d.year?.toString(), d.contentRating?.takeIf { it.isNotBlank() }, d.duration?.takeIf { it > 0 }?.let { "$it min" }, d.showStatus?.takeIf { it.isNotBlank() }).forEach {
                    Text(it, color = Palette.muted, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        BackButton(onBack)
        CastButton(Modifier.align(Alignment.TopEnd), overImage = true)
    }
}

@Composable
private fun Actions(d: Details, episodes: List<Episode>, next: Episode?, onPlay: (String) -> Unit, onBookmark: (String?) -> Unit) {
    val progress = if (episodes.isEmpty()) d.movieProgress else next?.progress
    val resuming = progress != null && !progress.watched && progress.position > 30
    Column(Modifier.padding(horizontal = Gutter, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val play = Modifier.weight(1f).heightIn(min = 52.dp)
            when {
                d.kind == "torrent" -> Text("Torrents aren't supported.", color = Palette.muted, modifier = Modifier.weight(1f))
                d.movieData != null && episodes.isEmpty() -> Button(shape = MaterialTheme.shapes.small, onClick = { onPlay(d.movieData!!) }, modifier = play.tvFocusIndicator()) {
                    Icon(Icons.Filled.PlayArrow, null)
                    val p = d.movieProgress
                    Text(if (resuming) "Resume" else if (d.kind == "live") "Watch live" else if (p?.watched == true) "Play again" else "Play", Modifier.padding(start = 8.dp))
                }
                next != null -> Button(shape = MaterialTheme.shapes.small, onClick = { onPlay(next.data) }, modifier = play.tvFocusIndicator()) {
                    Icon(Icons.Filled.PlayArrow, null)
                    val p = next.progress
                    val label = episodeTitle(next.copy(name = null), episodes.indexOf(next))
                    Text(if (resuming) "Resume $label" else if (p?.watched == true) "Replay $label" else "Play $label", Modifier.padding(start = 8.dp), maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                else -> Text("No playable video available.", color = Palette.muted, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
            }
            BookmarkMenu(d.bookmarked, onBookmark)
        }
        if (resuming) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (progress.duration > 0) LinearProgressIndicator(
                    progress = { (progress.position / progress.duration).toFloat().coerceIn(0f, 1f) },
                    modifier = Modifier.weight(1f).height(4.dp),
                    trackColor = Palette.surface3,
                    drawStopIndicator = {},
                )
                Text(remainingLabel(progress.position, progress.duration) ?: "At ${formatTime(progress.position)}", style = MaterialTheme.typography.bodySmall, color = Palette.muted)
            }
        }
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
    episodes: List<Episode>,
    canJump: Boolean,
    onDub: (String) -> Unit,
    onSeason: (Int?) -> Unit,
    onFilter: (String) -> Unit,
    onJump: () -> Unit,
) {
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val total = episodes.size
    val watched = episodes.count { it.progress?.watched == true }
    Column(Modifier.padding(horizontal = Gutter).padding(top = 24.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        HorizontalDivider(Modifier.padding(bottom = 8.dp), color = MaterialTheme.colorScheme.outlineVariant)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                SectionTitle("Episodes")
                Text(
                    "$total ${if (total == 1) "episode" else "episodes"}" + if (watched > 0) " · $watched watched" else "",
                    style = MaterialTheme.typography.bodySmall, color = Palette.muted,
                )
            }
            if (canJump) TextButton(onClick = { focus.clearFocus(); keyboard?.hide(); onJump() }, modifier = Modifier.tvFocusIndicator()) { Text("Jump to next") }
        }
        if (dubs.size > 1) {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(dubs) { x ->
                    FilterChip(x == dub, { onDub(x) }, label = { Text(x) }, modifier = Modifier.heightIn(min = 48.dp).tvFocusIndicator())
                }
            }
        }
        if (seasons.size > 1) {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(seasons) { s ->
                    val name = d.seasons.firstOrNull { it.season == s }?.let { it.name ?: "Season ${it.displaySeason ?: s}" } ?: s?.let { "Season $it" } ?: "Other"
                    FilterChip(season == s, { onSeason(s) }, label = { Text(name) }, modifier = Modifier.heightIn(min = 48.dp).tvFocusIndicator())
                }
            }
        }
        if (total > 20) {
            RemoteTextField(
                filter, onFilter,
                label = { Text("Find an episode") },
                leadingIcon = { Icon(Icons.Filled.Search, null) },
                trailingIcon = {
                    if (filter.isNotEmpty()) IconButton(onClick = { onFilter("") }, modifier = Modifier.tvFocusIndicator()) {
                        Icon(Icons.Filled.Close, "Clear episode search")
                    }
                },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { focus.clearFocus(); keyboard?.hide() }),
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun EpisodePagination(page: Int, pageCount: Int, onPage: (Int) -> Unit) {
    var choosingPage by remember(pageCount) { mutableStateOf(false) }
    val menuScroll = rememberScrollState()
    val density = LocalDensity.current
    LaunchedEffect(choosingPage, page) {
        if (choosingPage) menuScroll.scrollTo(with(density) { (48.dp * page).roundToPx() })
    }
    Row(
        Modifier.fillMaxWidth().padding(horizontal = Gutter, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        TextButton(onClick = { onPage(page - 1) }, enabled = page > 0, modifier = Modifier.heightIn(min = 48.dp).tvFocusIndicator()) {
            Text("Previous")
        }
        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
            TextButton(
                onClick = { choosingPage = true },
                modifier = (Modifier.heightIn(min = 48.dp).semantics { stateDescription = "Page ${page + 1} of $pageCount" }).tvFocusIndicator(),
                contentPadding = PaddingValues(horizontal = 8.dp),
            ) {
                Text("Page ${page + 1} of $pageCount", maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                Icon(Icons.Filled.ExpandMore, "Choose page", Modifier.padding(start = 4.dp).size(18.dp))
            }
            DropdownMenu(
                expanded = choosingPage,
                onDismissRequest = { choosingPage = false },
                modifier = Modifier.heightIn(max = 320.dp).widthIn(min = 180.dp),
                scrollState = menuScroll,
            ) {
                repeat(pageCount) { target ->
                    DropdownMenuItem(
                        text = { Text("Page ${target + 1}") },
                        onClick = { choosingPage = false; onPage(target) },
                        modifier = (Modifier.semantics { stateDescription = if (target == page) "Current page" else "" }).tvFocusIndicator(),
                        trailingIcon = { if (target == page) Icon(Icons.Filled.CheckCircle, "Current page", tint = Palette.accent) },
                    )
                }
            }
        }
        TextButton(onClick = { onPage(page + 1) }, enabled = page < pageCount - 1, modifier = Modifier.heightIn(min = 48.dp).tvFocusIndicator()) {
            Text("Next")
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
    val fontScale = LocalDensity.current.fontScale
    BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal = Gutter, vertical = 4.dp)) {
        val thumbnailWidth = if (maxWidth < 360.dp || fontScale > 1.2f) 88.dp else 112.dp
        Row(
            Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium)
                .background(if (isNext) Palette.surface2 else Color.Transparent)
                .tvFocusIndicator().clickable(role = Role.Button, onClickLabel = "Play $title", onClick = onPlay)
                .semantics { stateDescription = if (watched) "Watched" else if (p != null && p.position > 30) "In progress" else "Not watched" }
                .padding(start = 8.dp, top = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Box(Modifier.width(thumbnailWidth).aspectRatio(16f / 9f).clip(MaterialTheme.shapes.small).background(Palette.surface)) {
                AsyncImage(imageRequest(e.poster ?: d.background ?: d.poster, d.posterHeaders), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                Box(Modifier.align(Alignment.Center).size(28.dp).background(Palette.scrim, CircleShape), contentAlignment = Alignment.Center) {
                    Icon(Icons.Filled.PlayArrow, null, tint = Palette.onImage, modifier = Modifier.size(20.dp))
                }
                if (p != null && p.duration > 0 && !p.watched) {
                    Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(3.dp).background(Color.Black.copy(alpha = 0.5f))) {
                        Box(Modifier.fillMaxWidth((p.position / p.duration).toFloat().coerceIn(0f, 1f)).height(3.dp).background(Palette.accent))
                    }
                }
            }
            Column(Modifier.weight(1f).padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                if (isNext) Text(if (watched) "Watch again" else if (p != null && p.position > 30) "Continue watching" else "Up next", style = MaterialTheme.typography.labelSmall, color = Palette.accent)
                Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis, color = if (watched) Palette.muted else Palette.text)
                if (meta.isNotEmpty()) Text(meta, style = MaterialTheme.typography.bodySmall, color = Palette.muted, maxLines = 2)
                e.description?.takeIf { it.isNotBlank() }?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = Palette.muted, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
            IconButton(onClick = onWatched, modifier = (Modifier.semantics { stateDescription = if (watched) "Watched" else "Not watched" }).tvFocusIndicator()) {
                if (watched) Icon(Icons.Filled.CheckCircle, "Mark $title unwatched", tint = Palette.good)
                else Icon(Icons.Outlined.CheckCircle, "Mark $title watched", tint = Palette.muted)
            }
        }
    }
}

@Composable
private fun BookmarkMenu(current: String?, onPick: (String?) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val list = BOOKMARK_LISTS.firstOrNull { it.first == current }?.second
    Box {
        FilledTonalButton(shape = MaterialTheme.shapes.small,
            onClick = { open = true },
            modifier = Modifier.heightIn(min = 52.dp).semantics { stateDescription = list?.let { "Saved to $it" } ?: "Not in your library" },
            contentPadding = PaddingValues(horizontal = 16.dp),
        ) {
            if (current != null) Icon(Icons.Filled.Bookmark, null, tint = Palette.accent) else Icon(Icons.Filled.BookmarkBorder, null)
            Text(if (current != null) "Saved" else "Save", Modifier.padding(start = 8.dp), maxLines = 1)
        }
        DropdownMenu(open, { open = false }) {
            BOOKMARK_LISTS.forEach { (id, text) ->
                DropdownMenuItem(
                    text = { Text(text) },
                    onClick = { open = false; onPick(id) },
                    trailingIcon = { if (id == current) Icon(Icons.Filled.CheckCircle, "Current list", tint = Palette.accent) },
                    modifier = Modifier.tvFocusIndicator(),
                )
            }
            if (current != null) DropdownMenuItem(text = { Text("Remove from library") }, onClick = { open = false; onPick(null) }, modifier = Modifier.tvFocusIndicator())
        }
    }
}
