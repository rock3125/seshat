import org.json.JSONArray
import org.json.JSONObject
import org.slf4j.LoggerFactory

/**
 * The two tools, and the MCP JSON-RPC dispatcher over them.
 *
 * `search` and `load_chunk` are the whole surface: retrieval, and the ability
 * to read around a hit. They are defined once, here, and reached two ways —
 * over `POST /mcp` by any MCP client (Claude Code, an IDE agent), and directly
 * in-process by the chat agent, which calls [call] with no HTTP hop at all.
 *
 * One definition, two callers, is the point. The model that answers in the web
 * UI and the model in someone's editor see exactly the same tool descriptions
 * and get exactly the same results, because there is no second implementation
 * to drift.
 */
class Tools(
    private val cfg: Config,
    private val db: Db,
    private val store: Store,
    private val embeddings: Embeddings,
) {
    private val log = LoggerFactory.getLogger("Tools")

    // --- the catalogue --------------------------------------------------------

    fun list(): JSONArray = JSONArray()
        .put(
            JSONObject()
                .put("name", "search")
                .put(
                    "description",
                    "Search the library of documents. Hybrid retrieval fuses BM25 keyword " +
                        "matching (exact terms, names, codes, numbers) with dense vector " +
                        "similarity (meaning and paraphrase). Returns the best paragraphs, " +
                        "each with a chunk_id to cite it by and the title and path of the " +
                        "document it came from. Use mode=hybrid unless you specifically want " +
                        "keyword-only matching (an exact identifier) or vector-only matching " +
                        "(a concept the corpus may word completely differently).",
                )
                .put("inputSchema", JSONObject("""
                    {
                      "type": "object",
                      "properties": {
                        "query": { "type": "string",
                                   "description": "The question or keywords to search for." },
                        "mode":  { "type": "string", "enum": ["hybrid", "dense", "keyword"],
                                   "default": "hybrid",
                                   "description": "hybrid = BM25 + vector fused (recommended); dense = meaning only; keyword = exact terms only." },
                        "top_k": { "type": "integer", "default": 6, "minimum": 1, "maximum": 25,
                                   "description": "How many paragraphs to return." }
                      },
                      "required": ["query"]
                    }
                """)),
        )
        .put(
            JSONObject()
                .put("name", "load_chunk")
                .put(
                    "description",
                    "Load one paragraph by its chunk_id, optionally with the paragraphs " +
                        "around it from the same document. Use this when a search result is " +
                        "cut off mid-thought, when it refers to something stated just before " +
                        "or after it, or when you need to quote a passage exactly. Set before " +
                        "and after to widen the window.",
                )
                .put("inputSchema", JSONObject("""
                    {
                      "type": "object",
                      "properties": {
                        "chunk_id": { "type": "integer",
                                      "description": "The chunk_id from a search result." },
                        "before":   { "type": "integer", "default": 0, "minimum": 0, "maximum": 10,
                                      "description": "How many preceding paragraphs to include." },
                        "after":    { "type": "integer", "default": 0, "minimum": 0, "maximum": 10,
                                      "description": "How many following paragraphs to include." }
                      },
                      "required": ["chunk_id"]
                    }
                """)),
        )

    // --- invocation -----------------------------------------------------------

    /** The MCP tool-result shape: text content plus an error flag. */
    private fun result(text: String, isError: Boolean = false): JSONObject = JSONObject()
        .put("content", JSONArray().put(JSONObject().put("type", "text").put("text", text)))
        .put("isError", isError)

    private fun failure(text: String) = result(text, isError = true)

    /**
     * Run a tool. Never throws: a backend that is down, a bad argument or a
     * missing chunk all come back as a tool error the model can read and act
     * on, because an exception here would kill a chat turn that was otherwise
     * one retry away from succeeding.
     */
    fun call(name: String, args: JSONObject): JSONObject = try {
        when (name) {
            "search" -> search(args)
            "load_chunk" -> loadChunk(args)
            else -> failure("unknown tool '$name' — this server offers search and load_chunk")
        }
    } catch (e: Exception) {
        log.warn("tool '{}' failed: {}", name, e.toString())
        failure("tool '$name' failed — ${e.javaClass.simpleName}: ${e.message ?: "no detail"}")
    }

    private fun search(args: JSONObject): JSONObject {
        val query = args.optString("query").trim()
        if (query.isEmpty()) return failure("'query' is required and must not be empty.")
        val mode = try {
            Mode.from(args.optString("mode", "hybrid"))
        } catch (e: IllegalArgumentException) {
            return failure(e.message ?: "invalid mode")
        }
        val topK = args.optInt("top_k", 6).coerceIn(1, 25)

        // Only embed when the mode actually uses a dense vector — a keyword
        // search should cost nothing at the embedding API.
        val dense = if (mode != Mode.KEYWORD) embeddings.query(query) else null
        val sparse = if (mode != Mode.DENSE) Bm25.query(query) else null

        // Retrieve wider than topK and trim after the Postgres join: a chunk
        // deleted between indexing and now disappears from the join, and
        // over-fetching means that shows up as one fewer result rather than a
        // short page.
        val candidates = maxOf(topK, cfg.searchCandidates.coerceAtMost(100))
        val hits = store.search(mode, dense, sparse, candidates)
        val scores = hits.associate { it.chunkId to it.score }
        val rows = db.chunksByIds(hits.map { it.chunkId }).take(topK)

        val results = JSONArray()
        for (row in rows) {
            results.put(
                JSONObject()
                    .put("chunk_id", row.id)
                    .put("document_id", row.documentId)
                    .put("title", row.title)
                    .put("path", row.path)
                    .put("ordinal", row.ordinal)
                    .put("score", scores[row.id]?.toDouble() ?: 0.0)
                    .put("text", row.text),
            )
        }
        return result(
            JSONObject()
                .put("mode", mode.name.lowercase())
                .put("count", results.length())
                .put("results", results)
                .toString(2),
        )
    }

    private fun loadChunk(args: JSONObject): JSONObject {
        if (!args.has("chunk_id")) return failure("'chunk_id' is required.")
        val chunkId = args.optLong("chunk_id")
        val before = args.optInt("before", 0).coerceIn(0, 10)
        val after = args.optInt("after", 0).coerceIn(0, 10)

        val anchor = db.chunk(chunkId)
            ?: return failure("no chunk $chunkId — it may have been removed from the library.")

        // Ordinals are contiguous within a document, so the window is a range,
        // and Postgres clamps it at the document's edges by simply returning
        // fewer rows.
        val rows = db.window(anchor.documentId, anchor.ordinal - before, anchor.ordinal + after)

        val paragraphs = JSONArray()
        for (row in rows) {
            paragraphs.put(
                JSONObject()
                    .put("chunk_id", row.id)
                    .put("ordinal", row.ordinal)
                    .put("text", row.text),
            )
        }
        return result(
            JSONObject()
                .put("document_id", anchor.documentId)
                .put("title", anchor.title)
                .put("path", anchor.path)
                .put("count", paragraphs.length())
                .put("paragraphs", paragraphs)
                .toString(2),
        )
    }

    // --- MCP JSON-RPC ---------------------------------------------------------

    /**
     * Handle one JSON-RPC message, or return null when none is due
     * (notifications). [authorised] is false when the caller presented no valid
     * token: the handshake still answers, so a client can connect and discover
     * the catalogue, but `tools/call` is refused — the transport has already
     * sent a 401 in that case (see Http).
     */
    fun rpc(msg: JSONObject): JSONObject? {
        val method = msg.optString("method", null) ?: return null
        val hasId = msg.has("id") && !msg.isNull("id")
        val id: Any? = if (hasId) msg.get("id") else null
        val params = msg.optJSONObject("params") ?: JSONObject()

        fun ok(result: JSONObject) =
            JSONObject().put("jsonrpc", "2.0").put("id", id).put("result", result)

        return try {
            when (method) {
                "initialize" -> ok(
                    JSONObject()
                        .put("protocolVersion",
                            params.optString("protocolVersion").ifBlank { PROTOCOL_VERSION })
                        .put("capabilities", JSONObject().put("tools", JSONObject()))
                        .put("serverInfo", JSONObject()
                            .put("name", "seshat-library").put("version", "1.0.0"))
                        .put("instructions",
                            "Search a private library of text documents. Call `search` for " +
                                "passages, `load_chunk` to read the paragraphs around a hit. " +
                                "Cite every answer by chunk_id."),
                )
                "ping" -> ok(JSONObject())
                "tools/list" -> ok(JSONObject().put("tools", list()))
                "tools/call" -> ok(
                    call(params.optString("name"), params.optJSONObject("arguments") ?: JSONObject()),
                )
                "notifications/initialized", "notifications/cancelled" -> null
                else -> if (hasId) error(id, -32601, "method not found: $method") else null
            }
        } catch (e: Exception) {
            log.warn("error handling '{}': {}", method, e.toString())
            if (hasId) error(id, -32603, "internal error: ${e.message}") else null
        }
    }

    companion object {
        const val PROTOCOL_VERSION = "2024-11-05"

        fun error(id: Any?, code: Int, message: String): JSONObject = JSONObject()
            .put("jsonrpc", "2.0")
            .put("id", id ?: JSONObject.NULL)
            .put("error", JSONObject().put("code", code).put("message", message))
    }
}
