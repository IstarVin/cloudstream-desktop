package desktop.cdp

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.future.await
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.WebSocket
import java.util.concurrent.CompletionStage
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

class CdpException(message: String) : IOException(message)

/** A CDP event: [sessionId] is null for browser-level events. */
class CdpEvent(val method: String, val params: JsonObject, val sessionId: String?)

/**
 * One WebSocket to Chromium's browser endpoint, in flat session mode: page commands carry the `sessionId` from
 * `Target.attachToTarget`. Listeners run on the socket's thread, so they must only hand events off.
 */
class CdpConnection private constructor() : AutoCloseable {
    private val ids = AtomicInteger()
    private val pending = ConcurrentHashMap<Int, CompletableDeferred<JsonObject>>()
    private val listeners = CopyOnWriteArrayList<(CdpEvent) -> Unit>()
    private val sendLock = Mutex()
    private lateinit var socket: WebSocket

    @Volatile var closed = false
        private set

    fun onEvent(listener: (CdpEvent) -> Unit) {
        listeners.add(listener)
    }

    suspend fun send(method: String, params: JsonObject = EMPTY, sessionId: String? = null, timeoutMs: Long = 30_000): JsonObject {
        if (closed) throw CdpException("Chromium connection closed")
        val id = ids.incrementAndGet()
        val reply = CompletableDeferred<JsonObject>()
        pending[id] = reply
        val message = buildJsonObject {
            put("id", id)
            put("method", method)
            put("params", params)
            sessionId?.let { put("sessionId", it) }
        }
        try {
            // java.net.http.WebSocket allows one outstanding send at a time.
            sendLock.withLock { socket.sendText(message.toString(), true).await() }
            return withTimeout(timeoutMs) { reply.await() }
        } finally {
            pending.remove(id)
        }
    }

    private fun dispatch(text: String) {
        val msg = runCatching { Json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return
        val id = msg["id"]?.jsonPrimitive?.int
        if (id != null) {
            val reply = pending[id] ?: return
            val error = msg["error"]?.jsonObject
            if (error != null) reply.completeExceptionally(CdpException(error["message"]?.jsonPrimitive?.content ?: error.toString()))
            else reply.complete(msg["result"]?.jsonObject ?: EMPTY)
            return
        }
        val method = msg["method"]?.jsonPrimitive?.content ?: return
        val event = CdpEvent(method, msg["params"]?.jsonObject ?: EMPTY, msg["sessionId"]?.jsonPrimitive?.content)
        listeners.forEach { runCatching { it(event) } }
    }

    private fun closedNow(reason: String) {
        closed = true
        pending.values.forEach { it.completeExceptionally(CdpException(reason)) }
        listeners.forEach { runCatching { it(CdpEvent(DISCONNECTED, EMPTY, null)) } }
    }

    override fun close() {
        if (closed) return
        closed = true
        runCatching { socket.sendClose(WebSocket.NORMAL_CLOSURE, "").get() }
        socket.abort()
        closedNow("Chromium connection closed")
    }

    private inner class Listener : WebSocket.Listener {
        private val buffer = StringBuilder()

        override fun onText(webSocket: WebSocket, data: CharSequence, last: Boolean): CompletionStage<*>? {
            buffer.append(data)
            if (last) {
                val text = buffer.toString()
                buffer.setLength(0)
                dispatch(text)
            }
            webSocket.request(1)
            return null
        }

        override fun onClose(webSocket: WebSocket, statusCode: Int, reason: String): CompletionStage<*>? {
            closedNow("Chromium closed the connection")
            return null
        }

        override fun onError(webSocket: WebSocket, error: Throwable) {
            closedNow("Chromium connection failed: ${error.message}")
        }
    }

    companion object {
        val EMPTY = JsonObject(emptyMap())

        /** Pseudo-event sent to listeners when the socket closes. */
        const val DISCONNECTED = "desktop.disconnected"

        suspend fun connect(url: String): CdpConnection {
            val conn = CdpConnection()
            conn.socket = HttpClient.newHttpClient().newWebSocketBuilder()
                .buildAsync(URI(url), conn.Listener()).await()
            return conn
        }
    }
}
