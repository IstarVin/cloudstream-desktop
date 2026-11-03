package desktop.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
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
import desktop.app.data.ApiException
import java.io.IOException
import java.net.SocketTimeoutException

fun formatTime(seconds: Double): String {
    if (seconds.isNaN() || seconds < 0) return "0:00"
    val s = seconds.toLong()
    val h = s / 3600
    val m = (s % 3600) / 60
    val sec = s % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
}

/** "18 min left" for something part-watched; null when there's nothing meaningful to say. */
fun remainingLabel(position: Double, duration: Double): String? {
    if (duration <= 0 || position < 0 || position >= duration) return null
    val minutes = Math.ceil((duration - position) / 60).toInt()
    return if (minutes >= 60) "${minutes / 60} h ${minutes % 60} min left" else "$minutes min left"
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
    width: Dp? = LocalCatalogLayout.current.posterWidth,
    /** Width over height: posters are 2:3, landscape rows 16:9. */
    aspect: Float = 2f / 3f,
    onClick: () -> Unit,
) {
    Column(
        modifier.then(if (width != null) Modifier.width(width) else Modifier.fillMaxWidth())
            .clip(MaterialTheme.shapes.medium)
            .tvFocusIndicator(shape = MaterialTheme.shapes.medium)
            .clickable(role = Role.Button, onClickLabel = "Open $title", onClick = onClick)
            .semantics(mergeDescendants = true) {
                progress?.takeIf { it.isFinite() }?.let { stateDescription = "${(it.coerceIn(0f, 1f) * 100).toInt()}% watched" }
            }
            .padding(if (LocalTv.current) 8.dp else 0.dp),
    ) {
        Box(
            Modifier.fillMaxWidth().aspectRatio(aspect).clip(MaterialTheme.shapes.medium).background(Palette.surface2)
                .border(BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), MaterialTheme.shapes.medium),
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
                    color = Palette.onImage,
                    modifier = Modifier.align(Alignment.TopStart).padding(6.dp)
                        .background(Palette.scrim, MaterialTheme.shapes.extraSmall).padding(horizontal = 6.dp, vertical = 2.dp),
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
            style = MaterialTheme.typography.titleSmall,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 8.dp),
        )
        if (!subtitle.isNullOrBlank()) {
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = Palette.muted, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun Initials(title: String) {
    Box(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainerHigh),
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
        val offline = message == UNREACHABLE
        Icon(if (offline) Icons.Outlined.CloudOff else Icons.Outlined.ErrorOutline, null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(28.dp))
        Text(
            if (offline) "Desktop unreachable" else "Something went wrong",
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier.semantics { heading() },
        )
        Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center, modifier = Modifier.widthIn(max = 440.dp))
        if (onRetry != null) Button(shape = MaterialTheme.shapes.small, onClick = onRetry, modifier = Modifier.tvFocusIndicator()) { Text("Try again") }
    }
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(text, style = MaterialTheme.typography.titleLarge, modifier = modifier.semantics { heading() })
}

/** Shared page hierarchy, with native action targets and text that follows the system font scale. */
@Composable
fun ScreenHeading(title: String, modifier: Modifier = Modifier, subtitle: String? = null, actions: @Composable RowScope.() -> Unit = {}) {
    Row(
        modifier.fillMaxWidth().padding(start = Gutter, end = Gutter, top = if (LocalCatalogLayout.current.expanded) 40.dp else 24.dp, bottom = 24.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = if (LocalCatalogLayout.current.expanded) MaterialTheme.typography.headlineLarge else MaterialTheme.typography.headlineMedium,
                modifier = Modifier.semantics { heading() })
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = Palette.muted)
        }
        actions()
    }
}

@Composable
fun EmptyState(
    icon: ImageVector,
    title: String,
    message: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Column(
        modifier.fillMaxWidth().padding(horizontal = 28.dp, vertical = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(32.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center, modifier = Modifier.semantics { heading() })
        Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center, modifier = Modifier.widthIn(max = 360.dp))
        if (actionLabel != null && onAction != null) TextButton(onClick = onAction, modifier = Modifier.tvFocusIndicator()) { Text(actionLabel) }
    }
}

@Composable
fun PosterPlaceholders() {
    val posterWidth = LocalCatalogLayout.current.posterWidth
    LazyRow(
        Modifier.fillMaxWidth().padding(top = 12.dp).clearAndSetSemantics { contentDescription = "Loading titles" },
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        userScrollEnabled = false,
    ) {
        items(8) {
            Column(Modifier.width(posterWidth)) {
                Box(Modifier.fillMaxWidth().aspectRatio(2f / 3f).clip(RoundedCornerShape(12.dp)).background(Palette.surface2))
                Spacer(Modifier.height(8.dp))
                Box(Modifier.fillMaxWidth(0.8f).height(12.dp).clip(RoundedCornerShape(4.dp)).background(Palette.surface2))
            }
        }
    }
}

/** The gutter every top-level screen shares. */
val Gutter = 20.dp

/** Based on the content pane's available width, including split-window and rail layouts. */
data class CatalogLayout(val expanded: Boolean = false, val television: Boolean = false) {
    val posterWidth: Dp get() = if (television || expanded) 176.dp else 140.dp
    val landscapeWidth: Dp get() = if (television || expanded) 304.dp else 248.dp
    val gridMinWidth: Dp get() = if (television || expanded) 156.dp else 140.dp
}

val LocalCatalogLayout = staticCompositionLocalOf { CatalogLayout() }

@Composable
fun CatalogLoading() {
    Column(Modifier.padding(Gutter).clearAndSetSemantics { contentDescription = "Loading titles" }, verticalArrangement = Arrangement.spacedBy(28.dp)) {
        repeat(2) {
            Column {
                Box(Modifier.width(140.dp).height(20.dp).clip(RoundedCornerShape(4.dp)).background(Palette.surface2))
                PosterPlaceholders()
            }
        }
    }
}

/** Loading, loaded or failed, for screens that fetch one thing. */
sealed interface Load<out T> {
    data object Loading : Load<Nothing>
    data class Ok<T>(val value: T) : Load<T>
    data class Failed(val message: String) : Load<Nothing>
}

const val UNREACHABLE = "Make sure Cloudstream Desktop is running and this device is on the same network."

/** What to tell the person: network failures name the likely cause instead of an OkHttp socket message. */
fun Throwable.userMessage(): String = when (this) {
    is ApiException -> message.orEmpty()
    is SocketTimeoutException -> "The desktop took too long to answer. Try again in a moment."
    // Refused, unknown host, no route, a dropped connection: the server isn't there or went away mid-answer.
    is IOException -> UNREACHABLE
    else -> message?.takeIf { it.isNotBlank() } ?: javaClass.simpleName
}

/** A title page while it loads: the hero and the shape of what follows, not a lone spinner. */
@Composable
fun TitleLoading(modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().clearAndSetSemantics { contentDescription = "Loading title" }) {
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).background(Palette.surface2))
        Column(Modifier.padding(Gutter), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.fillMaxWidth().height(48.dp).clip(RoundedCornerShape(24.dp)).background(Palette.surface2))
            Box(Modifier.fillMaxWidth().height(14.dp).clip(RoundedCornerShape(4.dp)).background(Palette.surface2))
            Box(Modifier.fillMaxWidth(0.9f).height(14.dp).clip(RoundedCornerShape(4.dp)).background(Palette.surface2))
            Box(Modifier.fillMaxWidth(0.6f).height(14.dp).clip(RoundedCornerShape(4.dp)).background(Palette.surface2))
        }
    }
}
