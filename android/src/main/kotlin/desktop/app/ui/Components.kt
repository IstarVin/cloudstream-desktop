package desktop.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.SubcomposeAsyncImage
import coil3.network.NetworkHeaders
import coil3.network.httpHeaders
import coil3.request.ImageRequest
import desktop.app.app

// Cloudstream's TvType names, grouped into the web UI's browsing filters (`web/src/lib/content-types.ts`).
object ContentTypes {
    data class Group(val id: String, val label: String, val types: Set<String>)

    val groups = listOf(
        Group("anime", "Anime", setOf("Anime", "AnimeMovie", "OVA")),
        Group("movies", "Movies", setOf("Movie", "AnimeMovie")),
        Group("series", "TV Series", setOf("TvSeries")),
        Group("drama", "Asian Drama", setOf("AsianDrama")),
        Group("cartoons", "Cartoons", setOf("Cartoon")),
        Group("documentaries", "Documentaries", setOf("Documentary")),
        Group("live", "Live", setOf("Live")),
        Group("torrent", "Torrents", setOf("Torrent")),
        Group("adult", "Adult", setOf("NSFW")),
        Group("other", "Other", setOf("Others", "Music", "AudioBook", "CustomMedia")),
    )

    fun matches(types: List<String>, filter: String): Boolean =
        filter == "all" || groups.firstOrNull { it.id == filter }?.let { g -> types.any { it in g.types } } == true

    fun labels(types: List<String>): String =
        groups.filter { matches(types, it.id) }.joinToString(" · ") { it.label }.ifEmpty { "Unspecified content" }
}

fun formatTime(seconds: Double): String {
    if (seconds.isNaN() || seconds < 0) return "0:00"
    val s = seconds.toLong()
    val h = s / 3600
    val m = (s % 3600) / 60
    val sec = s % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
}

/** A poster URL: server-relative ones (proxied images) resolve against the paired server. */
@Composable
fun imageRequest(url: String?, headers: Map<String, String>? = null): ImageRequest? {
    val context = LocalContext.current
    if (url.isNullOrBlank()) return null
    val resolved = runCatching { context.app.api.resolve(url) }.getOrNull() ?: return null
    return ImageRequest.Builder(context).data(resolved).apply {
        if (!headers.isNullOrEmpty()) httpHeaders(NetworkHeaders.Builder().apply { headers.forEach { (k, v) -> set(k, v) } }.build())
    }.build()
}

@Composable
fun Poster(
    title: String,
    poster: String?,
    modifier: Modifier = Modifier,
    headers: Map<String, String>? = null,
    subtitle: String? = null,
    progress: Float? = null,
    badge: String? = null,
    width: Dp? = 120.dp,
    /** Width over height: posters are 2:3, landscape rows 16:9. */
    aspect: Float = 2f / 3f,
    onClick: () -> Unit,
) {
    Column(modifier.then(if (width != null) Modifier.width(width) else Modifier.fillMaxWidth()).clickable(onClick = onClick)) {
        Box(
            Modifier.fillMaxWidth().aspectRatio(aspect).clip(RoundedCornerShape(10.dp)).background(Palette.surface2),
        ) {
            val request = imageRequest(poster, headers)
            if (request != null) {
                SubcomposeAsyncImage(
                    model = request,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                    error = { Initials(title) },
                )
            } else Initials(title)
            if (badge != null) {
                Text(
                    badge,
                    style = MaterialTheme.typography.labelSmall,
                    color = Palette.accentInk,
                    modifier = Modifier.align(Alignment.TopStart).padding(6.dp)
                        .background(Palette.accent, RoundedCornerShape(4.dp)).padding(horizontal = 5.dp, vertical = 1.dp),
                )
            }
            if (progress != null && progress > 0f) {
                Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(4.dp).background(Color.Black.copy(alpha = 0.5f))) {
                    Box(Modifier.fillMaxWidth(progress.coerceIn(0f, 1f)).height(4.dp).background(Palette.accent))
                }
            }
        }
        Text(
            title,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 6.dp),
        )
        if (subtitle != null) {
            Text(subtitle, style = MaterialTheme.typography.labelSmall, color = Palette.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun Initials(title: String) {
    Box(
        Modifier.fillMaxSize().background(Brush.linearGradient(listOf(Palette.surface3, Palette.surface))),
        contentAlignment = Alignment.Center,
    ) {
        val initials = title.split(' ').filter { it.isNotBlank() }.take(2).joinToString("") { it.take(1).uppercase() }
        Text(initials, style = MaterialTheme.typography.headlineSmall, color = Palette.muted, fontWeight = FontWeight.Bold)
    }
}

@Composable
fun Loading(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
}

@Composable
fun ErrorBox(message: String, modifier: Modifier = Modifier, onRetry: (() -> Unit)? = null) {
    Column(
        modifier.fillMaxWidth().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(message, color = Palette.muted, textAlign = TextAlign.Center)
        if (onRetry != null) Button(onClick = onRetry) { Text("Retry") }
    }
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = modifier)
}

/** Loading, loaded or failed, for screens that fetch one thing. */
sealed interface Load<out T> {
    data object Loading : Load<Nothing>
    data class Ok<T>(val value: T) : Load<T>
    data class Failed(val message: String) : Load<Nothing>
}

fun Throwable.userMessage(): String = message?.takeIf { it.isNotBlank() } ?: javaClass.simpleName
