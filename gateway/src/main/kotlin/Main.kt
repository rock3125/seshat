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
    val log = LoggerFactory.getLogger("seshat")
    val cfg = Config.fromEnv()

    log.info("model {}, embeddings {} at {}d, collection '{}'",
        cfg.geminiModel, cfg.embedModel, cfg.embedDims, cfg.collection)
    if (cfg.geminiApiKey.isBlank()) {
        log.warn("GEMINI_API_KEY is not set — the service will start, but chat and " +
            "indexing will fail until it is (set it in .env and restart)")
    }

    val db = Db(cfg).apply { migrate() }
    val store = Store(cfg).apply { ensureCollection() }
    val embeddings = Embeddings(cfg)
    val tools = Tools(cfg, db, store, embeddings)
    val chat = Chat(cfg, Gemini(cfg), tools)

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

    val library = Library(cfg, db, store, embeddings)
    val server = Http(cfg, chat, tools, db, library, store, auth).start()
    library.start()

    // Drain in dependency order on SIGTERM: stop accepting, let in-flight chat
    // turns finish (a turn holds its connection for as long as the model takes
    // to answer, so "in flight" routinely means a user watching a half-written
    // reply), and only then close what those turns are using.
    Runtime.getRuntime().addShutdownHook(Thread({
        log.info("shutting down — draining for up to 20s")
        runCatching { server.stop(20) }
        runCatching { store.close() }
        runCatching { db.close() }
        log.info("shutdown complete")
    }, "shutdown"))

    Thread.currentThread().join()
}
