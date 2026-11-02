package desktop.cast

import desktop.api.CastFrame
import desktop.api.DeviceInfo
import desktop.api.DeviceKind
import desktop.api.NotFound
import desktop.api.PlayRequest
import desktop.api.PlayerCommand
import desktop.player.PlayerState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap

/**
 * The devices connected for casting, and the go-between for them: a controller's play and commands go to the target's
 * socket, and each receiver's state goes to every socket. The server's mpv is one more device, [MPV_ID], driven directly.
 */
class DeviceHub(private val scope: CoroutineScope, private val mpv: LocalPlayer?) {
    /** A player in the server process (mpv), so it needs no socket. */
    interface LocalPlayer {
        val name: String
        val available: Boolean
        val state: StateFlow<PlayerState>
        suspend fun play(req: PlayRequest)
        suspend fun command(cmd: String, body: PlayerCommand)
    }

    /** One device's socket. What the server sends it comes out of [outgoing]. */
    class Session internal constructor(@Volatile var info: DeviceInfo) {
        // Dropping the oldest keeps a stalled socket from holding the server up; states are resent often anyway.
        private val channel = Channel<CastFrame>(256, BufferOverflow.DROP_OLDEST)
        val outgoing: ReceiveChannel<CastFrame> get() = channel
        internal fun send(frame: CastFrame) { channel.trySend(frame) }
        internal fun close() { channel.close() }
    }

    private val log = LoggerFactory.getLogger(DeviceHub::class.java)
    private val sessions = ConcurrentHashMap<String, Session>()
    private val states = ConcurrentHashMap<String, PlayerState>()
    private val _devices = MutableStateFlow<List<DeviceInfo>>(emptyList())
    val devices: StateFlow<List<DeviceInfo>> = _devices

    init {
        refresh()
        if (mpv != null) scope.launch {
            // Throttled like /api/player/events: the StateFlow keeps only the latest value while this waits.
            mpv.state.collect { st ->
                states[MPV_ID] = st
                broadcast(CastFrame.State(st, MPV_ID))
                delay(250)
            }
        }
    }

    /** Registers a device that said hello. A device reconnecting under the same id replaces its old socket. */
    fun connect(hello: CastFrame.Hello): Session {
        require(hello.id.isNotBlank() && hello.id != MPV_ID) { "Invalid device id" }
        val session = Session(hello.info())
        sessions.put(hello.id, session)?.close()
        log.info("Cast device connected: {} ({})", hello.name, hello.kind)
        session.send(CastFrame.Devices(listed()))
        states.forEach { (id, st) -> if (id != hello.id) session.send(CastFrame.State(st, id)) }
        refresh()
        return session
    }

    /** A frame from [session]'s device. */
    fun receive(session: Session, frame: CastFrame) {
        val id = session.info.id
        if (sessions[id] !== session) return // replaced by a newer socket
        when (frame) {
            is CastFrame.Hello -> if (frame.id == id) {
                session.info = frame.info()
                refresh()
            }
            is CastFrame.State -> {
                states[id] = frame.state
                broadcast(CastFrame.State(frame.state, id), except = id)
            }
            else -> log.debug("Ignoring {} from {}", frame::class.simpleName, id)
        }
    }

    fun disconnect(session: Session) {
        val id = session.info.id
        if (!sessions.remove(id, session)) return
        session.close()
        states.remove(id)
        log.info("Cast device disconnected: {}", session.info.name)
        refresh()
    }

    fun state(device: String): PlayerState {
        if (device == MPV_ID) return mpvOrThrow().state.value
        target(device)
        return states[device] ?: PlayerState()
    }

    suspend fun play(device: String, req: PlayRequest, from: String? = null) {
        if (device == MPV_ID) return mpvOrThrow().play(req)
        target(device).send(CastFrame.Play(req, from))
    }

    suspend fun command(device: String, cmd: String, body: PlayerCommand) {
        if (device == MPV_ID) return mpvOrThrow().command(cmd, body)
        target(device).send(CastFrame.Command(cmd, body))
    }

    private fun target(device: String): Session {
        val s = sessions[device] ?: throw NotFound("That device isn't connected")
        require(s.info.receiver) { "${s.info.name} doesn't accept casts" }
        return s
    }

    private fun mpvOrThrow(): LocalPlayer = mpv?.takeIf { it.available } ?: throw NotFound("mpv isn't available on the server")

    private fun listed(): List<DeviceInfo> {
        val local = mpv?.takeIf { it.available }?.let { DeviceInfo(MPV_ID, it.name, DeviceKind.MPV, receiver = true) }
        return listOfNotNull(local) + sessions.values.map { it.info }.sortedBy { it.name.lowercase() }
    }

    private fun refresh() {
        val list = listed()
        if (list == _devices.value) return
        _devices.value = list
        broadcast(CastFrame.Devices(list))
    }

    private fun broadcast(frame: CastFrame, except: String? = null) {
        sessions.forEach { (id, s) -> if (id != except) s.send(frame) }
    }

    private fun CastFrame.Hello.info() = DeviceInfo(id, name.trim().ifEmpty { "Unnamed device" }.take(60), kind, receiver)

    companion object {
        const val MPV_ID = "mpv"
    }
}
