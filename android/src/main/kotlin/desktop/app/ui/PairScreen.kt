package desktop.app.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.TextButton
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import desktop.app.app
import desktop.app.data.ServerConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.util.concurrent.Executors

/** Pairing: scan the code on the desktop's Settings page, or type its address and token. */
@Composable
fun PairScreen(message: String?, onPaired: (ServerConfig) -> Unit) {
    val context = LocalContext.current
    val tv = LocalTv.current
    val addressFocus = remember { FocusRequester() }
    InitialTvFocus(addressFocus)
    val scope = rememberCoroutineScope()
    var scanning by remember { mutableStateOf(false) }
    var manual by rememberSaveable { mutableStateOf(false) }
    var address by rememberSaveable { mutableStateOf("") }
    var token by rememberSaveable { mutableStateOf("") }
    var checking by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf(message) }

    BackHandler(enabled = scanning && !checking) { scanning = false }

    fun tryPair(config: ServerConfig?) {
        if (checking) return
        if (config == null) {
            error = "That isn't a Cloudstream Desktop pairing code."
            return
        }
        checking = true
        error = null
        scope.launch {
            try {
                val status = context.app.api.authStatus(config)
                if (status.authed) onPaired(config) else error = "The desktop refused this token. Check the code in desktop Settings."
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = "Couldn't reach ${config.baseUrl}. ${e.userMessage()}"
            } finally {
                checking = false
                scanning = false
            }
        }
    }
    val canConnect = !checking && address.isNotBlank() && (token.isNotBlank() || "token=" in address)
    fun connect() { if (canConnect) tryPair(ServerConfig.parse(address, token)) }

    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) scanning = true else {
            manual = true
            error = "Camera permission is needed to scan. You can type the address instead."
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding()) {
        val wide = maxWidth >= 720.dp
        val intro: @Composable () -> Unit = {
            Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
                Box(
                    Modifier.size(56.dp).clip(MaterialTheme.shapes.large).background(Palette.accent),
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Filled.PlayArrow, null, tint = Palette.accentInk, modifier = Modifier.size(32.dp)) }
                Text("Cloudstream", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.semantics { heading() })
                Text(
                    "Watch on your ${if (tv) "TV" else "phone"}, with the sources and library from your desktop. Both need to be on the same network.",
                    color = Palette.muted,
                )
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    PairStep(1, "On the desktop, open Settings and find Pair a phone.")
                    PairStep(2, if (tv) "Enter the desktop address and token shown under the code." else "Scan the code it shows with this phone.")
                }
            }
        }
        val form: @Composable () -> Unit = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (scanning) {
                    QrScanner(
                        Modifier.fillMaxWidth().aspectRatio(1f).clip(MaterialTheme.shapes.extraLarge),
                        onError = {
                            scanning = false
                            manual = true
                            error = "Couldn't open the camera. Enter the desktop address and token instead."
                        },
                        onCode = { text -> if (!checking) tryPair(ServerConfig.parse(text)) },
                    )
                    Text("Point the camera at the code on your desktop.", color = Palette.muted, style = MaterialTheme.typography.bodySmall)
                    OutlinedButton(onClick = { scanning = false }, enabled = !checking, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).tvFocusIndicator()) { Text("Cancel") }
                } else {
                    if (!tv) Button(
                        onClick = {
                            if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) scanning = true
                            else permission.launch(Manifest.permission.CAMERA)
                        },
                        enabled = !checking,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).tvFocusIndicator(),
                    ) {
                        Icon(Icons.Filled.QrCodeScanner, null)
                        Text("Scan pairing code", Modifier.padding(start = 8.dp))
                    }
                    if (!tv) TextButton(onClick = { manual = !manual }, enabled = !checking, modifier = Modifier.align(Alignment.CenterHorizontally).heightIn(min = 48.dp).tvFocusIndicator()) {
                        Text(if (manual) "Hide manual entry" else "Enter the address instead")
                    }
                    if (manual || tv) {
                        OutlinedTextField(
                            address, { address = it },
                            label = { Text("Desktop address") },
                            placeholder = { Text("192.168.1.10:6969") },
                            enabled = !checking,
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false, imeAction = ImeAction.Next),
                            modifier = Modifier.fillMaxWidth().focusRequester(addressFocus).tvFocusIndicator(),
                        )
                        OutlinedTextField(
                            token, { token = it },
                            label = { Text("Token") },
                            supportingText = { Text("Shown under the code in the desktop's settings.") },
                            enabled = !checking,
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, imeAction = ImeAction.Done),
                            keyboardActions = KeyboardActions(onDone = { connect() }),
                            modifier = Modifier.fillMaxWidth().tvFocusIndicator(),
                        )
                        Button(onClick = ::connect, enabled = canConnect, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).tvFocusIndicator()) {
                            Text(if (checking) "Connecting…" else "Connect")
                        }
                    }
                }
                if (checking) LinearProgressIndicator(Modifier.fillMaxWidth())
                error?.let {
                    Row(Modifier.semantics { liveRegion = LiveRegionMode.Assertive }, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Outlined.ErrorOutline, null, tint = Palette.danger, modifier = Modifier.size(20.dp))
                        Text(it, color = Palette.danger, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
        if (wide) {
            Row(Modifier.fillMaxSize().padding(horizontal = 48.dp), horizontalArrangement = Arrangement.spacedBy(64.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f).verticalScroll(rememberScrollState())) { intro() }
                Box(Modifier.weight(1f).verticalScroll(rememberScrollState())) { form() }
            }
        } else {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(Gutter + 8.dp), verticalArrangement = Arrangement.spacedBy(28.dp)) {
                intro()
                form()
            }
        }
    }
}

@Composable
private fun PairStep(number: Int, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.size(28.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainerHighest), contentAlignment = Alignment.Center) {
            Text("$number", style = MaterialTheme.typography.labelLarge)
        }
        Text(text)
    }
}

@androidx.annotation.OptIn(ExperimentalGetImage::class)
@Composable
private fun QrScanner(modifier: Modifier, onError: () -> Unit, onCode: (String) -> Unit) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current
    val preview = remember { PreviewView(context) }
    val currentCode by rememberUpdatedState(onCode)
    val currentError by rememberUpdatedState(onError)

    DisposableEffect(lifecycle) {
        val executor = Executors.newSingleThreadExecutor()
        val scanner = BarcodeScanning.getClient()
        val future = ProcessCameraProvider.getInstance(context)
        var disposed = false
        var boundProvider: ProcessCameraProvider? = null
        val p = Preview.Builder().build().also { it.surfaceProvider = preview.surfaceProvider }
        val analysis = ImageAnalysis.Builder().setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()
        future.addListener({
            if (!disposed) {
                try {
                    val provider = future.get()
                    boundProvider = provider
                    analysis.setAnalyzer(executor) { proxy ->
                        val media = proxy.image
                        if (media == null) {
                            proxy.close()
                            return@setAnalyzer
                        }
                        scanner.process(InputImage.fromMediaImage(media, proxy.imageInfo.rotationDegrees))
                            .addOnSuccessListener { codes ->
                                if (!disposed) codes.firstOrNull {
                                    it.valueType == Barcode.TYPE_URL || it.rawValue?.contains("token=") == true
                                }?.rawValue?.let(currentCode)
                            }
                            .addOnCompleteListener { proxy.close() }
                    }
                    provider.bindToLifecycle(lifecycle, CameraSelector.DEFAULT_BACK_CAMERA, p, analysis)
                } catch (_: Exception) {
                    currentError()
                }
            }
        }, ContextCompat.getMainExecutor(context))
        onDispose {
            disposed = true
            analysis.clearAnalyzer()
            // Never block the UI waiting for camera initialization, or unbind another screen's use cases.
            boundProvider?.unbind(p, analysis)
            executor.shutdown()
            scanner.close()
        }
    }
    Box(modifier, contentAlignment = Alignment.Center) {
        AndroidView({ preview }, Modifier.fillMaxSize())
    }
}
