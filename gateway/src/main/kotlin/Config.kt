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

    val geminiApiKey: String,
    val geminiModel: String,
    val geminiBaseUrl: String,
    val embedModel: String,
    val embedDims: Int,

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

        private fun boolEnv(name: String, default: Boolean) =
            when (System.getenv(name)?.trim()?.lowercase()) {
                null, "" -> default
                "on", "true", "1", "yes" -> true
                else -> false
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
                scanMinutes = env("LIBRARY_SCAN_MINUTES", "5").toLongOrNull() ?: 5L,

                geminiApiKey = env("GEMINI_API_KEY"),
                geminiModel = env("GEMINI_MODEL", "gemini-flash-latest"),
                geminiBaseUrl = env("GEMINI_BASE_URL", "https://generativelanguage.googleapis.com")
                    .trimEnd('/'),
                embedModel = env("EMBED_MODEL", "gemini-embedding-001"),
                embedDims = intEnv("EMBED_DIMS", 768),

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
                corsOrigin = env("CORS_ORIGIN", "*"),
            )
        }
    }
}
