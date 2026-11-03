package desktop.api

import desktop.player.PlayerState
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// Casting: every client keeps one WebSocket to /api/cast. A controller asks the server to play on a device, the server
// passes that to the device's socket, and the device plays it with its own player and reports its state back.

@Serializable
enum class DeviceKind {
    @SerialName("web") WEB,
    @SerialName("phone") PHONE,
    @SerialName("tv") TV,
    /** The server's own mpv. */
    @SerialName("mpv") MPV,
}

@Serializable
data class DeviceInfo(
    /** Stable across reconnects; chosen by the client. */
    val id: String,
    val name: String,
    val kind: DeviceKind,
    /** Whether it accepts casts. A client can connect only to control others. */
    val receiver: Boolean,
    /** The device that has picked this one as its player, or null when it's free. Only that device may pick it. */
    val controller: String? = null,
)

/** A message on the /api/cast socket. The JSON carries the subtype as "type". */
@Serializable
sealed class CastFrame {
    /** client → server, first: who this is. Sent again to rename or to change [receiver]. */
    @Serializable
    @SerialName("hello")
    data class Hello(val id: String, val name: String, val kind: DeviceKind, val receiver: Boolean = true) : CastFrame()

    /** client → server: "I'm playing on [device]" (null: on none). Held until released, replaced, or either device drops. */
    @Serializable
    @SerialName("claim")
    data class Claim(val device: String? = null) : CastFrame()

    /** Both ways: a receiver's player state; from the server, [device] says whose. */
    @Serializable
    @SerialName("state")
    data class State(val state: PlayerState, val device: String? = null) : CastFrame()

    /** server → client: the connected devices, sent on connect and whenever the list changes. */
    @Serializable
    @SerialName("devices")
    data class Devices(val devices: List<DeviceInfo>) : CastFrame()

    /** server → receiver: start playing this. [from] is the controller's device id, when it has one. */
    @Serializable
    @SerialName("play")
    data class Play(val request: PlayRequest, val from: String? = null) : CastFrame()

    /** server → receiver: a player command, with the same names and body as POST /api/player/{cmd}. */
    @Serializable
    @SerialName("command")
    data class Command(val cmd: String, val body: PlayerCommand = PlayerCommand()) : CastFrame()
}
