package desktop.app.update

import android.content.Context
import android.content.pm.PackageInfo
import android.os.Build
import desktop.app.data.json
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Response
import okhttp3.OkHttpClient
import okhttp3.Request
import kotlin.coroutines.coroutineContext

internal sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data object Current : UpdateState
    data class Available(val release: AndroidRelease, val prompt: Boolean = true) : UpdateState
    data class Downloading(val release: AndroidRelease, val received: Long, val total: Long) : UpdateState
    data class Ready(val release: AndroidRelease, val file: File) : UpdateState
    data class Failed(val message: String, val release: AndroidRelease? = null) : UpdateState
}

/** Application-owned: checks/downloads survive navigation and Activity recreation. No paired-server credentials. */
internal class AppUpdater(private val context: Context, private val scope: CoroutineScope) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(15, TimeUnit.MINUTES).build()
    private val mutableState = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state = mutableState.asStateFlow()
    private val prompt = MutableStateFlow(0)
    val installPrompt = prompt.asStateFlow()
    fun showInstallPrompt() { prompt.value++ }
    private var job: Job? = null
    @Suppress("DEPRECATION")
    private val installed = context.packageManager.getPackageInfo(context.packageName, 0)
    val installedVersion: String = installed.versionName.orEmpty()
    private val directory = File(context.filesDir, "updates")

    fun check() {
        if (job?.isActive == true) return
        mutableState.value = UpdateState.Checking
        job = scope.launch {
            try {
                val latest = withContext(Dispatchers.IO) {
                    val releases = mutableListOf<AndroidRelease>()
                    var page = 1
                    do {
                        val batch = json.decodeFromString<List<AndroidRelease>>(readText(
                            "https://api.github.com/repos/IstarVin/cloudstream-desktop/releases?per_page=100&page=$page",
                        ))
                        releases += batch
                        page++
                        if (batch.size < 100) break
                        check(page <= 20) { "Too many releases to check. Try again later." }
                    } while (true)
                    newestAndroidRelease(releases)
                }
                check(latest != null) { "No stable Android release is published yet. Please try again later." }
                check(versionParts(installedVersion) != null) { "This build has an unsupported version: $installedVersion." }
                mutableState.value = if (compareVersions(latest.version, installedVersion) > 0)
                    UpdateState.Available(latest) else UpdateState.Current
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                coroutineContext.ensureActive()
                mutableState.value = UpdateState.Failed(e.message ?: "Couldn't check for updates. Try again.")
            }
        }
    }

    fun download(release: AndroidRelease) {
        if (job?.isActive == true) return
        mutableState.value = UpdateState.Downloading(release, 0, release.apk()?.size ?: 0)
        job = scope.launch {
            try {
                val file = withContext(Dispatchers.IO) { downloadApk(release) }
                mutableState.value = UpdateState.Ready(release, file)
            } catch (e: CancellationException) {
                mutableState.value = UpdateState.Available(release, prompt = false)
                throw e
            } catch (e: Exception) {
                coroutineContext.ensureActive()
                mutableState.value = UpdateState.Failed(e.message ?: "Download failed. Try again.", release)
            }
        }
    }

    fun cancelDownload() { if (mutableState.value is UpdateState.Downloading) job?.cancel() }

    private suspend fun readText(url: String): String = withContext(Dispatchers.IO) {
        coroutineContext.ensureActive()
        withResponse(Request.Builder().url(url)
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28")
            .header("User-Agent", "Cloudstream-Android/$installedVersion").build()) { response ->
            if (response.code == 403 || response.code == 429) throw IOException("GitHub's request limit was reached. Try again later.")
            if (!response.isSuccessful) throw IOException("Couldn't check releases (HTTP ${response.code}). Try again.")
            response.body.string()
        }
    }

    private suspend fun downloadApk(release: AndroidRelease): File {
        val asset = requireNotNull(release.apk()) { "This release has no Android APK." }
        require(asset.url == "https://github.com/IstarVin/cloudstream-desktop/releases/download/${release.tag}/${asset.name}") {
            "The release has an unexpected download address."
        }
        val expected = asset.digest?.removePrefix("sha256:")
        require(asset.digest?.startsWith("sha256:") == true && expected != null && Regex("[a-fA-F0-9]{64}").matches(expected)) {
            "This release has no SHA-256 checksum. Please try again after the release is republished."
        }
        check(directory.isDirectory || directory.mkdirs()) { "Couldn't create the update download folder." }
        directory.listFiles()?.forEach { it.delete() }
        val partial = File(directory, "update.apk.part")
        val target = File(directory, "update.apk")
        try {
            withResponse(Request.Builder().url(asset.url).build()) { response ->
                if (!response.isSuccessful) throw IOException("Download failed (HTTP ${response.code}). Try again.")
                response.body.byteStream().use { input ->
                    partial.outputStream().use { output ->
                        copyVerifiedApk(input, output, asset.size, expected) { received ->
                            mutableState.value = UpdateState.Downloading(release, received, asset.size)
                        }
                    }
                }
            }
            @Suppress("DEPRECATION")
            val archive = context.packageManager.getPackageArchiveInfo(partial.absolutePath, 0)
            check(archive != null && archive.packageName == context.packageName &&
                archive.versionName == release.version && versionCode(archive) > versionCode(installed)) {
                "This APK isn't a newer compatible Cloudstream build."
            }
            check(partial.renameTo(target)) { "Couldn't save the update. Please try again." }
            return target
        } finally {
            partial.delete()
        }
    }

    // Cancel the socket immediately, including when execute()/read() is blocked on a slow host.
    private suspend fun <T> withResponse(request: Request, block: suspend (Response) -> T): T = coroutineScope {
        val call = client.newCall(request)
        val cancellation = launch(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) {
            try { awaitCancellation() } finally { call.cancel() }
        }
        try {
            call.execute().use { block(it) }
        } finally {
            cancellation.cancel()
        }
    }

    @Suppress("DEPRECATION")
    private fun versionCode(info: PackageInfo): Long =
        if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()
}
