package desktop.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
    Column(
        modifier.then(if (width != null) Modifier.width(width) else Modifier.fillMaxWidth())
            .clip(RoundedCornerShape(10.dp))
            .clickable(role = Role.Button, onClickLabel = "Open $title", onClick = onClick)
            .semantics(mergeDescendants = true) {
                progress?.takeIf { it.isFinite() }?.let { stateDescription = "${(it.coerceIn(0f, 1f) * 100).toInt()}% watched" }
            },
    ) {
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
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 6.dp),
        )
        if (!subtitle.isNullOrBlank()) {
            Text(subtitle, style = MaterialTheme.typography.labelSmall, color = Palette.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
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
        Icon(Icons.Outlined.ErrorOutline, null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(28.dp))
        Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center, modifier = Modifier.widthIn(max = 440.dp))
        if (onRetry != null) Button(onClick = onRetry) { Text("Retry") }
    }
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = modifier.semantics { heading() })
}

@Composable
fun ScreenHeading(title: String, modifier: Modifier = Modifier) {
    Text(
        title,
        style = MaterialTheme.typography.headlineMedium,
        fontWeight = FontWeight.Bold,
        modifier = modifier.padding(horizontal = 20.dp, vertical = 16.dp).semantics { heading() },
    )
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
        if (actionLabel != null && onAction != null) TextButton(onClick = onAction) { Text(actionLabel) }
    }
}

@Composable
fun PosterPlaceholders() {
    LazyRow(
        Modifier.fillMaxWidth().padding(top = 12.dp).clearAndSetSemantics { contentDescription = "Loading titles" },
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        userScrollEnabled = false,
    ) {
        items(8) {
            Column(Modifier.width(120.dp)) {
                Box(Modifier.fillMaxWidth().aspectRatio(2f / 3f).clip(RoundedCornerShape(10.dp)).background(Palette.surface2))
                Spacer(Modifier.height(8.dp))
                Box(Modifier.fillMaxWidth(0.8f).height(12.dp).clip(RoundedCornerShape(4.dp)).background(Palette.surface2))
            }
        }
    }
}

@Composable
fun CatalogLoading() {
    Column(Modifier.padding(20.dp).clearAndSetSemantics { contentDescription = "Loading titles" }, verticalArrangement = Arrangement.spacedBy(28.dp)) {
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

fun Throwable.userMessage(): String = message?.takeIf { it.isNotBlank() } ?: javaClass.simpleName
