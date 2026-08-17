import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The two label functions, which exist to bound Prometheus cardinality.
 *
 * Both take something a caller controls — a request path, a status code — and
 * reduce it to one of a fixed set of values. That reduction IS the safety
 * property: a label value taken from the raw path means anyone who can reach
 * the gateway can create unbounded time series just by requesting `/a`, `/b`,
 * `/c`…, and the first symptom is the metrics endpoint, and then the scraper,
 * falling over. Worth pinning, because the natural "improvement" to any of
 * these functions is to pass the path through.
 */
class MetricsLabelTest {

    @Test
    fun `a status code becomes its class, not itself`() {
        assertEquals("2xx", Metrics.statusClass(200))
        assertEquals("2xx", Metrics.statusClass(204))
        assertEquals("2xx", Metrics.statusClass(299))
        assertEquals("3xx", Metrics.statusClass(304))
        assertEquals("4xx", Metrics.statusClass(401))
        assertEquals("4xx", Metrics.statusClass(413))
        assertEquals("5xx", Metrics.statusClass(500))
        assertEquals("5xx", Metrics.statusClass(503))
    }

    @Test
    fun `a code from outside the ranges is named, not passed through`() {
        // 1xx is not a response this gateway sends, and 0 is what a handler that
        // died before writing a status leaves behind.
        assertEquals("other", Metrics.statusClass(100))
        assertEquals("other", Metrics.statusClass(0))
        assertEquals("other", Metrics.statusClass(-1))
    }

    @Test
    fun `every status code in the whole range maps to one of five values`() {
        val classes = (-100..1000).map { Metrics.statusClass(it) }.toSet()
        assertEquals(setOf("2xx", "3xx", "4xx", "5xx", "other"), classes)
    }

    @Test
    fun `the fixed routes keep their own names`() {
        for (route in listOf("/health", "/metrics", "/config", "/chat", "/mcp", "/upload", "/reindex")) {
            assertEquals(route, Metrics.routeLabel(route))
        }
    }

    @Test
    fun `a chunk id is collapsed into the pattern`() {
        // One series for the route, not one per paragraph in the corpus.
        assertEquals("/chunk/:id", Metrics.routeLabel("/chunk/1"))
        assertEquals("/chunk/:id", Metrics.routeLabel("/chunk/1409"))
        assertEquals("/chunk/:id", Metrics.routeLabel("/chunk/not-a-number"))
    }

    @Test
    fun `an admin route keeps its first segment and drops the rest`() {
        assertEquals("/admin/audit", Metrics.routeLabel("/admin/audit"))
        assertEquals("/admin/audit", Metrics.routeLabel("/admin/audit/export"))
        assertEquals("/admin/logs", Metrics.routeLabel("/admin/logs"))
        assertEquals("/admin/metrics", Metrics.routeLabel("/admin/metrics/panel/http"))
    }

    @Test
    fun `an unknown path is called other rather than becoming a label of its own`() {
        for (path in listOf(
            "/",
            "/wp-login.php",
            "/../../etc/passwd",
            "/chunk",           // no trailing slash: not the chunk route
            "/config/",         // not an exact match
            "/CHAT",            // routing is case-sensitive, and so is this
        )) {
            assertEquals("other", Metrics.routeLabel(path), "path '$path'")
        }
    }

    @Test
    fun `a hostile admin path cannot smuggle a long or strange label value`() {
        // The one branch that keeps part of the caller's path. It is capped at
        // twenty characters and stripped to letters, digits and dots — so a
        // megabyte of junk, or a newline that would break the exposition
        // format, cannot get into a metric name.
        assertEquals("/admin/" + "a".repeat(20), Metrics.routeLabel("/admin/" + "a".repeat(5_000)))

        // Note what the strip does and does not promise: the punctuation goes,
        // the letters and digits around it stay — so this comes back as
        // `/admin/auditevil199`, which is ugly and harmless. Bounded and
        // syntactically inert is the requirement, not readable.
        for (path in listOf(
            "/admin/audit\n{evil=\"1\"} 99",
            "/admin/a b",
            "/admin/a\"b",
            "/admin/a}b",
            "/admin/a=b",
            "/admin/" + "x".repeat(400),
        )) {
            val label = Metrics.routeLabel(path)
            assertTrue(label.length <= "/admin/".length + 20, "'$path' produced ${label.length} chars")
            assertTrue(
                label.removePrefix("/admin/").all { it.isLetterOrDigit() || it == '.' },
                "'$path' produced '$label'",
            )
        }
    }

    @Test
    fun `the number of distinct labels is bounded whatever is thrown at it`() {
        // The property, stated directly: a thousand different unknown paths must
        // not be a thousand different series.
        val labels = (1..1_000).map { Metrics.routeLabel("/probe-$it/x/y") }.toSet()
        assertEquals(setOf("other"), labels)
    }
}
