package com.istarvin.cloudstream.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.istarvin.cloudstream.app
import com.istarvin.cloudstream.data.ApiException
import com.istarvin.cloudstream.data.ServerConfig
import desktop.api.DeviceAuthorization
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** A server address followed by device-code sign-in (the TV default) or owner credentials. */
@Composable
fun PairScreen(message: String?, onPaired: (ServerConfig) -> Unit) {
    val context = LocalContext.current
    val tv = LocalTv.current
    val api = context.app.api
    val scope = rememberCoroutineScope()
    val addressFocus = remember { FocusRequester() }
    val actionFocus = remember { FocusRequester() }
    InitialTvFocus(addressFocus)
    var address by rememberSaveable { mutableStateOf(api.server?.baseUrl.orEmpty()) }
    var base by remember { mutableStateOf<String?>(null) }
    var serverName by remember { mutableStateOf("") }
    var username by rememberSaveable { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var passwordMode by remember { mutableStateOf(false) }
    var grant by remember { mutableStateOf<DeviceAuthorization?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf(message) }
    var remaining by remember { mutableIntStateOf(0) }
    val deviceName = remember(tv) { if (tv) "Android TV (${android.os.Build.MODEL})".take(100) else "Android (${android.os.Build.MODEL})".take(100) }
    val passwordAllowed = base?.let { url -> ServerConfig.parse(url)?.url?.let { it.isHttps || it.host in setOf("localhost", "127.0.0.1", "::1") } } ?: false

    LaunchedEffect(base, passwordMode) {
        if (tv && base != null) { withFrameNanos { }; actionFocus.requestFocus() }
    }

    fun start() {
        val url = base ?: return
        grant = null; error = null; busy = true
        scope.launch {
            try { grant = api.startSignIn(url, deviceName) }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { error = e.userMessage() }
            finally { busy = false }
        }
    }
    fun connect() {
        val config = ServerConfig.parse(address)
        if (config == null) { error = "Enter a server address, such as 192.168.1.10:6969."; return }
        busy = true; error = null
        scope.launch {
            try {
                val status = api.authStatus(config)
                if (!status.configured) { error = "Authentication needs to be configured on this server."; return@launch }
                serverName = status.serverName; base = config.baseUrl
                grant = api.startSignIn(config.baseUrl, deviceName)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { error = "Couldn't connect. ${e.userMessage()}" }
            finally { busy = false }
        }
    }
    fun login() {
        val url = base ?: return
        val secret = password; password = ""; error = null; busy = true
        scope.launch {
            try {
                val result = api.login(url, username, secret, deviceName)
                onPaired(ServerConfig(url, result.token ?: error("Missing session")))
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { error = e.userMessage() }
            finally { busy = false }
        }
    }
    LaunchedEffect(grant, base) {
        val active = grant ?: return@LaunchedEffect
        val url = base ?: return@LaunchedEffect
        val expires = android.os.SystemClock.elapsedRealtime() + active.expiresIn * 1000L
        var nextPoll = android.os.SystemClock.elapsedRealtime() + active.interval * 1000L
        while (android.os.SystemClock.elapsedRealtime() < expires) {
            remaining = ((expires - android.os.SystemClock.elapsedRealtime()) / 1000).toInt().coerceAtLeast(0)
            delay(1000)
            if (android.os.SystemClock.elapsedRealtime() < nextPoll) continue
            try {
                val result = api.pollSignIn(url, active.deviceCode)
                if (result.status == "approved") {
                    onPaired(ServerConfig(url, result.token ?: error("Missing session")))
                    return@LaunchedEffect
                }
                error = null
            } catch (e: CancellationException) { throw e }
            catch (e: ApiException) {
                if (e.code == 410) { grant = null; error = e.message; return@LaunchedEffect }
                error = e.userMessage()
            } catch (e: Exception) { error = "Connection interrupted. Retrying… ${e.userMessage()}" }
            nextPoll = android.os.SystemClock.elapsedRealtime() + active.interval * 1000L
        }
        grant = null; error = "This code expired. Get a new code to continue."
    }

    BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding()) {
        val wide = maxWidth >= 720.dp
        val intro: @Composable () -> Unit = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("Cloudstream", style = MaterialTheme.typography.headlineLarge, modifier = Modifier.semantics { heading() })
                Text(if (base == null) "Enter your server address to open your sources and library on this ${if (tv) "TV" else "device"}." else serverName, color = Palette.muted)
                if (base != null) Text(base.orEmpty(), color = Palette.muted, style = MaterialTheme.typography.bodyMedium)
            }
        }
        val form: @Composable () -> Unit = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                if (base == null) {
                    RemoteTextField(address, { address = it }, label = { Text("Server address") }, placeholder = { Text("192.168.1.10:6969") },
                        enabled = !busy, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false, imeAction = ImeAction.Go),
                        keyboardActions = KeyboardActions(onGo = { if (!busy) connect() }), modifier = Modifier.fillMaxWidth().focusRequester(addressFocus))
                    Button(shape = MaterialTheme.shapes.small, onClick = ::connect, enabled = !busy && address.isNotBlank(), modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).tvFocusIndicator()) { Text(if (busy) "Connecting…" else "Continue") }
                } else if (passwordMode) {
                    Text("Sign in", style = MaterialTheme.typography.headlineSmall)
                    RemoteTextField(username, { username = it }, label = { Text("Username") }, enabled = !busy, singleLine = true, modifier = Modifier.fillMaxWidth().focusRequester(actionFocus))
                    RemoteTextField(password, { password = it }, label = { Text("Password") }, enabled = !busy, singleLine = true, visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done), keyboardActions = KeyboardActions(onDone = { if (!busy && username.isNotBlank() && password.isNotEmpty()) login() }), modifier = Modifier.fillMaxWidth())
                    Button(shape = MaterialTheme.shapes.small, onClick = ::login, enabled = !busy && username.isNotBlank() && password.isNotEmpty(), modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).tvFocusIndicator()) { Text(if (busy) "Signing in…" else "Sign in") }
                } else {
                    val active = grant
                    if (active != null) {
                        Text("Sign in on another device", style = MaterialTheme.typography.headlineSmall)
                        Text("Open this address, sign in, and enter the code:", color = Palette.muted)
                        Text(active.verificationUri, style = MaterialTheme.typography.bodyLarge, color = Palette.accent)
                        Text(active.userCode.chunked(3).joinToString(" "), style = MaterialTheme.typography.displayMedium, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                        Text("Waiting for approval · ${remaining / 60}:${(remaining % 60).toString().padStart(2, '0')}", color = Palette.muted)
                        if (!tv) OutlinedButton(shape = MaterialTheme.shapes.small, onClick = { runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(active.verificationUri))) }.onFailure { error = "Open the address above in a browser." } }, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text("Open sign-in page") }
                    }
                    OutlinedButton(shape = MaterialTheme.shapes.small, onClick = ::start, enabled = !busy, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).focusRequester(actionFocus).tvFocusIndicator()) { Text(if (busy) "Getting a code…" else "Get a new code") }
                }
                if (base != null) {
                    if (passwordAllowed) TextButton(shape = MaterialTheme.shapes.small, onClick = { grant = null; error = null; passwordMode = !passwordMode; password = ""; if (!passwordMode) start() }, enabled = !busy, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).tvFocusIndicator()) { Text(if (passwordMode) "Sign in on another device" else "Use username and password") }
                    TextButton(shape = MaterialTheme.shapes.small, onClick = { grant = null; base = null; error = null; password = ""; passwordMode = false }, enabled = !busy, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).tvFocusIndicator()) { Text("Change server") }
                }
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                error?.let { Text(it, color = Palette.danger, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Assertive }) }
            }
        }
        if (wide) Row(Modifier.fillMaxSize().padding(48.dp), horizontalArrangement = Arrangement.spacedBy(64.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f).verticalScroll(rememberScrollState())) { intro() }
            Box(Modifier.weight(1f).verticalScroll(rememberScrollState())) { form() }
        } else Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(32.dp)) { intro(); form() }
    }
}
