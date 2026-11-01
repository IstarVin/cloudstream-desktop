package desktop

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.Executors

/** A throwaway HTTP server on a free port, for tests that need a real upstream. */
class LocalServer(host: String = "127.0.0.1", private val handler: (HttpExchange) -> Unit) : AutoCloseable {
    private val server = HttpServer.create(InetSocketAddress(host, 0), 0).apply {
        createContext("/") { ex ->
            try {
                handler(ex)
            } catch (e: Exception) {
                ex.respond(500, e.toString())
            } finally {
                ex.close()
            }
        }
        executor = Executors.newCachedThreadPool()
        start()
    }

    val url = "http://$host:${server.address.port}"

    override fun close() = server.stop(0)
}

fun HttpExchange.respond(code: Int, body: String, type: String = "text/html", headers: Map<String, String> = emptyMap()) {
    respond(code, body.toByteArray(), type, headers)
}

fun HttpExchange.respond(code: Int, body: ByteArray, type: String, headers: Map<String, String> = emptyMap()) {
    responseHeaders.add("Content-Type", type)
    headers.forEach { (k, v) -> responseHeaders.add(k, v) }
    sendResponseHeaders(code, if (body.isEmpty()) -1 else body.size.toLong())
    if (body.isNotEmpty()) responseBody.use { it.write(body) }
}
