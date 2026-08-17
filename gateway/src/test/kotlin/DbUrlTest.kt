import java.sql.SQLException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The two things in Db's companion that run before there is a database.
 *
 * [Db.toJdbcUrl] is a translation between two URL dialects, and it is the first
 * thing that happens at startup — get it wrong and the gateway does not connect
 * at all, with a driver message that names neither the host nor the reason.
 * [Db.rootCause] is what turns that driver message into the line an operator
 * actually reads.
 */
class DbUrlTest {

    @Test
    fun `the compose-style URL becomes the form the driver wants`() {
        assertEquals(
            "jdbc:postgresql://postgres:5432/seshat?user=seshat&password=seshat",
            Db.toJdbcUrl("postgresql://seshat:seshat@postgres:5432/seshat"),
        )
    }

    @Test
    fun `a URL that is already JDBC is left exactly alone`() {
        // Someone who sets DATABASE_URL to the JDBC form has said what they
        // want; re-parsing it would only be a chance to lose a parameter.
        val given = "jdbc:postgresql://db.internal:5432/seshat?ssl=true&user=me"
        assertEquals(given, Db.toJdbcUrl(given))
    }

    @Test
    fun `the password is percent-encoded, not pasted into the query string`() {
        // A generated password containing '+' or '=' is the trap here: dropped
        // into a query string raw, the driver reads '+' as a space and
        // authentication fails against a password that IS correct.
        val url = Db.toJdbcUrl("postgresql://seshat:pa+ss=word@postgres:5432/seshat")

        assertTrue("password=pa%2Bss%3Dword" in url, url)
        assertFalse("pa+ss=word" in url, "the raw password must not reach the query string")
    }

    @Test
    fun `a username with an at sign or a space survives the round trip`() {
        val url = Db.toJdbcUrl("postgresql://svc%40seshat:pw@postgres:5432/seshat")

        assertTrue("user=svc%2540seshat" in url || "user=svc%40seshat" in url, url)
    }

    @Test
    fun `a missing port and a missing database name fall back to the defaults`() {
        assertEquals("jdbc:postgresql://postgres:5432/seshat", Db.toJdbcUrl("postgresql://postgres"))
        assertEquals("jdbc:postgresql://postgres:5432/seshat", Db.toJdbcUrl("postgresql://postgres/"))
    }

    @Test
    fun `a non-default port is kept`() {
        assertTrue(":5433/" in Db.toJdbcUrl("postgresql://postgres:5433/seshat"))
    }

    @Test
    fun `a missing host falls back to the compose service name`() {
        assertTrue("//postgres:5432/" in Db.toJdbcUrl("postgresql:///seshat"))
    }

    @Test
    fun `credentials-free URLs get no query string at all`() {
        val url = Db.toJdbcUrl("postgresql://postgres:5432/seshat")
        assertFalse("?" in url, url)
    }

    @Test
    fun `an existing query string is carried through alongside the credentials`() {
        val url = Db.toJdbcUrl("postgresql://u:p@postgres:5432/seshat?sslmode=require")

        assertTrue("sslmode=require" in url, url)
        assertTrue("user=u" in url, url)
        assertEquals(1, url.count { it == '?' }, "one query string, not two")
    }

    // ---- rootCause ----------------------------------------------------------

    @Test
    fun `a lone exception reports itself, class and message`() {
        assertEquals(
            "IllegalStateException: nothing is listening",
            Db.rootCause(IllegalStateException("nothing is listening")),
        )
    }

    @Test
    fun `a wrapped failure reports the wrapper's message and the real cause`() {
        // Which is the whole point: Hikari's "Failed to initialize pool" says
        // nothing, and the SQLException underneath it says everything.
        val e = RuntimeException(
            "Failed to initialize pool",
            SQLException("Connection to postgres:5432 refused"),
        )

        assertEquals(
            "Failed to initialize pool — caused by SQLException: Connection to postgres:5432 refused",
            Db.rootCause(e),
        )
    }

    @Test
    fun `a message-less cause still reads as a sentence`() {
        val text = Db.rootCause(RuntimeException("no detail here", NullPointerException()))
        assertTrue("(no detail)" in text, text)
    }

    @Test
    fun `a self-referential cause chain terminates`() {
        // Some drivers really do return themselves from getCause(), and an
        // unguarded walk down the chain never comes back.
        class Loop : RuntimeException("round and round") {
            override val cause: Throwable get() = this
        }

        assertEquals("Loop: round and round", Db.rootCause(Loop()))
    }

    @Test
    fun `an absurdly deep chain is cut off rather than walked forever`() {
        var e: Throwable = SQLException("the actual fault")
        repeat(40) { e = RuntimeException("layer $it", e) }

        val text = Db.rootCause(e)
        assertTrue("layer 39" in text, text)
        assertTrue("caused by" in text, text)
    }
}
