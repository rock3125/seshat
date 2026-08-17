import org.json.JSONArray
import org.json.JSONObject
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * What goes into the audit trail, and — more to the point — what does not.
 *
 * The trail is read by administrators. A secret that reaches it has been
 * disclosed to everyone holding a role, permanently, in a table with ninety
 * days of retention. [Audit.sanitize] is the one thing standing between a
 * careless route and that outcome, so it is tested harder than its size
 * suggests.
 */
class AuditTest {

    // --- redaction -------------------------------------------------------------

    @Test
    fun `a bearer token is redacted whatever the key is called`() {
        val detail = JSONObject()
            .put("Authorization", "Bearer eyJhbGciOi.secret.parts")
            .put("access_token", "eyJhbGciOi")
            .put("refresh_token", "eyJhbGciOi")
            .put("api_key", "AIzaSyC-not-a-real-key")
            .put("user_password", "hunter2")
            .put("session_cookie", "sid=abc")
            .put("client_secret", "shh")

        val clean = Audit.sanitize(detail).toString()

        assertFalse("eyJhbGciOi" in clean, "a token reached the audit trail: $clean")
        assertFalse("hunter2" in clean)
        assertFalse("AIzaSyC" in clean)
        assertFalse("shh" in clean)
        // Redacted, not dropped: the row should still say that a field was
        // there, or reading the trail gives a misleading picture of the request.
        assertEquals(7, Audit.sanitize(detail).length())
        assertContains(clean, "[redacted]")
    }

    @Test
    fun `redaction matches case-insensitively and on substrings`() {
        val clean = Audit.sanitize(JSONObject()
            .put("GEMINI_API_KEY", "x")
            .put("theUserPassword", "y")
            .put("BEARER", "z"))
        assertEquals("[redacted]", clean.getString("GEMINI_API_KEY"))
        assertEquals("[redacted]", clean.getString("theUserPassword"))
        assertEquals("[redacted]", clean.getString("BEARER"))
    }

    @Test
    fun `an ordinary field is left exactly as it was`() {
        val clean = Audit.sanitize(JSONObject()
            .put("mode", "hybrid").put("hits", 6).put("truncated", false))
        assertEquals("hybrid", clean.getString("mode"))
        assertEquals(6, clean.getInt("hits"))
        assertFalse(clean.getBoolean("truncated"))
    }

    @Test
    fun `redaction reaches into a nested object`() {
        val clean = Audit.sanitize(JSONObject()
            .put("request", JSONObject().put("authorization", "Bearer abc").put("path", "/upload")))
        val nested = clean.getJSONObject("request")
        assertEquals("[redacted]", nested.getString("authorization"))
        assertEquals("/upload", nested.getString("path"))
    }

    // --- bounds ----------------------------------------------------------------

    @Test
    fun `a huge string is truncated and says so`() {
        val clean = Audit.sanitize(JSONObject().put("body", "x".repeat(100_000)))
        val kept = clean.getString("body")
        assertTrue(kept.length < 2_100, "a 100k string was kept whole")
        assertContains(kept, "100000 chars")
    }

    @Test
    fun `a detail object cannot carry an unbounded number of keys`() {
        val fat = JSONObject()
        repeat(500) { fat.put("k$it", it) }
        assertTrue(Audit.sanitize(fat).length() <= 40)
    }

    @Test
    fun `a null value is dropped rather than stored as the string null`() {
        val clean = Audit.sanitize(JSONObject().put("chunks", JSONObject.NULL).put("path", "a.md"))
        assertFalse(clean.has("chunks"))
        assertEquals("a.md", clean.getString("path"))
    }

    @Test
    fun `an array value survives intact`() {
        val clean = Audit.sanitize(JSONObject().put("roles", JSONArray(listOf("admin", "use-ui"))))
        assertEquals(2, clean.getJSONArray("roles").length())
    }

    // --- the prompt digest -----------------------------------------------------

    @Test
    fun `the same prompt always digests to the same value`() {
        assertEquals(Audit.digest("what did Ebers say about honey?"),
            Audit.digest("what did Ebers say about honey?"))
    }

    @Test
    fun `a different prompt digests differently, and neither is readable`() {
        val a = Audit.digest("what did Ebers say about honey?")
        val b = Audit.digest("what did Ebers say about beer?")
        assertFalse(a == b)
        assertEquals(16, a.length)
        assertTrue(a.all { it in "0123456789abcdef" })
        assertFalse("honey" in a)
    }

    @Test
    fun `an empty prompt still digests`() {
        assertEquals(16, Audit.digest("").length)
    }

    // --- the outcome vocabulary -----------------------------------------------

    @Test
    fun `outcomes parse from the wire form the UI sends`() {
        assertEquals(Audit.Outcome.OK, Audit.Outcome.from("ok"))
        assertEquals(Audit.Outcome.DENIED, Audit.Outcome.from("DENIED"))
        assertEquals(Audit.Outcome.ERROR, Audit.Outcome.from(" error "))
        assertEquals(null, Audit.Outcome.from("maybe"))
    }

    @Test
    fun `every action constant is in the list the filter dropdown offers`() {
        // A typo'd action name is a row nobody will ever find again, so the
        // vocabulary is fixed and this is what keeps it honest.
        val constants = listOf(
            Audit.SESSION_START, Audit.AUTH_DENIED, Audit.CHAT_TURN, Audit.TOOL_SEARCH,
            Audit.TOOL_LOAD_CHUNK, Audit.CHUNK_VIEW, Audit.CONFIG_READ, Audit.UPLOAD,
            Audit.REINDEX, Audit.MCP_CALL, Audit.ADMIN_AUDIT_READ, Audit.ADMIN_LOGS_QUERY,
            Audit.ADMIN_METRICS_QUERY, Audit.ADMIN_SERVICES_READ,
        )
        assertEquals(constants.sorted(), Audit.ACTIONS.sorted())
        assertEquals(Audit.ACTIONS.size, Audit.ACTIONS.distinct().size)
    }

    // --- identifiers -----------------------------------------------------------

    @Test
    fun `an identifier is bounded so a megabyte of query string is not an index probe`() {
        assertEquals(128, Audit.cleanIdentifier("x".repeat(10_000)).length)
        assertEquals("rock", Audit.cleanIdentifier("  rock  "))
    }
}
