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
 *
 * Because there is one implementation, there is one place to audit. [call]
 * takes the caller, and every retrieval against the shared corpus is recorded
 * there — whether it arrived through a chat turn or from an editor over MCP.
 * Auditing at either call site instead would have meant auditing at both, and
 * then discovering months later that one of them had stopped.
 */
class Tools(
    private val cfg: Config,
    private val db: Db,
    private val store: Store,
    private val embeddings: Embeddings,
    private val audit: Audit? = null,
    private val metrics: Metrics? = null,
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
     *
     * [who] and [requestId] are for the audit trail and may be absent — a tool
     * called with no principal is recorded as such rather than refused, because
     * refusing here would mean the auth decision was being made in two places.
     * It is made in [Http].
     */
    fun call(
        name: String,
        args: JSONObject,
        who: Principal? = null,
        requestId: String = "",
    ): JSONObject {
        val started = System.nanoTime()
        val outcome = try {
            when (name) {
                "search" -> search(args)
                "load_chunk" -> loadChunk(args)
                else -> failure("unknown tool '$name' — this server offers search and load_chunk")
            }
        } catch (e: Exception) {
            log.warn("tool '{}' failed: {}", name, e.toString())
            failure("tool '$name' failed — ${e.javaClass.simpleName}: ${e.message ?: "no detail"}")
        }

        val millis = ((System.nanoTime() - started) / 1_000_000).toInt()
        val ok = !outcome.optBoolean("isError", false)
        metrics?.toolCall(name, ok, millis / 1000.0)
        recordCall(name, args, outcome, ok, who, requestId, millis)
        return outcome
    }

    /**
     * The audit record for one tool call.
     *
     * The QUERY is recorded in full, and that is the deliberate part. A search
     * runs against one shared corpus that everyone signed in can read, so what
     * was searched for is the thing an administrator actually needs to be able
     * to see — unlike the chat prompt, which is the user's own words and is
     * hashed by default (AUDIT_CHAT_PROMPTS). The distinction is between what
     * someone asked and what the system was made to do with the library.
     */
    private fun recordCall(
        name: String, args: JSONObject, outcome: JSONObject, ok: Boolean,
        who: Principal?, requestId: String, millis: Int,
    ) {
        val audit = audit ?: return
        val action = when (name) {
            "search" -> Audit.TOOL_SEARCH
            "load_chunk" -> Audit.TOOL_LOAD_CHUNK
            else -> return
        }
        val detail = JSONObject()
        val target: String
        if (name == "search") {
            target = args.optString("query")
            detail.put("mode", args.optString("mode", "hybrid"))
            detail.put("top_k", args.optInt("top_k", 6))
            detail.put("hits", hitCount(outcome))
        } else {
            target = args.optLong("chunk_id").toString()
            detail.put("before", args.optInt("before", 0))
            detail.put("after", args.optInt("after", 0))
        }
        audit.record(
            who = who,
            action = action,
            outcome = if (ok) Audit.Outcome.OK else Audit.Outcome.ERROR,
            target = target,
            requestId = requestId,
            durationMs = millis,
            detail = detail,
        )
    }

    /** How many results a successful tool result carried, for the audit row.
     *  Best-effort: a result that will not parse costs the record a number, not
     *  the record. */
    private fun hitCount(outcome: JSONObject): Int = runCatching {
        val text = outcome.optJSONArray("content")?.optJSONObject(0)?.optString("text") ?: return 0
        JSONObject(text).optInt("count", 0)
    }.getOrDefault(0)

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
     * (notifications).
     *
     * The handshake answers unauthenticated, so a client can connect and
     * discover the catalogue before any user is involved; `tools/call` does not
     * reach here at all without a verified principal, because the transport
     * has already sent a 401 (see Http). [who] is therefore non-null for every
     * call that touches the corpus, and is carried through purely so the audit
     * row says who.
     */
    fun rpc(msg: JSONObject, who: Principal? = null, requestId: String = ""): JSONObject? {
        val method = msg.optString("method", null) ?: return null
        val hasId = msg.has("id") && !msg.isNull("id")
        val id: Any? = if (hasId) msg.get("id") else null
        val params = msg.optJSONObject("params") ?: JSONObject()

        // A message with no `id` is a NOTIFICATION, and JSON-RPC 2.0 is explicit
        // that a notification gets no response — not an empty one, not one
        // carrying a null id. The work still happens: `result` is computed by the
        // caller of this and then dropped, so `tools/call` sent as a notification
        // still runs the tool and still writes its audit row.
        //
        // This used to answer whatever it was sent, which put a reply on the wire
        // that a strict client is entitled to close the connection over. Only the
        // unknown-method branch below was checking.
        fun ok(result: JSONObject): JSONObject? =
            if (!hasId) null
            else JSONObject().put("jsonrpc", "2.0").put("id", id).put("result", result)

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
                    call(
                        params.optString("name"),
                        params.optJSONObject("arguments") ?: JSONObject(),
                        who, requestId,
                    ),
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
