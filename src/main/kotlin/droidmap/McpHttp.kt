package droidmap

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URI

private const val MAX_BODY = 4 shl 20

/**
 * The MCP server over Streamable HTTP, for clients that cannot start a process, such as Gemini in Android Studio: one endpoint,
 * `/mcp`, that takes each JSON-RPC message as a POST and answers it as JSON. It listens on the loopback address only, and turns
 * away requests from a web page of another origin (an `Origin` header that is not localhost), so a website cannot reach it.
 * Server-sent events are not offered (GET answers 405): every tool answers its call directly.
 */
class McpHttpServer(private val server: McpServer, port: Int) {
    private val http: HttpServer = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), port), 0).apply {
        createContext("/mcp") { exchange -> exchange.use(::exchange) }
    }

    /** The port it listens on (the one asked for, or a free one for 0). */
    val port: Int get() = http.address.port
    val url: String get() = "http://127.0.0.1:$port/mcp"

    fun start(): McpHttpServer = apply { http.start() }
    fun stop() = http.stop(0)

    private fun exchange(x: HttpExchange) {
        val origin = x.requestHeaders.getFirst("Origin")
        when {
            origin != null && !isLocal(origin) -> send(x, 403, "{\"error\":\"origin not allowed\"}")
            x.requestMethod != "POST" -> { x.responseHeaders.add("Allow", "POST"); send(x, 405, null) }
            else -> {
                val body = x.requestBody.readNBytes(MAX_BODY + 1)
                if (body.size > MAX_BODY) return send(x, 413, null)
                val reply = server.reply(String(body, Charsets.UTF_8))
                if (reply == null) send(x, 202, null) else send(x, 200, reply)
            }
        }
    }

    private fun isLocal(origin: String): Boolean = try {
        URI(origin).host in setOf("localhost", "127.0.0.1", "[::1]", "::1")
    } catch (e: Exception) {
        false
    }

    private fun send(x: HttpExchange, status: Int, json: String?) {
        if (json == null) { x.sendResponseHeaders(status, -1); return }
        val bytes = json.toByteArray(Charsets.UTF_8)
        x.responseHeaders.add("Content-Type", "application/json")
        x.sendResponseHeaders(status, bytes.size.toLong())
        x.responseBody.write(bytes)
    }
}
