import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The guards on the edges of the gateway: what it accepts as a credential, how
 * much replayed conversation it will carry, and what it makes of a model turn
 * that stopped for a reason.
 *
 * All three are pure functions on purpose — they are the parts of Http and
 * Gemini that have rules worth stating, and none of them needs a socket to say
 * what those rules are.
 */
class GatewayGuardsTest {

    // --- Authorization ---------------------------------------------------------

    @Test
    fun `reads a bearer token whatever the scheme's case`() {
        assertEquals("abc.def.ghi", Http.bearerToken("Bearer abc.def.ghi"))
        assertEquals("abc.def.ghi", Http.bearerToken("bearer abc.def.ghi"))
        // The regression this exists for: the guard matched case-insensitively
        // and the strip did not, so an uppercase scheme was accepted and then
        // handed on whole as if the words "BEARER " were part of the token.
        assertEquals("abc.def.ghi", Http.bearerToken("BEARER abc.def.ghi"))
        assertEquals("abc.def.ghi", Http.bearerToken("BeArEr abc.def.ghi"))
    }

    @Test
    fun `trims around the token and tolerates a padded header`() {
        assertEquals("abc", Http.bearerToken("  Bearer    abc   "))
    }

    @Test
    fun `refuses anything that is not a bearer credential`() {
        assertNull(Http.bearerToken(null))
        assertNull(Http.bearerToken(""))
        assertNull(Http.bearerToken("Bearer"))          // no space, no credential
        assertNull(Http.bearerToken("Bearer    "))      // scheme with nothing after it
        assertNull(Http.bearerToken("Basic dXNlcjpwdw=="))
        assertNull(Http.bearerToken("Bearerabc"))       // not the scheme, just a prefix
    }

    // --- what a role lets you do -----------------------------------------------

    private fun principal(vararg roles: String) =
        Principal("someone", "Someone", roles.toSet(), subject = "sub-1", sessionId = "sid-1")

    @Test
    fun `a plain user may use the app and nothing else`() {
        val who = principal(Principal.USE_UI)
        assertTrue(Principal.USE_UI in who.roles)
        assertFalse(who.isAdmin)
        assertFalse(who.mayAudit)
    }

    @Test
    fun `an admin may both change the corpus and read the trail`() {
        val who = principal(Principal.USE_UI, Principal.ADMIN)
        assertTrue(who.isAdmin)
        assertTrue(who.mayAudit)
    }

    @Test
    fun `an auditor may read the trail without being able to reindex`() {
        // The whole reason the two capabilities are separate: this account is a
        // role assignment in Keycloak, not a change here.
        val who = principal(Principal.USE_UI, Principal.OBSERVABILITY)
        assertFalse(who.isAdmin)
        assertTrue(who.mayAudit)
    }

    @Test
    fun `a role that merely looks like admin grants nothing`() {
        val who = principal(Principal.USE_UI, "administrator", "admin-ui", "ADMIN")
        assertFalse(who.isAdmin)
        assertFalse(who.mayAudit)
    }

    // --- replayed history ------------------------------------------------------

    private fun msg(content: String, role: String = "user") = Chat.Message(role, content)

    @Test
    fun `keeps a normal thread whole`() {
        val history = (1..10).map { msg("turn $it") }
        assertEquals(history, Http.trimHistory(history))
    }

    @Test
    fun `caps the number of messages, keeping the most recent`() {
        val history = (1..100).map { msg("turn $it") }
        val kept = Http.trimHistory(history)
        assertEquals(Http.MAX_HISTORY_MESSAGES, kept.size)
        // The turns nearest the question are the ones that carry its context.
        assertEquals("turn 100", kept.last().content)
        assertEquals("turn 61", kept.first().content)
    }

    @Test
    fun `caps the total size, keeping the most recent`() {
        val big = "x".repeat(Http.MAX_HISTORY_CHARS / 3)
        val history = listOf(msg("${big}1"), msg("${big}2"), msg("${big}3"), msg("${big}4"))
        val kept = Http.trimHistory(history)
        assertTrue(kept.sumOf { it.content.length } <= Http.MAX_HISTORY_CHARS)
        assertTrue(kept.isNotEmpty())
        assertEquals("${big}4", kept.last().content)
    }

    @Test
    fun `a single oversized message is dropped rather than sent`() {
        val kept = Http.trimHistory(listOf(msg("x".repeat(Http.MAX_HISTORY_CHARS + 1))))
        assertEquals(emptyList(), kept)
    }

    @Test
    fun `an empty history stays empty`() {
        assertEquals(emptyList(), Http.trimHistory(emptyList()))
    }

    // --- why a turn ended ------------------------------------------------------

    private fun turn(reason: String) = Turn("some text", emptyList(), reason)

    @Test
    fun `a turn that simply finished is not trouble`() {
        assertNull(turn("STOP").trouble())
        assertNull(turn("stop").trouble())
        assertNull(turn("").trouble())
        assertNull(turn("FINISH_REASON_UNSPECIFIED").trouble())
    }

    @Test
    fun `a blocked or truncated turn explains itself`() {
        assertTrue(turn("MAX_TOKENS").trouble()!!.contains("length limit"))
        assertTrue(turn("SAFETY").trouble()!!.contains("safety"))
        assertTrue(turn("PROHIBITED_CONTENT").trouble()!!.contains("safety"))
        assertTrue(turn("RECITATION").trouble()!!.contains("training data"))
        // An unrecognised reason still reaches the reader, carrying its own name.
        assertNotNull(turn("SOMETHING_NEW").trouble())
        assertTrue(turn("SOMETHING_NEW").trouble()!!.contains("SOMETHING_NEW"))
    }
}
