import org.json.JSONArray
import org.json.JSONObject
import org.slf4j.LoggerFactory
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/** One function call the model asked for. */
data class ToolCall(val name: String, val args: JSONObject)

/**
 * What one streamed model turn produced.
 *
 * [finishReason] is Gemini's own last word on why the turn ended — "STOP" when
 * it simply finished, and otherwise the thing that stopped it. It is carried
 * out of here rather than acted on here, because whether a truncated answer is
 * worth an error message is the agent loop's decision, not the transport's.
 */
data class Turn(val text: String, val calls: List<ToolCall>, val finishReason: String = "") {

    /** A reason to tell the reader about, phrased for them, or null when the
     *  turn ended normally. STOP and an absent reason are both normal; a turn
     *  that stopped to call a tool reports nothing at all. */
    fun trouble(): String? = when (finishReason.uppercase()) {
        "", "STOP", "FINISH_REASON_UNSPECIFIED" -> null
        "MAX_TOKENS" ->
            "the answer hit the model's length limit and was cut off mid-thought"
        "SAFETY", "PROHIBITED_CONTENT", "BLOCKLIST", "SPII" ->
            "the model stopped: its safety filters blocked the response ($finishReason)"
        "RECITATION" ->
            "the model stopped because the answer reproduced its training data too closely"
        "MALFORMED_FUNCTION_CALL" ->
            "the model produced a tool call this gateway could not parse"
        else -> "the model stopped early ($finishReason)"
    }
}

/**
 * Google Gemini over `streamGenerateContent?alt=sse`.
 *
 * Two details are load-bearing and neither is obvious:
 *
 *   thought signatures  Gemini 3.x attaches a `thoughtSignature` to each
 *                       functionCall part and rejects the follow-up request
 *                       (HTTP 400) unless that part is echoed back verbatim.
 *                       So [Conversation] records the raw response parts, not
 *                       the parsed calls — the parsed [ToolCall] is for the
 *                       agent's benefit, the stored part is for Gemini's.
 *   result pairing      Gemini function calls carry no ids. A turn's
 *                       functionResponse parts are matched to its
 *                       functionCall parts BY ORDER, so tool results must be
 *                       appended in the same order the calls arrived.
 *
 * The JDK client's `BodyHandlers.ofLines()` yields response lines as the server
 * flushes them, which is exactly what SSE needs and costs no dependency.
 */
class Gemini(private val cfg: Config) {
    private val log = LoggerFactory.getLogger("Gemini")
    private val http: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(20))
        .build()

    fun conversation(tools: JSONArray, systemPrompt: String) = Conversation(tools, systemPrompt)

    inner class Conversation(private val tools: JSONArray, private val systemPrompt: String) {
        private val contents = JSONArray()

        fun addUser(text: String) {
            contents.put(content("user", JSONArray().put(JSONObject().put("text", text))))
        }

        fun addAssistant(text: String) {
            contents.put(content("model", JSONArray().put(JSONObject().put("text", text))))
        }

        /** Stream one turn, emitting answer text through [onText] as it arrives. */
        fun stream(onText: (String) -> Unit): Turn {
            val streamed = send(onText)
            val text = streamed.text
            val parts = streamed.calls

            // The model's turn goes into history as the model sent it: text
            // parts plus the untouched functionCall parts with their thought
            // signatures.
            val modelParts = JSONArray()
            if (text.isNotEmpty()) modelParts.put(JSONObject().put("text", text))
            for (p in parts) modelParts.put(p)
            if (modelParts.length() > 0) contents.put(content("model", modelParts))

            return Turn(
                text,
                parts.map { part ->
                    val fc = part.getJSONObject("functionCall")
                    ToolCall(fc.optString("name"), fc.optJSONObject("args") ?: JSONObject())
                },
                streamed.finishReason,
            )
        }

        /** One turn off the wire: the text, the raw functionCall parts, and why
         *  the model stopped. */
        private inner class Streamed(
            val text: String,
            val calls: List<JSONObject>,
            val finishReason: String,
        )

        /** Append tool results — same order as the calls (see the class note). */
        fun addToolResults(calls: List<ToolCall>, results: List<String>) {
            val parts = JSONArray()
            for (i in calls.indices) {
                parts.put(JSONObject().put("functionResponse", JSONObject()
                    .put("name", calls[i].name)
                    .put("response", JSONObject().put("content", results[i]))))
            }
            contents.put(content("user", parts))
        }

        private fun content(role: String, parts: JSONArray) =
            JSONObject().put("role", role).put("parts", parts)

        private fun send(onText: (String) -> Unit): Streamed {
            check(cfg.geminiApiKey.isNotBlank()) {
                "GEMINI_API_KEY is not set on the gateway — chat is unavailable until it is"
            }
            val body = JSONObject().put("contents", contents)
            if (systemPrompt.isNotBlank()) {
                body.put("systemInstruction", JSONObject()
                    .put("parts", JSONArray().put(JSONObject().put("text", systemPrompt))))
            }
            if (tools.length() > 0) {
                body.put("tools", JSONArray().put(JSONObject().put("functionDeclarations", tools)))
            }

            val url = "${cfg.geminiBaseUrl}/v1beta/models/${cfg.geminiModel}:streamGenerateContent?alt=sse"
            val req = HttpRequest.newBuilder(URI(url))
                .header("Content-Type", "application/json")
                .header("x-goog-api-key", cfg.geminiApiKey)
                .timeout(Duration.ofMinutes(5))
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                .build()

            val res = http.send(req, HttpResponse.BodyHandlers.ofLines())
            if (res.statusCode() !in 200..299) {
                val err = res.body().reduce("") { a, b -> a + b }
                throw IllegalStateException(
                    "Gemini HTTP ${res.statusCode()} for model '${cfg.geminiModel}': ${err.take(500)}")
            }

            val text = StringBuilder()
            val calls = ArrayList<JSONObject>()
            var finishReason = ""
            var blockReason = ""
            res.body().forEach { raw ->
                val line = raw.trim()
                if (!line.startsWith("data:")) return@forEach
                val json = line.removePrefix("data:").trim()
                if (json.isEmpty() || json == "[DONE]") return@forEach

                val chunk = try {
                    JSONObject(json)
                } catch (e: Exception) {
                    log.debug("unparseable chunk: {}", json.take(120)); return@forEach
                }
                chunk.optJSONObject("error")?.let {
                    throw IllegalStateException("Gemini error: ${it.optString("message")}")
                }
                // A prompt refused outright never reaches `candidates` at all —
                // the only thing on the wire is promptFeedback, which is why an
                // answer to a blocked question used to arrive as silence.
                chunk.optJSONObject("promptFeedback")?.optString("blockReason")
                    ?.takeIf { it.isNotBlank() }?.let { blockReason = it }

                val candidate = chunk.optJSONArray("candidates")?.optJSONObject(0)
                    ?: return@forEach
                // Last one wins: the reason arrives on the final chunk of the
                // turn, and earlier chunks carry none.
                candidate.optString("finishReason").takeIf { it.isNotBlank() }
                    ?.let { finishReason = it }

                val parts = candidate.optJSONObject("content")?.optJSONArray("parts")
                    ?: return@forEach
                for (i in 0 until parts.length()) {
                    val part = parts.getJSONObject(i)
                    if (part.has("functionCall")) calls.add(part)
                    val delta = part.optString("text", "")
                    if (delta.isNotEmpty()) {
                        text.append(delta)
                        onText(delta)
                    }
                }
            }
            if (blockReason.isNotBlank()) {
                throw IllegalStateException(
                    "Gemini refused the prompt before answering (blockReason: $blockReason)")
            }
            // A turn that produced nothing whatever is not a valid answer, and
            // returning it as one shows the reader an empty bubble and no
            // reason for it.
            if (text.isEmpty() && calls.isEmpty() && finishReason.isBlank()) {
                throw IllegalStateException(
                    "Gemini returned an empty response for model '${cfg.geminiModel}'")
            }
            return Streamed(text.toString(), calls, finishReason)
        }
    }

    companion object {
        /**
         * MCP tool schemas as Gemini function declarations.
         *
         * Gemini's schema dialect is OpenAPI-ish and narrower than JSON Schema:
         * it rejects `additionalProperties`, `$schema`, `default` and a few
         * other keywords outright rather than ignoring them. Stripping the
         * unsupported keys here means the tool definitions in Tools.kt stay
         * plain, correct JSON Schema for MCP clients, and only this one
         * translation has to know about the dialect.
         */
        fun declarations(tools: JSONArray): JSONArray {
            val out = JSONArray()
            for (i in 0 until tools.length()) {
                val t = tools.getJSONObject(i)
                out.put(JSONObject()
                    .put("name", t.getString("name"))
                    .put("description", t.optString("description"))
                    .put("parameters", sanitise(t.optJSONObject("inputSchema") ?: JSONObject())))
            }
            return out
        }

        private val UNSUPPORTED = setOf(
            "additionalProperties", "\$schema", "default", "examples",
            "exclusiveMinimum", "exclusiveMaximum",
        )

        private fun sanitise(schema: JSONObject): JSONObject {
            val out = JSONObject()
            for (key in schema.keys()) {
                if (key in UNSUPPORTED) continue
                when (val v = schema.get(key)) {
                    is JSONObject -> out.put(key, sanitise(v))
                    is JSONArray -> out.put(key, JSONArray().also { arr ->
                        for (j in 0 until v.length()) {
                            val e = v.get(j)
                            arr.put(if (e is JSONObject) sanitise(e) else e)
                        }
                    })
                    else -> out.put(key, v)
                }
            }
            return out
        }
    }
}
