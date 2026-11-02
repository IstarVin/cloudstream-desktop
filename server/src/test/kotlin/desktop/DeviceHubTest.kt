package desktop

import desktop.api.CastFrame
import desktop.api.DeviceKind
import desktop.api.NotFound
import desktop.api.PlayRequest
import desktop.api.PlayerCommand
import desktop.cast.DeviceHub
import desktop.player.PlayerState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DeviceHubTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @AfterTest
    fun tearDown() = scope.cancel()

    private class FakeMpv(override var available: Boolean = true) : DeviceHub.LocalPlayer {
        override val name = "mpv on test"
        override val state = MutableStateFlow(PlayerState())
        val played = mutableListOf<PlayRequest>()
        val commands = mutableListOf<String>()
        override suspend fun play(req: PlayRequest) { played += req }
        override suspend fun command(cmd: String, body: PlayerCommand) { commands += cmd }
    }

    private fun hello(id: String, receiver: Boolean = true) = CastFrame.Hello(id, "Device $id", DeviceKind.TV, receiver)

    /** The next frame of [type] the session gets, skipping others. */
    private inline fun <reified T : CastFrame> DeviceHub.Session.next(): T = runBlocking {
        withTimeout(2000) {
            while (true) {
                val f = outgoing.receive()
                if (f is T) return@withTimeout f
            }
            @Suppress("UNREACHABLE_CODE") error("unreachable")
        }
    }

    private val req = PlayRequest("Prov", "https://x/title", "ep1", linkId = "l1", startAt = 42.0)

    @Test
    fun `devices are listed to everyone, mpv first`() {
        val hub = DeviceHub(scope, FakeMpv())
        val a = hub.connect(hello("a"))
        assertEquals(listOf("mpv", "a"), a.next<CastFrame.Devices>().devices.map { it.id })
        hub.connect(hello("b"))
        // a hears about b
        val seen = generateSequence { a.next<CastFrame.Devices>() }.first { d -> d.devices.any { it.id == "b" } }
        assertEquals(listOf("mpv", "a", "b"), seen.devices.map { it.id })
    }

    @Test
    fun `mpv is hidden when unavailable`() {
        val hub = DeviceHub(scope, FakeMpv(available = false))
        val a = hub.connect(hello("a"))
        assertEquals(listOf("a"), a.next<CastFrame.Devices>().devices.map { it.id })
        assertFailsWith<NotFound> { runBlocking { hub.play(DeviceHub.MPV_ID, req) } }
    }

    @Test
    fun `play and commands reach the target`() = runBlocking {
        val hub = DeviceHub(scope, null)
        val phone = hub.connect(hello("phone"))
        val tv = hub.connect(hello("tv"))
        hub.play("tv", req, from = "phone")
        assertEquals(CastFrame.Play(req, "phone"), tv.next<CastFrame.Play>())
        hub.command("tv", "seek", PlayerCommand(seconds = 10.0, relative = true))
        assertEquals("seek", tv.next<CastFrame.Command>().cmd)
        assertTrue(phone.outgoing.tryReceive().getOrNull() !is CastFrame.Play, "the controller isn't sent the play")
    }

    @Test
    fun `mpv is driven directly`() = runBlocking {
        val mpv = FakeMpv()
        val hub = DeviceHub(scope, mpv)
        hub.play(DeviceHub.MPV_ID, req)
        hub.command(DeviceHub.MPV_ID, "toggle", PlayerCommand())
        assertEquals(listOf(req), mpv.played)
        assertEquals(listOf("toggle"), mpv.commands)
    }

    @Test
    fun `a receiver's state goes to the others and is remembered`() {
        val hub = DeviceHub(scope, null)
        val phone = hub.connect(hello("phone", receiver = false))
        val tv = hub.connect(hello("tv"))
        val st = PlayerState(status = "playing", position = 12.0, title = "Show")
        hub.receive(tv, CastFrame.State(st))
        assertEquals(CastFrame.State(st, "tv"), phone.next<CastFrame.State>())
        assertEquals(st, hub.state("tv"))
        // A device connecting later is sent the current states.
        val late = hub.connect(hello("late"))
        assertEquals(CastFrame.State(st, "tv"), late.next<CastFrame.State>())
    }

    @Test
    fun `mpv state is passed on`() {
        val mpv = FakeMpv()
        val hub = DeviceHub(scope, mpv)
        val a = hub.connect(hello("a"))
        mpv.state.value = PlayerState(status = "playing", title = "Movie")
        val st = generateSequence { a.next<CastFrame.State>() }.first { it.state.title == "Movie" }
        assertEquals(DeviceHub.MPV_ID, st.device)
    }

    @Test
    fun `controllers can't be cast to, unknown devices are not found`() {
        val hub = DeviceHub(scope, null)
        hub.connect(hello("phone", receiver = false))
        assertFailsWith<IllegalArgumentException> { runBlocking { hub.play("phone", req) } }
        assertFailsWith<NotFound> { runBlocking { hub.play("nope", req) } }
    }

    @Test
    fun `disconnect removes the device and its state`() {
        val hub = DeviceHub(scope, null)
        val phone = hub.connect(hello("phone"))
        val tv = hub.connect(hello("tv"))
        hub.receive(tv, CastFrame.State(PlayerState(status = "playing")))
        hub.disconnect(tv)
        assertEquals(listOf("phone"), hub.devices.value.map { it.id })
        assertFailsWith<NotFound> { hub.state("tv") }
        val last = generateSequence { phone.next<CastFrame.Devices>() }.first { d -> d.devices.none { it.id == "tv" } }
        assertEquals(listOf("phone"), last.devices.map { it.id })
    }

    @Test
    fun `reconnecting replaces the old socket, whose disconnect is then ignored`(): Unit = runBlocking {
        val hub = DeviceHub(scope, null)
        val old = hub.connect(hello("tv"))
        val new = hub.connect(hello("tv"))
        while (old.outgoing.tryReceive().isSuccess) Unit
        assertTrue(old.outgoing.isClosedForReceive, "the old socket is closed")
        hub.disconnect(old)
        assertEquals(listOf("tv"), hub.devices.value.map { it.id })
        hub.play("tv", req)
        assertIs<CastFrame.Play>(new.next<CastFrame.Play>())
    }

    @Test
    fun `hello again renames`() {
        val hub = DeviceHub(scope, null)
        val tv = hub.connect(hello("tv"))
        hub.receive(tv, CastFrame.Hello("tv", "Living room", DeviceKind.TV))
        assertEquals("Living room", hub.devices.value.single().name)
    }

    @Test
    fun `frames use a type field the web client can read`() {
        val json = Json { encodeDefaults = true; ignoreUnknownKeys = true; explicitNulls = false }
        val encoded = json.encodeToString<CastFrame>(CastFrame.Command("seek", PlayerCommand(seconds = 5.0)))
        assertTrue(encoded.startsWith("""{"type":"command","cmd":"seek""""), encoded)
        val hello = json.decodeFromString<CastFrame>("""{"type":"hello","id":"x","name":"Firefox","kind":"web","extra":1}""")
        assertEquals(CastFrame.Hello("x", "Firefox", DeviceKind.WEB, receiver = true), hello)
        val play = json.decodeFromString<CastFrame>(json.encodeToString<CastFrame>(CastFrame.Play(req)))
        assertNull((play as CastFrame.Play).from)
    }
}
