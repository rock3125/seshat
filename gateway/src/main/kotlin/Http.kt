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
 *   POST /upload    one file of any format into the library folder — converted
 *                   to text if it is not text, and indexed before it answers
 *   POST /reindex   reconcile with the library folder, then re-embed (admin)
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

    /**
     * How many uploads may be in Tika at once.
     *
     * An upload is held whole in memory — the request bytes, then the parser's
     * state, then the extracted text — and the server hands out a virtual
     * thread per request, so nothing else in the process bounds this. Four is
     * chosen against the default 25MB cap and the container's heap, and the
     * fifth caller waits rather than being refused: uploads arrive in a burst
     * from one person dropping a folder on the window, and a queue is the right
     * answer to a burst.
     */
    private val extracting = java.util.concurrent.Semaphore(4, true)

    fun start(): HttpServer {
        val server = HttpServer.create(InetSocketAddress(cfg.port), 0)
        server.createContext("/health") { it.handle(::health) }
        server.createContext("/config") { it.handle(::config) }
        server.createContext("/chat") { it.handle(::chatRoute) }
        server.createContext("/mcp") { it.handle(::mcpRoute) }
        server.createContext("/chunk/") { it.handle(::chunkRoute) }
        server.createContext("/upload") { it.handle(::uploadRoute) }
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
        val who = requireUser(ex) ?: return
        val stats = db.stats()
        ex.json(200, JSONObject()
            .put("model", cfg.geminiModel)
            .put("documents", stats.documents)
            .put("chunks", stats.chunks)
            .put("library_bytes", stats.bytes)
            .put("chat_enabled", cfg.geminiApiKey.isNotBlank())
            .put("scan_minutes", cfg.scanMinutes)
            // The upload rules come from here rather than being repeated in the
            // bundle: the size the UI refuses locally, whether the control
            // renders at all, and whether it should filter the file picker are
            // the server's answers, so they cannot drift out of step with what
            // /upload will actually accept. `converts` is what tells the UI to
            // stop filtering — with Tika in the jar, every format is a format.
            .put("upload", JSONObject()
                .put("allowed", mayUpload(who))
                .put("max_bytes", cfg.uploadMaxBytes)
                .put("converts", true)
                .put("text_extensions", JSONArray(Library.TEXT_EXTENSIONS.sorted()))))
    }

    private fun chatRoute(ex: HttpExchange) {
        if (ex.requestMethod != "POST") return ex.methodNotAllowed("POST")
        val who = requireUser(ex) ?: return

        val body = try {
            JSONObject(ex.readJsonBody())
        } catch (e: BodyTooLarge) {
            return ex.json(413, JSONObject().put("error", e.message))
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
        //
        // It also makes the size of a request someone else pays for a choice
        // made by the caller, so the window is re-trimmed here. The UI already
        // sends at most twenty messages; this is the bound for everything that
        // is not the UI.
        val sent = body.optJSONArray("history")?.let { arr ->
            (0 until arr.length()).mapNotNull { i ->
                val m = arr.optJSONObject(i) ?: return@mapNotNull null
                val role = m.optString("role")
                if (role != "user" && role != "assistant") null
                else Chat.Message(role, m.optString("content"))
            }
        } ?: emptyList()
        val history = trimHistory(sent)
        if (history.size < sent.size) {
            log.info("history trimmed from {} to {} message(s) for {}",
                sent.size, history.size, who.username)
        }

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
            JSONObject(ex.readJsonBody())
        } catch (e: BodyTooLarge) {
            return ex.json(413, Tools.error(null, -32600, e.message ?: "request too large"))
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

    /**
     * One file, one request: `POST /upload?name=notes.md` with the bytes as the
     * body.
     *
     * Not multipart. A browser can only send several files in one multipart
     * body, and then the whole batch shares one status and one progress bar —
     * whereas the thing a person uploading five documents wants to know is
     * which of the five landed. One request each gives every file its own
     * verdict, and costs a hand-rolled multipart parser nothing because there
     * isn't one.
     */
    private fun uploadRoute(ex: HttpExchange) {
        if (ex.requestMethod != "POST") return ex.methodNotAllowed("POST")
        val who = requireUser(ex) ?: return
        if (!mayUpload(who)) {
            return ex.json(403, JSONObject().put("error",
                "the 'admin' role is required to add documents to the library"))
        }

        val name = query(ex)["name"]?.trim().orEmpty()
        if (name.isEmpty()) {
            return ex.json(400, JSONObject().put("error", "?name=<file name> is required"))
        }

        // Content-Length first so an oversized upload is refused before it is
        // read, then a hard cap on the read itself — the header is the client's
        // claim, and a chunked request has none at all.
        val declared = ex.requestHeaders.getFirst("Content-Length")?.toLongOrNull()
        if (declared != null && declared > cfg.uploadMaxBytes) return ex.tooLarge()
        val bytes = ex.requestBody.readNBytes((cfg.uploadMaxBytes + 1).toInt())
        if (bytes.size > cfg.uploadMaxBytes) return ex.tooLarge()

        // Bounded here rather than inside Library: the wait is a property of
        // this hop (a person watching a progress row), not of what indexing
        // costs. Library's own lock still serialises the indexing half.
        if (!extracting.tryAcquire(2, java.util.concurrent.TimeUnit.MINUTES)) {
            return ex.json(503, JSONObject().put("error",
                "the gateway is busy converting other uploads — try this file again shortly"))
        }
        val upload = try {
            library.upload(name, bytes)
        } catch (e: Library.Rejected) {
            log.info("upload of {} by {} refused: {}", name, who.username, e.message)
            return ex.json(400, JSONObject().put("error", e.message))
        } catch (e: java.nio.file.AccessDeniedException) {
            // Almost always the mount: a read-only bind, or a folder owned by a
            // uid the container is not running as. Worth its own message —
            // "internal error" would send someone reading Kotlin.
            log.error("cannot write to the library folder: {}", e.toString())
            return ex.json(503, JSONObject().put("error",
                "the library folder is not writable by the gateway — check the mount in " +
                    "docker-compose.yml (it must not be :ro) and its ownership"))
        } catch (e: IllegalStateException) {
            return ex.json(503, JSONObject().put("error", e.message))
        } finally {
            extracting.release()
        }

        val stats = db.stats()
        ex.json(200, JSONObject()
            .put("source", upload.source)
            .put("path", upload.path)
            .put("bytes", upload.bytes)
            .put("replaced", upload.replaced)
            .put("status", upload.status)
            .put("chunks", upload.chunks ?: JSONObject.NULL)
            .put("converted_from", upload.convertedFrom ?: JSONObject.NULL)
            .put("truncated", upload.truncated)
            .put("documents", stats.documents)
            .put("total_chunks", stats.chunks))
    }

    /** Who may add to the corpus. Everyone signed in when UPLOAD_ADMIN_ONLY is
     *  off; admins only by default, because the library is shared. */
    private fun mayUpload(who: Principal): Boolean =
        auth == null || !cfg.uploadAdminOnly || who.isAdmin

    private fun reindexRoute(ex: HttpExchange) {
        if (ex.requestMethod != "POST") return ex.methodNotAllowed("POST")
        val who = requireUser(ex) ?: return
        if (auth != null && !who.isAdmin) {
            return ex.json(403, JSONObject().put("error", "the 'admin' role is required"))
        }
        // Long job, and the caller only needs to know it started.
        Thread {
            runCatching { library.reindex() }
                .onSuccess {
                    log.info("reindex by {}: {} chunk(s) re-embedded, {} document(s) added, {} removed",
                        who.username, it.chunks, it.scan.indexed, it.scan.removed)
                }
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

        val token = bearerToken(ex.requestHeaders.getFirst("Authorization"))
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
     *  The UI is same-origin in both modes — nginx proxies /seshat/api in
     *  production and the Vite dev server proxies the identical path — so no
     *  browser here ever makes a cross-origin call and CORS_ORIGIN defaults to
     *  blank, which sends no CORS headers at all. Set it to an origin only if
     *  something genuinely cross-origin has to reach this port. */
    private fun HttpExchange.handle(route: (HttpExchange) -> Unit) {
        try {
            if (cfg.corsOrigin.isNotBlank()) {
                responseHeaders.apply {
                    set("Access-Control-Allow-Origin", cfg.corsOrigin)
                    set("Access-Control-Allow-Headers", "Authorization, Content-Type")
                    set("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
                    set("Access-Control-Max-Age", "600")
                    if (cfg.corsOrigin != "*") add("Vary", "Origin")
                }
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

    /** The request body as a JSON string, refusing anything implausible.
     *
     *  `/upload` streams its own bytes under its own cap; these two routes take
     *  a JSON document, and `readBytes()` on an untrusted stream is an
     *  allocation the caller chooses the size of. */
    private fun HttpExchange.readJsonBody(): String {
        val bytes = requestBody.readNBytes(MAX_JSON_BODY_BYTES + 1)
        if (bytes.size > MAX_JSON_BODY_BYTES) {
            throw BodyTooLarge("the request body is larger than the " +
                "${MAX_JSON_BODY_BYTES / (1024 * 1024)}MB limit for this endpoint")
        }
        return bytes.toString(Charsets.UTF_8)
    }

    private class BodyTooLarge(message: String) : RuntimeException(message)

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

    private fun HttpExchange.tooLarge() {
        json(413, JSONObject().put("error",
            "the file is larger than the ${cfg.uploadMaxBytes / (1024 * 1024)}MB upload limit " +
                "(UPLOAD_MAX_MB)"))
    }

    private fun HttpExchange.methodNotAllowed(allowed: String) {
        responseHeaders.set("Allow", allowed)
        json(405, JSONObject().put("error", "method not allowed"))
    }

    companion object {
        /**
         * The credentials out of an `Authorization` header, or null if it does
         * not carry a bearer token.
         *
         * RFC 7235 makes the scheme case-INSENSITIVE, so the length of the
         * scheme is what may be trusted, never its spelling: matching the
         * prefix case-insensitively and then stripping the literal `"Bearer "`
         * accepted `BEARER x` and handed the whole header on as if it were the
         * token, which came back to the caller as "malformed token".
         */
        fun bearerToken(header: String?): String? {
            val h = header?.trim() ?: return null
            if (!h.startsWith(BEARER, ignoreCase = true)) return null
            return h.substring(BEARER.length).trim().ifBlank { null }
        }

        private const val BEARER = "Bearer "

        /**
         * How much replayed conversation `/chat` will accept. The browser owns
         * the history and sends the window it wants replayed (see [chatRoute]),
         * which makes its size a caller's choice — and an unbounded one, until
         * here. Both limits are on what is FORWARDED to the model, so the cost
         * of a hostile or simply runaway client is bounded at this hop rather
         * than on the Gemini bill.
         */
        const val MAX_HISTORY_MESSAGES = 40
        const val MAX_HISTORY_CHARS = 200_000

        /** The ceiling on a JSON request body (`/chat`, `/mcp`). Comfortably
         *  above [MAX_HISTORY_CHARS] of UTF-8, and far below what an unbounded
         *  `readBytes()` would let a caller allocate. */
        const val MAX_JSON_BODY_BYTES = 4 * 1024 * 1024

        /**
         * Keep the most recent messages that fit within both caps, oldest
         * first. Trimming from the END backwards rather than the start is what
         * keeps the turns nearest the question — the ones that carry the
         * thread's actual context — when something has to go.
         */
        fun trimHistory(history: List<Chat.Message>): List<Chat.Message> {
            val kept = ArrayDeque<Chat.Message>()
            var chars = 0
            for (m in history.asReversed()) {
                if (kept.size >= MAX_HISTORY_MESSAGES) break
                chars += m.content.length
                if (chars > MAX_HISTORY_CHARS) break
                kept.addFirst(m)
            }
            return kept.toList()
        }
    }
}
