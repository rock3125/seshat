import org.json.JSONObject
import org.junit.jupiter.api.AfterAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The MCP envelope: everything `POST /mcp` answers that does not touch a store.
 *
 * This is the surface an editor speaks to, and JSON-RPC has two rules that are
 * easy to break and impossible to notice from here: a NOTIFICATION (no `id`)
 * must never be answered, and a request must be answered with the id it was
 * sent with, unchanged. Get either wrong and a client either hangs waiting for
 * a reply it will never match, or drops the connection over an unsolicited one.
 *
 * The third rule is MCP's rather than JSON-RPC's: a tool that fails returns a
 * RESULT carrying `isError`, not a protocol error — because the failure is
 * something the model is meant to read and act on, and a transport error is not
 * addressed to the model at all.
 *
 * The handshake, the catalogue and every argument-validation refusal are reached
 * before Postgres or Qdrant is consulted, which is what makes them unit
 * testable; the two stores are constructed but never connect (Hikari is
 * configured to start without Postgres, and the gRPC channel dials lazily).
 */
class ToolsRpcTest {

    private fun rpc(body: JSONObject, who: Principal? = null) = tools.rpc(body, who)

    private fun request(method: String, id: Any? = 1, params: JSONObject? = null): JSONObject =
        JSONObject().put("jsonrpc", "2.0").put("method", method).also {
            if (id != null) it.put("id", id)
            if (params != null) it.put("params", params)
        }

    /** The tool result inside a `tools/call` reply. */
    private fun toolResult(name: String, arguments: JSONObject): JSONObject =
        rpc(request("tools/call", params = JSONObject()
            .put("name", name)
            .put("arguments", arguments)))!!
            .getJSONObject("result")

    private fun textOf(result: JSONObject): String =
        result.getJSONArray("content").getJSONObject(0).getString("text")

    // ---- the handshake ------------------------------------------------------

    @Test
    fun `initialize answers with the catalogue capability and how to use it`() {
        val result = rpc(request("initialize"))!!.getJSONObject("result")

        assertTrue(result.getJSONObject("capabilities").has("tools"))
        assertEquals("seshat-library", result.getJSONObject("serverInfo").getString("name"))
        // The instructions are the only prompt an MCP client gets, so the rule
        // that makes the answers checkable belongs in them.
        assertTrue("chunk_id" in result.getString("instructions"))
    }

    @Test
    fun `initialize echoes the client's protocol version, or names its own`() {
        // Answering with a version the client did not ask for is how a handshake
        // fails on a client stricter than this one.
        val echoed = rpc(request("initialize",
            params = JSONObject().put("protocolVersion", "2025-06-18")))!!

        assertEquals("2025-06-18", echoed.getJSONObject("result").getString("protocolVersion"))
        assertEquals(
            Tools.PROTOCOL_VERSION,
            rpc(request("initialize"))!!.getJSONObject("result").getString("protocolVersion"),
        )
    }

    @Test
    fun `ping answers`() {
        // Some clients use it as a liveness check between calls.
        val reply = rpc(request("ping"))!!

        assertEquals("2.0", reply.getString("jsonrpc"))
        assertTrue(reply.getJSONObject("result").isEmpty)
    }

    // ---- the catalogue ------------------------------------------------------

    @Test
    fun `both tools are advertised, each with a schema`() {
        val listed = rpc(request("tools/list"))!!.getJSONObject("result").getJSONArray("tools")
        val byName = (0 until listed.length()).associate { i ->
            val t = listed.getJSONObject(i)
            t.getString("name") to t
        }

        assertEquals(setOf("search", "load_chunk"), byName.keys)
        for ((name, tool) in byName) {
            assertTrue(tool.getString("description").isNotBlank(), "$name has no description")
            val schema = tool.getJSONObject("inputSchema")
            assertEquals("object", schema.getString("type"), name)
            assertTrue(schema.getJSONObject("properties").length() > 0, name)
        }
        assertEquals(
            listOf("query"),
            byName.getValue("search").getJSONObject("inputSchema").getJSONArray("required").toList(),
        )
    }

    // ---- ids and notifications ----------------------------------------------

    @Test
    fun `a request is answered with the id it carried, whatever its type`() {
        assertEquals(7, rpc(request("ping", id = 7))!!.get("id"))
        assertEquals("abc", rpc(request("ping", id = "abc"))!!.get("id"))
    }

    @Test
    fun `the notifications a client actually sends are never answered`() {
        assertNull(rpc(request("notifications/initialized", id = null)))
        assertNull(rpc(request("notifications/cancelled", id = null)))
    }

    @Test
    fun `a KNOWN method sent without an id is still answered — a deviation`() {
        // Documenting what this does, not endorsing it. JSON-RPC 2.0 says a
        // request with no `id` is a notification and MUST NOT be replied to;
        // only the unknown-method branch checks that here, so `ping`,
        // `initialize`, `tools/list` and `tools/call` reply with `"id": null`.
        //
        // It has never bitten because no client sends any of those as a
        // notification — there is nothing to gain by asking for the catalogue
        // and declining to hear the answer. Left as it is rather than changed
        // under cover of a test: making `ok()` return null when there is no id
        // is the one-line fix, and it is a behaviour change, not test coverage.
        val answered = rpc(request("ping", id = null))!!

        assertTrue(answered.isNull("id"))
        assertTrue(answered.getJSONObject("result").isEmpty)
    }

    @Test
    fun `an explicit null id is a notification too, not an id of null`() {
        val withNullId = JSONObject()
            .put("jsonrpc", "2.0")
            .put("method", "no/such/method")
            .put("id", JSONObject.NULL)

        assertNull(rpc(withNullId))
    }

    @Test
    fun `a message with no method at all is ignored`() {
        assertNull(rpc(JSONObject().put("jsonrpc", "2.0").put("id", 1)))
    }

    @Test
    fun `an unknown method is a method-not-found error naming the method`() {
        val error = rpc(request("resources/list"))!!.getJSONObject("error")

        assertEquals(-32601, error.getInt("code"))
        assertTrue("resources/list" in error.getString("message"), error.toString())
    }

    @Test
    fun `an unknown notification gets no error either`() {
        // Answering a notification with an error is still answering it.
        assertNull(rpc(request("resources/list", id = null)))
    }

    @Test
    fun `an error carries the id it was sent, and a real null when there was none`() {
        assertEquals(9, Tools.error(9, -32603, "internal error").get("id"))
        // JSON-RPC wants the member present with a null value, not absent.
        val anonymous = Tools.error(null, -32700, "parse error")
        assertTrue(anonymous.has("id"))
        assertEquals(JSONObject.NULL, anonymous.get("id"))
        assertEquals("2.0", anonymous.getString("jsonrpc"))
    }

    // ---- tool failures are results, not protocol errors ---------------------

    @Test
    fun `an unknown tool is reported to the model, not to the transport`() {
        val reply = rpc(request("tools/call", params = JSONObject()
            .put("name", "delete_everything")
            .put("arguments", JSONObject())))!!

        assertTrue(!reply.has("error"), "a tool failure must not become a JSON-RPC error")
        val result = reply.getJSONObject("result")
        assertTrue(result.getBoolean("isError"))
        // The message tells the model what it could have called instead.
        assertTrue("search" in textOf(result) && "load_chunk" in textOf(result))
    }

    @Test
    fun `a search with no query says which argument is missing`() {
        val result = toolResult("search", JSONObject())

        assertTrue(result.getBoolean("isError"))
        assertTrue("query" in textOf(result), textOf(result))
    }

    @Test
    fun `a blank query is as missing as no query`() {
        assertTrue(toolResult("search", JSONObject().put("query", "   ")).getBoolean("isError"))
    }

    @Test
    fun `an invalid mode comes back as a usable instruction`() {
        // The model reads this and retries, so the refusal has to list what
        // would have worked rather than just saying no.
        val result = toolResult("search", JSONObject().put("query", "obstruction").put("mode", "fuzzy"))

        assertTrue(result.getBoolean("isError"))
        for (valid in listOf("hybrid", "dense", "keyword")) {
            assertTrue(valid in textOf(result), "should name '$valid': ${textOf(result)}")
        }
    }

    @Test
    fun `load_chunk without a chunk id says so`() {
        val result = toolResult("load_chunk", JSONObject())

        assertTrue(result.getBoolean("isError"))
        assertTrue("chunk_id" in textOf(result), textOf(result))
    }

    @Test
    fun `every tool result is text content, error or not`() {
        // The shape MCP clients render. An error that came back in some other
        // shape would be shown to the reader as nothing at all.
        val result = toolResult("search", JSONObject())
        val content = result.getJSONArray("content").getJSONObject(0)

        assertEquals("text", content.getString("type"))
        assertTrue(content.getString("text").isNotBlank())
    }

    private companion object {
        /** One set of collaborators for the whole class. Neither store is ever
         *  reached by the paths under test — see the class comment. */
        val cfg: Config = Config.fromEnv().copy(geminiApiKey = "")
        val db = Db(cfg)
        val store = Store(cfg)
        val tools = Tools(cfg, db, store, Embeddings(cfg))

        @AfterAll
        @JvmStatic
        fun releaseThePools() {
            db.close()
            store.close()
        }
    }
}
