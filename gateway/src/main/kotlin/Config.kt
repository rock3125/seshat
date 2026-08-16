/**
 * Every knob the service has, read once from the environment at boot.
 *
 * The brief fixes the model and the key in `.env`, so there is deliberately no
 * runtime provider switch, no admin settings table and no per-request model
 * override — one process, one model, one corpus.
 */
data class Config(
    val port: Int,
    val databaseUrl: String,

    val qdrantHost: String,
    val qdrantPort: Int,
    val collection: String,

    val libraryDir: String,
    val libraryMirror: Boolean,
    val scanMinutes: Long,
    val uploadMaxBytes: Long,
    val uploadAdminOnly: Boolean,
    val extractMaxChars: Int,

    val semanticChunking: Boolean,
    val chunkMinChars: Int,
    val chunkMaxChars: Int,
    val semanticThreshold: Double,

    val geminiApiKey: String,
    val geminiModel: String,
    val geminiBaseUrl: String,
    val embedModel: String,
    val embedDims: Int,
    val embedConcurrency: Int,

    val keycloakIssuer: String,
    val keycloakJwksUrl: String,
    val keycloakAudience: String,

    val systemPrompt: String,
    val maxToolRounds: Int,
    val searchCandidates: Int,
    val corsOrigin: String,
) {
    /** Auth is on unless the issuer is blank — the same switch the search side
     *  of the service uses, so `/chat` and `/mcp` can never disagree about
     *  whether a caller had to sign in. */
    val authEnabled: Boolean get() = keycloakIssuer.isNotBlank()

    companion object {
        private fun env(name: String, default: String = "") =
            System.getenv(name)?.trim()?.ifBlank { null } ?: default

        private fun intEnv(name: String, default: Int) =
            System.getenv(name)?.trim()?.toIntOrNull() ?: default

        /**
         * A flag, and a complaint about anything that is not one.
         *
         * The `else -> false` branch is the trap this exists to light up:
         * LIBRARY_MIRROR=enabled, UPLOAD_ADMIN_ONLY=y, SEMANTIC_CHUNKING=True
         * (before lowercasing) all read as OFF, silently, and the first sign is
         * a feature that isn't running. Unrecognised values still resolve to
         * false — changing that would be a behaviour change on a live
         * deployment — but they no longer do it quietly.
         */
        private fun boolEnv(name: String, default: Boolean): Boolean {
            val raw = System.getenv(name)?.trim() ?: return default
            if (raw.isEmpty()) return default
            return when (raw.lowercase()) {
                "on", "true", "1", "yes" -> true
                "off", "false", "0", "no" -> false
                else -> {
                    System.err.println(
                        "WARN  $name='$raw' is not a recognised flag value — reading it as OFF. " +
                            "Use on/off, true/false, 1/0 or yes/no.",
                    )
                    false
                }
            }
        }

        const val DEFAULT_SYSTEM_PROMPT = """
You are Seshat, an assistant that answers strictly from a private library of
documents. Seshat kept the record; so do you.

Method, every time:
  1. Call `search` before answering anything about the library. Search first,
     answer second — never from memory.
  2. If the first results are thin, search again with different words. Use
     mode=keyword for exact names, codes and identifiers; mode=hybrid otherwise.
  3. Call `load_chunk` when a passage is cut off mid-thought and you need the
     paragraphs around it before you can answer safely.

Answering:
  - Cite every claim as [chunk:<chunk_id>] immediately after the sentence it
    supports. A sentence carrying a fact from the library and no citation is a
    defect.
  - If the library does not answer the question, say exactly that and stop. Do
    not fill the gap from general knowledge, and do not apologise at length.
  - Quote the source's own wording for definitions, names, figures and dates.
  - Be plain and short. A record does not persuade.
"""

        fun fromEnv(): Config {
            val issuer = env("KEYCLOAK_ISSUER").trimEnd('/')
            return Config(
                port = intEnv("PORT", 8090),
                databaseUrl = env("DATABASE_URL", "postgresql://seshat:seshat@postgres:5432/seshat"),

                qdrantHost = env("QDRANT_HOST", "qdrant"),
                qdrantPort = intEnv("QDRANT_PORT", 6334),
                collection = env("QDRANT_COLLECTION", "seshat"),

                libraryDir = env("LIBRARY_DIR", "/library"),
                libraryMirror = boolEnv("LIBRARY_MIRROR", true),
                // One minute: an upload is indexed on the spot, so this cadence
                // is what catches a file dropped into the folder by hand and a
                // file deleted from it. An unchanged corpus costs a read and a
                // hash per file per tick and no API call at all.
                scanMinutes = env("LIBRARY_SCAN_MINUTES", "1").toLongOrNull() ?: 1L,
                uploadMaxBytes = (env("UPLOAD_MAX_MB", "25").toLongOrNull() ?: 25L)
                    .coerceIn(1, 1024) * 1024 * 1024,
                // The library is one shared corpus: anything uploaded is
                // searchable by everyone signed in, so writing to it is an
                // admin act by default. Set UPLOAD_ADMIN_ONLY=off to let every
                // `use-ui` account add documents.
                uploadAdminOnly = boolEnv("UPLOAD_ADMIN_ONLY", true),
                // Tika's ceiling on one document. A 2M-character book is not
                // refused — its first two million characters are indexed and
                // the upload says it was cut.
                extractMaxChars = intEnv("EXTRACT_MAX_CHARS", 2_000_000),

                semanticChunking = boolEnv("SEMANTIC_CHUNKING", true),
                // The brief's number, and the one worth tuning: below this a
                // bunch takes the next sentence whatever the similarity says.
                chunkMinChars = intEnv("CHUNK_MIN_CHARS", 200).coerceIn(50, 4_000),
                chunkMaxChars = intEnv("CHUNK_MAX_CHARS", 3_000).coerceIn(200, 8_000),
                // Cosine between the next sentence and the bunch so far. Higher
                // splits more often; 1.0 would make every sentence its own
                // chunk if the minimum did not hold them together.
                semanticThreshold = (env("SEMANTIC_THRESHOLD", "0.75").toDoubleOrNull() ?: 0.75)
                    .coerceIn(0.0, 1.0),

                geminiApiKey = env("GEMINI_API_KEY"),
                geminiModel = env("GEMINI_MODEL", "gemini-flash-latest"),
                geminiBaseUrl = env("GEMINI_BASE_URL", "https://generativelanguage.googleapis.com")
                    .trimEnd('/'),
                embedModel = env("EMBED_MODEL", "gemini-embedding-001"),
                embedDims = intEnv("EMBED_DIMS", 768),
                // How many embedding batches are in flight at once while
                // indexing. Four is chosen for a free-tier key, where more
                // earns 429s and the retry backoff makes the whole thing
                // slower; raise it on a paid key with room to move.
                embedConcurrency = intEnv("EMBED_CONCURRENCY", 4).coerceIn(1, 32),

                keycloakIssuer = issuer,
                // Where THIS SERVICE fetches the realm keys. The issuer is the
                // browser-facing URL (through the proxy), which a container
                // cannot resolve — so the JWKS URL is overridden to the
                // internal service name in compose.
                keycloakJwksUrl = env("KEYCLOAK_JWKS_URL")
                    .ifBlank { if (issuer.isBlank()) "" else "$issuer/protocol/openid-connect/certs" },
                keycloakAudience = env("KEYCLOAK_AUDIENCE"),

                systemPrompt = env("SYSTEM_PROMPT", DEFAULT_SYSTEM_PROMPT).trim(),
                maxToolRounds = intEnv("MAX_TOOL_ROUNDS", 8),
                searchCandidates = intEnv("SEARCH_CANDIDATES", 40),
                // Blank, and no CORS headers are sent at all.
                //
                // The app is same-origin in BOTH modes — nginx proxies
                // /seshat/api to the gateway in production, and the Vite dev
                // server proxies the identical path (vite.config.ts) — so the
                // browser never makes a cross-origin request to this service
                // and the `*` that used to be the default was permission
                // nothing had asked for. Set it to a specific origin if you
                // front the gateway some other way.
                corsOrigin = env("CORS_ORIGIN"),
            )
        }
    }
}
