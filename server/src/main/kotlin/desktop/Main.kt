package desktop

import desktop.compat.DesktopContext
import desktop.config.Config
import desktop.config.Paths
import desktop.config.Settings
import desktop.db.Database
import desktop.plugins.PluginHost
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.slf4j.bridge.SLF4JBridgeHandler
import java.io.File
import kotlin.system.exitProcess

fun main(args: Array<String>) {
    SLF4JBridgeHandler.removeHandlersForRootLogger()
    SLF4JBridgeHandler.install()
    DesktopContext.dataDir = Paths.dataDir

    val config = Config.load()
    val db = Database(File(Paths.dataDir, "cloudstream.db"))
    val settings = Settings(db, config.settingDefaults)
    val host = PluginHost(config, settings)
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    when (args.firstOrNull() ?: "serve") {
        "spike" -> {
            val (plugin, query) = args.drop(1).takeIf { it.size >= 2 } ?: run {
                System.err.println("usage: spike <Plugin> <query>")
                exitProcess(2)
            }
            runBlocking { host.start(scope) }
            spike(host, plugin, query)
            exitProcess(0)
        }
        "serve" -> serve(config, db, settings, host, scope)
        else -> {
            System.err.println("usage: cloudstream-desktop [serve | spike <Plugin> <query>]")
            exitProcess(2)
        }
    }
}
