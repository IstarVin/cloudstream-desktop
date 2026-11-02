package desktop.api

import desktop.auth.Auth
import desktop.cdp.Chromium
import desktop.cdp.Clearances
import desktop.compat.DesktopContext
import desktop.compat.PrefsCodec
import desktop.config.Settings
import desktop.db.Store
import desktop.links.ClientStreams
import desktop.links.LinkService
import desktop.links.StreamProxy
import desktop.player.Playback
import desktop.player.SkipTimes
import desktop.player.Watched
import desktop.plugins.DevSource
import desktop.plugins.PluginHost
import desktop.plugins.RepoSource
import io.ktor.http.CacheControl
import io.ktor.http.ContentType
import io.ktor.http.Cookie
import io.ktor.http.HttpStatusCode
import io.ktor.http.defaultForFilePath
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receive
import io.ktor.server.response.cacheControl
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondRedirect
import io.ktor.server.response.respondText
import io.ktor.server.response.respondTextWriter
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.Frame
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import java.io.Writer

class Services(
    val host: PluginHost,
    val catalog: Catalog,
    val links: LinkService,
    val proxy: StreamProxy,
    val playback: Playback,
    val store: Store,
    val settings: Settings,
    val auth: Auth,
    val scope: CoroutineScope,
    val port: Int,
    val devPath: File,
    val clientStreams: ClientStreams,
    val skipTimes: SkipTimes,
)

@Serializable
data class EnabledRequest(val enabled: Boolean)

@Serializable
data class RebuildRequest(val modules: List<String>? = null)

private val sseJson = Json { encodeDefaults = true }

private fun ApplicationCall.param(name: String): String =
    request.queryParameters[name] ?: throw IllegalArgumentException("Missing parameter $name")

/** Server-sent events with serialized writes (several coroutines may emit at once). */
private suspend fun ApplicationCall.sse(block: suspend (emit: suspend (event: String, data: String) -> Unit) -> Unit) {
    response.cacheControl(CacheControl.NoCache(null))
    response.headers.append("X-Accel-Buffering", "no")
    respondTextWriter(ContentType.Text.EventStream) {
        val lock = Mutex()
        val writer: Writer = this
        block { event, data ->
            lock.withLock {
                writer.write("event: $event\ndata: $data\n\n")
                writer.flush()
            }
        }
    }
}

fun Route.apiRoutes(s: Services) {
    get("/pair") {
        val token = call.request.queryParameters["token"]
        if (!s.auth.matches(token)) return@get call.respondText("This pairing link is no longer valid. Scan the code on the desktop again.", status = HttpStatusCode.Forbidden)
        call.response.cookies.append(Cookie(Auth.COOKIE, token!!, maxAge = 400 * 86400, httpOnly = true, path = "/", extensions = mapOf("SameSite" to "Lax")))
        call.respondRedirect("/")
    }

    get("/proxy/{token}/{encoded}/{name?}") {
        s.proxy.serve(call, call.parameters["token"]!!, call.parameters["encoded"]!!)
    }

    route("/api") {
        get("/auth/status") {
            call.respond(AuthStatus(s.auth.isAuthorized(call), s.auth.isLoopback(call)))
        }
        get("/auth/pairing") {
            if (!s.auth.isLoopback(call)) return@get call.respond(HttpStatusCode.Forbidden, mapOf("error" to "Only from the desktop"))
            val lan = s.auth.lanAddresses().map { "http://$it:${s.port}" }
            val base = lan.firstOrNull() ?: "http://127.0.0.1:${s.port}"
            call.respond(PairingInfo("$base/pair?token=${s.auth.token}", s.auth.token, lan))
        }
        post("/auth/rotate") {
            if (!s.auth.isLoopback(call)) return@post call.respond(HttpStatusCode.Forbidden, mapOf("error" to "Only from the desktop"))
            s.auth.rotate()
            call.respond(mapOf("ok" to true))
        }

        get("/status") {
            val plugins = s.host.plugins
            call.respond(StatusDto(
                building = s.host.building,
                plugins = plugins.size,
                loaded = plugins.count { it.status == PluginHost.Status.LOADED },
                providers = s.catalog.visibleProviders().size,
                mpvRunning = s.playback.state.value.mpvRunning,
                mpvAvailable = s.playback.mpvAvailable,
                authed = true,
                loopback = s.auth.isLoopback(call),
            ))
        }

        get("/providers") { call.respond(s.catalog.providerDtos()) }
        get("/providers/{name}/home") { call.respond(s.catalog.homeSections(call.parameters["name"]!!)) }
        get("/providers/{name}/home/{section}") {
            val page = call.request.queryParameters["page"]?.toIntOrNull() ?: 1
            call.respond(s.catalog.home(call.parameters["name"]!!, call.parameters["section"]!!.toInt(), page))
        }

        get("/search") {
            val q = call.param("q")
            val only = call.request.queryParameters["providers"]?.split(',')?.filter { it.isNotBlank() }?.toSet()
            call.sse { emit ->
                s.catalog.search(q, only) { provider, items, error ->
                    if (items != null) emit("results", sseJson.encodeToString(SearchResults(provider, items)))
                    else emit("error", sseJson.encodeToString(mapOf("provider" to provider, "error" to error)))
                }
                emit("done", "{}")
            }
        }

        get("/load") { call.respond(s.catalog.details(call.param("provider"), call.param("url"))) }

        get("/links") {
            val provider = s.catalog.provider(call.param("provider"))
            val data = call.param("data")
            val set = s.links.resolve(provider.api, data)
            call.sse { emit ->
                var sentLinks = 0
                var sentSubs = 0
                while (true) {
                    val v = set.version.value
                    while (sentLinks < set.links.size) emit("link", sseJson.encodeToString(s.links.dto(set.links[sentLinks++])))
                    while (sentSubs < set.subtitles.size) emit("subtitle", sseJson.encodeToString(set.subtitles[sentSubs++].dto()))
                    if (set.done && sentLinks == set.links.size && sentSubs == set.subtitles.size) break
                    withTimeoutOrNull(15_000) { set.version.first { it != v } } ?: emit("ping", "{}")
                }
                set.error?.let { emit("error", sseJson.encodeToString(mapOf("error" to it))) }
                emit("done", "{}")
            }
        }

        post("/play") {
            val req = call.receive<PlayRequest>()
            s.catalog.provider(req.provider) // NSFW/visibility check
            s.playback.play(req)
            call.respond(mapOf("ok" to true))
        }
        get("/player/state") { call.respond(s.playback.state.value) }
        post("/player/{cmd}") {
            val body = runCatching { call.receive<PlayerCommand>() }.getOrDefault(PlayerCommand())
            s.playback.command(call.parameters["cmd"]!!, body)
            call.respond(mapOf("ok" to true))
        }
        webSocket("/player/events") {
            val json = Json { encodeDefaults = true }
            s.playback.state.collect { st ->
                outgoing.send(Frame.Text(json.encodeToString(st)))
                delay(250)
            }
        }

        get("/history") { call.respond(s.store.history()) }
        delete("/history") {
            s.store.deleteHistory(call.param("provider"), call.param("url"))
            call.respond(mapOf("ok" to true))
        }
        delete("/history/all") {
            s.store.clearHistory()
            call.respond(mapOf("ok" to true))
        }
        get("/continue") { call.respond(s.store.continueWatching()) }
        post("/progress") {
            val req = call.receive<ProgressRequest>()
            val position = req.position
            val duration = req.duration ?: 0.0
            if (position == null) {
                s.store.setWatched(req.provider, req.url, req.data, req.watched)
            } else {
                if (s.store.progress(req.provider, req.data) == null) {
                    // The first save of a title played elsewhere puts it in the history, as starting it in mpv does.
                    val d = s.catalog.details(req.provider, req.url)
                    s.store.touchHistory(d.provider, d.url, d.name, d.poster, d.type, d.nsfw)
                }
                s.store.saveProgress(req.provider, req.url, req.data, req.label, position, duration, req.watched || Watched.watched(position, duration))
            }
            call.respond(mapOf("ok" to true))
        }
        get("/resume") { call.respond(ResumeDto(s.store.resumePosition(call.param("provider"), call.param("data")))) }

        // ---- a client's own player (the Android app) ---------------------------------------------------------------
        get("/stream") {
            val direct = call.request.queryParameters["direct"] == "1"
            call.respond(s.clientStreams.stream(call.param("link"), direct, s.settings.bool(Settings.PROXY_ALL)))
        }
        get("/subtitle") {
            val (format, bytes) = s.clientStreams.subtitle(call.param("id"))
            call.respondBytes(bytes, ContentType.parse(format.mime + "; charset=utf-8"))
        }
        post("/links/report") {
            val req = call.receive<LinkReport>()
            val link = s.links.link(req.link) ?: throw ClientStreams.Gone("Unknown link")
            s.store.recordLink(link.provider, link.link.source, req.success)
            call.respond(mapOf("ok" to true))
        }
        get("/skips") {
            val q = call.request.queryParameters
            val episode = q["episode"]?.toIntOrNull() ?: throw IllegalArgumentException("Missing parameter episode")
            val duration = q["duration"]?.toDoubleOrNull() ?: 0.0
            val ranges = if (s.settings.string(Settings.SKIP_SEGMENTS) == "off") emptyList()
                else s.skipTimes.forEpisode(q["malId"]?.toIntOrNull(), q["aniListId"]?.toIntOrNull(), episode, duration)
            call.respond(ranges)
        }
        get("/bookmarks") { call.respond(s.store.bookmarks(s.settings.showNsfw)) }
        post("/bookmarks") {
            val req = call.receive<BookmarkRequest>()
            val p = s.catalog.provider(req.provider)
            s.store.setBookmark(req.provider, req.url, req.title, req.poster, req.type, p.nsfw || req.type == "NSFW", req.list)
            call.respond(mapOf("ok" to true))
        }

        get("/browser") { call.respond(browserStatus()) }
        post("/browser/checks/{id}/solve") {
            Chromium.solveManually(call.parameters["id"]!!)
            call.respond(browserStatus())
        }
        delete("/browser/checks/{id}") {
            Chromium.dismiss(call.parameters["id"]!!)
            call.respond(browserStatus())
        }
        post("/browser/stop") {
            withContext(Dispatchers.IO) { Chromium.stop() }
            call.respond(browserStatus())
        }
        delete("/browser/clearances") {
            Clearances.hosts.remove(call.param("host"))
            call.respond(browserStatus())
        }

        get("/settings") { call.respond(s.settings.all()) }
        put("/settings") {
            val values = call.receive<JsonObject>()
            if (values.keys.any { it in Settings.desktopOnly } && !s.auth.isLoopback(call)) {
                return@put call.respond(HttpStatusCode.Forbidden, mapOf("error" to "The plugin source can only be changed on the desktop"))
            }
            s.settings.update(values)
            call.respond(s.settings.all())
        }

        get("/plugins") {
            val nsfw = s.settings.showNsfw
            val disabled = s.settings.disabledPlugins
            val providers = s.host.providers().groupBy { it.plugin.name }
            val repo = s.host.source as? RepoSource
            call.respond(s.host.plugins.filter { nsfw || !it.info.nsfw }.map { r ->
                PluginDto(
                    name = r.name,
                    version = r.info.version,
                    description = r.info.description,
                    status = r.status.name.lowercase(),
                    error = r.error,
                    nsfw = r.info.nsfw,
                    tvTypes = r.info.tvTypes,
                    enabled = r.name !in disabled,
                    providers = providers[r.name].orEmpty().map { it.api.name },
                    lastUsed = r.lastUsed,
                    settingsFiles = r.loaded?.context?.openedPrefs?.sorted().orEmpty(),
                    linkage = r.linkage,
                    runtimeLinkage = r.runtimeLinkage,
                    available = repo?.available(r.name)?.takeIf { repo.installable(r.name) }?.version,
                    previous = repo?.installed(r.name)?.previous?.firstOrNull()?.entry?.version,
                    settingsSchema = r.loaded?.settingsSchema.orEmpty(),
                    iconUrl = r.info.iconUrl,
                )
            })
        }
        get("/plugins/source") { call.respond(pluginSource(s)) }
        post("/plugins/check-updates") {
            s.host.checkUpdates()
            call.respond(pluginSource(s))
        }
        post("/plugins/update") {
            val req = runCatching { call.receive<RebuildRequest>() }.getOrDefault(RebuildRequest())
            val failed = s.host.update(req.modules)
            call.respond(UpdateResult(failed, pluginSource(s)))
        }
        post("/plugins/{name}/rollback") {
            s.host.rollback(call.parameters["name"]!!)
            call.respond(pluginSource(s))
        }
        post("/plugins/rebuild") {
            val req = runCatching { call.receive<RebuildRequest>() }.getOrDefault(RebuildRequest())
            if (!s.host.building) s.scope.launch { s.host.rebuild(req.modules) }
            call.respond(HttpStatusCode.Accepted, mapOf("ok" to true))
        }
        get("/plugins/build-log") { call.respondText(s.host.lastBuildOutput) }
        put("/plugins/{name}/enabled") {
            val name = call.parameters["name"]!!
            val enabled = call.receive<EnabledRequest>().enabled
            val disabled = s.settings.disabledPlugins.let { if (enabled) it - name else it + name }
            s.settings.update(JsonObject(mapOf(Settings.DISABLED_PLUGINS to JsonArray(disabled.sorted().map { JsonPrimitive(it) }))))
            call.respond(mapOf("ok" to true))
        }
        get("/plugins/{name}/settings") { call.respond(pluginSettings(s, call.parameters["name"]!!)) }
        put("/plugins/{name}/settings") {
            val name = call.parameters["name"]!!
            val body = call.receive<PluginSettingsDto>()
            val allowed = prefFiles(s, name)
            for ((file, entries) in body.files) {
                require(file in allowed) { "$name doesn't use preference file $file" }
                val values = entries.filter { it.value != null }.associate { e ->
                    val v: Any = if (e.type == "stringSet") e.value!!.split(',').map(String::trim).filter(String::isNotEmpty) else e.value!!
                    e.key to (PrefsCodec.typed(e.type, v) ?: throw IllegalArgumentException("${e.key} isn't a valid ${e.type}"))
                }
                DesktopContext.prefs(file).replaceAll(values)
            }
            call.respond(pluginSettings(s, name))
        }
    }
}

private fun browserStatus(): BrowserStatusDto {
    val st = Chromium.status()
    return BrowserStatusDto(
        running = st.running,
        mode = st.mode,
        configuredMode = st.configuredMode,
        executable = st.executable,
        userAgent = st.userAgent,
        error = st.error,
        checks = st.challenges.map { BrowserCheckDto(it.id, it.kind, it.host, it.url, it.since, it.solving) },
        clearances = st.clearances,
    )
}

private fun pluginSource(s: Services): PluginSourceDto {
    val src = s.host.source
    val repo = src as? RepoSource
    return PluginSourceDto(
        mode = if (repo != null) "repo" else "dev",
        devPath = s.devPath.path,
        devAvailable = DevSource(s.devPath).error == null,
        repoUrl = s.settings.string(Settings.PLUGIN_REPO_URL),
        repoName = repo?.repoName,
        checkedAt = repo?.checkedAt ?: 0,
        error = src.error,
        updates = repo?.updates().orEmpty(),
    )
}

/** The preference files the plugin opened, plus any its settings schema names. */
private fun prefFiles(s: Services, name: String): Set<String> {
    val r = s.host.plugins.firstOrNull { it.name == name } ?: throw NotFound("No plugin $name")
    val opened = r.loaded?.context?.openedPrefs?.toSet().orEmpty()
    val declared = r.loaded?.settingsSchema.orEmpty().mapNotNull { it.file }
    return (opened + declared).ifEmpty { setOf(name) }
}

private fun pluginSettings(s: Services, name: String): PluginSettingsDto {
    val files = prefFiles(s, name).sorted().associateWith { file ->
        val prefs = DesktopContext.prefs(file)
        val all = prefs.getAll()
        val keys = (all.keys + prefs.requestedKeys.keys).sorted()
        keys.map { k ->
            val v = all[k]
            PrefEntry(
                key = k,
                type = v?.let(PrefsCodec::typeOf) ?: "string",
                value = when (v) {
                    null -> null
                    is Set<*> -> v.joinToString(",")
                    else -> v.toString()
                },
                default = prefs.requestedKeys[k],
            )
        }
    }
    return PluginSettingsDto(files)
}

/** The built web UI from resources, falling back to index.html for client-side routes. */
fun Route.webRoutes() {
    get("/{path...}") {
        val path = call.parameters.getAll("path")?.joinToString("/").orEmpty()
        if (path.startsWith("api/") || path.startsWith("proxy/")) return@get call.respond(HttpStatusCode.NotFound)
        val loader = Services::class.java.classLoader
        val res = path.takeIf { it.isNotEmpty() && !it.contains("..") }?.let { loader.getResource("web/$it") }
            ?.takeIf { it.protocol != "file" || java.io.File(it.toURI()).isFile }
        if (res != null) {
            if (path.startsWith("_app/immutable/")) call.response.cacheControl(CacheControl.MaxAge(31536000))
            return@get call.respondBytes(res.readBytes(), ContentType.defaultForFilePath(path))
        }
        val index = loader.getResource("web/index.html")
            ?: return@get call.respondText("The web UI isn't built. Run `vp build` in web/ (or ./gradlew :server:run).", status = HttpStatusCode.NotFound)
        call.response.cacheControl(CacheControl.NoCache(null))
        call.respondBytes(index.readBytes(), ContentType.Text.Html)
    }
}
