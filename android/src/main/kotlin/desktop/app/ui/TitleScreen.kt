package desktop.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
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
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.font.FontWeight
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

@Composable
fun TitleScreen(provider: String, url: String, onTitle: (String, String) -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    val api = context.app.api
    val scope = rememberCoroutineScope()
    var details by remember { mutableStateOf<Load<Details>>(Load.Loading) }
    var dubChoice by rememberSaveable { mutableStateOf<String?>(null) }

    fun load(quiet: Boolean = false) = scope.launch {
        if (!quiet) details = Load.Loading
        details = runCatching { Load.Ok(api.details(provider, url)) }.getOrElse { if (quiet && details is Load.Ok) details else Load.Failed(it.userMessage()) }
    }
    LaunchedEffect(provider, url) { load() }
    // Back from the player: progress and watched marks changed.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { if (details is Load.Ok) load(quiet = true) }

    when (val l = details) {
        Load.Loading -> Box(Modifier.fillMaxSize().statusBarsPadding()) { BackButton(onBack); Loading(Modifier.align(Alignment.Center)) }
        is Load.Failed -> Box(Modifier.fillMaxSize().statusBarsPadding()) {
            BackButton(onBack)
            ErrorBox(l.message, Modifier.align(Alignment.Center), onRetry = { load() })
        }
        is Load.Ok -> {
            val d = l.value
            val dub = activeDub(d, dubChoice)
            val episodes = d.episodes.filter { dub == null || it.dub == dub }
            fun play(data: String) = PlayerActivity.start(context, d, data)
            TitleContent(
                d, episodes, dub,
                onBack = onBack,
                onDub = { dubChoice = it },
                onPlay = ::play,
                onTitle = onTitle,
                onBookmark = { list ->
                    scope.launch {
                        runCatching { api.setBookmark(BookmarkRequest(d.provider, d.url, d.name, d.poster, d.type, list)) }
                        load(quiet = true)
                    }
                },
                onWatched = { e ->
                    scope.launch {
                        runCatching { api.saveProgress(ProgressRequest(d.provider, d.url, e.data, watched = e.progress?.watched != true)) }
                        load(quiet = true)
                    }
                },
            )
        }
    }
}

@Composable
private fun BackButton(onBack: () -> Unit, modifier: Modifier = Modifier) {
    FilledTonalIconButton(
        onClick = onBack,
        modifier = modifier.padding(8.dp),
        colors = IconButtonDefaults.filledTonalIconButtonColors(containerColor = Color.Black.copy(alpha = 0.45f), contentColor = Color.White),
    ) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
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
    var season by rememberSaveable(d.url, dub) {
        mutableStateOf(nextUp(episodes)?.season ?: seasons.firstOrNull())
    }
    var filter by rememberSaveable { mutableStateOf("") }
    var plotExpanded by rememberSaveable { mutableStateOf(false) }
    val shown = episodes.withIndex().filter { (_, e) ->
        (seasons.size <= 1 || e.season == season) &&
            (filter.isBlank() || episodeTitle(e, 0).contains(filter, ignoreCase = true) || e.episode?.toString() == filter.trim())
    }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 32.dp)) {
        item {
            Box(Modifier.fillMaxWidth().aspectRatio(16f / 10f)) {
                AsyncImage(imageRequest(d.background ?: d.poster, d.posterHeaders), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Palette.bg), startY = 150f)))
                Row(Modifier.align(Alignment.BottomStart).padding(16.dp), verticalAlignment = Alignment.Bottom) {
                    Box(Modifier.width(96.dp).aspectRatio(2f / 3f).clip(RoundedCornerShape(8.dp)).background(Palette.surface2)) {
                        AsyncImage(imageRequest(d.poster, d.posterHeaders), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                    }
                    Column(Modifier.padding(start = 14.dp)) {
                        val logo = imageRequest(d.logo)
                        if (logo != null) AsyncImage(logo, d.name, Modifier.height(48.dp).fillMaxWidth(), contentScale = ContentScale.Fit, alignment = Alignment.CenterStart)
                        else Text(d.name, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                        val meta = listOfNotNull(
                            d.year?.toString(), d.score?.let { "★ %.1f".format(it) }, d.contentRating,
                            d.duration?.let { "$it min" }, d.showStatus,
                        )
                        Text(meta.joinToString(" · "), color = Palette.muted, style = MaterialTheme.typography.bodySmall)
                        Text(d.provider, color = Palette.muted, style = MaterialTheme.typography.bodySmall)
                    }
                }
                // Last, so it draws over the poster.
                BackButton(onBack, Modifier.statusBarsPadding())
            }
        }
        item {
            Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                val resume = nextUp(episodes)
                when {
                    d.kind == "torrent" -> Text("Torrents aren't supported.", color = Palette.muted, modifier = Modifier.weight(1f))
                    d.movieData != null && episodes.isEmpty() -> Button(onClick = { onPlay(d.movieData!!) }, Modifier.weight(1f)) {
                        Icon(Icons.Filled.PlayArrow, null)
                        val p = d.movieProgress
                        Text(if (p != null && !p.watched && p.position > 30) "Resume ${formatTime(p.position)}" else "Play")
                    }
                    resume != null -> Button(onClick = { onPlay(resume.data) }, Modifier.weight(1f)) {
                        Icon(Icons.Filled.PlayArrow, null)
                        val p = resume.progress
                        val label = episodeTitle(resume.copy(name = null), episodes.indexOf(resume))
                        Text(if (p != null && !p.watched && p.position > 30) "Resume $label" else "Play $label", maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    else -> Spacer(Modifier.weight(1f))
                }
                BookmarkMenu(d.bookmarked, onBookmark)
            }
        }
        if (d.tags.isNotEmpty()) item {
            LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                items(d.tags) { t ->
                    Text(t, style = MaterialTheme.typography.labelMedium, modifier = Modifier.clip(RoundedCornerShape(12.dp))
                        .background(Palette.surface2).padding(horizontal = 10.dp, vertical = 4.dp))
                }
            }
        }
        d.plot?.takeIf { it.isNotBlank() }?.let { html ->
            item {
                val plot = HtmlCompat.fromHtml(html, HtmlCompat.FROM_HTML_MODE_COMPACT).toString().trim()
                Text(
                    plot,
                    maxLines = if (plotExpanded) Int.MAX_VALUE else 4,
                    overflow = TextOverflow.Ellipsis,
                    color = Palette.muted,
                    modifier = Modifier.padding(16.dp).clickable { plotExpanded = !plotExpanded },
                )
            }
        }
        if (episodes.isNotEmpty() || dubs.size > 1) {
            item {
                Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SectionTitle("Episodes")
                    if (dubs.size > 1) {
                        SingleChoiceSegmentedButtonRow {
                            dubs.forEachIndexed { i, x ->
                                SegmentedButton(x == dub, { onDub(x) }, SegmentedButtonDefaults.itemShape(i, dubs.size)) { Text(x) }
                            }
                        }
                    }
                    if (seasons.size > 1) {
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(seasons) { s ->
                                val name = d.seasons.firstOrNull { it.season == s }?.let { it.name ?: "Season ${it.displaySeason ?: s}" } ?: s?.let { "Season $it" } ?: "Other"
                                FilterChip(season == s, { season = s }, label = { Text(name) })
                            }
                        }
                    }
                    if (episodes.size > 20) {
                        OutlinedTextField(filter, { filter = it }, placeholder = { Text("Find an episode") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    }
                }
            }
            items(shown, key = { it.value.data }) { (i, e) -> EpisodeRow(e, i, d, onPlay = { onPlay(e.data) }, onWatched = { onWatched(e) }) }
        }
        if (d.actors.isNotEmpty()) {
            item { SectionTitle("Cast", Modifier.padding(start = 16.dp, top = 20.dp, bottom = 8.dp)) }
            item {
                LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(d.actors) { a ->
                        Column(Modifier.width(72.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Box(Modifier.size(64.dp).clip(CircleShape).background(Palette.surface2)) {
                                AsyncImage(imageRequest(a.image), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                            }
                            Text(a.name, style = MaterialTheme.typography.labelSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
        if (d.recommendations.isNotEmpty()) {
            item { SectionTitle("More like this", Modifier.padding(start = 16.dp, top = 20.dp, bottom = 8.dp)) }
            item {
                LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(d.recommendations, key = { it.provider + it.url }) { r ->
                        Poster(r.name, r.poster, headers = r.posterHeaders, onClick = { onTitle(r.provider, r.url) })
                    }
                }
            }
        }
    }
}

@Composable
private fun EpisodeRow(e: Episode, index: Int, d: Details, onPlay: () -> Unit, onWatched: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onPlay).padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(128.dp).aspectRatio(16f / 9f).clip(RoundedCornerShape(8.dp)).background(Palette.surface2)) {
            AsyncImage(imageRequest(e.poster ?: d.background ?: d.poster, d.posterHeaders), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            val p = e.progress
            if (p != null && p.duration > 0 && !p.watched) {
                Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(3.dp).background(Color.Black.copy(alpha = 0.5f))) {
                    Box(Modifier.fillMaxWidth((p.position / p.duration).toFloat().coerceIn(0f, 1f)).height(3.dp).background(Palette.accent))
                }
            }
        }
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text(episodeTitle(e, index), maxLines = 2, overflow = TextOverflow.Ellipsis,
                color = if (e.progress?.watched == true) Palette.muted else Palette.text)
            e.description?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Palette.muted, maxLines = 2, overflow = TextOverflow.Ellipsis) }
        }
        IconButton(onClick = onWatched) {
            if (e.progress?.watched == true) Icon(Icons.Filled.CheckCircle, "Mark unwatched", tint = Palette.good)
            else Icon(Icons.Outlined.CheckCircle, "Mark watched", tint = Palette.muted)
        }
    }
}

@Composable
private fun BookmarkMenu(current: String?, onPick: (String?) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) {
            if (current != null) Icon(Icons.Filled.Bookmark, "Bookmarked", tint = Palette.accent) else Icon(Icons.Filled.BookmarkBorder, "Bookmark")
        }
        DropdownMenu(open, { open = false }) {
            BOOKMARK_LISTS.forEach { (id, label) ->
                DropdownMenuItem(
                    text = { Text(label) },
                    onClick = { open = false; onPick(id) },
                    trailingIcon = { if (id == current) Icon(Icons.Filled.CheckCircle, null, tint = Palette.accent) },
                )
            }
            if (current != null) DropdownMenuItem(text = { Text("Remove") }, onClick = { open = false; onPick(null) })
        }
    }
}
