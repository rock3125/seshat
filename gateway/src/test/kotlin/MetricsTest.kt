import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * What a scrape actually contains.
 *
 * The label functions are pinned next door in MetricsLabelTest; this is the
 * other half — that recording a thing puts a sample in the exposition, under
 * the name and labels the dashboards and alert rules are written against.
 * Renaming a series or dropping a label is a one-word change here and a silent
 * failure everywhere else: the panel goes blank, the alert stops firing, and
 * nothing in this process looks wrong.
 *
 * Also that METRICS_ENABLED=off means off. It is the switch someone reaches for
 * when they suspect the metrics themselves, so it has to be inert rather than
 * nearly inert.
 */
class MetricsTest {

    private fun scrape(metrics: Metrics) = String(metrics.scrape())

    /** The sample lines for one series, labels and value included. */
    private fun samples(text: String, name: String) =
        text.lines().map { it.trim() }.filter { it.startsWith(name) }

    /** The value of an unlabelled series. */
    private fun value(text: String, name: String): Double? =
        samples(text, "$name ").firstOrNull()?.substringAfterLast(' ')?.toDouble()

    @Test
    fun `a served request is counted by route, method and status class`() {
        val metrics = Metrics(true)

        metrics.request("/config", "GET", 200, 0.004)

        assertEquals(
            listOf("""seshat_http_requests_total{method="GET",route="/config",status="2xx"} 1.0"""),
            samples(scrape(metrics), "seshat_http_requests_total"),
        )
    }

    @Test
    fun `the exact status code never reaches a label`() {
        // The cardinality rule, asserted through the exposition rather than
        // through routeLabel: five codes on one route are three series, one per
        // class, and no code appears anywhere in them.
        val metrics = Metrics(true)

        metrics.request("/chunk/:id", "GET", 200, 0.01)
        metrics.request("/chunk/:id", "GET", 404, 0.01)
        metrics.request("/chunk/:id", "GET", 410, 0.01)
        metrics.request("/chunk/:id", "GET", 422, 0.01)
        metrics.request("/chunk/:id", "GET", 503, 0.01)

        val lines = samples(scrape(metrics), "seshat_http_requests_total")
        assertEquals(3, lines.size, lines.toString())
        assertTrue(lines.any { """status="2xx"""" in it && it.endsWith(" 1.0") })
        assertTrue(lines.any { """status="4xx"""" in it && it.endsWith(" 3.0") })
        assertTrue(lines.any { """status="5xx"""" in it && it.endsWith(" 1.0") })
        for (code in listOf("200", "404", "410", "422", "503")) {
            assertFalse(lines.any { code in it }, "the code $code became a label value")
        }
    }

    @Test
    fun `request duration lands in the histogram for its route`() {
        val metrics = Metrics(true)

        metrics.request("/chat", "POST", 200, 42.0)

        val text = scrape(metrics)
        assertTrue("""seshat_http_request_seconds_count{route="/chat"} 1""" in text)
        assertTrue("""seshat_http_request_seconds_sum{route="/chat"} 42.0""" in text)
        // A long SSE turn must not land in +Inf and turn the p95 into nothing:
        // the buckets run to two minutes, so 42 seconds is inside 60.
        assertTrue("""seshat_http_request_seconds_bucket{route="/chat",le="60.0"} 1""" in text)
        assertTrue("""seshat_http_request_seconds_bucket{route="/chat",le="30.0"} 0""" in text)
    }

    @Test
    fun `the work of a turn is counted where the dashboards look for it`() {
        val metrics = Metrics(true)

        metrics.chatTurn("ok", 3.2)
        metrics.chatTurn("blocked", 1.0)
        metrics.toolCall("search", true, 0.4)
        metrics.toolCall("load_chunk", false, 0.01)
        metrics.embedCall("rate_limited")
        metrics.documentIndexed("indexed")

        val text = scrape(metrics)
        assertTrue("""seshat_chat_turns_total{outcome="ok"} 1.0""" in text)
        assertTrue("""seshat_chat_turns_total{outcome="blocked"} 1.0""" in text)
        assertTrue("""seshat_tool_calls_total{outcome="ok",tool="search"} 1.0""" in text)
        assertTrue("""seshat_tool_calls_total{outcome="error",tool="load_chunk"} 1.0""" in text)
        // Rate limiting is counted apart from other failures on purpose: it is a
        // tuning signal (EMBED_CONCURRENCY too high), not a fault.
        assertTrue("""seshat_embed_requests_total{outcome="rate_limited"} 1.0""" in text)
        assertTrue("""seshat_documents_indexed_total{outcome="indexed"} 1.0""" in text)
    }

    @Test
    fun `counters accumulate and gauges hold their last value`() {
        val metrics = Metrics(true)

        metrics.auditWritten(3)
        metrics.auditWritten(2)
        metrics.auditDropped()
        metrics.auditQueueDepth(7)
        metrics.auditQueueDepth(1)      // drained
        metrics.corpus(12, 340)
        metrics.corpus(13, 400)         // a document was added

        val text = scrape(metrics)
        assertTrue("seshat_audit_records_total 5.0" in text, "counters add up")
        assertTrue("seshat_audit_dropped_total 1.0" in text)
        assertTrue("seshat_audit_queue_depth 1.0" in text, "a gauge is the latest value, not a sum")
        assertTrue("seshat_corpus_documents 13.0" in text)
        assertTrue("seshat_corpus_chunks 400.0" in text)
    }

    @Test
    fun `readiness is a gauge with two values, and both are reported`() {
        // What an alert rule fires on when the gateway is up but its stores are
        // not, so 0 has to be exposed rather than absent.
        val metrics = Metrics(true)

        metrics.ready(true)
        assertTrue("seshat_ready 1.0" in scrape(metrics))

        metrics.ready(false)
        assertTrue("seshat_ready 0.0" in scrape(metrics))
    }

    @Test
    fun `the JVM's own metrics come for free, and say which JVM`() {
        val text = scrape(Metrics(true))

        assertTrue(text.lines().any { it.startsWith("jvm_memory_used_bytes") }, "no JVM metrics")
        assertTrue(text.lines().any { it.startsWith("seshat_build_info{jvm=") })
    }

    @Test
    fun `disabled means nothing is recorded, and scraping still works`() {
        val metrics = Metrics(false)

        metrics.request("/config", "GET", 200, 0.004)
        metrics.chatTurn("ok", 1.0)
        metrics.toolCall("search", true, 0.4)
        metrics.embedCall("ok")
        metrics.documentIndexed("indexed")
        metrics.auditWritten(1)
        metrics.auditDropped()
        metrics.auditQueueDepth(9)
        metrics.corpus(12, 340)
        metrics.ready(true)

        // Nothing was recorded, and nothing expensive was registered. The
        // labelled series have no samples at all, because a sample only exists
        // once some label combination has been observed.
        val text = scrape(metrics)
        assertEquals(emptyList(), samples(text, "seshat_http_requests_total"))
        assertEquals(emptyList(), samples(text, "seshat_chat_turns_total"))
        assertEquals(emptyList(), samples(text, "seshat_tool_calls_total"))
        assertEquals(emptyList(), samples(text, "seshat_embed_requests_total"))
        assertFalse(text.lines().any { it.startsWith("jvm_") }, "JVM metrics registered while off")
        assertFalse("seshat_build_info" in text)

        // The UNLABELLED series are a different matter: a counter with no labels
        // has exactly one sample from the moment it is constructed, so these are
        // present and sitting at zero rather than absent. Worth knowing and not
        // worth fixing — /metrics answers 404 while METRICS_ENABLED=off (see
        // Http.metricsRoute), so nothing ever scrapes them.
        assertEquals(0.0, value(text, "seshat_audit_records_total"))
        assertEquals(0.0, value(text, "seshat_corpus_documents"))
        assertEquals(0.0, value(text, "seshat_ready"), "ready() was called with true")
    }

    @Test
    fun `the content type is the format Prometheus scrapes`() {
        // Sent verbatim on /metrics. A wrong one here is a scrape that parses as
        // nothing, with no error at either end.
        assertTrue(Metrics(true).contentType.startsWith("text/plain"))
        assertTrue("version=0.0.4" in Metrics(true).contentType)
    }

    @Test
    fun `two registries do not share state`() {
        // Each Metrics owns its registry rather than using the library's global
        // default — which is what makes these tests independent, and what stops
        // a second instance in a test JVM throwing on duplicate registration.
        val first = Metrics(true)
        val second = Metrics(true)

        first.request("/config", "GET", 200, 0.001)

        assertEquals(emptyList(), samples(scrape(second), "seshat_http_requests_total"))
    }
}
