package io.kestra.jetbrains

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList

// A minimal stand-in for a Kestra instance. Routes match "METHOD /path" after /api/v1.
class FakeKestra : AutoCloseable {
    data class Request(val method: String, val path: String, val headers: Map<String, String>, val body: String)

    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    private val routes = LinkedHashMap<String, (HttpExchange, Request) -> Unit>()
    val requests = CopyOnWriteArrayList<Request>()

    val url: String get() = "http://127.0.0.1:${server.address.port}"

    init {
        server.createContext("/") { exchange ->
            val path = exchange.requestURI.rawPath.removePrefix("/api/v1") + (exchange.requestURI.rawQuery?.let { "?$it" } ?: "")
            val headers = exchange.requestHeaders.mapValues { it.value.joinToString(",") }.mapKeys { it.key.lowercase() }
            val request = Request(exchange.requestMethod, path, headers, exchange.requestBody.readBytes().toString(Charsets.UTF_8))
            requests.add(request)
            val route = routes["${request.method} ${path.substringBefore('?')}"]
            try {
                if (route == null) respond(exchange, 404, """{"message":"not found"}""") else route(exchange, request)
            } finally {
                exchange.close()
            }
        }
        server.start()
    }

    fun json(method: String, path: String, status: Int = 200, body: () -> String) {
        routes["$method $path"] = { exchange, _ -> respond(exchange, status, body()) }
    }

    fun handle(method: String, path: String, handler: (HttpExchange, Request) -> Unit) {
        routes["$method $path"] = handler
    }

    // Writes the frames as Kestra does (named with "id:"), then closes the stream.
    fun sse(method: String, path: String, frames: () -> List<Pair<String, String>>) {
        routes["$method $path"] = { exchange, _ ->
            exchange.responseHeaders.add("Content-Type", "text/event-stream")
            exchange.sendResponseHeaders(200, 0)
            exchange.responseBody.use { out ->
                for ((name, data) in frames()) {
                    out.write("id:$name\ndata:$data\n\n".toByteArray())
                    out.flush()
                }
            }
        }
    }

    override fun close() = server.stop(0)

    companion object {
        fun respond(exchange: HttpExchange, status: Int, body: String) {
            val bytes = body.toByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(status, if (bytes.isEmpty()) -1 else bytes.size.toLong())
            if (bytes.isNotEmpty()) exchange.responseBody.use { it.write(bytes) }
        }
    }
}
