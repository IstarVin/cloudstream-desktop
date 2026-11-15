@file:OptIn(UnstableApi::class)

package com.istarvin.cloudstream.player

import android.app.PendingIntent
import android.content.Intent
import android.os.Looper
import androidx.annotation.OptIn
import androidx.core.net.toUri
import androidx.media3.common.DeviceInfo
import androidx.media3.common.ForwardingSimpleBasePlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSourceBitmapLoader
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.session.CacheBitmapLoader
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.istarvin.cloudstream.App
import com.istarvin.cloudstream.app
import com.istarvin.cloudstream.data.PlayTargets
import com.istarvin.cloudstream.ui.MainActivity
import desktop.api.DeviceKind
import desktop.api.PlayerCommand
import desktop.player.PlayerState
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * The system media sessions: one for the player screen while it's open (notification, lock screen, headset buttons),
 * and one while this device controls another's player, so the notification and the volume keys drive that device.
 * Both live in [PlaybackService], which Media3 keeps in the foreground while something plays.
 */
class MediaHub(private val app: App) {
    internal var service: PlaybackService? = null
        set(value) {
            field = value
            value?.let { s -> listOfNotNull(local, remote).forEach { if (!s.isSessionAdded(it)) s.addSession(it) } }
        }

    var local: MediaSession? = null
        private set
    var remote: MediaSession? = null
        private set
    private var remotePlayer: RemotePlayer? = null

    /** Posters the desktop proxies need the pairing token, so artwork loads through the API's client. */
    private val bitmaps by lazy {
        CacheBitmapLoader(
            DataSourceBitmapLoader.Builder(app)
                .setDataSourceFactory(DefaultDataSource.Factory(app, OkHttpDataSource.Factory(app.api.http)))
                .build(),
        )
    }

    /** Whether the casting socket must stay up with the app hidden: a remote session is showing that device. */
    val holdsCast: Boolean get() = remote != null

    /** Called when the remote session goes away, so the app can drop the socket it kept up for it. */
    var onRemoteReleased: (() -> Unit)? = null

    fun start() {
        val cast = app.cast
        app.scope.launch {
            combine(cast.devices, cast.states) { devices, states ->
                val device = PlayTargets.controlled(devices, cast.id)
                val state = device?.let { states[it.id] }?.takeIf(PlayTargets::active)
                if (device == null || state == null) null else Controlled(device.id, device.name, device.kind, state)
            }.distinctUntilChanged().collect { c -> if (c == null) releaseRemote() else showRemote(c) }
        }
    }

    private data class Controlled(val id: String, val name: String, val kind: DeviceKind?, val state: PlayerState)

    // ---- this device's player screen ------------------------------------------------------------------------------

    fun openLocal(player: Player): MediaSession {
        local?.let { closeLocal(it) }
        val session = MediaSession.Builder(app, player)
            .setId("local")
            .setBitmapLoader(bitmaps)
            .setSessionActivity(activityIntent(Intent(app, PlayerActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)))
            .build()
        local = session
        attach(session)
        return session
    }

    fun closeLocal(session: MediaSession) {
        if (local === session) local = null
        detach(session)
        session.release()
    }

    // ---- another device's player -------------------------------------------------------------------------------

    private fun showRemote(c: Controlled) {
        val player = remotePlayer?.takeIf { it.deviceId == c.id } ?: run {
            releaseRemote()
            RemotePlayer(app, c.id).also { p ->
                remotePlayer = p
                val session = MediaSession.Builder(app, p)
                    .setId("remote")
                    .setBitmapLoader(bitmaps)
                    .setSessionActivity(activityIntent(Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)))
                    .build()
                remote = session
                attach(session)
            }
        }
        player.update(c.name, c.kind, c.state)
    }

    private fun releaseRemote() {
        val session = remote ?: return
        remote = null
        remotePlayer = null
        detach(session)
        session.player.release()
        session.release()
        onRemoteReleased?.invoke()
    }

    /** The volume keys while the app is on screen: adjust the controlled device. Returns its new volume, or null when there's none. */
    fun adjustRemoteVolume(up: Boolean): Int? {
        val p = remotePlayer ?: return null
        if (up) p.increaseDeviceVolume(0) else p.decreaseDeviceVolume(0)
        return p.deviceVolume
    }

    // ---- service --------------------------------------------------------------------------------------------------

    private fun attach(session: MediaSession) {
        val s = service
        if (s != null) {
            if (!s.isSessionAdded(session)) s.addSession(session)
        } else {
            // The app is on screen when a session starts; Android refuses to start services from the background.
            runCatching { app.startService(Intent(app, PlaybackService::class.java)) }
        }
    }

    private fun detach(session: MediaSession) {
        val s = service ?: return
        if (s.isSessionAdded(session)) s.removeSession(session)
        if (s.sessions.isEmpty()) s.stopSelf()
    }

    private fun activityIntent(intent: Intent): PendingIntent =
        PendingIntent.getActivity(app, intent.component.hashCode(), intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
}

/** Hosts [MediaHub]'s sessions; Media3 posts their notifications and runs it in the foreground while one plays. */
class PlaybackService : MediaSessionService() {
    private val hub get() = app.media

    override fun onCreate() {
        super.onCreate()
        hub.service = this
        if (sessions.isEmpty()) stopSelf()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = hub.local ?: hub.remote

    override fun onTaskRemoved(rootIntent: Intent?) {
        // The player screen goes with the task; a remote device keeps playing on its own.
        hub.local?.player?.pause()
        stopSelf()
    }

    override fun onDestroy() {
        if (hub.service === this) hub.service = null
        super.onDestroy()
    }
}

/**
 * The local player as the system sees it: next and previous move between episodes (the player itself holds one
 * item), and are offered only when there is an episode to move to.
 */
class EpisodePlayer(player: Player, private val hasNext: () -> Boolean, private val hasPrev: () -> Boolean, private val onNext: () -> Unit, private val onPrev: () -> Unit) :
    ForwardingSimpleBasePlayer(player) {

    override fun getState(): State {
        val s = super.getState()
        val commands = s.availableCommands.buildUpon()
            .removeAll(Player.COMMAND_SEEK_TO_NEXT, Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM, Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
            .apply { if (hasNext()) addAll(Player.COMMAND_SEEK_TO_NEXT, Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM) }
            .apply { if (hasPrev()) add(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM) }
            .build()
        return s.buildUpon().setAvailableCommands(commands).build()
    }

    override fun handleSeek(mediaItemIndex: Int, positionMs: Long, seekCommand: Int): ListenableFuture<*> = when (seekCommand) {
        Player.COMMAND_SEEK_TO_NEXT, Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM -> { onNext(); Futures.immediateVoidFuture() }
        // Previous restarts the episode after its first seconds, as players do; before that it goes back one.
        Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM -> { onPrev(); Futures.immediateVoidFuture() }
        Player.COMMAND_SEEK_TO_PREVIOUS ->
            if (hasPrev() && player.currentPosition <= player.maxSeekToPreviousPosition) { onPrev(); Futures.immediateVoidFuture() }
            else super.handleSeek(mediaItemIndex, 0, Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM)
        else -> super.handleSeek(mediaItemIndex, positionMs, seekCommand)
    }

    /** The episode changed, so whether next/previous apply did too. */
    fun refresh() = invalidateState()
}

/**
 * Another device's player (a TV, mpv on the desktop), mirrored from its cast state. A remote device volume makes
 * Android send the volume keys here, and the system shows that device's volume instead of the phone's.
 */
class RemotePlayer(private val app: App, val deviceId: String) : SimpleBasePlayer(Looper.getMainLooper()) {
    private var name = ""
    private var kind: DeviceKind? = null
    private var state = PlayerState()
    /** A volume just sent, used until the device reports it back so quick key presses add up. */
    private var pendingVolume: Int? = null
    private var pendingAt = 0L

    private val maxVolume get() = if (kind == DeviceKind.MPV) 130 else 100

    fun update(name: String, kind: DeviceKind?, state: PlayerState) {
        this.name = name
        this.kind = kind
        this.state = state
        if (pendingVolume == state.volume.roundToInt() || System.currentTimeMillis() - pendingAt > 2000) pendingVolume = null
        invalidateState()
    }

    private fun volume(): Int = (pendingVolume ?: state.volume.roundToInt()).coerceIn(0, maxVolume)

    // The non-WITH_FLAGS volume commands are deprecated but still advertised for older controllers.
    @Suppress("DEPRECATION")
    override fun getState(): State {
        val s = state
        val busy = s.status == "resolving"
        val commands = Player.Commands.Builder()
            .addAll(
                Player.COMMAND_PLAY_PAUSE, Player.COMMAND_STOP, Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM,
                Player.COMMAND_SEEK_BACK, Player.COMMAND_SEEK_FORWARD, Player.COMMAND_GET_CURRENT_MEDIA_ITEM,
                Player.COMMAND_GET_TIMELINE, Player.COMMAND_GET_METADATA, Player.COMMAND_GET_DEVICE_VOLUME,
                Player.COMMAND_SET_DEVICE_VOLUME, Player.COMMAND_SET_DEVICE_VOLUME_WITH_FLAGS,
                Player.COMMAND_ADJUST_DEVICE_VOLUME, Player.COMMAND_ADJUST_DEVICE_VOLUME_WITH_FLAGS,
            )
            .apply { if (s.hasNext) addAll(Player.COMMAND_SEEK_TO_NEXT, Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM) }
            .apply { if (s.hasPrev) add(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM) }
            .add(Player.COMMAND_SEEK_TO_PREVIOUS)
            .build()
        val metadata = MediaMetadata.Builder()
            .setTitle(s.title)
            .setArtist(listOfNotNull(s.episodeLabel, name.takeIf { kind != DeviceKind.MPV }?.let { "on $it" }).joinToString(" · ").ifEmpty { null })
            .setArtworkUri(s.poster?.let { runCatching { app.api.resolve(it).toUri() }.getOrNull() })
            .build()
        val durationUs = if (s.duration > 0) (s.duration * 1_000_000).toLong() else androidx.media3.common.C.TIME_UNSET
        val item = MediaItemData.Builder("${s.provider}|${s.url}|${s.data}")
            .setMediaItem(MediaItem.Builder().setMediaId(s.data ?: deviceId).setMediaMetadata(metadata).build())
            .setMediaMetadata(metadata)
            .setDurationUs(durationUs)
            .setIsSeekable(s.duration > 0)
            .build()
        val playing = !s.paused && !busy && s.status != "error"
        return State.Builder()
            .setAvailableCommands(commands)
            .setPlaylist(listOf(item))
            .setCurrentMediaItemIndex(0)
            .setPlaybackState(if (busy) Player.STATE_BUFFERING else Player.STATE_READY)
            .setPlayWhenReady(!s.paused, Player.PLAY_WHEN_READY_CHANGE_REASON_REMOTE)
            .setContentPositionMs(PositionSupplier.getExtrapolating((s.position * 1000).toLong(), if (playing) 1f else 0f))
            .setSeekBackIncrementMs(10_000)
            .setSeekForwardIncrementMs(10_000)
            .setDeviceInfo(DeviceInfo.Builder(DeviceInfo.PLAYBACK_TYPE_REMOTE).setMinVolume(0).setMaxVolume(maxVolume).build())
            .setDeviceVolume(volume())
            .build()
    }

    private fun send(cmd: String, body: PlayerCommand = PlayerCommand()): ListenableFuture<*> {
        app.scope.launch { runCatching { app.api.castCommand(deviceId, cmd, body) } }
        return Futures.immediateVoidFuture()
    }

    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
        // Shown at once; the device's next report confirms it.
        state = state.copy(paused = !playWhenReady)
        return send(if (playWhenReady) "resume" else "pause")
    }

    override fun handleStop() = send("stop")

    override fun handleSeek(mediaItemIndex: Int, positionMs: Long, seekCommand: Int): ListenableFuture<*> = when (seekCommand) {
        Player.COMMAND_SEEK_TO_NEXT, Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM -> send("next")
        Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM -> send("prev")
        Player.COMMAND_SEEK_TO_PREVIOUS -> if (state.hasPrev && state.position <= 3) send("prev") else send("seek", PlayerCommand(seconds = 0.0))
        Player.COMMAND_SEEK_BACK -> send("seek", PlayerCommand(seconds = -10.0, relative = true))
        Player.COMMAND_SEEK_FORWARD -> send("seek", PlayerCommand(seconds = 10.0, relative = true))
        else -> send("seek", PlayerCommand(seconds = positionMs.coerceAtLeast(0) / 1000.0))
    }

    override fun handleSetDeviceVolume(deviceVolume: Int, flags: Int) = setVolume(deviceVolume)

    override fun handleIncreaseDeviceVolume(flags: Int) = setVolume(volume() + STEP)

    override fun handleDecreaseDeviceVolume(flags: Int) = setVolume(volume() - STEP)

    override fun handleSetDeviceMuted(muted: Boolean, flags: Int) = setVolume(if (muted) 0 else volume())

    private fun setVolume(v: Int): ListenableFuture<*> {
        val to = v.coerceIn(0, maxVolume)
        pendingVolume = to
        pendingAt = System.currentTimeMillis()
        return send("volume", PlayerCommand(value = to.toDouble()))
    }

    override fun handleRelease(): ListenableFuture<*> = Futures.immediateVoidFuture()

    private companion object {
        const val STEP = 5
    }
}
