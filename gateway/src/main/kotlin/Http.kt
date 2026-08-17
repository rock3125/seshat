import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import org.json.JSONArray
import org.json.JSONObject
import org.slf4j.LoggerFactory
import org.slf4j.MDC
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
 *   POST /chat            SSE: one chat turn, tokens and tool activity live
 *   POST /mcp             MCP Streamable HTTP — one JSON-RPC message in, one out
 *   GET  /chunk/N         one paragraph plus neighbours, for the sources panel
 *   GET  /config          what the UI needs to render itself
 *   POST /upload          one file of any format into the library folder
 *   POST /reindex         reconcile with the folder, then re-embed (admin)
 *   GET  /health          readiness, unauthenticated
 *   GET  /metrics         Prometheus exposition, unauthenticated, NOT proxied
 *   GET  /admin/logs      consolidated container logs, from Loki   (auditor)
 *   GET  /admin/logs/tail SSE live tail of the same                (auditor)
 *   GET  /admin/audit     the audit trail                          (auditor)
 *   GET  /admin/metrics   one named panel, from Prometheus         (auditor)
 *   GET  /admin/services  which scrape targets are up              (auditor)
 *
 * Auth is one rule with two deliberate exceptions: with KEYCLOAK_ISSUER set,
 * every route except /health and /metrics needs a verified bearer token
 * carrying the `use-ui` realm role, and everything under `/admin/` additionally
 * needs `admin` or `admin-observability`.
 *
 * The first exception is the MCP handshake — `initialize`, `tools/list` and
 * `ping` answer unauthenticated, because a client has to be able to connect and
 * discover the catalogue before any user is involved, and those methods reveal
 * nothing but the names of two tools. `tools/call`, which reads the corpus,
 * does not.
 *
 * The second is `/metrics`, which is unauthenticated because Prometheus does
 * not hold a token — and is therefore explicitly 404'd in ui/nginx.conf. The
 * gateway's port is not published, but the proxy in front of it is, and that
 * is the whole path an unauthenticated scrape endpoint could travel.
 *
 * EVERY REQUEST IS AUDITED FROM ONE PLACE. [handle] wraps every route, times
 * it, gives it a request id that also lands on every log line it produces, and
 * writes the audit record in a `finally`. A route added later cannot forget to
 * audit itself; it can only decline to, by leaving [Ctx.action] unset.
 */
class Http(
    private val cfg: Config,
    private val chat: Chat,
    private val tools: Tools,
    private val db: Db,
    private val library: Library,
    private val store: Store,
    private val auth: Auth?,
    private val audit: Audit,
    private val metrics: Metrics,
    private val admin: Admin,
) {
    private val log = LoggerFactory.getLogger("Http")

    @Volatile private var ready = false

    /**
     * What one request accumulated on its way through, so the wrapper can audit
     * it without every route having to remember to.
     *
     * Carried on the exchange itself rather than in a ThreadLocal: the server
     * hands out a virtual thread per request today, but a ThreadLocal would be
     * a silent correctness bug the day any part of this is handed to a pool,
     * and an exchange attribute cannot be.
     */
    class Ctx(val id: String, val startNanos: Long) {
        var who: Principal? = null
        /** Null means "do not audit this request" — /health, /metrics, and
         *  /config unless AUDIT_READS is on. */
        var action: String? = null
        var target: String = ""
        var outcome: Audit.Outcome = Audit.Outcome.OK
        val detail: JSONObject = JSONObject()
    }

    /**
     * Sessions already recorded, so `session.start` is written once per session
     * rather than once per request.
     *
     * A bounded LRU because the alternative is a set that grows for as long as
     * the process runs. Evicting the oldest costs at worst a duplicate
     * `session.start` for a very long-lived session on a very busy gateway,
     * which is a harmless extra row rather than a missing one.
     */
    private val seenSessions = object : LinkedHashMap<String, Boolean>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Boolean>) =
            size > 10_000
    }

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
        server.createContext("/metrics") { it.handle(::metricsRoute) }
        server.createContext("/config") { it.handle(::config) }
        server.createContext("/chat") { it.handle(::chatRoute) }
        server.createContext("/mcp") { it.handle(::mcpRoute) }
        server.createContext("/chunk/") { it.handle(::chunkRoute) }
        server.createContext("/upload") { it.handle(::uploadRoute) }
        server.createContext("/reindex") { it.handle(::reindexRoute) }
        server.createContext("/admin/") { it.handle(::adminRoute) }
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
                    store.ping()
                    val stats = db.stats()
                    metrics.corpus(stats.documents, stats.chunks)
                    ready = true
                    metrics.ready(true)
                    return@Thread
                } catch (e: Exception) {
                    Thread.sleep(3_000)
                }
            }
        }.apply { isDaemon = true; name = "readiness" }.start()

        log.info("listening on :{} — POST /chat (SSE), POST /mcp, GET /metrics", cfg.port)
        return server
    }

    // --- routes ---------------------------------------------------------------

    private fun health(ex: HttpExchange) {
        ex.json(if (ready) 200 else 503,
            JSONObject().put("status", if (ready) "ok" else "starting"))
    }

    /**
     * The Prometheus scrape.
     *
     * Unauthenticated, because Prometheus holds no token and giving it one
     * would mean a service-account client in Keycloak, a refresh loop, and a
     * credential in the scrape config — all to protect a document that says how
     * many chunks are indexed and how long a request took. The real control is
     * reachability: this port is not published, and nginx returns 404 for the
     * path that would otherwise expose it through the proxy that IS published.
     */
    private fun metricsRoute(ex: HttpExchange) {
        if (ex.requestMethod != "GET") return ex.methodNotAllowed("GET")
        if (!cfg.metricsEnabled) {
            return ex.json(404, JSONObject().put("error", "metrics are disabled (METRICS_ENABLED=off)"))
        }
        val body = metrics.scrape()
        ex.responseHeaders.set("Content-Type", metrics.contentType)
        ex.sendResponseHeaders(200, body.size.toLong())
        ex.responseBody.use { it.write(body) }
    }

    private fun config(ex: HttpExchange) {
        val who = requireUser(ex) ?: return
        // Off by default: this is polled once a minute per open tab, and a row
        // per user per minute buries every row that means something.
        if (cfg.auditReads) ex.ctx().action = Audit.CONFIG_READ
        val stats = db.stats()
        metrics.corpus(stats.documents, stats.chunks)
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
                .put("text_extensions", JSONArray(Library.TEXT_EXTENSIONS.sorted())))
            // What the Admin tab may render, decided here for the same reason
            // `upload.allowed` is: the server enforces it, so the server is
            // what says whether the control appears. `features` is separately
            // useful — a deployment started without the `observe` profile has an
            // administrator with no Loki behind them, and a tab that renders and
            // then 503s is worse than a tab that is honestly absent.
            .put("admin", JSONObject()
                .put("is_admin", auth == null || who.isAdmin)
                .put("may_audit", auth == null || who.mayAudit)
                .put("features", JSONObject()
                    .put("audit", cfg.auditEnabled)
                    .put("logs", cfg.logsEnabled)
                    .put("metrics", cfg.metricsQueryEnabled))))
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

        val ctx = ex.ctx()
        ctx.action = Audit.CHAT_TURN
        // The turn is always recorded; the prompt itself is not, unless
        // AUDIT_CHAT_PROMPTS says so. See Config for why that default is off —
        // in short, /chat promises that no transcript is written server-side,
        // and a hash plus a length keeps that promise while still proving the
        // turn happened.
        ctx.detail.put("prompt_chars", prompt.length)
        ctx.detail.put("prompt_sha256", Audit.digest(prompt))
        ctx.detail.put("history_messages", history.size)
        if (cfg.auditChatPrompts) ctx.target = prompt

        val sse = Sse(ex.responseBody)
        val started = System.nanoTime()
        try {
            log.info("chat turn for {} ({} history message(s))", who.username, history.size)
            val outcome = chat.run(prompt, history, sse, who, ctx.id)
            ctx.detail.put("tool_calls", outcome.toolCalls)
            ctx.detail.put("tool_rounds", outcome.rounds)
            ctx.detail.put("answer_chars", outcome.answerChars)
            metrics.chatTurn("ok", seconds(started))
        } catch (e: Sse.ClientGone) {
            log.info("client disconnected mid-answer")
            ctx.detail.put("client_gone", true)
            metrics.chatTurn("abandoned", seconds(started))
        } catch (e: Exception) {
            log.warn("chat turn failed: {}", e.toString())
            ctx.outcome = Audit.Outcome.ERROR
            ctx.detail.put("error", e.javaClass.simpleName)
            metrics.chatTurn("error", seconds(started))
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
        val ctx = ex.ctx()
        var who: Principal? = null
        if (msg.optString("method") == "tools/call") {
            who = requireUser(ex) ?: return
            // The tool call itself is audited inside Tools, where the query and
            // the hit count are. This row records that MCP was the door it came
            // through, which is the part Tools cannot see.
            ctx.action = Audit.MCP_CALL
            ctx.target = msg.optJSONObject("params")?.optString("name").orEmpty()
        }

        val response = tools.rpc(msg, who, ctx.id)
        if (response != null) ex.json(200, response) else ex.empty(202)
    }

    private fun chunkRoute(ex: HttpExchange) {
        if (ex.requestMethod != "GET") return ex.methodNotAllowed("GET")
        val who = requireUser(ex) ?: return

        val id = ex.requestURI.path.removePrefix("/chunk/").toLongOrNull()
            ?: return ex.json(400, JSONObject().put("error", "chunk id must be a number"))
        val around = query(ex)["around"]?.toIntOrNull()?.coerceIn(0, 10) ?: 1

        val ctx = ex.ctx()
        ctx.action = Audit.CHUNK_VIEW
        ctx.target = id.toString()
        ctx.detail.put("around", around)

        val result = tools.call("load_chunk", JSONObject()
            .put("chunk_id", id).put("before", around).put("after", around), who, ctx.id)
        if (result.optBoolean("isError", false)) {
            ctx.outcome = Audit.Outcome.ERROR
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
        val ctx = ex.ctx()
        ctx.action = Audit.UPLOAD
        ctx.target = query(ex)["name"]?.trim().orEmpty()

        if (!mayUpload(who)) {
            ctx.outcome = Audit.Outcome.DENIED
            ctx.detail.put("reason", "not an admin, and UPLOAD_ADMIN_ONLY is on")
            return ex.json(403, JSONObject().put("error",
                "the 'admin' role is required to add documents to the library"))
        }

        val name = ctx.target
        if (name.isEmpty()) {
            ctx.outcome = Audit.Outcome.ERROR
            return ex.json(400, JSONObject().put("error", "?name=<file name> is required"))
        }

        // Content-Length first so an oversized upload is refused before it is
        // read, then a hard cap on the read itself — the header is the client's
        // claim, and a chunked request has none at all.
        val declared = ex.requestHeaders.getFirst("Content-Length")?.toLongOrNull()
        if (declared != null && declared > cfg.uploadMaxBytes) {
            ctx.outcome = Audit.Outcome.DENIED
            ctx.detail.put("reason", "larger than UPLOAD_MAX_MB")
            ctx.detail.put("declared_bytes", declared)
            return ex.tooLarge()
        }
        val bytes = ex.requestBody.readNBytes((cfg.uploadMaxBytes + 1).toInt())
        if (bytes.size > cfg.uploadMaxBytes) {
            ctx.outcome = Audit.Outcome.DENIED
            ctx.detail.put("reason", "larger than UPLOAD_MAX_MB")
            return ex.tooLarge()
        }
        ctx.detail.put("bytes", bytes.size)

        // Bounded here rather than inside Library: the wait is a property of
        // this hop (a person watching a progress row), not of what indexing
        // costs. Library's own lock still serialises the indexing half.
        if (!extracting.tryAcquire(2, java.util.concurrent.TimeUnit.MINUTES)) {
            ctx.outcome = Audit.Outcome.ERROR
            ctx.detail.put("reason", "conversion queue full")
            return ex.json(503, JSONObject().put("error",
                "the gateway is busy converting other uploads — try this file again shortly"))
        }
        val upload = try {
            library.upload(name, bytes)
        } catch (e: Library.Rejected) {
            log.info("upload of {} by {} refused: {}", name, who.username, e.message)
            ctx.outcome = Audit.Outcome.DENIED
            ctx.detail.put("reason", e.message ?: "rejected")
            return ex.json(400, JSONObject().put("error", e.message))
        } catch (e: java.nio.file.AccessDeniedException) {
            // Almost always the mount: a read-only bind, or a folder owned by a
            // uid the container is not running as. Worth its own message —
            // "internal error" would send someone reading Kotlin.
            log.error("cannot write to the library folder: {}", e.toString())
            ctx.outcome = Audit.Outcome.ERROR
            ctx.detail.put("reason", "library folder not writable")
            return ex.json(503, JSONObject().put("error",
                "the library folder is not writable by the gateway — check the mount in " +
                    "docker-compose.yml (it must not be :ro) and its ownership"))
        } catch (e: IllegalStateException) {
            ctx.outcome = Audit.Outcome.ERROR
            ctx.detail.put("reason", e.message ?: "unavailable")
            return ex.json(503, JSONObject().put("error", e.message))
        } finally {
            extracting.release()
        }

        ctx.detail.put("stored_as", upload.path)
        ctx.detail.put("status", upload.status)
        ctx.detail.put("replaced", upload.replaced)
        ctx.detail.put("chunks", upload.chunks ?: JSONObject.NULL)
        ctx.detail.put("converted_from", upload.convertedFrom ?: JSONObject.NULL)
        ctx.detail.put("truncated", upload.truncated)
        metrics.documentIndexed(upload.status.replace(' ', '_'))

        val stats = db.stats()
        metrics.corpus(stats.documents, stats.chunks)
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
        val ctx = ex.ctx()
        ctx.action = Audit.REINDEX
        val who = requireAdmin(ex) ?: return

        // Long job, and the caller only needs to know it started. The audit row
        // for the REQUEST is written when this returns 202; the row for the
        // OUTCOME is written by the thread, minutes later, because those are two
        // different facts and collapsing them would mean either lying about when
        // it finished or holding the connection open until it did.
        Thread {
            runCatching { library.reindex() }
                .onSuccess {
                    log.info("reindex by {}: {} chunk(s) re-embedded, {} document(s) added, {} removed",
                        who.username, it.chunks, it.scan.indexed, it.scan.removed)
                    val stats = db.stats()
                    metrics.corpus(stats.documents, stats.chunks)
                    audit.record(who, Audit.REINDEX, Audit.Outcome.OK, target = "completed",
                        requestId = ctx.id, detail = JSONObject()
                            .put("chunks_reembedded", it.chunks)
                            .put("documents_added", it.scan.indexed)
                            .put("documents_removed", it.scan.removed))
                }
                .onFailure {
                    log.error("reindex failed: {}", it.toString())
                    audit.record(who, Audit.REINDEX, Audit.Outcome.ERROR, target = "failed",
                        requestId = ctx.id,
                        detail = JSONObject().put("error", Db.rootCause(it)))
                }
        }.apply { isDaemon = true; name = "reindex" }.start()
        ctx.target = "started"
        ex.json(202, JSONObject().put("status", "reindex started"))
    }

    // --- the admin API --------------------------------------------------------

    /**
     * Everything under `/admin/`, behind one role check.
     *
     * Dispatched here rather than as seven `createContext` calls so the guard
     * cannot be missed on a route added later: there is one door, and the check
     * is the first thing through it.
     */
    private fun adminRoute(ex: HttpExchange) {
        if (ex.requestMethod != "GET") return ex.methodNotAllowed("GET")
        val ctx = ex.ctx()
        val path = ex.requestURI.path.removePrefix("/admin/")

        // The action is set BEFORE the role check, so a refused attempt to read
        // the audit trail is itself an audit row. Someone probing an admin API
        // they do not have is exactly the thing this table exists to remember.
        ctx.action = when (path) {
            "logs", "logs.csv", "logs/tail", "logs/facets" -> Audit.ADMIN_LOGS_QUERY
            "audit", "audit.csv", "audit/facets" -> Audit.ADMIN_AUDIT_READ
            "metrics", "metrics/panels" -> Audit.ADMIN_METRICS_QUERY
            "services" -> Audit.ADMIN_SERVICES_READ
            else -> null
        }
        requireAuditor(ex) ?: return

        val params = query(ex)
        ctx.target = params.entries.sortedBy { it.key }
            .filter { it.key != "cursor" }
            .joinToString(" ") { "${it.key}=${it.value}" }

        try {
            when (path) {
                "logs" -> ex.json(200, admin.logs(params))
                "logs/facets" -> ex.json(200, admin.logFacets(params))
                "logs.csv" -> ex.csv("seshat-logs.csv", admin.logsCsv(params))
                "logs/tail" -> tailRoute(ex, params)
                "audit" -> ex.json(200, admin.auditQuery(params))
                "audit/facets" -> ex.json(200, admin.auditFacets(params))
                "audit.csv" -> ex.csv("seshat-audit.csv", admin.auditCsv(params))
                "metrics" -> ex.json(200, admin.metrics(params))
                "metrics/panels" -> ex.json(200, admin.panels())
                "services" -> ex.json(200, admin.services())
                else -> ex.json(404, JSONObject().put("error", "no such admin route: /admin/$path"))
            }
        } catch (e: Admin.BadRequest) {
            ctx.outcome = Audit.Outcome.ERROR
            ex.json(400, JSONObject().put("error", e.message))
        } catch (e: Observability.Unavailable) {
            // 503 and not 500: the upstream is absent or unreachable, which the
            // Admin tab states plainly rather than sending a reader to look for
            // a bug in the gateway.
            ctx.outcome = Audit.Outcome.ERROR
            ex.json(503, JSONObject().put("error", e.message))
        }
    }

    /** The live tail. Headers first, then events until the client goes away. */
    private fun tailRoute(ex: HttpExchange, params: Map<String, String>) {
        ex.responseHeaders.apply {
            set("Content-Type", "text/event-stream; charset=utf-8")
            set("Cache-Control", "no-cache, no-transform")
            set("Connection", "keep-alive")
            set("X-Accel-Buffering", "no")
        }
        ex.sendResponseHeaders(200, 0)
        val sse = Sse(ex.responseBody)
        try {
            admin.tail(params, sse)
        } catch (e: Admin.BadRequest) {
            runCatching { sse.send("error", JSONObject().put("message", e.message)) }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    // --- auth + plumbing ------------------------------------------------------

    /**
     * The verified caller, or null when a 401/403 has already been sent.
     * With auth off, every caller is an anonymous admin — that is bare local
     * development, and it is why compose always sets KEYCLOAK_ISSUER.
     */
    private fun requireUser(ex: HttpExchange): Principal? {
        val ctx = ex.ctx()
        val auth = auth ?: return ANONYMOUS.also { ctx.bind(it) }

        val token = bearerToken(ex.requestHeaders.getFirst("Authorization"))
        if (token == null) {
            ex.responseHeaders.set("WWW-Authenticate", "Bearer realm=\"seshat\"")
            ex.denied(ctx, "no bearer token")
            ex.json(401, JSONObject().put("error", "a Keycloak bearer token is required"))
            return null
        }
        val principal = try {
            auth.verify(token)
        } catch (e: Auth.Rejected) {
            ex.responseHeaders.set("WWW-Authenticate", "Bearer realm=\"seshat\", error=\"invalid_token\"")
            // The REASON is recorded, never the token — a rejected credential in
            // an admin-readable table is still a credential.
            ex.denied(ctx, e.message ?: "invalid token")
            ex.json(401, JSONObject().put("error", e.message ?: "invalid token"))
            return null
        }
        ctx.bind(principal)

        if (Principal.USE_UI !in principal.roles) {
            ex.denied(ctx, "missing the use-ui role")
            ex.json(403, JSONObject().put("error", "the 'use-ui' role is required"))
            return null
        }
        noteSession(principal, ctx)
        return principal
    }

    /** A caller who may change the corpus. */
    private fun requireAdmin(ex: HttpExchange): Principal? {
        val who = requireUser(ex) ?: return null
        if (auth != null && !who.isAdmin) {
            ex.denied(ex.ctx(), "missing the admin role")
            ex.json(403, JSONObject().put("error", "the 'admin' role is required"))
            return null
        }
        return who
    }

    /** A caller who may read what everyone else did. Separate from [requireAdmin]
     *  on purpose: `admin-observability` alone is an auditor who cannot reindex,
     *  and that is a role assignment rather than a code change. */
    private fun requireAuditor(ex: HttpExchange): Principal? {
        val who = requireUser(ex) ?: return null
        if (auth != null && !who.mayAudit) {
            ex.denied(ex.ctx(), "missing the admin or admin-observability role")
            ex.json(403, JSONObject().put("error",
                "the 'admin' or 'admin-observability' role is required to read logs and the audit trail"))
            return null
        }
        return who
    }

    /** Turn whatever this request was going to be into a refusal, keeping what
     *  it was ATTEMPTING as the target — "denied · admin.audit.read" is the row
     *  worth having, not "denied · something". */
    private fun HttpExchange.denied(ctx: Ctx, reason: String) {
        ctx.detail.put("attempted", ctx.action ?: requestURI.path)
        ctx.detail.put("reason", reason)
        ctx.action = Audit.AUTH_DENIED
        ctx.outcome = Audit.Outcome.DENIED
        if (ctx.target.isBlank()) ctx.target = requestURI.path
    }

    /** The first request of a session gets an extra row, so the trail begins
     *  with somebody arriving rather than with whatever they did first. */
    private fun noteSession(who: Principal, ctx: Ctx) {
        val key = who.sessionId.ifBlank { return }
        val fresh = synchronized(seenSessions) { seenSessions.put(key, true) == null }
        if (!fresh) return
        audit.record(who, Audit.SESSION_START, Audit.Outcome.OK,
            target = who.username, requestId = ctx.id,
            detail = JSONObject().put("roles", JSONArray(who.roles.sorted())))
    }

    private fun query(ex: HttpExchange): Map<String, String> =
        ex.requestURI.rawQuery?.split('&').orEmpty().mapNotNull { pair ->
            val i = pair.indexOf('=')
            if (i <= 0) null
            else java.net.URLDecoder.decode(pair.take(i), Charsets.UTF_8) to
                java.net.URLDecoder.decode(pair.substring(i + 1), Charsets.UTF_8)
        }.toMap()

    /**
     * CORS, identity, timing, metrics, the audit record, error trapping and
     * close — around every route.
     *
     * The UI is same-origin in both modes — nginx proxies /seshat/api in
     * production and the Vite dev server proxies the identical path — so no
     * browser here ever makes a cross-origin call and CORS_ORIGIN defaults to
     * blank, which sends no CORS headers at all. Set it to an origin only if
     * something genuinely cross-origin has to reach this port.
     *
     * The request id is minted here and put on the MDC, which means EVERY log
     * line any route writes carries `req`, and the username too once the caller
     * is known. That is what makes a log line and the audit row that describes
     * it joinable — one id, one query, both views in the Admin tab side by
     * side. It costs a UUID per request.
     */
    private fun HttpExchange.handle(route: (HttpExchange) -> Unit) {
        val ctx = Ctx(java.util.UUID.randomUUID().toString().replace("-", "").take(16),
            System.nanoTime())
        setAttribute(CTX, ctx)
        MDC.put("req", ctx.id)
        MDC.put("route", requestURI.path)
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
            // The id goes back to the caller as well. When a user reports "it
            // failed at about eleven", this is the difference between searching
            // and looking it up.
            responseHeaders.set("X-Request-Id", ctx.id)
            if (requestMethod == "OPTIONS") return empty(204)
            route(this)
        } catch (e: Sse.ClientGone) {
            // Already logged where it mattered; nothing to send.
        } catch (e: Exception) {
            log.error("unhandled error on {}: {}", requestURI.path, e.toString(), e)
            ctx.outcome = Audit.Outcome.ERROR
            ctx.detail.put("error", e.javaClass.simpleName)
            runCatching { json(500, JSONObject().put("error", "internal error")) }
        } finally {
            finish(ctx)
            MDC.clear()
            close()
        }
    }

    /** Metrics and the audit row, for a request that is over one way or another. */
    private fun HttpExchange.finish(ctx: Ctx) {
        val millis = ((System.nanoTime() - ctx.startNanos) / 1_000_000).toInt()
        // -1 when the route threw before sending anything, which the exchange
        // will answer as a 500 once it is closed.
        val status = if (responseCode > 0) responseCode else 500

        metrics.request(Metrics.routeLabel(requestURI.path), requestMethod, status, millis / 1000.0)

        val action = ctx.action ?: return
        if (ctx.outcome == Audit.Outcome.OK && status >= 400) {
            // A route that answered 4xx/5xx without saying so — belt and braces,
            // so an outcome is never more cheerful than the status code.
            ctx.outcome = if (status < 500) Audit.Outcome.DENIED else Audit.Outcome.ERROR
        }
        audit.record(
            who = ctx.who,
            action = action,
            outcome = ctx.outcome,
            target = ctx.target,
            status = status,
            ip = clientIp(),
            requestId = ctx.id,
            durationMs = millis,
            detail = ctx.detail,
        )
    }

    /**
     * The caller's address.
     *
     * X-Forwarded-For is trusted because nothing but nginx can reach this port —
     * it is not published, and compose puts the gateway on the internal network
     * only. The FIRST entry is the client; the rest are proxies, and taking the
     * last would faithfully record nginx's own address on every row.
     */
    private fun HttpExchange.clientIp(): String {
        val forwarded = requestHeaders.getFirst("X-Forwarded-For")
        if (!forwarded.isNullOrBlank()) return forwarded.split(',').first().trim().take(64)
        return remoteAddress?.address?.hostAddress.orEmpty()
    }

    private fun HttpExchange.ctx(): Ctx = getAttribute(CTX) as Ctx

    private fun Ctx.bind(principal: Principal) {
        who = principal
        MDC.put("user", principal.username)
    }

    private fun seconds(startNanos: Long) = (System.nanoTime() - startNanos) / 1e9

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

    /** A CSV export, as a download rather than something the browser renders. */
    private fun HttpExchange.csv(filename: String, body: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        responseHeaders.set("Content-Type", "text/csv; charset=utf-8")
        responseHeaders.set("Content-Disposition", "attachment; filename=\"$filename\"")
        sendResponseHeaders(200, bytes.size.toLong())
        responseBody.use { it.write(bytes) }
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
        private const val CTX = "seshat.ctx"

        /** With auth off every caller is an anonymous admin — bare local
         *  development, and the reason compose always sets KEYCLOAK_ISSUER. */
        private val ANONYMOUS = Principal(
            "anonymous", "Anonymous",
            setOf(Principal.USE_UI, Principal.ADMIN, Principal.OBSERVABILITY),
        )

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
