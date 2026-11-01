package desktop

import desktop.auth.Auth
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue

class AuthTest {
    private val file = File(createTempDirectory().toFile(), "token")
    private val auth = Auth(file)

    @Test
    fun tokenPersistsAndRotationInvalidatesTheOldOne() {
        assertEquals(auth.token, Auth(file).token, "the token survives a restart")
        val old = auth.token
        auth.rotate()
        assertNotEquals(old, auth.token)
        assertFalse(auth.matches(old))
        assertTrue(auth.matches(auth.token))
    }

    @Test
    fun loopbackNeedsNothingAndOthersNeedTheCookieOrBearer() {
        assertTrue(auth.isAuthorized("127.0.0.1", null, null))
        assertTrue(auth.isAuthorized("::1", null, null))
        assertFalse(auth.isAuthorized("192.168.1.20", null, null))
        assertFalse(auth.isAuthorized("192.168.1.20", "wrong", "Bearer wrong"))
        assertTrue(auth.isAuthorized("192.168.1.20", auth.token, null))
        assertTrue(auth.isAuthorized("192.168.1.20", null, "Bearer ${auth.token}"))
        assertFalse(auth.isAuthorized("192.168.1.20", null, auth.token), "a bare token isn't a bearer header")
    }

    @Test
    fun theApiAndProxyNeedATokenButTheUiAndPairingDont() {
        assertTrue(Auth.needsToken("/api/providers"))
        assertTrue(Auth.needsToken("/api/player/events"))
        assertTrue(Auth.needsToken("/proxy/abc/def"))
        assertFalse(Auth.needsToken("/api/auth/status"))
        assertFalse(Auth.needsToken("/pair"))
        assertFalse(Auth.needsToken("/"))
        assertFalse(Auth.needsToken("/settings"))
    }

    /** End to end over a LAN address, so the server sees a non-loopback client. */
    @Test
    fun lanRequestsAreRefusedWithoutPairing() {
        val lan = auth.lanAddresses().firstOrNull()
        assumeTrue(lan != null, "no LAN address on this machine")
        val server = embeddedServer(CIO, port = 0, host = lan!!) {
            install(ContentNegotiation) { json() }
            install(Auth.plugin(auth))
            routing {
                get("/api/providers") { call.respondText("[]") }
                get("/proxy/{token}/{rest}") { call.respondText("stream") }
                get("/") { call.respondText("ui") }
            }
        }.start(wait = false)
        try {
            val port = kotlinx.coroutines.runBlocking { server.engine.resolvedConnectors().first().port }
            fun status(path: String, cookie: String? = null): Int {
                val conn = URI("http://$lan:$port$path").toURL().openConnection() as HttpURLConnection
                cookie?.let { conn.setRequestProperty("Cookie", "${Auth.COOKIE}=$it") }
                return conn.responseCode.also { conn.disconnect() }
            }
            assertEquals(401, status("/api/providers"))
            assertEquals(401, status("/proxy/t/x"))
            assertEquals(200, status("/"))
            assertEquals(200, status("/api/providers", auth.token))
            assertEquals(200, status("/proxy/t/x", auth.token))
        } finally {
            server.stop(0, 0)
        }
    }
}
