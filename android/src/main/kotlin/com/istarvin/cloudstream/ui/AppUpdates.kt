package com.istarvin.cloudstream.ui

import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircleOutline
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.core.content.FileProvider
import com.istarvin.cloudstream.app
import com.istarvin.cloudstream.update.UpdateState
import java.util.Locale

/** Kept outside the lazy settings item so permission callbacks and prompts survive tab changes. */
@Composable
internal fun AppUpdatePrompts() {
    val context = LocalContext.current
    val updater = context.app.updater
    val state by updater.state.collectAsState()
    var showPrompt by rememberSaveable { mutableStateOf(false) }
    var installError by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(state) {
        if ((state as? UpdateState.Available)?.prompt == true || state is UpdateState.Ready) showPrompt = true
    }

    fun install() {
        val ready = updater.state.value as? UpdateState.Ready ?: return
        try {
            check(ready.file.isFile) { "The downloaded update is missing. Check for updates to download it again." }
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", ready.file)
            context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
            showPrompt = false
        } catch (e: Exception) {
            installError = e.message ?: "Couldn't open Android's installer. Try again."
        }
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (context.packageManager.canRequestPackageInstalls()) install()
        else installError = "Installation wasn't allowed. Tap Install update to allow Cloudstream to install this download."
    }
    fun requestInstall() {
        if (context.packageManager.canRequestPackageInstalls()) install()
        else try {
            permission.launch(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, "package:${context.packageName}".toUri()))
        } catch (e: Exception) {
            installError = "Couldn't open installation settings. Allow Cloudstream under Install unknown apps in Android settings, then try again."
        }
    }

    // The inline Install button can reopen this prompt after the installer is dismissed.
    val requested by updater.installPrompt.collectAsState()
    LaunchedEffect(requested) { if (requested > 0) showPrompt = true }
    val available = state as? UpdateState.Available
    val ready = state as? UpdateState.Ready
    if (showPrompt && (available != null || ready != null)) {
        val release = available?.release ?: ready!!.release
        AlertDialog(
            onDismissRequest = { showPrompt = false },
            title = { Text(if (ready != null) "Update ready to install" else "Update available") },
            text = {
                Column(Modifier.sizeIn(maxHeight = 320.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Cloudstream ${release.version}")
                    Text(if (ready != null) "The download is complete and verified. Android will ask you to confirm installation."
                        else "Download ${formatUpdateSize(release.apk()!!.size)} in the app, then install the update.")
                    if (!release.body.isNullOrBlank()) Text(release.body, style = MaterialTheme.typography.bodyMedium)
                    if (ready != null && !context.packageManager.canRequestPackageInstalls()) {
                        Text("First, allow Cloudstream to install apps on the next screen. Return here to continue.")
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { if (ready != null) requestInstall() else { showPrompt = false; updater.download(release) } },
                    modifier = Modifier.tvFocusIndicator()) { Text(if (ready != null) "Install update" else "Download update") }
            },
            dismissButton = { TextButton(onClick = { showPrompt = false }, modifier = Modifier.tvFocusIndicator()) { Text("Later") } },
        )
    }
    installError?.let { message ->
        AlertDialog(onDismissRequest = { installError = null }, title = { Text("Couldn't install update") },
            text = { Text(message) }, confirmButton = {
                TextButton(onClick = { installError = null }, modifier = Modifier.tvFocusIndicator()) { Text("OK") }
            })
    }
}

@Composable
internal fun AppUpdateSettings() {
    val updater = LocalContext.current.app.updater
    val state by updater.state.collectAsState()
    AppUpdateContent(
        installedVersion = updater.installedVersion,
        state = state,
        onCheck = updater::check,
        onDownload = {
            val release = when (val current = state) {
                is UpdateState.Available -> current.release
                is UpdateState.Failed -> current.release
                else -> null
            }
            release?.let(updater::download)
        },
        onInstall = updater::showInstallPrompt,
        onCancel = updater::cancelDownload,
    )
}

@Composable
internal fun AppUpdateContent(
    installedVersion: String,
    state: UpdateState,
    onCheck: () -> Unit,
    onDownload: () -> Unit,
    onInstall: () -> Unit,
    onCancel: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val busy = state is UpdateState.Checking || state is UpdateState.Downloading
    val statusColor = when (state) {
        UpdateState.Current -> Palette.good
        is UpdateState.Failed -> colors.error
        else -> colors.primary
    }
    val title = when (state) {
        UpdateState.Idle -> "Keep Cloudstream up to date"
        UpdateState.Checking -> "Checking for updates…"
        UpdateState.Current -> "You're up to date."
        is UpdateState.Available -> "Update available"
        is UpdateState.Downloading -> "Downloading update…"
        is UpdateState.Ready -> "Ready to install"
        is UpdateState.Failed -> if (state.release != null) "Couldn't download update" else "Couldn't check for updates"
    }
    val detail = when (state) {
        UpdateState.Idle -> "Check for stable Android releases on GitHub."
        UpdateState.Checking -> "Checking for stable Android releases on GitHub."
        UpdateState.Current -> "You're running the latest stable Android release."
        is UpdateState.Available -> "Version ${state.release.version} is available."
        is UpdateState.Downloading -> "Downloading ${state.release.version}…"
        is UpdateState.Ready -> "Version ${state.release.version} is ready to install."
        is UpdateState.Failed -> state.message
    }
    Column(Modifier.widthIn(max = 560.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(24.dp)) {
        Surface(shape = MaterialTheme.shapes.medium, color = colors.surfaceContainer) {
            Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                Surface(shape = MaterialTheme.shapes.medium, color = statusColor.copy(alpha = 0.12f)) {
                    Box(Modifier.size(56.dp), contentAlignment = Alignment.Center) {
                        if (busy) CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 2.dp)
                        else Icon(
                            when (state) {
                                UpdateState.Current -> Icons.Rounded.CheckCircleOutline
                                is UpdateState.Failed -> Icons.Rounded.ErrorOutline
                                is UpdateState.Available -> Icons.Rounded.Download
                                else -> Icons.Rounded.SystemUpdate
                            },
                            contentDescription = null,
                            tint = statusColor,
                            modifier = Modifier.size(28.dp),
                        )
                    }
                }
                Column(
                    Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(title, style = MaterialTheme.typography.titleLarge)
                    Text(detail, style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
                }
                if (state is UpdateState.Downloading) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        LinearProgressIndicator(
                            progress = { (state.received.toFloat() / state.total.coerceAtLeast(1)).coerceIn(0f, 1f) },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Text("${formatUpdateSize(state.received)} of ${formatUpdateSize(state.total)}",
                            style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                    }
                }
                if (state is UpdateState.Downloading) {
                    TextButton(onClick = onCancel, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).tvFocusIndicator()) {
                        Text("Cancel download")
                    }
                } else {
                    val download = state is UpdateState.Available || (state is UpdateState.Failed && state.release != null)
                    Button(
                        shape = MaterialTheme.shapes.small,
                        onClick = when {
                            state is UpdateState.Ready -> onInstall
                            download -> onDownload
                            else -> onCheck
                        },
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).tvFocusIndicator(),
                    ) {
                        Text(when {
                            state is UpdateState.Ready -> "Install update"
                            state is UpdateState.Failed && download -> "Retry download"
                            download -> "Download update"
                            state is UpdateState.Checking -> "Checking…"
                            else -> "Check for updates"
                        })
                    }
                    if (download || state is UpdateState.Ready) {
                        TextButton(onClick = onCheck, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).tvFocusIndicator()) {
                            Text("Check for updates")
                        }
                    }
                }
                HorizontalDivider(color = colors.outlineVariant)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Installed version:", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
                    Text(installedVersion, style = MaterialTheme.typography.titleSmall)
                }
            }
        }
        Text("Updates download directly in the app.",
            style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
    }
}

private fun formatUpdateSize(bytes: Long): String = String.format(Locale.getDefault(), "%.1f MB", bytes / (1024.0 * 1024.0))
