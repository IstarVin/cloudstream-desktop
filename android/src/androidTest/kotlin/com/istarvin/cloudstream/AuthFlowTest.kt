package com.istarvin.cloudstream

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.istarvin.cloudstream.data.ServerConfig
import com.istarvin.cloudstream.data.SessionVault
import com.istarvin.cloudstream.ui.AppTheme
import com.istarvin.cloudstream.ui.LocalTv
import com.istarvin.cloudstream.ui.PairScreen
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class AuthFlowTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun keystoreEncryptsEachSessionWithAFreshNonce() {
        val token = "a".repeat(64)
        val first = SessionVault.seal(token)
        val second = SessionVault.seal(token)
        assertNotEquals(first, second)
        assertFalse(first.contains(token))
        assertEquals(token, SessionVault.open(first))
        assertEquals(token, SessionVault.open(second))
    }

    @Test fun phoneShowsCodeAndReceivesOnlyItsOwnSession() {
        val app = compose.activity.app
        val previous = app.api.server
        app.api.server = null
        try {
            MockWebServer().use { server ->
                server.start()
                fun response(body: String) = MockResponse.Builder().setHeader("Content-Type", "application/json").body(body).build()
                server.enqueue(response("""{"authed":false,"loopback":true,"configured":true,"workerUrl":"https://auth.example","serverName":"Home server"}"""))
                server.enqueue(response("""{"userCode":"ABC234","deviceCode":"private-device-secret","verificationUri":"https://auth.example/activate","expiresIn":300,"interval":2}"""))
                server.enqueue(response("""{"status":"pending"}"""))
                server.enqueue(response("""{"status":"approved","token":"${"b".repeat(64)}","expiresAt":9999999999999}"""))
                var paired: ServerConfig? = null
                compose.setContent { AppTheme {
                    CompositionLocalProvider(LocalTv provides false) {
                        Surface(Modifier.fillMaxSize()) { PairScreen(null) { paired = it } }
                    }
                } }
                compose.onNodeWithText("Server address").performTextInput(server.url("/").toString())
                compose.onNodeWithText("Continue").performClick()
                compose.waitUntil(10_000) { compose.onAllNodesWithText("ABC 234").fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithText("private-device-secret").assertDoesNotExist()
                captureTvScreen("phone-sign-in-code")
                compose.waitUntil(10_000) { paired != null }
                assertEquals("b".repeat(64), paired!!.token)
                assertEquals(server.url("/").toString().trimEnd('/'), paired!!.baseUrl)
            }
        } finally { app.api.server = previous }
    }
}
