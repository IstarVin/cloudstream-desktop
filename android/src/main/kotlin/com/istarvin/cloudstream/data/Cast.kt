package com.istarvin.cloudstream.data

import desktop.api.CastFrame
import desktop.api.DeviceInfo
import desktop.api.DeviceKind
import desktop.api.PlayRequest
import desktop.api.PlayerCommand
import desktop.player.PlayerState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

/** The player screen while it's open: plays what other devices cast here, obeys their commands, and reports back. */
interface CastReceiver {
    fun castState(): PlayerState
    fun castPlay(req: PlayRequest)
    fun castCommand(cmd: String, body: PlayerCommand)
}

/** Where this device plays: here, or another device on the casting socket ("mpv" is the desktop's). Pure, for tests. */
object PlayTargets {
    const val HERE = "here"

    data class Option(val id: String, val label: String, val kind: DeviceKind?, val busy: Boolean)

    /** Whether a device other than [self] has picked [id] as its player. */
    fun takenByOther(devices: List<DeviceInfo>, self: String, id: String): Boolean {
        val c = devices.firstOrNull { it.id == id }?.controller
        return c != null && c != self
    }

    /** The player the next play uses: here when the picked device is away, refuses casts, or another device has it. */
    fun effective(saved: String?, devices: List<DeviceInfo>, self: String): String {
        val t = saved ?: HERE
        if (t == HERE) return HERE
        val d = devices.firstOrNull { it.id == t } ?: return HERE
        return if (d.id == self || !d.receiver || takenByOther(devices, self, t)) HERE else t
    }

    /** Here first, then the devices that take casts (the server lists mpv first). A busy one can't be picked. */
    fun options(devices: List<DeviceInfo>, self: String, hereLabel: String): List<Option> =
        listOf(Option(HERE, hereLabel, null, busy = false)) +
            devices.filter { it.id != self && it.receiver }.map { Option(it.id, it.name, it.kind, takenByOther(devices, self, it.id)) }

    /** The device whose player this one controls: the one the server lists as picked by [self]. */
    fun controlled(devices: List<DeviceInfo>, self: String): DeviceInfo? = devices.firstOrNull { it.id != self && it.controller == self }

    /** A player state worth showing in the now-playing bar. */
    fun active(state: PlayerState?): Boolean = state != null && (state.status != "idle" || state.title != null)
}

/**
 * This device on the desktop's casting socket (`/api/cast`), as the web UI does it: it lists the other devices and their
 * players, takes casts to play here (when allowed), reports this player's state, and keeps the server's claim in step
 * with the device picked to play on. Connected only while the app is on screen, since Android won't open the player
 * from the background.
 */
class Cast(
    private val api: Api,
    private val prefs: Prefs,
    private val scope: CoroutineScope,
    val kind: DeviceKind,
    initial: Prefs.CastPrefs,
    private val defaultName: String,
) {
    val id: String = initial.id

    private val _name = MutableStateFlow(initial.name ?: defaultName)
    val name: StateFlow<String> = _name
    private val _receiver = MutableStateFlow(initial.receiver)
    /** Whether other devices may play here. */
    val receiver: StateFlow<Boolean> = _receiver
    private val _connected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = _connected
    private val _devices = MutableStateFlow<List<DeviceInfo>>(emptyList())
    val devices: StateFlow<List<DeviceInfo>> = _devices
    private val _states = MutableStateFlow<Map<String, PlayerState>>(emptyMap())
    /** The latest state of every other device's player, by device id. */
    val states: StateFlow<Map<String, PlayerState>> = _states
    private val _saved = MutableStateFlow(initial.playTarget)
    /** The picked player; null until one is picked, which means here. */
    val saved: StateFlow<String?> = _saved

    /** The open player screen, if any. */
    var receiverPlayer: CastReceiver? = null
    /** Opens the player screen for a cast that arrived while none is open. */
    var onPlay: ((PlayRequest) -> Unit)? = null

    private var socket: WebSocket? = null
    private var running = false
    private var retry: Job? = null
    private var reporter: Job? = null
    private var lastSent: String? = null

    val hereLabel: String get() = if (kind == DeviceKind.TV) "This TV" else "This device"

    /** The player the next play uses ([PlayTargets.HERE] or a device id). */
    fun effective(): String = PlayTargets.effective(_saved.value, _devices.value, id)

    fun device(id: String?): DeviceInfo? = id?.let { d -> _devices.value.firstOrNull { it.id == d } }

    fun start() {
        if (running) return
        running = true
        connect()
        reporter = scope.launch {
            while (isActive) {
                report()
                delay(500)
            }
        }
    }

    fun stop() {
        running = false
        retry?.cancel()
        reporter?.cancel()
        socket?.close(1000, null)
        socket = null
        _connected.value = false
        _devices.value = emptyList()
        _states.value = emptyMap()
    }

    /** After pairing with a (different) desktop. */
    fun reconnect() {
        stop()
        start()
    }

    fun rename(name: String) {
        val trimmed = name.trim().take(60)
        _name.value = trimmed.ifEmpty { defaultName }
        scope.launch { prefs.setCastName(trimmed.ifEmpty { null }) }
        send(hello())
    }

    fun setReceiver(on: Boolean) {
        _receiver.value = on
        scope.launch { prefs.setCastReceiver(on) }
        send(hello())
        lastSent = null
    }

    /** Picks where to play; claims that device on the server so no other device can pick it too. */
    fun select(target: String) {
        if (target != PlayTargets.HERE && PlayTargets.takenByOther(_devices.value, id, target)) return
        _saved.value = target
        send(CastFrame.Claim(target.takeUnless { it == PlayTargets.HERE }))
        scope.launch { prefs.setPlayTarget(target) }
    }

    private fun hello() = CastFrame.Hello(id, _name.value, kind, _receiver.value)

    private fun send(frame: CastFrame) {
        socket?.send(json.encodeToString<CastFrame>(frame))
    }

    private fun connect() {
        if (!running || api.server == null) return
        val ws = runCatching { api.castSocket(Listener()) }.getOrElse { dropped(); return }
        socket = ws
        // Queued until the socket opens; the server wants hello first.
        send(hello())
    }

    /** The socket closed; tries again shortly, unless the desktop refused the token (the app asks to pair again). */
    private fun dropped(again: Boolean = true) {
        socket = null
        _connected.value = false
        retry?.cancel()
        if (running && again) retry = scope.launch {
            delay(1500)
            if (running && socket == null) connect()
        }
    }

    private inner class Listener : WebSocketListener() {
        /** Socket callbacks run on OkHttp's threads; state changes on the main one, for the socket still current. */
        private fun main(ws: WebSocket, block: () -> Unit) {
            scope.launch { if (ws === socket) block() }
        }

        override fun onOpen(webSocket: WebSocket, response: Response) = main(webSocket) {
            _connected.value = true
            // The server forgets a device's state when it drops; send it again.
            lastSent = null
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            val frame = runCatching { json.decodeFromString<CastFrame>(text) }.getOrNull() ?: return
            main(webSocket) { onFrame(frame) }
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(1000, null)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = main(webSocket) { dropped() }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = main(webSocket) { dropped(again = response?.code != 401) }
    }

    private fun onFrame(f: CastFrame) {
        when (f) {
            is CastFrame.Devices -> {
                _devices.value = f.devices
                val ids = f.devices.mapTo(HashSet()) { it.id }
                _states.update { s -> s.filterKeys { it in ids } }
                reconcile()
            }
            is CastFrame.State -> {
                val from = f.device
                if (from != null && from != id) _states.update { it + (from to f.state) }
            }
            is CastFrame.Play -> if (_receiver.value) {
                val player = receiverPlayer
                if (player != null) player.castPlay(f.request) else onPlay?.invoke(f.request)
            }
            is CastFrame.Command -> if (_receiver.value) receiverPlayer?.castCommand(f.cmd, f.body)
            else -> {}
        }
    }

    /**
     * Keeps the pick and the server's claim in step whenever the device list changes: claims the picked device when
     * it's free, and goes back to playing here when it has gone or another device has it.
     */
    private fun reconcile() {
        val t = _saved.value
        val devices = _devices.value
        if (t == null || t == PlayTargets.HERE) {
            if (devices.any { it.controller == id }) send(CastFrame.Claim(null))
            return
        }
        val d = devices.firstOrNull { it.id == t }
        if (d == null || !d.receiver || (d.controller != null && d.controller != id)) select(PlayTargets.HERE)
        else if (d.controller == null) send(CastFrame.Claim(t))
    }

    /** Sends this player's state when it changed; position moves it on about twice a second while playing. */
    private fun report() {
        if (!_receiver.value || socket == null) return
        val state = receiverPlayer?.castState() ?: PlayerState(paused = true)
        val text = json.encodeToString<CastFrame>(CastFrame.State(state))
        if (text == lastSent) return
        lastSent = text
        socket?.send(text)
    }
}
