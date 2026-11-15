package com.istarvin.cloudstream.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import com.istarvin.cloudstream.app
import desktop.api.DeviceRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

/**
 * Lets this signed-in device approve another one's sign-in code, e.g. a phone approving the TV. Shown only when the
 * server signs devices in itself (local mode); with the Worker, approval happens on the account site instead.
 */
@Composable
fun ApproveDevice() {
    val api = LocalContext.current.app.api
    val scope = rememberCoroutineScope()
    var local by remember { mutableStateOf(false) }
    var code by remember { mutableStateOf("") }
    var request by remember { mutableStateOf<DeviceRequest?>(null) }
    var result by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val normalized = code.uppercase().filter { it.isLetterOrDigit() }

    LaunchedEffect(Unit) {
        local = runCatching { api.server?.let { api.authStatus(it).mode == "local" } }.getOrNull() == true
    }
    if (!local) return

    fun run(block: suspend () -> Unit) {
        busy = true; error = null
        scope.launch {
            try { block() }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { error = e.userMessage() }
            finally { busy = false }
        }
    }
    fun lookup() = run { request = api.lookupDevice(normalized) }
    fun decide(approve: Boolean) = run {
        api.approveDevice(normalized, approve)
        result = if (approve) "${request?.deviceName ?: "The device"} is now signed in." else "Declined. That device has to request a new code to try again."
    }
    fun reset() { code = ""; request = null; result = null; error = null }

    Column(Modifier.padding(top = 28.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Text("Approve a device", style = MaterialTheme.typography.titleMedium)
        val current = request
        when {
            result != null -> {
                Text(result.orEmpty(), modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                OutlinedButton(onClick = ::reset, shape = MaterialTheme.shapes.small, modifier = Modifier.heightIn(min = 48.dp).tvFocusIndicator()) { Text("Approve another device") }
            }
            current != null -> {
                Text("${current.deviceName} wants to sign in.", fontWeight = FontWeight.SemiBold)
                Text("Code ${normalized.chunked(3).joinToString(" ")} · from ${describe(current.address)} · at ${DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(current.requestedAt))}", color = Palette.muted)
                Text("Approve only if this code is on a screen in front of you. Anyone approved can use this server for 30 days.")
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(onClick = { decide(true) }, enabled = !busy, shape = MaterialTheme.shapes.small, modifier = Modifier.heightIn(min = 48.dp).tvFocusIndicator()) { Text("Approve") }
                    OutlinedButton(onClick = { decide(false) }, enabled = !busy, shape = MaterialTheme.shapes.small, modifier = Modifier.heightIn(min = 48.dp).tvFocusIndicator()) { Text("Decline") }
                }
            }
            else -> {
                Text("Enter the code another TV, phone, or browser shows on its sign-in screen.", color = Palette.muted)
                RemoteTextField(code, { if (it.length <= 8) code = it }, label = { Text("Device code") }, enabled = !busy, singleLine = true,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, autoCorrectEnabled = false, imeAction = ImeAction.Go),
                    keyboardActions = KeyboardActions(onGo = { if (!busy && normalized.length == 6) lookup() }), modifier = Modifier.fillMaxWidth())
                Button(onClick = ::lookup, enabled = !busy && normalized.length == 6, shape = MaterialTheme.shapes.small, modifier = Modifier.heightIn(min = 48.dp).tvFocusIndicator()) { Text(if (busy) "Checking…" else "Continue") }
            }
        }
        error?.let { Text(it, color = Palette.danger, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Assertive }) }
    }
}

private fun describe(address: String): String =
    if (address == "localhost" || address == "::1" || address == "0:0:0:0:0:0:0:1" || address.startsWith("127.") || address.startsWith("::ffff:127.")) "the server's computer" else address
