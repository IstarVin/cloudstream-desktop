package desktop.player

import com.lagradost.cloudstream3.utils.ExtractorLinkType
import desktop.api.Catalog
import desktop.api.Details
import desktop.api.Episode
import desktop.api.Labels
import desktop.api.LinkDto
import desktop.api.Mapper
import desktop.api.PlayRequest
import desktop.api.PlayerCommand
import desktop.config.Settings
import desktop.db.Store
import desktop.links.LinkHeaders
import desktop.links.LinkService
import desktop.links.LinkSet
import desktop.links.ResolvedLink
import desktop.links.ResolvedSubtitle
import desktop.links.StreamProxy
import desktop.plugins.PluginHost
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import org.slf4j.LoggerFactory

@Serializable
data class Track(val id: Int, val type: String, val title: String?, val lang: String?, val selected: Boolean, val external: Boolean)

/** The next episode's links, resolved in the background near the end of the current one. */
@Serializable
data class NextEpisode(
    val label: String?,
    /** waiting (before 85%) | resolving | ready | failed */
    val status: String,
    /** Playable links found so far. */
    val links: Int = 0,
)

@Serializable
data class PlayerState(
    /** idle | resolving | playing | paused | error */
    val status: String = "idle",
    val message: String? = null,
    val position: Double = 0.0,
    val duration: Double = 0.0,
    val paused: Boolean = false,
    val volume: Double = 100.0,
    val title: String? = null,
    val episodeLabel: String? = null,
    val provider: String? = null,
    val url: String? = null,
    val data: String? = null,
    val poster: String? = null,
    val link: LinkDto? = null,
    val audioTracks: List<Track> = emptyList(),
    val subTracks: List<Track> = emptyList(),
    val hasNext: Boolean = false,
    val hasPrev: Boolean = false,
    val mpvRunning: Boolean = false,
    val next: NextEpisode? = null,
    /** Links of this item that failed to play this session. */
    val failedLinks: List<String> = emptyList(),
    /** The opening, ending or recap the position is in (AniSkip), while the player offers to skip it. */
    val skip: SkipRange? = null,
)

/** One title being played: its details and episode list (one dub for anime), and which entry is current. */
class Item(
    val details: Details,
    val episodes: List<Episode>,
    val index: Int,
    val data: String,
) {
    val provider get() = details.provider
    val url get() = details.url
    val episode get() = episodes.getOrNull(index)
    val hasNext get() = index >= 0 && index + 1 < episodes.size
    val hasPrev get() = index > 0

    val label: String?
        get() = episode?.let { Labels.episode(it, index) }

    fun at(i: Int) = Item(details, episodes, i, episodes[i].data)
}

private class Session(val item: Item, val set: LinkSet, val generation: Int, var startAt: Double?) {
    val tried = mutableSetOf<String>()
    val failed = mutableSetOf<String>()
    var link: ResolvedLink? = null
    var entryId: Long? = null
    val addedSubs = mutableSetOf<String>()
    var chosenSubs: List<String> = emptyList()
    var subtitleSelectionMade = false
    var playStartedAt: Double? = null
    var recordedSuccess = false
    /** The next episode's links, once the prefetch near the end has started. */
    var nextSet: LinkSet? = null
    /** AniSkip ranges for the loaded file, and the ones already skipped in it. */
    var skips: List<SkipRange> = emptyList()
    val skipped = mutableSetOf<SkipRange>()
    var skipsRequested = false
    var lastSaved = 0L
    var finished = false
    /** True once mpv has loaded the current link; position events before that belong to the previous file. */
    var fileLoaded = false
    var watchers: Job? = null
}

/**
 * The source of truth for playback. The web UI and the mpv script both send intents here; this decides what mpv
 * plays and tells both what's happening, so they can't disagree.
 */
class Playback(
    private val scope: CoroutineScope,
    private val host: PluginHost,
    private val catalog: Catalog,
    private val links: LinkService,
    private val proxy: StreamProxy,
    private val mpv: MpvManager,
    private val store: Store,
    private val settings: Settings,
    private val port: Int,
    private val skipTimes: SkipTimes,
) {
    private val log = LoggerFactory.getLogger(Playback::class.java)
    private val json = Json { encodeDefaults = true }
    private val mutex = Mutex()
    private var generation = 0
    @Volatile private var session: Session? = null

    /** Debug aid: `-Dcs.debug.failSources=Dulo Pickle,…` makes those sources fail to load, to exercise fallback. */
    private val debugFailSources = System.getProperty("cs.debug.failSources")?.split(',')?.map(String::trim)?.toSet().orEmpty()

    private val _state = MutableStateFlow(PlayerState())
    val state: StateFlow<PlayerState> = _state

    init {
        mpv.onConnected = { ipc -> scope.launch { listen(ipc) } }
    }

    // ---- starting playback -------------------------------------------------------------------------------------

    suspend fun play(req: PlayRequest) {
        val (p, r) = catalog.loadRaw(req.provider, req.url)
        val details = Mapper.details(p.api.name, r, p.nsfw, true)
        val item = if (details.movieData == req.data || details.episodes.isEmpty()) {
            Item(details, emptyList(), -1, req.data)
        } else {
            val target = details.episodes.firstOrNull { it.data == req.data } ?: throw IllegalArgumentException("Episode not found")
            val list = details.episodes.filter { it.dub == target.dub }
            Item(details, list, list.indexOf(target), req.data)
        }
        start(item, req.linkId, req.subtitleIds, req.startAt)
    }

    private suspend fun start(item: Item, linkId: String? = null, subtitleIds: List<String> = emptyList(), startAt: Double? = null) {
        val provider = host.provider(item.provider) ?: throw IllegalStateException("Provider ${item.provider} isn't loaded")
        val s = mutex.withLock {
            saveProgress()
            session?.watchers?.cancel()
            val set = links.resolve(provider.api, item.data)
            val resume = startAt ?: store.resumePosition(item.provider, item.data)
            Session(item, set, ++generation, resume).also {
                it.chosenSubs = subtitleIds
                session = it
            }
        }
        val d = item.details
        store.touchHistory(d.provider, d.url, d.name, d.poster, d.type, d.nsfw)
        _state.update {
            it.copy(
                status = "resolving", message = "Resolving links…", position = 0.0, duration = 0.0,
                title = d.name, episodeLabel = item.label, provider = d.provider, url = d.url, data = item.data,
                poster = d.poster, link = null, audioTracks = emptyList(), subTracks = emptyList(),
                hasNext = item.hasNext, hasPrev = item.hasPrev, failedLinks = emptyList(),
                next = if (item.hasNext) NextEpisode(item.at(item.index + 1).label, "waiting") else null,
            )
        }
        val ipc = try {
            mpv.ensureRunning()
        } catch (e: Exception) {
            fail(s, e.message ?: "mpv failed to start")
            return
        }
        _state.update { it.copy(mpvRunning = true) }
        // Stop whatever was playing so its position updates can't leak into this item; mpv shows the overlay meanwhile.
        runCatching { ipc.command("stop") }
        script("loading", mapOf("title" to d.name, "label" to item.label, "provider" to d.provider))
        if (ipc.closed) return

        s.watchers = scope.launch { watchSubtitles(s) }
        val chosen = linkId?.let { links.link(it) }
        if (chosen != null) playLink(s, chosen) else if (settings.bool(Settings.AUTOPLAY_BEST)) scope.launch { autoPick(s, fallback = false) }
        else _state.update { it.copy(message = "Pick a source") }
    }

    /**
     * Waits for a link with auto-play priority (links keep resolving meanwhile), or for all of them, then plays the
     * best untried one. In fallback mode it takes the next one as soon as any is available.
     */
    private suspend fun autoPick(s: Session, fallback: Boolean) {
        while (s.generation == generation) {
            val profile = settings.activeSourceProfile()
            val candidates = ranked(s, profile).filter { it.id !in s.tried }
            val ready = AutoPlay.ready(fallback, s.set.done, candidates.firstOrNull()?.let { profile.priority(it.link.source, it.link.quality) } ?: 0)
            if (ready && candidates.isNotEmpty()) {
                playLink(s, candidates.first())
                return
            }
            if (s.set.done && candidates.isEmpty()) {
                fail(s, if (s.tried.isEmpty()) (s.set.error?.let { "No links: $it" } ?: "No playable links match this source profile. Pick another profile or source.") else "All links failed")
                return
            }
            val v = s.set.version.value
            withTimeoutOrNull(250) { s.set.version.first { it != v } }
        }
    }

    private fun ranked(s: Session, profile: SourceProfile = settings.activeSourceProfile()): List<ResolvedLink> =
        links.ranked(s.set, profile).filter {
            profile.visible(it.id, profile.priority(it.link.source, it.link.quality), s.failed, s.link?.id)
        }

    private suspend fun playLink(s: Session, link: ResolvedLink, startAt: Double? = s.startAt) {
        if (s.generation != generation) return
        val ipc = mpv.ensureRunning()
        s.tried.add(link.id)
        s.link = link
        s.playStartedAt = null
        s.recordedSuccess = false
        s.finished = false
        s.fileLoaded = false
        s.addedSubs.clear()
        s.subtitleSelectionMade = false
        s.skips = emptyList()
        s.skipped.clear()
        s.skipsRequested = false
        val l = link.link
        val lh = LinkHeaders.of(l)
        val headers = lh.extra
        val ua = lh.userAgent
        val referer = lh.referer
        // mpv splits header lists on commas, and ffmpeg cuts URLs at 4096 bytes; anything it can't express goes
        // through the proxy.
        val needsProxy = link.proxied || headers.values.any { ',' in it } || l.url.length > 4000 || settings.bool(Settings.PROXY_ALL) ||
            (l.type == ExtractorLinkType.M3U8 && proxy.hasTextSubtitles(l.url, lh.all))
        var sampleAesKey: String? = null
        val url = if (l.source in debugFailSources) {
            "http://127.0.0.1:9/debug-failed-source"
        } else if (needsProxy) {
            val path = proxy.register(l.url, lh.all, link.interceptor)
            if (l.type == ExtractorLinkType.M3U8) {
                sampleAesKey = runCatching { proxy.sampleAesKey(path) }.onFailure { log.info("Couldn't read {}'s playlists ahead: {}", l.source, it.toString()) }.getOrNull()
            }
            // FFmpeg's DASH demuxer reads one period; mpv joins a multi-period manifest's periods itself.
            val periods = if (link.clearKey != null) {
                runCatching { proxy.periodPaths(path) }.onFailure { log.info("Couldn't read {}'s manifest ahead: {}", l.source, it.toString()) }.getOrNull()
            } else null
            if (periods != null) {
                "edl://" + periods.joinToString(";") { "http://127.0.0.1:$port$it".let { u -> "%${u.toByteArray().size}%$u" } }
            } else "http://127.0.0.1:$port$path"
        } else l.url
        val title = listOfNotNull(s.item.details.name, s.item.label).joinToString(" · ")
        val opts = buildMap {
            put("force-media-title", title)
            if (!needsProxy) {
                put("user-agent", ua)
                referer?.let { put("referrer", it) }
                if (headers.isNotEmpty()) put("http-header-fields", headers.entries.joinToString(",") { "${it.key}: ${it.value}" })
            }
            if (startAt != null && startAt > 0) put("start", "%.1f".format(java.util.Locale.ROOT, startAt))
            link.clearKey?.let { put("demuxer-lavf-o", "cenc_decryption_key=$it") }
            sampleAesKey?.let { put("demuxer-lavf-o", "seg_format_options=decryption_key=$it") }
        }
        log.info("Playing {} [{}] {} via {}", l.source, Mapper.qualityLabel(l.quality), if (needsProxy) "proxy" else "direct", brief(l.url))
        _state.update { it.copy(status = "resolving", message = "Loading ${l.source} ${Mapper.qualityLabel(l.quality)}…", link = links.dto(link), position = startAt ?: 0.0, duration = 0.0, skip = null) }
        try {
            val reply = ipc.command("loadfile", url, "replace", -1, opts)
            s.entryId = (reply as? JsonObject)?.get("playlist_entry_id")?.jsonPrimitive?.longOrNull
        } catch (e: MpvError) {
            onLinkFailed(s, e.message ?: "mpv error")
        }
    }

    private suspend fun onLinkFailed(s: Session, reason: String) {
        val failed = s.link ?: return
        store.recordLink(s.item.provider, failed.link.source, success = false)
        s.failed.add(failed.id)
        _state.update { it.copy(failedLinks = s.failed.toList()) }
        val next = ranked(s).firstOrNull { it.id !in s.tried }
        log.info("Link {} failed ({}); next: {}", failed.link.source, reason, next?.link?.source)
        val msg = if (next != null) "Link failed, trying ${next.link.source} ${Mapper.qualityLabel(next.link.quality)}…" else "Link failed, waiting for more links…"
        osd(msg)
        _state.update { it.copy(status = "resolving", message = msg) }
        s.startAt = _state.value.position.takeIf { it > 5 } ?: s.startAt
        autoPick(s, fallback = true)
    }

    private fun fail(s: Session, message: String) {
        if (s.generation != generation) return
        _state.update { it.copy(status = "error", message = message) }
        script("loading-done", null)
        osd(message)
    }

    private suspend fun watchSubtitles(s: Session) {
        var seen = -1
        while (s.generation == generation) {
            val v = s.set.version.value
            if (v != seen && s.link != null && s.entryId != null && _state.value.status == "playing") {
                seen = v
                addSubtitles(s)
            }
            withTimeoutOrNull(1000) { s.set.version.first { it != v } }
        }
    }

    /** Adds the subtitles the user chose, plus up to three in the preferred languages. */
    private suspend fun addSubtitles(s: Session) {
        val ipc = mpv.ipc ?: return
        val prefs = listOf("en", "eng", "english")
        val wanted = s.set.subtitles.filter { sub ->
            sub.id in s.chosenSubs || prefs.any { p -> sub.sub.lang.lowercase().startsWith(p) || sub.sub.langTag?.lowercase() == p }
        }
        val link = s.link?.link ?: return
        val auto = SubtitlePreference.order(wanted.filter { it.id !in s.chosenSubs }, link.source, link.name, s.set.done) { it.sub.lang }.take(3)
        // Without an explicit choice, turn the first preferred-language subtitle on, except for dubs.
        val select = if (s.subtitleSelectionMade) null else s.chosenSubs.firstOrNull()
            ?: auto.firstOrNull()?.id?.takeIf { s.item.episode?.dub != "Dubbed" }
        for (sub in wanted.filter { it.id in s.chosenSubs } + auto) {
            if (!s.addedSubs.add(sub.id)) continue
            addSubtitle(ipc, sub, select = sub.id == select)
            if (sub.id == select) s.subtitleSelectionMade = true
        }
    }

    private suspend fun addSubtitle(ipc: MpvIpc, sub: ResolvedSubtitle, select: Boolean) {
        val url = sub.sub.headers?.takeIf { it.isNotEmpty() }?.let { "http://127.0.0.1:$port" + proxy.register(sub.sub.url, it, null) } ?: sub.sub.url
        runCatching { ipc.command("sub-add", url, if (select) "select" else "auto", sub.sub.lang, sub.sub.langTag ?: "") }
            .onFailure { log.debug("sub-add {} failed: {}", sub.sub.url, it.message) }
    }

    // ---- mpv events --------------------------------------------------------------------------------------------

    private suspend fun listen(ipc: MpvIpc) {
        val props = listOf("time-pos", "duration", "pause", "volume", "track-list", "idle-active")
        props.forEachIndexed { i, p -> runCatching { ipc.command("observe_property", i + 1, p) } }
        ipc.events.collect { e ->
            try {
                handle(e)
            } catch (t: Throwable) {
                log.warn("mpv event {} failed", e, t)
            }
        }
    }

    private suspend fun handle(e: JsonObject) {
        val s = session
        when (e["event"]?.jsonPrimitive?.content) {
            "property-change" -> onProperty(s, e["name"]!!.jsonPrimitive.content, e["data"])
            "file-loaded" -> if (s != null && s.link != null) {
                s.fileLoaded = true
                _state.update { it.copy(status = "playing", message = null) }
                script("item", mapOf("title" to s.item.details.name, "label" to s.item.label, "provider" to s.item.provider,
                    "source" to "${s.link!!.link.source} ${Mapper.qualityLabel(s.link!!.link.quality)}"))
                addSubtitles(s)
            }
            "end-file" -> {
                val entry = e["playlist_entry_id"]?.jsonPrimitive?.longOrNull
                if (s == null || entry != s.entryId) return
                when (e["reason"]?.jsonPrimitive?.content) {
                    "error" -> scope.launch { onLinkFailed(s, e["file_error"]?.jsonPrimitive?.contentOrNull ?: "playback error") }
                    "eof" -> onFinished(s)
                }
            }
            "client-message" -> {
                val args = e["args"]?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty()
                if (args.firstOrNull() == "cs-event") onScriptEvent(args.getOrNull(1)?.let { Json.parseToJsonElement(it).jsonObject } ?: return)
            }
            "ipc-closed" -> {
                saveProgress()
                session?.watchers?.cancel()
                generation++
                session = null
                _state.value = PlayerState()
            }
        }
    }

    private fun onProperty(s: Session?, name: String, data: kotlinx.serialization.json.JsonElement?) {
        when (name) {
            "time-pos" -> {
                if (s?.fileLoaded != true) return
                val pos = (data as? JsonPrimitive)?.doubleOrNull ?: return
                _state.update { it.copy(position = pos) }
                onTick(s, pos)
            }
            "duration" -> if (s?.fileLoaded == true) (data as? JsonPrimitive)?.doubleOrNull?.let { d ->
                _state.update { it.copy(duration = d) }
                if (d > 0 && !s.skipsRequested) {
                    s.skipsRequested = true
                    scope.launch { loadSkips(s, d) }
                }
            }
            "pause" -> (data as? JsonPrimitive)?.booleanOrNull?.let { p ->
                _state.update { it.copy(paused = p, status = if (it.status == "playing" || it.status == "paused") (if (p) "paused" else "playing") else it.status) }
            }
            "volume" -> (data as? JsonPrimitive)?.doubleOrNull?.let { v -> _state.update { it.copy(volume = v) } }
            "track-list" -> (data as? JsonArray)?.let { list ->
                val tracks = list.map { it.jsonObject }.mapNotNull { t ->
                    val type = t["type"]?.jsonPrimitive?.content ?: return@mapNotNull null
                    Track(
                        id = t["id"]!!.jsonPrimitive.int,
                        type = type,
                        title = t["title"]?.jsonPrimitive?.contentOrNull,
                        lang = t["lang"]?.jsonPrimitive?.contentOrNull,
                        selected = t["selected"]?.jsonPrimitive?.booleanOrNull == true,
                        external = t["external"]?.jsonPrimitive?.booleanOrNull == true,
                    )
                }
                _state.update { st -> st.copy(audioTracks = tracks.filter { it.type == "audio" }, subTracks = tracks.filter { it.type == "sub" }) }
            }
            "idle-active" -> if ((data as? JsonPrimitive)?.booleanOrNull == true && _state.value.status in setOf("playing", "paused")) {
                _state.update { it.copy(status = "idle", message = null) }
            }
        }
    }

    private fun onTick(s: Session, pos: Double) {
        val duration = _state.value.duration
        if (s.playStartedAt == null) s.playStartedAt = pos
        // A link counts as working once it has played 15 s.
        if (!s.recordedSuccess && pos - (s.playStartedAt ?: pos) > 15) {
            s.recordedSuccess = true
            s.link?.let { store.recordLink(s.item.provider, it.link.source, success = true) }
        }
        val now = System.currentTimeMillis()
        if (now - s.lastSaved > 5000) {
            s.lastSaved = now
            saveProgress()
        }
        updateSkip(s, pos)
        // Resolve the next episode's links in the background near the end, and again if they go stale (a long pause)
        // so the next episode always starts from fresh links.
        if (duration > 0 && pos > duration * 0.85 && s.item.hasNext && s.nextSet?.stale != false) prefetchNext(s)
    }

    /** Fetches AniSkip ranges for an anime episode once its length is known, and hands them to the script. */
    private suspend fun loadSkips(s: Session, duration: Double) {
        val d = s.item.details
        val episode = s.item.episode?.episode
        if (d.kind != "anime" || episode == null || (d.malId == null && d.aniListId == null)) return
        if (settings.string(Settings.SKIP_SEGMENTS) == "off") return
        val entry = s.entryId
        val ranges = skipTimes.forEpisode(d.malId, d.aniListId, episode, duration)
        if (ranges.isEmpty() || s.generation != generation || s.entryId != entry) return
        log.info("AniSkip for {} {}: {}", d.name, s.item.label, ranges.joinToString { "${it.type} ${it.start.toInt()}–${it.end.toInt()}" })
        s.skips = ranges
        script("skips", mapOf("ranges" to ranges.map { mapOf("type" to it.type, "label" to it.label, "start" to it.start, "end" to it.end) },
            "auto" to (settings.string(Settings.SKIP_SEGMENTS) == "auto")))
    }

    /** Publishes the range the position is in, and in auto mode seeks past it (once per range per file). */
    private fun updateSkip(s: Session, pos: Double) {
        if (s.skips.isEmpty()) return
        val mode = settings.string(Settings.SKIP_SEGMENTS)
        // A range already skipped stays hidden, so the prompt doesn't flash back while the seek lands.
        val current = SkipRule.current(s.skips, pos)?.takeIf { mode != "off" && it !in s.skipped }
        if (_state.value.skip != current) _state.update { it.copy(skip = current) }
        if (mode == "auto") SkipRule.autoSkip(s.skips, pos, s.skipped)?.let { skipTo(s, it, auto = true) }
    }

    private fun skipTo(s: Session, range: SkipRange, auto: Boolean) {
        s.skipped.add(range)
        mpv.ipc?.post("seek", range.end, "absolute")
        _state.update { it.copy(skip = null) }
        if (auto) osd("Skipped ${range.label.lowercase()}")
    }

    private fun prefetchNext(s: Session) {
        val provider = host.provider(s.item.provider) ?: return
        val next = s.item.at(s.item.index + 1)
        val set = links.resolve(provider.api, next.data)
        s.nextSet = set
        scope.launch {
            while (s.generation == generation && s.nextSet === set) {
                val v = set.version.value
                val playable = set.links.count { it.playable }
                val status = when {
                    !set.done -> "resolving"
                    playable > 0 -> "ready"
                    else -> "failed"
                }
                _state.update { it.copy(next = NextEpisode(next.label, status, playable)) }
                if (set.done) return@launch
                withTimeoutOrNull(1000) { set.version.first { it != v } }
            }
        }
    }

    private fun saveProgress() {
        val s = session ?: return
        val st = _state.value
        if (s.link == null || !s.fileLoaded || st.duration <= 0 || st.position <= 0) return
        store.saveProgress(s.item.provider, s.item.url, s.item.data, s.item.label, st.position, st.duration, st.position >= st.duration * 0.9)
    }

    private suspend fun onFinished(s: Session) {
        if (s.finished) return
        s.finished = true
        val st = _state.value
        store.saveProgress(s.item.provider, s.item.url, s.item.data, s.item.label, st.duration, st.duration, true)
        if (settings.bool(Settings.AUTOPLAY_NEXT) && s.item.hasNext) {
            start(s.item.at(s.item.index + 1))
        } else {
            _state.update { it.copy(status = "idle", message = "Finished") }
        }
    }

    // ---- intents from the UI and the script --------------------------------------------------------------------

    suspend fun command(cmd: String, body: PlayerCommand) {
        val ipc = mpv.ipc
        val s = session
        when (cmd) {
            "toggle" -> ipc?.command("cycle", "pause")
            "pause" -> ipc?.command("set_property", "pause", true)
            "resume" -> ipc?.command("set_property", "pause", false)
            "seek" -> ipc?.command("seek", body.seconds ?: 0.0, if (body.relative) "relative" else "absolute")
            "volume" -> ipc?.command("set_property", "volume", body.value ?: 100.0)
            "stop" -> {
                saveProgress()
                generation++
                session?.watchers?.cancel()
                session = null
                ipc?.command("stop")
                _state.update { PlayerState(mpvRunning = mpv.running) }
            }
            "next" -> if (s != null && s.item.hasNext) { markIfNearEnd(s); start(s.item.at(s.item.index + 1)) }
            "prev" -> if (s != null && s.item.hasPrev) start(s.item.at(s.item.index - 1))
            "watched-next" -> if (s != null) {
                store.setWatched(s.item.provider, s.item.url, s.item.data, true)
                if (s.item.hasNext) start(s.item.at(s.item.index + 1))
            }
            "episode" -> if (s != null) body.value?.toInt()?.takeIf { it in s.item.episodes.indices }?.let { start(s.item.at(it)) }
            "skip" -> if (s != null) SkipRule.current(s.skips, _state.value.position)?.let { skipTo(s, it, auto = false) }
            "track" -> {
                val prop = when (body.type) { "audio" -> "aid"; "sub" -> "sid"; else -> throw IllegalArgumentException("type must be audio or sub") }
                if (body.type == "sub" && s != null) s.subtitleSelectionMade = true
                ipc?.command("set_property", prop, body.id ?: "no")
            }
            "source" -> {
                val link = body.linkId?.let(links::link) ?: throw IllegalArgumentException("Unknown link")
                if (s != null && s.set.links.contains(link)) {
                    playLink(s, link, _state.value.position.takeIf { it > 5 } ?: s.startAt)
                }
            }
            "sub-add" -> {
                val sub = body.id?.let(links::subtitle) ?: throw IllegalArgumentException("Unknown subtitle")
                if (s != null && ipc != null && s.addedSubs.add(sub.id)) {
                    s.subtitleSelectionMade = true
                    addSubtitle(ipc, sub, select = true)
                }
            }
            else -> throw IllegalArgumentException("Unknown player command $cmd")
        }
    }

    private fun markIfNearEnd(s: Session) {
        val st = _state.value
        if (st.duration > 0 && st.position > st.duration * 0.85) store.setWatched(s.item.provider, s.item.url, s.item.data, true)
    }

    private suspend fun onScriptEvent(e: JsonObject) {
        val s = session
        when (e["type"]?.jsonPrimitive?.content) {
            "next" -> command("next", PlayerCommand())
            "prev" -> command("prev", PlayerCommand())
            "watched-next" -> command("watched-next", PlayerCommand())
            "skip" -> command("skip", PlayerCommand())
            "sources" -> if (s != null) {
                val stats = store.linkStats(s.item.provider)
                val profile = settings.activeSourceProfile()
                val list = ranked(s, profile).map { l ->
                    val quality = Mapper.qualityLabel(l.link.quality)
                    val failed = if (l.id in s.failed && l != s.link) " (failed)" else ""
                    val record = stats[l.link.source]?.let { "worked ${it.successes} of ${it.successes + it.failures}" } ?: "new"
                    mapOf("id" to l.id, "label" to "${l.link.source} · $quality$failed", "current" to (l == s.link),
                        "title" to l.link.source, "hint" to "$quality$failed · priority ${profile.priority(l.link.source, l.link.quality)} · $record")
                }
                script("sources", mapOf("items" to list, "loading" to !s.set.done))
            }
            "source" -> e["id"]?.jsonPrimitive?.contentOrNull?.let { command("source", PlayerCommand(linkId = it)) }
            "episodes" -> if (s != null) {
                val season = s.item.episode?.season
                val progress = store.progressFor(s.item.provider)
                val list = s.item.episodes.withIndex().filter { it.value.season == season }.map { (i, ep) ->
                    mapOf("index" to i, "label" to (s.item.at(i).label ?: "Episode ${i + 1}"), "current" to (i == s.item.index), "watched" to (progress[ep.data]?.watched == true))
                }
                script("episodes", mapOf("items" to list))
            }
            "episode" -> e["index"]?.jsonPrimitive?.intOrNull?.let { command("episode", PlayerCommand(value = it.toDouble())) }
        }
    }

    private fun script(cmd: String, payload: Any?) {
        mpv.ipc?.post("script-message-to", "cloudstream", cmd, payload?.let { json.encodeToString(toJson(it)) } ?: "{}")
    }

    private fun osd(text: String) = script("osd", mapOf("text" to text))

    private fun toJson(v: Any?): kotlinx.serialization.json.JsonElement = when (v) {
        null -> kotlinx.serialization.json.JsonNull
        is String -> JsonPrimitive(v)
        is Number -> JsonPrimitive(v)
        is Boolean -> JsonPrimitive(v)
        is Map<*, *> -> JsonObject(v.entries.associate { (k, x) -> k.toString() to toJson(x) })
        is List<*> -> JsonArray(v.map(::toJson))
        else -> JsonPrimitive(v.toString())
    }

    /** Current link set for the sources sheet (the item being played), if any. */
    fun currentSet(): LinkSet? = session?.set

    /** [url] short enough for the log: no query, and a long path cut (proxy URLs pack signed headers into it). */
    private fun brief(url: String): String = runCatching {
        val uri = java.net.URI(url)
        val path = uri.rawPath.orEmpty()
        "${uri.scheme}://${uri.host}" + if (path.length > 48) path.take(48) + "…" else path
    }.getOrDefault(url.take(80))
}
