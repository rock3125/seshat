import org.json.JSONArray
import org.json.JSONObject
import org.slf4j.LoggerFactory

/**
 * The agent loop: stream a model turn, run whatever tools it asked for, feed
 * the results back, repeat until it answers without asking for another tool.
 *
 * Everything is surfaced to the browser as it happens, over SSE:
 *
 *   event: token       {"text"}                  an incremental answer chunk
 *   event: tool_call   {"name","args"}           the model reached for a tool
 *   event: tool_result {"name","ok","results"}   it finished; a successful
 *                                                `search` also carries the
 *                                                passages, so the UI can show
 *                                                what was retrieved even when
 *                                                the model cites none of it
 *   event: done        {}                        the turn is complete
 *   event: error       {"message"}               something failed
 *
 * Tools are called in-process — the same [Tools] instance `POST /mcp` serves —
 * so a chat turn costs no HTTP round trip to reach its own retrieval.
 */
class Chat(private val cfg: Config, private val gemini: Gemini, private val tools: Tools) {
    private val log = LoggerFactory.getLogger("Chat")

    /** One prior turn, replayed to give the model the thread's context. */
    data class Message(val role: String, val content: String)

    /** What a turn did, for the audit row and the metrics — the answer itself
     *  has already gone to the browser by the time this is returned. */
    data class Outcome(val toolCalls: Int, val rounds: Int, val answerChars: Int)

    fun run(
        prompt: String,
        history: List<Message>,
        sse: Sse,
        who: Principal? = null,
        requestId: String = "",
    ): Outcome {
        val conversation = gemini.conversation(
            Gemini.declarations(tools.list()), cfg.systemPrompt)

        // History is replayed as plain text turns, without the tool traffic
        // that produced it. The model does not need to re-see which searches
        // ran three turns ago — it needs what was said — and replaying tool
        // calls would mean replaying their thought signatures, which are only
        // valid within the request that produced them.
        for (m in history) {
            if (m.content.isBlank()) continue
            when (m.role) {
                "user" -> conversation.addUser(m.content)
                "assistant" -> conversation.addAssistant(m.content)
            }
        }
        conversation.addUser(prompt)

        var round = 0
        var toolCalls = 0
        var answerChars = 0
        while (true) {
            val turn = conversation.stream { delta ->
                answerChars += delta.length
                sse.send("token", JSONObject().put("text", delta))
            }

            if (turn.calls.isEmpty()) {
                // A final answer — or a turn that stopped for a reason the
                // reader needs, which looks identical from here unless it is
                // asked about. A safety block or a length cut-off otherwise
                // arrives as a bubble that is empty or ends mid-sentence, with
                // nothing to say why.
                turn.trouble()?.let { why ->
                    log.info("turn ended on {}: {}", turn.finishReason, why)
                    sse.send("error", JSONObject().put("message", why))
                }
                break
            }

            if (++round > cfg.maxToolRounds) {
                sse.send("error", JSONObject().put("message",
                    "stopped after ${cfg.maxToolRounds} rounds of tool calls without an answer"))
                break
            }

            val results = turn.calls.map { call ->
                sse.send("tool_call", JSONObject().put("name", call.name).put("args", call.args))
                toolCalls++
                val outcome = tools.call(call.name, call.args, who, requestId)
                val ok = !outcome.optBoolean("isError", false)
                val text = textOf(outcome)

                sse.send("tool_result", JSONObject()
                    .put("name", call.name)
                    .put("ok", ok)
                    .apply { if (ok) passagesOf(call.name, text)?.let { put("results", it) } })

                if (ok) text else "ERROR: $text"
            }
            conversation.addToolResults(turn.calls, results)
        }

        sse.send("done", JSONObject())
        return Outcome(toolCalls, round, answerChars)
    }

    /** An MCP tool result is `{content:[{type,text}], isError}` — flatten the
     *  text parts into the one string the model is given. */
    private fun textOf(result: JSONObject): String {
        val content = result.optJSONArray("content") ?: return ""
        return buildString {
            for (i in 0 until content.length()) {
                val c = content.optJSONObject(i) ?: continue
                if (c.optString("type") == "text") append(c.optString("text"))
            }
        }
    }

    /**
     * The passages a successful `search` returned, trimmed to what the sources
     * panel shows. Null for any other tool, or if the result isn't the JSON
     * `search` always produces — a malformed result costs the panel its
     * contents, never the answer.
     */
    private fun passagesOf(toolName: String, result: String): JSONArray? {
        if (toolName != "search") return null
        return try {
            val results = JSONObject(result).getJSONArray("results")
            JSONArray().apply {
                for (i in 0 until results.length()) {
                    val r = results.getJSONObject(i)
                    put(JSONObject()
                        .put("chunk_id", r.getLong("chunk_id"))
                        .put("title", r.optString("title"))
                        .put("path", r.optString("path"))
                        .put("score", r.optDouble("score", 0.0))
                        .put("text", r.optString("text").take(400)))
                }
            }
        } catch (e: Exception) {
            log.debug("could not parse search results for the sources panel: {}", e.toString())
            null
        }
    }
}
