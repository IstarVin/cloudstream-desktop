@file:OptIn(UnstableApi::class)

package com.istarvin.cloudstream.player

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import android.app.Application
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import desktop.api.Details
import desktop.api.Episode
import desktop.api.Labels
import desktop.api.LinkDto
import desktop.api.PlayRequest
import desktop.api.PlayerCommand
import desktop.api.ProgressRequest
import desktop.api.SubtitleDto
import desktop.api.SubtitleFormat
import com.istarvin.cloudstream.app
import com.istarvin.cloudstream.data.ApiException
import com.istarvin.cloudstream.data.CastReceiver
import com.istarvin.cloudstream.data.ServerSettings
import com.istarvin.cloudstream.data.error
import com.istarvin.cloudstream.data.link
import com.istarvin.cloudstream.data.subtitle
import com.istarvin.cloudstream.ui.episodeTitle
import desktop.links.LinkRanking
import desktop.player.AutoPlay
import desktop.player.PlayerState
import desktop.player.Track
import desktop.player.SkipRange
import desktop.player.SkipRule
import desktop.player.SourceProfile
import desktop.player.SubtitlePreference
import desktop.player.Watched
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File

/** One title being played: its episodes (one dub for anime) and which one is current; movies have none. */
data class Item(val details: Details, val episodes: List<Episode>, val index: Int, val data: String) {
    val episode get() = episodes.getOrNull(index)
    val hasNext get() = index >= 0 && index + 1 < episodes.size
    val hasPrev get() = index > 0
    val label get() = episode?.let { Labels.episode(it, index) }
    val displayLabel get() = episode?.let { episodeTitle(it, index) }

    fun at(i: Int) = copy(index = i, data = episodes[i].data)

    companion object {
        fun of(details: Details, data: String): Item {
            val target = details.episodes.firstOrNull { it.data == data }
            if (target == null || details.movieData == data) return Item(details, emptyList(), -1, data)
            val list = details.episodes.filter { it.dub == target.dub }
            return Item(details, list, list.indexOf(target), data)
        }
    }
}

/** What the player screen shows besides the video. */
data class Session(
    val item: Item? = null,
    /** resolving | loading | playing | error | ended */
    val status: String = "resolving",
    val message: String? = null,
    val links: List<LinkDto> = emptyList(),
    val subtitles: List<SubtitleDto> = emptyList(),
    val linksDone: Boolean = false,
    val current: LinkDto? = null,
    val failed: Set<String> = emptySet(),
    /** The side-loaded subtitle on screen, or null for an embedded track or none. */
    val subtitle: String? = null,
    val subtitlesOff: Boolean = false,
    val skips: List<SkipRange> = emptyList(),
    /** The opening/ending the position is in, while the player offers to skip it. */
    val skip: SkipRange? = null,
    /** Seconds until the next episode starts, while the end card counts down. */
    val upNext: Int? = null,
    /** Asks the screen to open the source picker (auto-play is off, or every link failed). */
    val pickSource: Boolean = false,
)

class PlayerViewModel(application: Application) : AndroidViewModel(application), CastReceiver {
    private val api = application.app.api
    private val cast = application.app.cast
    private val prefs = application.app.prefs
    private val appScope = application.app.scope
    val engine = Engine(application, api.http)
    val player get() = engine.player

    private val _session = MutableStateFlow(Session())
    val session: StateFlow<Session> = _session

    private var settings = ServerSettings()
    val profile: SourceProfile get() = settings.activeProfile

    private var linksJob: Job? = null
    private var pickJob: Job? = null
    private var prefetchJob: Job? = null
    private var countdownJob: Job? = null
    private val tried = mutableSetOf<String>()
    private var startAt: Double? = null
    private var playStartedAt: Long? = null
    private var recordedSuccess = false
    private var skipsRequested = false
    private val skipped = mutableSetOf<SkipRange>()
    private var lastSaved = 0L
    /** Subtitles explicitly chosen in the picker this episode. */
    private val chosenSubs = mutableListOf<String>()
    /** Side-loaded subtitles, downloaded once, by id: the file and its format. */
    private val subtitleFiles = mutableMapOf<String, Pair<File, SubtitleFormat>>()
    private var started = false

    private val _closed = MutableStateFlow(false)
    /** A device controlling this one sent Stop; the screen closes. */
    val closed: StateFlow<Boolean> = _closed

    init {
        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                when (state) {
                    Player.STATE_READY -> if (_session.value.status == "loading") _session.update { it.copy(status = "playing", message = null) }
                    Player.STATE_ENDED -> onEnded()
                    else -> {}
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                onLinkFailed(error.errorCodeName + (error.cause?.message?.let { ": $it" } ?: ""))
            }

            override fun onTracksChanged(tracks: Tracks) = applySubtitleChoice(tracks)

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (!isPlaying) saveProgress(force = true)
            }
        })
        viewModelScope.launch { while (isActive) { tick(); delay(500) } }
        cast.receiverPlayer = this
    }

    /**
     * Starts [data] of [details] once; later calls (a recreated activity) keep the running session. A cast or a move
     * from another device may name the [linkId] to play and the [subtitleIds] chosen there.
     */
    fun start(details: Details, data: String, at: Double?, linkId: String? = null, subtitleIds: List<String> = emptyList()) {
        if (started) return
        started = true
        viewModelScope.launch {
            settings = runCatching { api.settings() }.getOrDefault(settings)
            open(Item.of(details, data), at, linkId, subtitleIds)
        }
    }

    private fun open(item: Item, at: Double?, linkId: String? = null, subtitleIds: List<String> = emptyList()) {
        saveProgress(force = true)
        linksJob?.cancel()
        pickJob?.cancel()
        prefetchJob?.cancel()
        countdownJob?.cancel()
        player.stop()
        player.clearMediaItems()
        tried.clear()
        chosenSubs.clear()
        subtitleFiles.clear()
        skipped.clear()
        skipsRequested = false
        startAt = at
        chosenSubs.addAll(subtitleIds)
        _session.value = Session(item = item, status = "resolving", message = "Finding links…")

        linksJob = viewModelScope.launch {
            if (startAt == null) startAt = runCatching { api.resume(item.details.provider, item.data) }.getOrNull()
            collectLinks(item)
        }
        when {
            linkId != null -> pickWhenFound(linkId)
            settings.autoplayBest -> autoPick(fallback = false)
            else -> _session.update { it.copy(message = "Choose a source", pickSource = true) }
        }
    }

    private suspend fun collectLinks(item: Item, fresh: Boolean = false) {
        try {
            api.links(item.details.provider, item.data, fresh).collect { ev ->
                when (ev.event) {
                    "link" -> {
                        val l = ev.link()
                        _session.update { s -> s.copy(links = (s.links + l).sortedWith(LinkRanking.comparator(profile))) }
                    }
                    "subtitle" -> {
                        val sub = ev.subtitle()
                        _session.update { s -> s.copy(subtitles = s.subtitles + sub) }
                        maybeAddSubtitle()
                    }
                    "error" -> _session.update { it.copy(message = ev.error()) }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (_session.value.links.isEmpty()) _session.update { it.copy(status = "error", message = "Couldn't load links: ${e.message}") }
        }
        _session.update { it.copy(linksDone = true) }
    }

    /** Runs the plugin's `loadLinks` again for the current episode, without interrupting what's playing. */
    fun reloadLinks() {
        val item = _session.value.item ?: return
        linksJob?.cancel()
        _session.update { it.copy(links = emptyList(), subtitles = emptyList(), linksDone = false) }
        linksJob = viewModelScope.launch { collectLinks(item, fresh = true) }
    }

    /** Plays the link another device picked once it streams in; auto-picks if it never does. */
    private fun pickWhenFound(linkId: String) {
        pickJob?.cancel()
        pickJob = viewModelScope.launch {
            while (isActive) {
                val s = _session.value
                val link = s.links.firstOrNull { it.id == linkId && it.playable }
                if (link != null) {
                    play(link)
                    return@launch
                }
                if (s.linksDone) {
                    autoPick(fallback = false)
                    return@launch
                }
                delay(250)
            }
        }
    }

    /** Playable links the profile shows, best first. */
    fun ranked(s: Session = _session.value): List<LinkDto> =
        s.links.filter { it.playable && profile.visible(it.id, profile.priority(it), s.failed, s.current?.id) }
            .sortedWith(LinkRanking.comparator(profile))

    /** Hidden by the profile (negative priority or failed), for the picker's "show hidden" toggle. */
    fun hiddenCount(s: Session) = s.links.count { it.playable && !profile.visible(it.id, profile.priority(it), s.failed, s.current?.id) }

    fun priority(link: LinkDto) = profile.priority(link)

    private fun autoPick(fallback: Boolean) {
        pickJob?.cancel()
        pickJob = viewModelScope.launch {
            while (isActive) {
                val s = _session.value
                val candidates = ranked(s).filter { it.id !in tried }
                val ready = AutoPlay.ready(fallback, s.linksDone, candidates.firstOrNull()?.let(profile::priority) ?: 0)
                if (ready && candidates.isNotEmpty()) {
                    play(candidates.first())
                    return@launch
                }
                if (s.linksDone && candidates.isEmpty()) {
                    val msg = when {
                        tried.isNotEmpty() -> "All links failed"
                        s.links.none { it.playable } -> s.message?.takeIf { s.links.isEmpty() } ?: "No playable links"
                        else -> "No playable links match this source profile. Pick one yourself."
                    }
                    _session.update { it.copy(status = "error", message = msg, pickSource = true) }
                    return@launch
                }
                delay(250)
            }
        }
    }

    /** Plays [link], from the current position when switching sources mid-episode. */
    fun play(link: LinkDto, from: Double? = null) {
        pickJob?.cancel()
        val s = _session.value
        val item = s.item ?: return
        val at = from ?: startAt
        tried.add(link.id)
        playStartedAt = null
        recordedSuccess = false
        _session.update { it.copy(current = link, status = "loading", message = "Loading ${link.source} ${link.qualityLabel}…", pickSource = false, upNext = null) }
        viewModelScope.launch {
            try {
                val direct = prefs.player.first().directLinks
                val stream = api.stream(link.id, direct)
                val subs = subtitleConfigs(defaultSubtitles(link))
                val media = engine.item(stream, api.resolve(stream.url), subs, listOfNotNull(item.details.name, item.displayLabel).joinToString(" · "))
                player.setMediaItem(media, ((at ?: 0.0) * 1000).toLong().coerceAtLeast(0))
                player.prepare()
                player.playWhenReady = true
            } catch (e: CancellationException) {
                throw e
            } catch (e: ApiException) {
                if (e.code == 410) {
                    // The server forgot these links (they expire); resolve again from where we were.
                    open(item, currentPosition().takeIf { it > 5 } ?: at)
                } else onLinkFailed(e.message ?: "error")
            } catch (e: Exception) {
                onLinkFailed(e.message ?: e.javaClass.simpleName)
            }
        }
    }

    private fun onLinkFailed(reason: String) {
        val s = _session.value
        val failed = s.current ?: return
        if (failed.id in s.failed) return
        viewModelScope.launch { runCatching { api.report(failed.id, success = false) } }
        val pos = currentPosition()
        if (pos > 5) startAt = pos
        _session.update { it.copy(failed = it.failed + failed.id) }
        val next = ranked(_session.value).firstOrNull { it.id !in tried }
        android.util.Log.i("Player", "Link ${failed.source} failed ($reason); next: ${next?.source}")
        _session.update {
            it.copy(status = "resolving", message = if (next != null) "Link failed, trying ${next.source} ${next.qualityLabel}…" else "Link failed, waiting for more links…")
        }
        autoPick(fallback = true)
    }

    // ---- subtitles ---------------------------------------------------------------------------------------------

    /** Chosen ones, plus up to three in the preferred language; the first of those is shown unless it's a dub. */
    private fun defaultSubtitles(link: LinkDto): List<SubtitleDto> {
        val s = _session.value
        val preferred = s.subtitles.filter { it.id !in chosenSubs && SubtitlePreference.preferredLanguage(it.lang) }
        val auto = SubtitlePreference.order(preferred, link.source, link.name, s.linksDone) { it.lang }.take(3)
        val chosen = s.subtitles.filter { it.id in chosenSubs }
        if (s.subtitle == null && !s.subtitlesOff) {
            val select = chosen.firstOrNull() ?: auto.firstOrNull()?.takeIf { s.item?.episode?.dub != "Dubbed" }
            _session.update { it.copy(subtitle = select?.id) }
        }
        return chosen + auto
    }

    /** Subtitles arriving after the link started: add the first preferred one if none is on yet. */
    private fun maybeAddSubtitle() {
        val s = _session.value
        val link = s.current ?: return
        if (s.status != "playing" || s.subtitle != null || s.subtitlesOff || s.item?.episode?.dub == "Dubbed") return
        val pick = SubtitlePreference.order(s.subtitles.filter { SubtitlePreference.preferredLanguage(it.lang) }, link.source, link.name, s.linksDone) { it.lang }
            .firstOrNull() ?: return
        selectSubtitle(pick)
    }

    /** Downloads side-loaded subtitles once; the server sniffs the format and serves it with the right type. */
    private suspend fun subtitleConfigs(subs: List<SubtitleDto>): List<MediaItem.SubtitleConfiguration> = withContext(Dispatchers.IO) {
        val dir = File(getApplication<Application>().cacheDir, "subs").apply { mkdirs() }
        subs.mapNotNull { sub ->
            val (file, format) = subtitleFiles[sub.id] ?: runCatching {
                api.http.newCall(Request.Builder().url(api.subtitleUrl(sub)).build()).execute().use { res ->
                    if (!res.isSuccessful) return@runCatching null
                    val type = res.header("Content-Type").orEmpty().substringBefore(';').trim()
                    val format = SubtitleFormat.entries.firstOrNull { it.mime == type } ?: SubtitleFormat.SRT
                    val f = File(dir, "${sub.id}.${format.extension}")
                    f.writeBytes(res.body.bytes())
                    (f to format).also { subtitleFiles[sub.id] = it }
                }
            }.getOrNull() ?: return@mapNotNull null
            MediaItem.SubtitleConfiguration.Builder(Uri.fromFile(file))
                .setId(SUB_PREFIX + sub.id)
                .setMimeType(format.mime)
                .setLanguage(sub.lang)
                .setLabel(sub.lang)
                .setSelectionFlags(if (sub.id == _session.value.subtitle) C.SELECTION_FLAG_DEFAULT else 0)
                .build()
        }
    }

    /** Shows a side-loaded subtitle: re-opens the stream with it added, at the same position. */
    fun selectSubtitle(sub: SubtitleDto) {
        if (sub.id !in chosenSubs) chosenSubs.add(sub.id)
        _session.update { it.copy(subtitle = sub.id, subtitlesOff = false) }
        val loaded = player.currentMediaItem?.localConfiguration?.subtitleConfigurations?.any { it.id == SUB_PREFIX + sub.id } == true
        if (loaded) {
            applySubtitleChoice(player.currentTracks)
            return
        }
        val current = player.currentMediaItem ?: return
        viewModelScope.launch {
            val configs = current.localConfiguration?.subtitleConfigurations.orEmpty() + subtitleConfigs(listOf(sub))
            val pos = player.currentPosition
            player.setMediaItem(current.buildUpon().setSubtitleConfigurations(configs).build(), pos)
            player.prepare()
        }
    }

    /** Shows an embedded text track (from the stream itself). */
    fun selectEmbedded(group: Tracks.Group, index: Int) {
        _session.update { it.copy(subtitle = null, subtitlesOff = false) }
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
            .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, index))
            .build()
    }

    fun subtitlesOff() {
        _session.update { it.copy(subtitle = null, subtitlesOff = true) }
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).build()
    }

    fun selectAudio(group: Tracks.Group, index: Int) {
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, index))
            .build()
    }

    private fun applySubtitleChoice(tracks: Tracks) {
        val s = _session.value
        val id = s.subtitle ?: return
        val group = tracks.groups.firstOrNull { g ->
            g.type == C.TRACK_TYPE_TEXT && (0 until g.length).any { g.getTrackFormat(it).id?.contains(SUB_PREFIX + id) == true }
        } ?: return
        if (group.isSelected) return
        val index = (0 until group.length).first { group.getTrackFormat(it).id?.contains(SUB_PREFIX + id) == true }
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
            .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, index))
            .build()
    }

    // ---- progress, skips, the end ------------------------------------------------------------------------------

    private fun currentPosition() = player.currentPosition / 1000.0
    private fun duration() = player.duration.takeIf { it != C.TIME_UNSET && it > 0 }?.div(1000.0) ?: 0.0

    private fun tick() {
        val s = _session.value
        val item = s.item ?: return
        if (s.status != "playing") return
        val pos = currentPosition()
        val dur = duration()
        if (player.isPlaying) {
            val started = playStartedAt ?: System.currentTimeMillis().also { playStartedAt = it }
            if (!recordedSuccess && System.currentTimeMillis() - started >= Watched.SUCCESS_AFTER_SECONDS * 1000) {
                recordedSuccess = true
                s.current?.let { l -> viewModelScope.launch { runCatching { api.report(l.id, success = true) } } }
            }
            saveProgress()
        }
        if (dur > 0 && !skipsRequested) {
            skipsRequested = true
            loadSkips(item, dur)
        }
        val current = SkipRule.current(s.skips, pos)?.takeIf { settings.skipSegments != "off" && it !in skipped }
        if (settings.skipSegments == "auto") SkipRule.autoSkip(s.skips, pos, skipped)?.let { skip(it) }
        if (current != s.skip) _session.update { it.copy(skip = current) }
        // Warm the server's link cache for the next episode near the end.
        if (dur > 0 && pos > dur * Watched.PREFETCH_FRACTION && item.hasNext && prefetchJob == null) {
            val next = item.at(item.index + 1)
            prefetchJob = viewModelScope.launch { runCatching { api.links(next.details.provider, next.data).collect {} } }
        }
    }

    private fun loadSkips(item: Item, duration: Double) {
        val d = item.details
        val episode = item.episode?.episode ?: return
        if (d.kind != "anime" || (d.malId == null && d.aniListId == null) || settings.skipSegments == "off") return
        viewModelScope.launch {
            val ranges = runCatching { api.skips(d.malId, d.aniListId, episode, duration) }.getOrDefault(emptyList())
            if (_session.value.item == item) _session.update { it.copy(skips = ranges) }
        }
    }

    fun skip(range: SkipRange? = _session.value.skip) {
        range ?: return
        skipped.add(range)
        player.seekTo((range.end * 1000).toLong())
        _session.update { it.copy(skip = null) }
    }

    private fun saveProgress(force: Boolean = false) {
        val s = _session.value
        val item = s.item ?: return
        val pos = currentPosition()
        val dur = duration()
        if (s.current == null || dur <= 0 || pos <= 0) return
        val now = System.currentTimeMillis()
        if (!force && now - lastSaved < 5000) return
        lastSaved = now
        val req = ProgressRequest(item.details.provider, item.details.url, item.data, position = pos, duration = dur, label = item.label)
        appScope.launch { runCatching { api.saveProgress(req) } }
    }

    private fun onEnded() {
        val s = _session.value
        val item = s.item ?: return
        val dur = duration()
        viewModelScope.launch {
            runCatching { api.saveProgress(ProgressRequest(item.details.provider, item.details.url, item.data, watched = true, position = dur, duration = dur, label = item.label)) }
        }
        if (settings.autoplayNext && item.hasNext) {
            countdownJob?.cancel()
            countdownJob = viewModelScope.launch {
                for (n in 5 downTo 1) {
                    _session.update { it.copy(status = "ended", upNext = n) }
                    delay(1000)
                }
                next()
            }
        } else _session.update { it.copy(status = "ended", message = "Finished") }
    }

    fun cancelUpNext() {
        countdownJob?.cancel()
        _session.update { it.copy(upNext = null) }
    }

    fun next() {
        val item = _session.value.item ?: return
        if (item.hasNext) open(item.at(item.index + 1), null)
    }

    fun prev() {
        val item = _session.value.item ?: return
        if (item.hasPrev) open(item.at(item.index - 1), null)
    }

    fun episode(index: Int) {
        val item = _session.value.item ?: return
        if (index in item.episodes.indices) open(item.at(index), null)
    }

    fun retry() {
        val item = _session.value.item ?: return
        open(item, currentPosition().takeIf { it > 5 } ?: startAt)
    }

    fun dismissPicker() = _session.update { it.copy(pickSource = false) }

    fun onStop() = saveProgress(force = true)

    override fun onCleared() {
        saveProgress(force = true)
        if (cast.receiverPlayer === this) cast.receiverPlayer = null
        engine.release()
    }

    // ---- casting: this player as a device others control -------------------------------------------------------

    /** Audio or text tracks, numbered from 1 in the order the stream lists them: the ids other devices see. */
    private data class TrackRef(val id: Int, val group: Tracks.Group, val index: Int)

    private fun trackRefs(type: Int): List<TrackRef> {
        var n = 0
        return player.currentTracks.groups.filter { it.type == type }.flatMap { g ->
            (0 until g.length).filter { g.isTrackSupported(it) }.map { TrackRef(++n, g, it) }
        }
    }

    override fun castState(): PlayerState {
        val s = _session.value
        val item = s.item ?: return PlayerState(paused = true)
        val paused = !player.playWhenReady || s.status == "ended"
        val status = when (s.status) {
            "resolving", "loading" -> "resolving"
            "error" -> "error"
            else -> if (paused) "paused" else "playing"
        }
        fun tracks(type: Int, name: String) = trackRefs(type).map { r ->
            val f = r.group.getTrackFormat(r.index)
            Track(r.id, name, f.label, f.language, r.group.isTrackSelected(r.index), f.id?.contains(SUB_PREFIX) == true)
        }
        return PlayerState(
            status = status,
            message = s.message,
            position = currentPosition(),
            duration = duration(),
            paused = paused,
            volume = (player.volume * 100).toDouble(),
            title = item.details.name,
            episodeLabel = item.label,
            provider = item.details.provider,
            url = item.details.url,
            data = item.data,
            poster = item.episode?.poster ?: item.details.poster,
            link = s.current,
            audioTracks = tracks(C.TRACK_TYPE_AUDIO, "audio"),
            subTracks = if (s.subtitlesOff) tracks(C.TRACK_TYPE_TEXT, "sub").map { it.copy(selected = false) } else tracks(C.TRACK_TYPE_TEXT, "sub"),
            hasNext = item.hasNext,
            hasPrev = item.hasPrev,
            failedLinks = s.failed.toList(),
            skip = s.skip,
        )
    }

    /** Another device cast something here while the player is open: play it in place. */
    override fun castPlay(req: PlayRequest) {
        started = true
        viewModelScope.launch {
            val current = _session.value.item?.details
            val details = if (current != null && current.provider == req.provider && current.url == req.url) current
            else runCatching { api.details(req.provider, req.url) }.getOrElse { e ->
                _session.update { it.copy(status = "error", message = "Couldn't load the title: ${e.message}") }
                return@launch
            }
            settings = runCatching { api.settings() }.getOrDefault(settings)
            open(Item.of(details, req.data), req.startAt, req.linkId, req.subtitleIds)
        }
    }

    /** A command from a controlling device, with the meaning it has for the desktop's mpv. */
    override fun castCommand(cmd: String, body: PlayerCommand) {
        val s = _session.value
        when (cmd) {
            "toggle" -> {
                if (player.playbackState == Player.STATE_ENDED) player.seekTo(0)
                player.playWhenReady = !player.playWhenReady
            }
            "pause" -> player.playWhenReady = false
            "resume" -> player.playWhenReady = true
            "seek" -> {
                val to = if (body.relative) currentPosition() + (body.seconds ?: 0.0) else body.seconds ?: 0.0
                player.seekTo((to.coerceAtLeast(0.0) * 1000).toLong())
            }
            "volume" -> player.volume = ((body.value ?: 100.0) / 100).toFloat().coerceIn(0f, 1f)
            "stop" -> {
                saveProgress(force = true)
                player.pause()
                _closed.value = true
            }
            "next", "watched-next" -> next()
            "prev" -> prev()
            "episode" -> body.value?.toInt()?.let(::episode)
            "skip" -> skip()
            "track" -> castTrack(body.type, body.id)
            "source" -> s.links.firstOrNull { it.id == body.linkId }?.let { play(it, currentPosition().takeIf { p -> p > 5 }) }
            "sub-add" -> s.subtitles.firstOrNull { it.id == body.id }?.let(::selectSubtitle)
        }
    }

    private fun castTrack(type: String?, id: String?) {
        val n = id?.toIntOrNull()
        when (type) {
            "audio" -> trackRefs(C.TRACK_TYPE_AUDIO).firstOrNull { it.id == n }?.let { selectAudio(it.group, it.index) }
            "sub" -> {
                val ref = trackRefs(C.TRACK_TYPE_TEXT).firstOrNull { it.id == n } ?: return subtitlesOff()
                val formatId = ref.group.getTrackFormat(ref.index).id.orEmpty()
                val sideLoaded = _session.value.subtitles.firstOrNull { formatId.contains(SUB_PREFIX + it.id) }
                if (sideLoaded != null) selectSubtitle(sideLoaded) else selectEmbedded(ref.group, ref.index)
            }
        }
    }

    /** What moving this episode to another device carries over: the link, the chosen subtitles and the position. */
    fun handoff(): PlayRequest? {
        val s = _session.value
        val item = s.item ?: return null
        return PlayRequest(
            item.details.provider, item.details.url, item.data,
            linkId = s.current?.id,
            subtitleIds = chosenSubs.toList(),
            startAt = currentPosition().takeIf { it > 5 },
        )
    }

    companion object {
        private const val SUB_PREFIX = "cs-sub-"
    }
}
