package desktop

import desktop.auth.Auth
import desktop.auth.AuthFailure
import desktop.auth.WorkerClient
import desktop.auth.authRoutes
import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsText
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.*
import org.junit.jupiter.api.Assumptions.assumeTrue

class AuthTest {
    private class Worker : WorkerClient("https://auth.example", "a".repeat(32), "server-a", "Home server") {
        var epoch = 1L
        var offline = false
        var requests = 0
        var consumed = false
        override suspend fun request(action: String, values: Map<String, String>): JsonObject {
            requests++
            if (offline) throw AuthFailure(503, "Worker unavailable")
            return when (action) {
                "epoch", "login" -> buildJsonObject { put("epoch", epoch) }
                "device" -> buildJsonObject {
                    put("userCode", "ABC234"); put("deviceCode", "a".repeat(64)); put("verificationUri", "https://auth.example/activate"); put("expiresIn", 300); put("interval", 5)
                }
                "poll" -> {
                    if (values["deviceCode"] != "a".repeat(64) || consumed) throw AuthFailure(410, "Expired")
                    consumed = true
                    buildJsonObject { put("status", "approved"); put("epoch", epoch); put("deviceName", "TV") }
                }
                else -> error("Unexpected action $action")
            }
        }
    }
    private val file = File(createTempDirectory().toFile(), "sessions.json")
    private val worker = Worker()
    private var now = 1_000_000L
    private fun auth() = Auth(file, worker, publicOrigin = null, clock = { now })

    @Test fun sessionsPersistAsHashesAndRevokeIndependently() = runBlocking<Unit> {
        val auth = auth()
        val first = auth.issue("TV", 1)
        val second = auth.issue("Phone", 1)
        assertFalse(file.readText().contains(first.token))
        assertNotEquals(first.token, second.token)
        assertTrue(auth().isAuthorized(first.token))
        assertTrue(auth.isAuthorized(second.token))
        auth.revoke(auth.list(first.token).first { it.current }.id)
        assertFalse(auth.isAuthorized(first.token))
        assertTrue(auth.isAuthorized(second.token))
        auth.revokeAll()
        assertFalse(auth().isAuthorized(second.token))
    }
    @Test fun sessionsExpireAndLegacyTokensAreRejected() = runBlocking<Unit> {
        val auth = auth()
        assertFalse(auth.isAuthorized(null))
        assertFalse(auth.isAuthorized("a".repeat(48)))
        val token = auth.issue("Browser", 1).token
        now += Auth.SESSION_SECONDS * 1000L
        assertFalse(auth.isAuthorized(token))
        assertEquals(0, worker.requests, "invalid and expired sessions do not contact the Worker")
    }
    @Test fun epochChecksAreCachedForOneMinuteAndOutagesAreToleratedOnlyBriefly() = runBlocking<Unit> {
        val auth = auth()
        val token = auth.issue("TV", 1).token
        assertTrue(auth.isAuthorized(token))
        worker.epoch = 2
        assertTrue(auth.isAuthorized(token))
        assertEquals(1, worker.requests)
        now += 60_000
        assertFalse(auth.isAuthorized(token))
        val current = auth.issue("Phone", 2).token
        worker.offline = true
        assertTrue(auth.isAuthorized(current))
        now += 60_000
        assertTrue(auth.isAuthorized(current), "a short Worker outage keeps the last verified epoch")
        val before = worker.requests
        assertTrue(auth.isAuthorized(current))
        assertEquals(before, worker.requests, "retries are spaced a minute apart during an outage")
        now += Auth.OUTAGE_GRACE_SECONDS * 1000L - 60_000
        val long = auth.issue("TV 2", 2).token
        assertFailsWith<AuthFailure> { auth.isAuthorized(long) }
    }
    @Test fun outageBeforeAnyVerifiedEpochFailsClosed() = runBlocking<Unit> {
        val auth = auth()
        val token = auth.issue("TV", 1).token
        worker.offline = true
        assertFailsWith<AuthFailure> { auth.isAuthorized(token) }
    }
    @Test fun loopbackDetectionCoversTheWholeLoopbackRange() {
        for (a in listOf("127.0.0.1", "127.0.0.2", "::1", "0:0:0:0:0:0:0:1", "::ffff:127.0.0.1")) assertTrue(Auth.isLoopback(a), a)
        for (a in listOf("192.168.1.5", "10.0.0.1", "not-an-ip")) assertFalse(Auth.isLoopback(a), a)
    }
    @Test fun newLoginUpdatesCachedEpochWithoutAcceptingAnOlderEpoch() = runBlocking<Unit> {
        val auth = auth()
        auth.acceptEpoch(2)
        auth.acceptEpoch(1)
        assertTrue(auth.isAuthorized(auth.issue("Phone", 2).token))
        assertFalse(auth.isAuthorized(auth.issue("TV", 1).token))
    }
    @Test fun apiProxyAndSocketRequireLogin() {
        for (path in listOf("/api/providers", "/api/cast", "/proxy/t/x", "/api/auth/sessions", "/api/auth/logout", "/api/auth/pairing", "/api/auth/rotate")) assertTrue(Auth.needsToken(path))
        for (path in listOf("/", "/settings", "/api/auth/status", "/api/auth/login", "/api/auth/device", "/api/auth/device/poll")) assertFalse(Auth.needsToken(path))
    }
    @Test fun loopbackAlsoRequiresLoginAndCrossOriginRequestsAreRejected() = testApplication {
        val auth = auth()
        application {
            install(ContentNegotiation) { json() }
            install(Auth.plugin(auth))
            routing {
                authRoutes(auth)
                get("/api/providers") { call.respondText("[]") }
                get("/proxy/t/x") { call.respondText("stream") }
                get("/api/cast") { call.respondText("socket") }
                get("/api/subtitle") { call.respondText("sub") }
            }
        }
        // mpv's own loopback requests carry a per-process secret, and only for the paths mpv actually fetches.
        assertEquals(HttpStatusCode.OK, client.get("/proxy/t/x") { header(Auth.INTERNAL_HEADER, Auth.INTERNAL_TOKEN) }.status)
        assertEquals(HttpStatusCode.OK, client.get("/api/subtitle?id=1&internal=${Auth.INTERNAL_TOKEN}").status)
        assertEquals(HttpStatusCode.Unauthorized, client.get("/proxy/t/x") { header(Auth.INTERNAL_HEADER, "wrong") }.status)
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/providers") { header(Auth.INTERNAL_HEADER, Auth.INTERNAL_TOKEN) }.status)
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/providers?internal=${Auth.INTERNAL_TOKEN}").status)
        for (path in listOf("/api/providers", "/proxy/t/x", "/api/cast")) assertEquals(HttpStatusCode.Unauthorized, client.get(path).status)
        assertContains(client.get("/api/auth/status").bodyAsText(), "\"authed\":false")
        val token = auth.issue("Browser", 1).token
        assertEquals(HttpStatusCode.OK, client.get("/api/providers") { bearerAuth(token) }.status)
        assertEquals(HttpStatusCode.OK, client.get("/proxy/t/x") { header(HttpHeaders.Cookie, "${Auth.COOKIE}=$token") }.status)
        assertEquals(HttpStatusCode.Forbidden, client.get("/api/providers") { bearerAuth(token); header(HttpHeaders.Origin, "https://evil.example") }.status)
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/providers") { header(HttpHeaders.Cookie, "cs_token=$token") }.status)
    }
    @Test fun browserGetsOnlyACookieAndAndroidGetsItsOwnBearer() = testApplication {
        val auth = auth()
        application {
            install(ContentNegotiation) { json() }
            install(Auth.plugin(auth))
            install(StatusPages) { exception<AuthFailure> { call, e -> call.respond(HttpStatusCode.fromValue(e.status), mapOf("error" to e.message)) } }
            routing { authRoutes(auth) }
        }
        val credentials = """{"username":"owner","password":"long-password","deviceName":"Browser"}"""
        val web = client.post("/api/auth/login") { contentType(ContentType.Application.Json); setBody(credentials) }
        assertEquals(HttpStatusCode.OK, web.status)
        assertFalse(web.bodyAsText().contains("token"))
        assertContains(web.headers[HttpHeaders.SetCookie].orEmpty(), "HttpOnly")
        assertContains(web.headers[HttpHeaders.SetCookie].orEmpty(), "SameSite=Strict")
        val native = client.post("/api/auth/login?client=android") { contentType(ContentType.Application.Json); setBody(credentials) }
        val nativeToken = Json.parseToJsonElement(native.bodyAsText()).jsonObject["token"]!!.jsonPrimitive.content
        assertTrue(auth.isAuthorized(nativeToken))
        assertEquals(HttpStatusCode.OK, client.post("/api/auth/logout") { bearerAuth(nativeToken) }.status)
        assertFalse(auth.isAuthorized(nativeToken))
    }
    @Test fun deviceExchangeCannotBeReplayed() = testApplication {
        val auth = auth()
        application {
            install(ContentNegotiation) { json() }; install(Auth.plugin(auth))
            install(StatusPages) { exception<AuthFailure> { call, e -> call.respond(HttpStatusCode.fromValue(e.status), mapOf("error" to e.message)) } }
            routing { authRoutes(auth) }
        }
        val response = client.post("/api/auth/device") { contentType(ContentType.Application.Json); setBody("""{"deviceName":"TV"}""") }
        assertContains(response.bodyAsText(), "ABC234")
        suspend fun poll() = client.post("/api/auth/device/poll?client=android") { contentType(ContentType.Application.Json); setBody("""{"deviceCode":"${"a".repeat(64)}"}""") }
        assertEquals(HttpStatusCode.OK, poll().status)
        assertEquals(HttpStatusCode.Gone, poll().status)
        assertEquals(1, auth.list(null).size)
    }
    @Test fun serviceSecretRequiresHttpsAndAWorkerOrigin() {
        assertFailsWith<IllegalArgumentException> { WorkerClient("http://auth.example", "a".repeat(32), "a", "a") }
        assertFailsWith<IllegalArgumentException> { WorkerClient("https://auth.example/untrusted", "a".repeat(32), "a", "a") }
    }
    @Test fun realWorkerHttpClientHandlesSimulationResponses() = runBlocking<Unit> {
        val url = System.getenv("AUTH_TEST_WORKER_URL")
        assumeTrue(url != null, "Run the local Postman simulation and set AUTH_TEST_WORKER_URL to exercise HTTP failures")
        val gateway = WorkerClient(url!!, "test-service-secret-at-least-32-characters", "server-a", "Home server", allowLocalHttp = true)
        assertEquals(1, gateway.request("login", mapOf("username" to "owner", "password" to "test-password-123"))["epoch"]!!.jsonPrimitive.int)
        assertEquals(401, assertFailsWith<AuthFailure> { gateway.request("login", mapOf("username" to "owner", "password" to "wrong")) }.status)
        for (username in listOf("unavailable", "html-error")) assertEquals(503, assertFailsWith<AuthFailure> { gateway.request("login", mapOf("username" to username)) }.status)
        val offline = WorkerClient("http://127.0.0.1:1", "a".repeat(32), "a", "a", allowLocalHttp = true)
        assertEquals(503, assertFailsWith<AuthFailure> { offline.request("epoch") }.status)
    }
}
