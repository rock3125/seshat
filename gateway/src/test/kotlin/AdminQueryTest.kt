import org.json.JSONObject
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The query builders behind the Admin tab.
 *
 * These are the seam where a filter someone typed into a browser becomes a
 * query language, which makes escaping the entire security story of the admin
 * API — and makes it testable without a Loki, a Prometheus or a Postgres, which
 * is what CI requires.
 */
class AdminQueryTest {

    private fun filter(
        level: Level? = null,
        services: List<String> = emptyList(),
        users: List<String> = emptyList(),
        q: String = "",
        req: String = "",
    ) = LogFilter(
        level = level, services = services, users = users, q = q, requestId = req,
        from = Instant.parse("2026-08-17T00:00:00Z"),
        to = Instant.parse("2026-08-17T01:00:00Z"),
    )

    // --- the severity floor ----------------------------------------------------

    @Test
    fun `a level filter is a floor, not an equality`() {
        // The whole reason this test exists: a reader filters to `warn` BECAUSE
        // they are looking for trouble. A filter that then hides the errors is
        // worse than no filter at all.
        assertEquals(
            listOf(Level.WARN, Level.ERROR, Level.FATAL),
            Level.atOrAbove(Level.WARN),
        )
        assertEquals(Level.entries, Level.atOrAbove(Level.DEBUG))
        assertEquals(listOf(Level.FATAL), Level.atOrAbove(Level.FATAL))
    }

    @Test
    fun `the floor reaches the query as an alternation of every level above it`() {
        val q = LogQl.query(filter(level = Level.WARN))
        assertContains(q, """level=~"warn|error|fatal"""")
        assertFalse("debug" in q)
        assertFalse("\"info\"" in q)
    }

    @Test
    fun `no level filter means no level matcher at all`() {
        assertFalse("level" in LogQl.query(filter()))
    }

    // --- escaping --------------------------------------------------------------

    @Test
    fun `a search term containing quotes and backslashes is escaped, not injected`() {
        val q = LogQl.query(filter(q = """he said "hi" \ then left"""))
        // The backslash is doubled and the quotes are escaped — so the literal
        // survives intact and none of it can close the string it sits in.
        assertContains(q, """|= "he said \"hi\" \\ then left"""")
    }

    @Test
    fun `a newline in a search term cannot break the query onto a second line`() {
        val q = LogQl.query(filter(q = "first\nsecond"))
        assertContains(q, """\n""")
        assertFalse('\n' in q)
    }

    @Test
    fun `escaping the backslash happens before escaping the quote`() {
        // Doing it the other way round doubles the escape character that the
        // quote escape just inserted — the classic way to write an escaping
        // function that appears to work on every input you happen to try.
        assertEquals(""""a\\b"""", LogQl.quote("""a\b"""))
        assertEquals(""""a\\\"b"""", LogQl.quote("""a\"b"""))
    }

    @Test
    fun `regex metacharacters in a service or user name are escaped`() {
        assertEquals("""a\.b""", LogQl.escapeRe("a.b"))
        assertEquals("""\.\^\$\*\+\?\(\)\[\]\{\}\|\\""", LogQl.escapeRe(""".^$*+?()[]{}|\"""))
    }

    @Test
    fun `a wildcard in a username cannot widen the filter to every user`() {
        // Two defences, in this order: clean() drops `*` because it has no
        // business in a username at all, and escapeRe() would have neutered it
        // if it had survived. What reaches Loki is a literal dot and nothing
        // else — narrower than what was asked for, which is the safe direction.
        val q = LogQl.query(filter(users = listOf(".*")))
        // TWO layers of escaping, and both are load-bearing. escapeRe turns the
        // dot into the regex `\.`, and quote then turns that backslash into
        // `\\` because the regex sits inside a Go string literal. What Loki
        // parses is the two-character regex `\.`, matching a literal dot.
        assertContains(q, """user=~"\\."""")
        assertFalse("*" in q)
    }

    @Test
    fun `a label value is stripped of characters that have no business in a name`() {
        assertEquals("gateway", LogQl.clean("""gate"way{}"""))
        assertEquals(128, LogQl.clean("x".repeat(500)).length)
    }

    // --- the shape of the query ------------------------------------------------

    @Test
    fun `with no service filter the selector still matches every stream`() {
        // `{}` is a LogQL parse error, so "everything" has to be spelled out.
        assertContains(LogQl.query(filter()), """{service=~".+"}""")
    }

    @Test
    fun `several services become one alternation inside the selector`() {
        val q = LogQl.query(filter(services = listOf("gateway", "ui")))
        assertContains(q, """{service=~"gateway|ui"}""")
    }

    @Test
    fun `a request id filters on structured metadata`() {
        assertContains(LogQl.query(filter(req = "abc123")), """| req="abc123"""")
    }

    @Test
    fun `label tests come before the line filter`() {
        val q = LogQl.query(filter(users = listOf("rock"), q = "timeout"))
        // Loki discards whole entries on the cheap test first; the expensive
        // substring scan only sees what survived.
        assertTrue(q.indexOf("user=~") < q.indexOf("|= "))
    }

    // --- metrics panels --------------------------------------------------------

    @Test
    fun `every panel is reachable by name and carries a rate window`() {
        for (p in Panels.all) {
            assertEquals(p, Panels.get(p.name), "panel ${p.name} is not resolvable by its name")
            assertTrue(p.title.isNotBlank())
            assertTrue(p.unit.isNotBlank())
        }
    }

    @Test
    fun `an unknown panel name resolves to nothing`() {
        assertEquals(null, Panels.get("../../etc/passwd"))
        assertEquals(null, Panels.get("""up} or {job="x"""))
    }

    @Test
    fun `the rate window widens with the range`() {
        assertEquals("1m", Panels.window(900))
        assertEquals("5m", Panels.window(3 * 3600))
        assertEquals("1h", Panels.window(7 * 24 * 3600))
        // ~180 points whatever the range, and never finer than 15s.
        assertEquals(15L, Panels.step(60))
        assertEquals(480L, Panels.step(24 * 3600))
    }

    // --- the audit filter ------------------------------------------------------

    @Test
    fun `an empty audit filter produces no where clause at all`() {
        val where = Audit.whereClause(Audit.Filter())
        assertEquals("", where.sql)
        assertTrue(where.args.isEmpty())
    }

    @Test
    fun `every audit filter value is a bind parameter`() {
        val where = Audit.whereClause(Audit.Filter(
            from = Instant.parse("2026-08-01T00:00:00Z"),
            users = listOf("rock", "guest"),
            actions = listOf(Audit.CHAT_TURN),
            outcomes = listOf(Audit.Outcome.DENIED),
            q = "secret",
        ))
        // The values themselves appear nowhere in the SQL — only placeholders.
        assertFalse("rock" in where.sql)
        assertFalse("secret" in where.sql)
        assertFalse(Audit.CHAT_TURN in where.sql)
        assertEquals(where.sql.count { it == '?' }, where.args.size)
    }

    @Test
    fun `a search term's LIKE wildcards are escaped so they match literally`() {
        val where = Audit.whereClause(Audit.Filter(q = "100%_off"))
        val like = where.args.filterIsInstance<String>().first { it.startsWith("%") }
        assertEquals("""%100\%\_off%""", like)
    }

    @Test
    fun `the cursor pages on timestamp AND id together`() {
        val where = Audit.whereClause(Audit.Filter(
            cursor = Audit.Cursor(Instant.parse("2026-08-17T00:00:00Z"), 42),
        ))
        // Two rows written in the same millisecond are ordinary; paging on the
        // timestamp alone would either repeat one or lose one.
        assertContains(where.sql, "(at, id) < (?, ?)")
        assertEquals(2, where.args.size)
    }

    @Test
    fun `a cursor round-trips through its wire form`() {
        val c = Audit.Cursor(Instant.ofEpochMilli(1_755_000_000_123), 987)
        assertEquals(c, Audit.Cursor.parse(c.toString()))
        assertEquals(null, Audit.Cursor.parse(null))
        assertEquals(null, Audit.Cursor.parse(""))
        assertEquals(null, Audit.Cursor.parse("nonsense"))
    }

    // --- CSV export ------------------------------------------------------------

    @Test
    fun `a log message containing commas and newlines stays one CSV row`() {
        val csv = Admin.csv(listOf("msg"), listOf(listOf("a, b\nc")))
        assertEquals("\"msg\"\r\n\"a, b\nc\"\r\n", csv)
    }

    @Test
    fun `a quote inside a CSV field is doubled`() {
        // say "hi"  ->  "say ""hi"""
        val csv = Admin.csv(listOf("h"), listOf(listOf("say \"hi\"")))
        assertContains(csv, "\"say \"\"hi\"\"\"")
    }

    @Test
    fun `a field that looks like a spreadsheet formula is defused`() {
        // Without the leading apostrophe this is code execution on the machine
        // of whoever opens the export.
        assertContains(
            Admin.csv(listOf("target"), listOf(listOf("""=cmd|'/c calc'!A0"""))),
            """"'=cmd""",
        )
    }
}
