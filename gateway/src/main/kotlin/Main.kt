import org.slf4j.LoggerFactory

/**
 * Seshat — one jar that is both the MCP server and the chat gateway.
 *
 * It reads text files from a library folder, splits them along paragraphs,
 * stores the paragraphs in Postgres and indexes them in Qdrant as both a dense
 * vector and a BM25 sparse vector. It serves that corpus two ways: as MCP tools
 * (`POST /mcp`) to any MCP client, and as a grounded chat stream (`POST /chat`)
 * to the web UI, with Gemini calling those same tools in-process.
 *
 *   GEMINI_API_KEY=… DATABASE_URL=… QDRANT_HOST=… java -jar gateway-all.jar
 *
 * Start-up is deliberately tolerant: Postgres, Qdrant and Keycloak all come up
 * alongside this process, so each connection retries rather than exiting. The
 * one thing that fails fast is a missing library folder, which is a mounting
 * mistake and silently indexes nothing.
 */
fun main() {
    // BEFORE the first logger is touched, because this decides whether there
    // will be any logging at all. logback.xml names its appenders after the
    // values LOG_FORMAT takes and refers to one by substitution, so an
    // unrecognised value silently produces a root logger with no appender —
    // a service that runs perfectly and says nothing, which is the worst way
    // to be misconfigured. Refusing to start is the kinder failure.
    val format = System.getenv("LOG_FORMAT")?.trim()?.lowercase().orEmpty()
    if (format.isNotEmpty() && format != "json" && format != "text") {
        System.err.println("FATAL LOG_FORMAT='$format' is not 'json' or 'text'.")
        kotlin.system.exitProcess(2)
    }

    val log = LoggerFactory.getLogger("seshat")
    val cfg = Config.fromEnv()

    log.info("model {}, embeddings {} at {}d, collection '{}'",
        cfg.geminiModel, cfg.embedModel, cfg.embedDims, cfg.collection)
    if (cfg.geminiApiKey.isBlank()) {
        log.warn("GEMINI_API_KEY is not set — the service will start, but chat and " +
            "indexing will fail until it is (set it in .env and restart)")
    }

    val metrics = Metrics(cfg.metricsEnabled)
    val db = Db(cfg).apply { migrate() }
    val store = Store(cfg).apply { ensureCollection() }
    val embeddings = Embeddings(cfg, metrics)
    val audit = Audit(cfg, db, metrics).apply { start() }
    val tools = Tools(cfg, db, store, embeddings, audit, metrics)
    val chat = Chat(cfg, Gemini(cfg), tools)
    val admin = Admin(cfg, Observability(cfg), audit)

    val auth = if (cfg.authEnabled) {
        log.info("auth ON — issuer {}, JWKS {}, audience {}",
            cfg.keycloakIssuer, cfg.keycloakJwksUrl,
            cfg.keycloakAudience.ifBlank { "(any client in the realm)" })
        if (cfg.keycloakAudience.isBlank()) {
            log.warn("KEYCLOAK_AUDIENCE is unset — any token this realm issued is accepted, " +
                "including one minted for another client")
        }
        Auth(cfg.keycloakIssuer, cfg.keycloakJwksUrl, cfg.keycloakAudience)
    } else {
        log.warn("auth OFF — every caller is treated as an admin. Set KEYCLOAK_ISSUER " +
            "to require sign-in")
        null
    }

    if (cfg.logsEnabled || cfg.metricsQueryEnabled) {
        log.info("admin observability — logs {}, metrics {}",
            cfg.lokiUrl.ifBlank { "(off)" }, cfg.prometheusUrl.ifBlank { "(off)" })
    } else {
        log.info("admin observability is off — LOKI_URL and PROMETHEUS_URL are both unset, " +
            "so the Admin tab offers the audit trail only. Start the stack with " +
            "`docker compose --profile observe up -d` for the rest.")
    }

    val library = Library(cfg, db, store, embeddings, metrics)
    val server = Http(cfg, chat, tools, db, library, store, auth, audit, metrics, admin).start()
    library.start()

    // Drain in dependency order on SIGTERM: stop accepting, let in-flight chat
    // turns finish (a turn holds its connection for as long as the model takes
    // to answer, so "in flight" routinely means a user watching a half-written
    // reply), and only then close what those turns are using.
    //
    // The audit writer drains BEFORE the pool it writes through is closed —
    // getting that order wrong would throw away the records of whatever the
    // service was doing when it was asked to stop, which is exactly the moment
    // an audit trail is most likely to be read.
    Runtime.getRuntime().addShutdownHook(Thread({
        log.info("shutting down — draining for up to 20s")
        runCatching { server.stop(20) }
        runCatching { audit.close() }
        runCatching { store.close() }
        runCatching { db.close() }
        log.info("shutdown complete")
    }, "shutdown"))

    Thread.currentThread().join()
}
