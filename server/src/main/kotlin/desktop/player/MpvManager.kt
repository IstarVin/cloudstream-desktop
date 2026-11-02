package desktop.player

import desktop.config.Config
import desktop.config.Paths
import desktop.config.Settings
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.slf4j.LoggerFactory
import java.io.File

class NoDisplayException : Exception(
    "No graphical session: DISPLAY/WAYLAND_DISPLAY aren't set for the server. " +
        "Run `systemctl --user import-environment DISPLAY WAYLAND_DISPLAY` and restart the service."
)

class MpvUnavailableException(message: String) : MpvError(message)

/**
 * Owns the one mpv process: started on the first play, kept idle between items, and started again on the next play
 * after the user closes it (never restarted on its own).
 */
class MpvManager(private val config: Config, private val settings: Settings) {
    private val log = LoggerFactory.getLogger(MpvManager::class.java)
    private val socket = File(Paths.runtimeDir, "cs-mpv.sock")
    private val mutex = Mutex()
    private var process: Process? = null

    @Volatile var ipc: MpvIpc? = null
        private set

    /** Called with each new connection, so the controller can (re)subscribe to its events. */
    var onConnected: (MpvIpc) -> Unit = {}

    val running get() = process?.isAlive == true && ipc?.closed == false

    /** The Lua script: the repo copy when running from a checkout (so edits apply on the next mpv start), else the bundled one. */
    private fun scriptFile(): File {
        File("mpv/cloudstream.lua").takeIf { it.exists() }?.let { return it.absoluteFile }
        return File(Paths.dataDir, "cloudstream.lua").apply {
            writeText(MpvManager::class.java.getResource("/mpv/cloudstream.lua")!!.readText())
        }
    }

    private val hasDisplay get() = !System.getenv("DISPLAY").isNullOrBlank() || !System.getenv("WAYLAND_DISPLAY").isNullOrBlank()

    /** The configured binary as a path, or found on `PATH`. */
    private val binaryFound by lazy {
        val path = File(config.mpvPath)
        if (path.isAbsolute || config.mpvPath.contains(File.separatorChar)) return@lazy path.canExecute()
        System.getenv("PATH").orEmpty().split(File.pathSeparator).any { File(it, config.mpvPath).canExecute() }
    }

    /** Whether mpv can be started here at all; clients without it play on their own player. */
    val available get() = config.mpvEnabled && hasDisplay && binaryFound

    /** Throws when playing in mpv can't work on this host. */
    fun checkAvailable() {
        if (!config.mpvEnabled) throw MpvUnavailableException("mpv is disabled (mpv.enabled = false); play in the browser or the phone app instead")
        if (!hasDisplay) throw NoDisplayException()
        if (!binaryFound) throw MpvUnavailableException("mpv isn't installed (mpv.path = ${config.mpvPath}); play in the browser or the phone app instead")
    }

    suspend fun ensureRunning(): MpvIpc = mutex.withLock {
        ipc?.takeIf { running }?.let { return it }
        checkAvailable()

        socket.delete()
        val cmd = buildList {
            add(config.mpvPath)
            add("--idle=yes")
            add("--force-window=yes")
            add("--keep-open=no")
            add("--input-ipc-server=${socket.absolutePath}")
            add("--script=${scriptFile().absolutePath}")
            addAll(settings.strings(Settings.MPV_EXTRA_ARGS))
        }
        log.info("Starting mpv: {}", cmd.joinToString(" "))
        val proc = ProcessBuilder(cmd).redirectErrorStream(true)
            .redirectOutput(File(Paths.dataDir, "mpv.log")).start()
        process = proc

        var conn: MpvIpc? = null
        for (attempt in 1..50) {
            if (!proc.isAlive) break
            if (socket.exists()) conn = runCatching { MpvIpc.connect(socket.toPath()) }.getOrNull()
            if (conn != null) break
            delay(100)
        }
        if (conn == null) {
            proc.destroy()
            throw MpvError("mpv didn't start (see ${File(Paths.dataDir, "mpv.log")})")
        }
        ipc = conn
        onConnected(conn)
        conn
    }

    fun stop() {
        ipc?.post("quit")
        ipc?.close()
        process?.destroy()
    }
}
