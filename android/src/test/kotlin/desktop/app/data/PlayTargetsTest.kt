package desktop.app.data

import desktop.api.CastFrame
import desktop.api.DeviceInfo
import desktop.api.DeviceKind
import desktop.player.PlayerState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayTargetsTest {
    private val self = "me"
    private val mpv = DeviceInfo("mpv", "Desktop mpv", DeviceKind.MPV, receiver = true)
    private val tv = DeviceInfo("tv", "Living room", DeviceKind.TV, receiver = true)
    private val remoteOnly = DeviceInfo("web", "Firefox on Linux", DeviceKind.WEB, receiver = false)
    private val me = DeviceInfo(self, "Pixel", DeviceKind.PHONE, receiver = true)

    @Test fun playsHereUntilAnotherDeviceIsPicked() {
        assertEquals(PlayTargets.HERE, PlayTargets.effective(null, listOf(mpv, tv, me), self))
        assertEquals("tv", PlayTargets.effective("tv", listOf(mpv, tv, me), self))
    }

    @Test fun fallsBackHereWhenThePickIsAwayOrRefusesCasts() {
        assertEquals(PlayTargets.HERE, PlayTargets.effective("tv", listOf(mpv, me), self))
        assertEquals(PlayTargets.HERE, PlayTargets.effective("web", listOf(remoteOnly, me), self))
        assertEquals(PlayTargets.HERE, PlayTargets.effective(self, listOf(me), self))
    }

    @Test fun aDeviceAnotherControllerHoldsIsBusy() {
        val taken = tv.copy(controller = "someone-else")
        assertEquals(PlayTargets.HERE, PlayTargets.effective("tv", listOf(taken, me), self))
        assertTrue(PlayTargets.options(listOf(mpv, taken, me), self, "This device").single { it.id == "tv" }.busy)
        // Held by this device: still pickable.
        val mine = tv.copy(controller = self)
        assertEquals("tv", PlayTargets.effective("tv", listOf(mine, me), self))
        assertFalse(PlayTargets.takenByOther(listOf(mine), self, "tv"))
    }

    @Test fun optionsListHereThenReceiversButNeverThisDevice() {
        val ids = PlayTargets.options(listOf(mpv, tv, remoteOnly, me), self, "This device").map { it.id }
        assertEquals(listOf(PlayTargets.HERE, "mpv", "tv"), ids)
    }

    @Test fun theControlledDeviceIsTheOneThisDevicePicked() {
        assertNull(PlayTargets.controlled(listOf(mpv, tv, me), self))
        assertEquals("tv", PlayTargets.controlled(listOf(mpv, tv.copy(controller = self), me), self)?.id)
    }

    @Test fun anIdlePlayerWithNothingLoadedIsNotShown() {
        assertFalse(PlayTargets.active(null))
        assertFalse(PlayTargets.active(PlayerState()))
        assertTrue(PlayTargets.active(PlayerState(status = "paused", title = "Dune")))
        assertTrue(PlayTargets.active(PlayerState(status = "resolving")))
    }

    @Test fun framesUseTheServersTypeTags() {
        val hello = json.encodeToString<CastFrame>(CastFrame.Hello(self, "Pixel", DeviceKind.PHONE, receiver = true))
        assertTrue(hello, "\"type\":\"hello\"" in hello && "\"kind\":\"phone\"" in hello)
        assertEquals("""{"type":"claim"}""", json.encodeToString<CastFrame>(CastFrame.Claim(null)))
        val play = json.decodeFromString<CastFrame>(
            """{"type":"play","request":{"provider":"P","url":"u","data":"d","linkId":"l1","startAt":12.5},"from":"web"}""",
        ) as CastFrame.Play
        assertEquals("l1", play.request.linkId)
        assertEquals(12.5, play.request.startAt!!, 0.0)
        val cmd = json.decodeFromString<CastFrame>("""{"type":"command","cmd":"seek","body":{"seconds":-10,"relative":true}}""") as CastFrame.Command
        assertTrue(cmd.body.relative)
    }
}
