package desktop

import desktop.api.Catalog
import desktop.api.PlayRequest
import desktop.api.PlayerCommand
import desktop.api.NotFound
import desktop.api.PluginFailure
import desktop.api.Services
import desktop.api.apiRoutes
import desktop.api.webRoutes
import desktop.auth.Auth
import desktop.cast.DeviceHub
import desktop.cdp.ChromiumUnavailableException
import desktop.config.Config
import desktop.config.Settings
import desktop.db.Database
import desktop.db.Store
import desktop.links.ClientStreams
import desktop.links.LinkService
import desktop.links.StreamProxy
import desktop.player.MpvManager
import desktop.player.MpvUnavailableException
import desktop.player.NoDisplayException
import desktop.player.Playback
import desktop.player.SkipTimes
import desktop.plugins.PluginHost
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respond
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.pingPeriod
import io.ktor.server.websocket.timeout
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.net.InetAddress
import kotlin.time.Duration.Companion.seconds

fun serve(config: Config, db: Database, settings: Settings, host: PluginHost, scope: CoroutineScope) {
    val log = LoggerFactory.getLogger("desktop.Server")
    val store = Store(db)
    val catalog = Catalog(host, settings, store)
    val links = LinkService(scope, store, host)
    val proxy = StreamProxy()
    val mpv = MpvManager(config, settings)
    val skipTimes = SkipTimes(db)
    val playback = Playback(scope, host, catalog, links, proxy, mpv, store, settings, config.bindPort, skipTimes)
    val auth = Auth()
    val cast = DeviceHub(scope, object : DeviceHub.LocalPlayer {
        override val name = "mpv on " + (runCatching { InetAddress.getLocalHost().hostName }.getOrNull() ?: "the server")
        override val available get() = mpv.available
        override val state get() = playback.state
        override suspend fun play(req: PlayRequest) = playback.play(req)
        override suspend fun command(cmd: String, body: PlayerCommand) = playback.command(cmd, body)
    })
    val services = Services(host, catalog, links, proxy, playback, store, settings, auth, scope, config.bindPort, config.devPath,
        ClientStreams(links, proxy), skipTimes, cast)

    // Plugins build and load in the background; the UI shows progress through /api/status.
    scope.launch { host.start(scope) }
    Runtime.getRuntime().addShutdownHook(Thread { mpv.stop() })

    log.info("Listening on http://{}:{}", config.bindHost, config.bindPort)
    embeddedServer(CIO, port = config.bindPort, host = config.bindHost) {
        install(ContentNegotiation) {
            json(Json { encodeDefaults = true; ignoreUnknownKeys = true; explicitNulls = false })
        }
        install(WebSockets) {
            // Notices a cast receiver that dropped off the network without closing its socket.
            pingPeriod = 15.seconds
            timeout = 30.seconds
        }
        install(Auth.plugin(auth))
        install(StatusPages) {
            exception<desktop.auth.AuthFailure> { call, e -> call.respond(HttpStatusCode.fromValue(e.status), mapOf("error" to e.message)) }
            exception<NotFound> { call, e -> call.respond(HttpStatusCode.NotFound, mapOf("error" to e.message)) }
            exception<ClientStreams.Gone> { call, e -> call.respond(HttpStatusCode.Gone, mapOf("error" to e.message)) }
            exception<IllegalArgumentException> { call, e -> call.respond(HttpStatusCode.BadRequest, mapOf("error" to e.message)) }
            exception<BadRequestException> { call, e -> call.respond(HttpStatusCode.BadRequest, mapOf("error" to (e.cause?.message ?: e.message))) }
            exception<NoDisplayException> { call, e -> call.respond(HttpStatusCode.ServiceUnavailable, mapOf("error" to e.message)) }
            exception<MpvUnavailableException> { call, e -> call.respond(HttpStatusCode.ServiceUnavailable, mapOf("error" to e.message)) }
            exception<ChromiumUnavailableException> { call, e -> call.respond(HttpStatusCode.ServiceUnavailable, mapOf("error" to e.message)) }
            exception<PluginFailure> { call, e -> call.respond(HttpStatusCode.BadGateway, mapOf("error" to e.message)) }
            exception<TimeoutCancellationException> { call, _ -> call.respond(HttpStatusCode.GatewayTimeout, mapOf("error" to "The provider took too long")) }
            exception<Throwable> { call, e ->
                log.warn("Request {} failed", call.request.local.uri, e)
                call.respond(HttpStatusCode.InternalServerError, mapOf("error" to (e.message ?: e.javaClass.simpleName)))
            }
        }
        routing {
            apiRoutes(services)
            webRoutes()
        }
    }.start(wait = true)
}
