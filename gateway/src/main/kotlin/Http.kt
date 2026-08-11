import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import org.json.JSONArray
import org.json.JSONObject
import org.slf4j.LoggerFactory
import java.io.OutputStream
import java.net.InetSocketAddress
import java.util.concurrent.Executors

/** A Server-Sent Events response, already begun. Writes are synchronized: the
 *  agent loop is sequential today, but a half-interleaved event is unparseable
 *  and the cost of preventing it is one lock. */
class Sse(private val out: OutputStream) {
    private val log = LoggerFactory.getLogger("Sse")

    @Synchronized
    fun send(event: String, data: JSONObject) {
        try {
            // Data is one line: the JSON serializer never emits a raw newline,
            // and SSE would treat one as a field break.
            out.write("event: $event\ndata: $data\n\n".toByteArray(Charsets.UTF_8))
            out.flush()
        } catch (e: java.io.IOException) {
            // The browser navigated away or pressed Stop. Not an error.
            throw ClientGone(e)
        }
    }

    class ClientGone(cause: Exception) : RuntimeException(cause)
}

/**
 * The whole HTTP surface, on the JDK's own server. One virtual thread per
 * request, because every route here blocks on something remote — Gemini,
 * Qdrant, Postgres — and a pool would just be a queue in front of that wait.
 *
 * Routes:
 *   POST /chat      SSE: one chat turn, tokens and tool activity as they happen
 *   POST /mcp       MCP Streamable HTTP — one JSON-RPC message in, one out
 *   GET  /chunk/N   one paragraph plus neighbours, for the UI's sources panel
 *   GET  /config    what the UI needs to render itself (model name, corpus size)
 *   POST /reindex   re-embed the corpus from Postgres (admin)
 *   GET  /health    readiness, unauthenticated
 *
 * Auth is one rule with one deliberate exception: with KEYCLOAK_ISSUER set,
 * every route except /health needs a verified bearer token carrying the
 * `use-ui` realm role. The exception is the MCP handshake — `initialize`,
 * `tools/list` and `ping` answer unauthenticated, because a client has to be
 * able to connect and discover the catalogue before any user is involved, and
 * those methods reveal nothing but the names of two tools. `tools/call`, which
 * reads the corpus, does not.
 */
class Http(
    private val cfg: Config,
    private val chat: Chat,
    private val tools: Tools,
    private val db: Db,
    private val library: Library,
    private val store: Store,
    private val auth: Auth?,
) {
    private val log = LoggerFactory.getLogger("Http")

    @Volatile private var ready = false

    fun start(): HttpServer {
        val server = HttpServer.create(InetSocketAddress(cfg.port), 0)
        server.createContext("/health") { it.handle(::health) }
        server.createContext("/config") { it.handle(::config) }
        server.createContext("/chat") { it.handle(::chatRoute) }
        server.createContext("/mcp") { it.handle(::mcpRoute) }
        server.createContext("/chunk/") { it.handle(::chunkRoute) }
        server.createContext("/reindex") { it.handle(::reindexRoute) }
        server.createContext("/") { it.handle { ex -> ex.json(404, JSONObject().put("error", "not found")) } }
        server.executor = Executors.newVirtualThreadPerTaskExecutor()
        server.start()

        // Readiness is a real round trip to both backends, retried in the
        // background: compose starts us alongside Qdrant and Postgres, and a
        // service that reports healthy before it can answer is worse than one
        // that takes ten more seconds to say so.
        Thread {
            while (true) {
                try {
                    store.ping(); db.stats(); ready = true; return@Thread
                } catch (e: Exception) {
                    Thread.sleep(3_000)
                }
            }
        }.apply { isDaemon = true; name = "readiness" }.start()

        log.info("listening on :{} — POST /chat (SSE), POST /mcp", cfg.port)
        return server
    }

    // --- routes ---------------------------------------------------------------

    private fun health(ex: HttpExchange) {
        ex.json(if (ready) 200 else 503,
            JSONObject().put("status", if (ready) "ok" else "starting"))
    }

    private fun config(ex: HttpExchange) {
        requireUser(ex) ?: return
        val stats = db.stats()
        ex.json(200, JSONObject()
            .put("model", cfg.geminiModel)
            .put("documents", stats.documents)
            .put("chunks", stats.chunks)
            .put("library_bytes", stats.bytes)
            .put("chat_enabled", cfg.geminiApiKey.isNotBlank()))
    }

    private fun chatRoute(ex: HttpExchange) {
        if (ex.requestMethod != "POST") return ex.methodNotAllowed("POST")
        val who = requireUser(ex) ?: return

        val body = try {
            JSONObject(ex.requestBody.readBytes().toString(Charsets.UTF_8))
        } catch (e: Exception) {
            return ex.json(400, JSONObject().put("error", "request body must be JSON"))
        }
        val prompt = body.optString("prompt").trim()
        if (prompt.isEmpty()) {
            return ex.json(400, JSONObject().put("error", "'prompt' is required"))
        }
        if (cfg.geminiApiKey.isBlank()) {
            return ex.json(503, JSONObject().put("error",
                "GEMINI_API_KEY is not set on the gateway — set it in .env and restart"))
        }

        // The browser owns conversation history (localStorage), so it sends the
        // window it wants replayed. That keeps Postgres to the one job the
        // brief gives it — the chunks — and means no chat transcript is ever
        // written to disk server-side.
        val history = body.optJSONArray("history")?.let { arr ->
            (0 until arr.length()).mapNotNull { i ->
                val m = arr.optJSONObject(i) ?: return@mapNotNull null
                val role = m.optString("role")
                if (role != "user" && role != "assistant") null
                else Chat.Message(role, m.optString("content"))
            }
        } ?: emptyList()

        // Headers before the first token: once these are sent the status code
        // is fixed, so any failure after this point is an SSE `error` event
        // rather than an HTTP status the browser could act on.
        ex.responseHeaders.apply {
            set("Content-Type", "text/event-stream; charset=utf-8")
            set("Cache-Control", "no-cache, no-transform")
            set("Connection", "keep-alive")
            set("X-Accel-Buffering", "no")   // nginx must not buffer the stream
        }
        ex.sendResponseHeaders(200, 0)

        val sse = Sse(ex.responseBody)
        try {
            log.info("chat turn for {} ({} history message(s))", who.username, history.size)
            chat.run(prompt, history, sse)
        } catch (e: Sse.ClientGone) {
            log.info("client disconnected mid-answer")
        } catch (e: Exception) {
            log.warn("chat turn failed: {}", e.toString())
            runCatching {
                sse.send("error", JSONObject().put("message", e.message ?: e.javaClass.simpleName))
                sse.send("done", JSONObject())
            }
        }
    }

    private fun mcpRoute(ex: HttpExchange) {
        if (ex.requestMethod != "POST") return ex.methodNotAllowed("POST")
        val msg = try {
            JSONObject(ex.requestBody.readBytes().toString(Charsets.UTF_8))
        } catch (e: Exception) {
            return ex.json(400, Tools.error(null, -32700, "parse error: ${e.message}"))
        }
        // Only tools/call reads the corpus; the handshake stays open so a
        // client can discover the catalogue before a user signs in.
        if (msg.optString("method") == "tools/call" && requireUser(ex) == null) return

        val response = tools.rpc(msg)
        if (response != null) ex.json(200, response) else ex.empty(202)
    }

    private fun chunkRoute(ex: HttpExchange) {
        if (ex.requestMethod != "GET") return ex.methodNotAllowed("GET")
        requireUser(ex) ?: return

        val id = ex.requestURI.path.removePrefix("/chunk/").toLongOrNull()
            ?: return ex.json(400, JSONObject().put("error", "chunk id must be a number"))
        val around = query(ex)["around"]?.toIntOrNull()?.coerceIn(0, 10) ?: 1

        val result = tools.call("load_chunk", JSONObject()
            .put("chunk_id", id).put("before", around).put("after", around))
        if (result.optBoolean("isError", false)) {
            return ex.json(404, JSONObject().put("error", "no chunk $id"))
        }
        // The tool returns its payload as text (the MCP shape); the browser
        // wants it as JSON, so unwrap it rather than making the UI parse a
        // string out of a string.
        val text = result.getJSONArray("content").getJSONObject(0).getString("text")
        ex.json(200, JSONObject(text))
    }

    private fun reindexRoute(ex: HttpExchange) {
        if (ex.requestMethod != "POST") return ex.methodNotAllowed("POST")
        val who = requireUser(ex) ?: return
        if (auth != null && !who.isAdmin) {
            return ex.json(403, JSONObject().put("error", "the 'admin' role is required"))
        }
        // Long job, and the caller only needs to know it started.
        Thread {
            runCatching { library.reindex() }
                .onFailure { log.error("reindex failed: {}", it.toString()) }
        }.apply { isDaemon = true; name = "reindex" }.start()
        ex.json(202, JSONObject().put("status", "reindex started"))
    }

    // --- auth + plumbing ------------------------------------------------------

    /**
     * The verified caller, or null when a 401/403 has already been sent.
     * With auth off, every caller is an anonymous admin — that is bare local
     * development, and it is why compose always sets KEYCLOAK_ISSUER.
     */
    private fun requireUser(ex: HttpExchange): Principal? {
        val auth = auth ?: return Principal("anonymous", "Anonymous", setOf("use-ui", "admin"))

        val header = ex.requestHeaders.getFirst("Authorization")
        val token = header?.takeIf { it.startsWith("Bearer ", ignoreCase = true) }
            ?.removePrefix("Bearer ")?.removePrefix("bearer ")?.trim()?.ifBlank { null }
        if (token == null) {
            ex.responseHeaders.set("WWW-Authenticate", "Bearer realm=\"seshat\"")
            ex.json(401, JSONObject().put("error", "a Keycloak bearer token is required"))
            return null
        }
        val principal = try {
            auth.verify(token)
        } catch (e: Auth.Rejected) {
            ex.responseHeaders.set("WWW-Authenticate", "Bearer realm=\"seshat\", error=\"invalid_token\"")
            ex.json(401, JSONObject().put("error", e.message ?: "invalid token"))
            return null
        }
        if ("use-ui" !in principal.roles) {
            ex.json(403, JSONObject().put("error", "the 'use-ui' role is required"))
            return null
        }
        return principal
    }

    private fun query(ex: HttpExchange): Map<String, String> =
        ex.requestURI.rawQuery?.split('&').orEmpty().mapNotNull { pair ->
            val i = pair.indexOf('=')
            if (i <= 0) null
            else java.net.URLDecoder.decode(pair.take(i), Charsets.UTF_8) to
                java.net.URLDecoder.decode(pair.substring(i + 1), Charsets.UTF_8)
        }.toMap()

    /** CORS, error trapping and close, around every route.
     *
     *  In compose the UI is same-origin (nginx serves it under the same host as
     *  /seshat/api), so CORS is dead weight there. It is here for `npm run dev`
     *  on :5173, which is cross-origin against this port. */
    private fun HttpExchange.handle(route: (HttpExchange) -> Unit) {
        try {
            responseHeaders.apply {
                set("Access-Control-Allow-Origin", cfg.corsOrigin)
                set("Access-Control-Allow-Headers", "Authorization, Content-Type")
                set("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
                set("Access-Control-Max-Age", "600")
            }
            if (requestMethod == "OPTIONS") return empty(204)
            route(this)
        } catch (e: Sse.ClientGone) {
            // Already logged where it mattered; nothing to send.
        } catch (e: Exception) {
            log.error("unhandled error on {}: {}", requestURI.path, e.toString(), e)
            runCatching { json(500, JSONObject().put("error", "internal error")) }
        } finally {
            close()
        }
    }

    private fun HttpExchange.json(status: Int, body: JSONObject) = send(status, body.toString())
    private fun HttpExchange.json(status: Int, body: JSONArray) = send(status, body.toString())

    private fun HttpExchange.send(status: Int, body: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        responseHeaders.set("Content-Type", "application/json; charset=utf-8")
        sendResponseHeaders(status, bytes.size.toLong())
        responseBody.use { it.write(bytes) }
    }

    private fun HttpExchange.empty(status: Int) {
        sendResponseHeaders(status, -1)
        responseBody.close()
    }

    private fun HttpExchange.methodNotAllowed(allowed: String) {
        responseHeaders.set("Allow", allowed)
        json(405, JSONObject().put("error", "method not allowed"))
    }
}
