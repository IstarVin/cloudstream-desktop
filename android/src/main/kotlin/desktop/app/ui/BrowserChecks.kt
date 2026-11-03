package desktop.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import android.widget.Toast
import desktop.api.BrowserCheckDto
import desktop.api.BrowserStatusDto
import desktop.app.app
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Checks the desktop's background browser couldn't pass (usually Cloudflare's Turnstile), as the web UI shows them.
 * Solving opens a Chromium window on the desktop; the request that hit the check waits for it, and later ones reuse
 * the cookies.
 */
@Composable
fun BrowserChecks() {
    val context = LocalContext.current
    val api = context.app.api
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf<BrowserStatusDto?>(null) }
    LaunchedEffect(Unit) {
        while (true) {
            runCatching { api.browser() }.onSuccess { status = it }
            delay(if (status?.checks?.any { it.solving } == true) 1500 else 5000)
        }
    }
    fun act(block: suspend () -> BrowserStatusDto) = scope.launch {
        try {
            status = block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Toast.makeText(context, e.userMessage(), Toast.LENGTH_SHORT).show()
        }
    }
    val checks = status?.checks.orEmpty()
    if (checks.isEmpty()) return
    Column(Modifier.fillMaxWidth().padding(horizontal = Gutter, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        checks.forEach { c -> CheckCard(c, onSolve = { act { api.solveCheck(c.id) } }, onDismiss = { act { api.dismissCheck(c.id) } }) }
    }
}

private fun what(c: BrowserCheckDto) =
    if (c.kind == "cloudflare") "Cloudflare is asking for a human check" else "This page didn't finish loading in the background browser"

@Composable
private fun CheckCard(c: BrowserCheckDto, onSolve: () -> Unit, onDismiss: () -> Unit) {
    Surface(
        color = Palette.surface2, shape = RoundedCornerShape(10.dp),
        border = BorderStroke(1.dp, Palette.danger.copy(alpha = 0.4f)),
        modifier = Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
    ) {
        Row(Modifier.heightIn(min = 56.dp).padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(Icons.Filled.Warning, null, Modifier.size(20.dp), tint = Palette.danger)
            Column(Modifier.weight(1f)) {
                Text(c.host, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium)
                Text(
                    if (c.solving) "Pass the check in the Chromium window on the desktop${if (c.kind == "page") ", then close the tab" else ""}."
                    else "${what(c)}. Solving opens it in a window on the desktop.",
                    style = MaterialTheme.typography.bodySmall, color = Palette.muted,
                )
            }
            if (c.solving) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            else Button(shape = MaterialTheme.shapes.small, onClick = onSolve, modifier = Modifier.tvFocusIndicator()) { Text("Solve") }
            IconButton(onClick = onDismiss, modifier = Modifier.tvFocusIndicator()) { Icon(Icons.Filled.Close, "Dismiss") }
        }
    }
}
