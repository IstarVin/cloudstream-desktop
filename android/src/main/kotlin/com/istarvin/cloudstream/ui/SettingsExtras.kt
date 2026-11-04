package com.istarvin.cloudstream.ui

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import desktop.api.BrowserStatusDto
import desktop.api.DeviceKind
import com.istarvin.cloudstream.app
import com.istarvin.cloudstream.data.ServerSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** A text setting saved when the field is left or submitted, not on every keystroke. */
@Composable
internal fun TextSettingRow(label: String, value: String, help: String? = null, placeholder: String = "", onCommit: (String) -> Unit) {
    var text by remember(value) { mutableStateOf(value) }
    val focus = LocalFocusManager.current
    Column(Modifier.padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label)
        help?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Palette.muted) }
        RemoteTextField(
            text, { text = it },
            placeholder = { Text(placeholder) },
            singleLine = true,
            shape = RoundedCornerShape(8.dp),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { focus.clearFocus() }),
            modifier = Modifier.fillMaxWidth().onFocusChanged { if (!it.isFocused && text != value) onCommit(text) },
        )
    }
}

/** This device on the casting socket: its name in other devices' Play on list, and whether they may play here. */
@Composable
internal fun CastingSettings() {
    val cast = LocalContext.current.app.cast
    val name by cast.name.collectAsState()
    val receiver by cast.receiver.collectAsState()
    val connected by cast.connected.collectAsState()
    val what = if (cast.kind == DeviceKind.TV) "TV" else "device"
    TextSettingRow("This $what's name", name, help = "How it shows in other devices' Play on list.") { cast.rename(it) }
    SwitchRow(
        "Allow casting to this $what", receiver,
        help = "Other paired devices can play here, and control it, while the app is open." +
            if (connected) "" else " Not connected to the desktop right now.",
    ) { cast.setReceiver(it) }
}

private val BrowserModes = listOf("headless", "xvfb", "headed")
private fun modeLabel(mode: String) = when (mode) { "xvfb" -> "Virtual display"; "headed" -> "Visible window"; else -> "Headless" }

private fun browserState(b: BrowserStatusDto) = when {
    b.executable == null -> "Chromium isn't installed on the desktop"
    !b.running -> "Not running; it starts when a plugin needs it"
    else -> "Running " + when (b.mode) { "headed" -> "in a visible window"; "xvfb" -> "on a virtual display"; else -> "headless" }
}

/** The desktop's Chromium, for sites behind Cloudflare: its mode, a Stop button and the saved clearances. */
@Composable
internal fun BrowserSettings(settings: ServerSettings, onMode: (String) -> Unit) {
    val context = LocalContext.current
    val api = context.app.api
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf<Load<BrowserStatusDto>>(Load.Loading) }
    suspend fun load() {
        status = runCatching { Load.Ok(api.browser()) }.getOrElse { if (status is Load.Ok) status else Load.Failed(it.userMessage()) }
    }
    LaunchedEffect(settings.chromiumMode) { load() }
    fun act(block: suspend () -> BrowserStatusDto) = scope.launch {
        try {
            status = Load.Ok(block())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Toast.makeText(context, e.userMessage(), Toast.LENGTH_SHORT).show()
        }
    }
    when (val s = status) {
        Load.Loading -> Loading()
        is Load.Failed -> ErrorBox(s.message, onRetry = { scope.launch { status = Load.Loading; load() } })
        is Load.Ok -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val b = s.value
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Chromium passes Cloudflare checks and loads pages that need a real browser. ${browserState(b)}.",
                    Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = Palette.muted)
                if (b.running) OutlinedButton(shape = MaterialTheme.shapes.small, onClick = { act { api.stopBrowser() } }, modifier = Modifier.padding(start = 12.dp).tvFocusIndicator()) {
                    Icon(Icons.Rounded.Stop, null, Modifier.size(16.dp)); Text("Stop", Modifier.padding(start = 6.dp))
                }
            }
            b.error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Palette.danger) }
            ChoiceRow("Mode", BrowserModes, settings.chromiumMode, ::modeLabel) { onMode(it) }
            Text(
                "Cloudflare's Turnstile often refuses headless browsers. A virtual display passes more checks without a window; a visible window lets you click through them yourself. Checks that still fail show a Solve button.",
                style = MaterialTheme.typography.bodySmall, color = Palette.muted,
            )
            if (b.clearances.isNotEmpty()) {
                Header("Saved Cloudflare clearances")
                Text("Reused until the site refuses them. Forget one to solve it again.", style = MaterialTheme.typography.bodySmall, color = Palette.muted)
                b.clearances.forEach { host ->
                    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(host, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                        TextButton(onClick = { act { api.forgetClearance(host) } }, modifier = Modifier.tvFocusIndicator()) { Text("Forget") }
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
            }
        }
    }
}
