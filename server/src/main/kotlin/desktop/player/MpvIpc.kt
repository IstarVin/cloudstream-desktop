package desktop.player

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import org.slf4j.LoggerFactory
import java.io.IOException
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.Channels
import java.nio.channels.SocketChannel
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

class MpvError(message: String) : IOException(message)

/** mpv's JSON IPC over its unix socket: commands with request ids, and a stream of events. */
class MpvIpc private constructor(private val channel: SocketChannel) {
    private val log = LoggerFactory.getLogger(MpvIpc::class.java)
    private val pending = ConcurrentHashMap<Long, CompletableDeferred<JsonElement>>()
    private val ids = AtomicLong()
    private val _events = MutableSharedFlow<JsonObject>(extraBufferCapacity = 256, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val events: SharedFlow<JsonObject> = _events
    @Volatile var closed = false
        private set

    private val reader = Thread({ readLoop() }, "mpv-ipc-reader").apply { isDaemon = true; start() }

    private fun readLoop() {
        try {
            Channels.newReader(channel, Charsets.UTF_8).buffered().useLines { lines ->
                for (line in lines) {
                    val msg = runCatching { Json.parseToJsonElement(line) as JsonObject }.getOrNull() ?: continue
                    val id = (msg["request_id"] as? JsonPrimitive)?.long
                    if (id != null && "event" !in msg) {
                        pending.remove(id)?.complete(msg)
                    } else if ("event" in msg) {
                        _events.tryEmit(msg)
                    }
                }
            }
        } catch (e: IOException) {
            log.debug("mpv IPC closed: {}", e.message)
        } finally {
            closed = true
            pending.values.forEach { it.completeExceptionally(MpvError("mpv exited")) }
            _events.tryEmit(buildJsonObject { put("event", "ipc-closed") })
        }
    }

    private fun send(obj: JsonObject) = synchronized(channel) {
        val bytes = (obj.toString() + "\n").toByteArray()
        val buf = ByteBuffer.wrap(bytes)
        while (buf.hasRemaining()) channel.write(buf)
    }

    /** Runs a command and returns its `data`. Throws [MpvError] if mpv reports an error. */
    suspend fun command(vararg args: Any?): JsonElement {
        if (closed) throw MpvError("mpv is not running")
        val id = ids.incrementAndGet()
        val deferred = CompletableDeferred<JsonElement>()
        pending[id] = deferred
        withContext(Dispatchers.IO) {
            send(buildJsonObject {
                put("command", JsonArray(args.map(::toJson)))
                put("request_id", id)
            })
        }
        val reply = try {
            withTimeout(10_000) { deferred.await() } as JsonObject
        } finally {
            pending.remove(id)
        }
        val error = reply["error"]?.jsonPrimitive?.content
        if (error != "success") throw MpvError("${args.firstOrNull()}: $error")
        return reply["data"] ?: JsonNull
    }

    /** Fire-and-forget variant for commands whose result doesn't matter (OSD messages, script messages). */
    fun post(vararg args: Any?) {
        if (closed) return
        runCatching {
            send(buildJsonObject {
                put("command", JsonArray(args.map(::toJson)))
            })
        }
    }

    fun close() {
        runCatching { channel.close() }
    }

    companion object {
        fun connect(socket: Path): MpvIpc {
            val ch = SocketChannel.open(StandardProtocolFamily.UNIX)
            ch.connect(UnixDomainSocketAddress.of(socket))
            return MpvIpc(ch)
        }

        private fun toJson(v: Any?): JsonElement = when (v) {
            null -> JsonNull
            is JsonElement -> v
            is String -> JsonPrimitive(v)
            is Number -> JsonPrimitive(v)
            is Boolean -> JsonPrimitive(v)
            is Map<*, *> -> JsonObject(v.entries.associate { (k, x) -> k.toString() to toJson(x) })
            is List<*> -> JsonArray(v.map(::toJson))
            else -> JsonPrimitive(v.toString())
        }
    }
}
