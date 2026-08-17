import io.prometheus.metrics.config.EscapingScheme
import io.prometheus.metrics.core.metrics.Counter
import io.prometheus.metrics.core.metrics.Gauge
import io.prometheus.metrics.core.metrics.GaugeWithCallback
import io.prometheus.metrics.core.metrics.Histogram
import io.prometheus.metrics.expositionformats.PrometheusTextFormatWriter
import io.prometheus.metrics.instrumentation.jvm.JvmMetrics
import io.prometheus.metrics.model.registry.PrometheusRegistry
import java.io.ByteArrayOutputStream

/**
 * What this process reports about itself, in Prometheus' text format.
 *
 * Everything here is application-level: requests, chat turns, tool calls,
 * embedding traffic, the audit queue. Container CPU and memory are cAdvisor's
 * job and are deliberately not duplicated — a gauge of our own heap is useful,
 * a gauge of our own cgroup is a second opinion nobody asked for.
 *
 * Registered on the gateway's existing HttpServer at `/metrics` rather than on
 * a second listener of the library's own, so there is exactly one port to
 * publish, one place to bind, and one thing to keep off the public proxy.
 *
 * ROUTE LABELS ARE BOUNDED. `/chunk/912` must be labelled `/chunk/:id`, never
 * with the id in it — a label whose value comes from the request path is an
 * unbounded cardinality explosion, and the first symptom is Prometheus falling
 * over rather than anything looking wrong here. [routeLabel] is the whole
 * defence and it works by allow-list.
 */
class Metrics(private val enabled: Boolean) {

    val registry: PrometheusRegistry = PrometheusRegistry()

    private val writer = PrometheusTextFormatWriter.create()

    // --- HTTP -----------------------------------------------------------------

    private val requests: Counter = Counter.builder()
        .name("seshat_http_requests_total")
        .help("HTTP requests served, by route, method and status class.")
        .labelNames("route", "method", "status")
        .register(registry)

    private val requestSeconds: Histogram = Histogram.builder()
        .name("seshat_http_request_seconds")
        .help("How long a request took, end to end.")
        // A chat turn is a long-lived SSE response and belongs in the same
        // histogram as a 3ms /config: the buckets run to two minutes so the
        // p95 of a turn is a number rather than +Inf.
        .classicUpperBounds(0.005, 0.025, 0.1, 0.5, 1.0, 2.5, 5.0, 10.0, 30.0, 60.0, 120.0)
        .labelNames("route")
        .register(registry)

    // --- the work -------------------------------------------------------------

    private val chatTurns: Counter = Counter.builder()
        .name("seshat_chat_turns_total")
        .help("Chat turns completed, by outcome.")
        .labelNames("outcome")
        .register(registry)

    private val chatSeconds: Histogram = Histogram.builder()
        .name("seshat_chat_turn_seconds")
        .help("How long a whole chat turn took, including every tool round.")
        .classicUpperBounds(0.5, 1.0, 2.5, 5.0, 10.0, 20.0, 40.0, 80.0, 160.0)
        .register(registry)

    private val toolCalls: Counter = Counter.builder()
        .name("seshat_tool_calls_total")
        .help("Tool invocations, by tool name and outcome.")
        .labelNames("tool", "outcome")
        .register(registry)

    private val toolSeconds: Histogram = Histogram.builder()
        .name("seshat_tool_seconds")
        .help("How long one tool call took.")
        .classicUpperBounds(0.01, 0.05, 0.1, 0.5, 1.0, 2.5, 5.0, 15.0)
        .labelNames("tool")
        .register(registry)

    private val embedRequests: Counter = Counter.builder()
        .name("seshat_embed_requests_total")
        .help("Calls to the embedding API, by outcome. `rate_limited` is the one to watch.")
        .labelNames("outcome")
        .register(registry)

    private val indexed: Counter = Counter.builder()
        .name("seshat_documents_indexed_total")
        .help("Documents indexed, by outcome.")
        .labelNames("outcome")
        .register(registry)

    // --- the audit trail ------------------------------------------------------

    private val auditWritten: Counter = Counter.builder()
        .name("seshat_audit_records_total")
        .help("Audit records durably written.")
        .register(registry)

    /**
     * Audit records dropped because the writer queue was full.
     *
     * This exists because a silent gap in an audit trail is the worst possible
     * failure: the table looks fine and is incomplete. Anything above zero is
     * either load worth knowing about or a reason to set AUDIT_BLOCKING=on. The
     * Admin tab surfaces it as a banner, not as a chart.
     */
    private val auditDropped: Counter = Counter.builder()
        .name("seshat_audit_dropped_total")
        .help("Audit records dropped because the writer queue was full.")
        .register(registry)

    private val auditQueue: Gauge = Gauge.builder()
        .name("seshat_audit_queue_depth")
        .help("Audit records waiting to be written.")
        .register(registry)

    // --- the corpus -----------------------------------------------------------

    /** Set by whoever last counted, rather than queried on scrape: a scrape must
     *  never be able to put load on Postgres, or a monitoring outage and a
     *  database outage become the same incident. */
    private val corpusDocuments: Gauge = Gauge.builder()
        .name("seshat_corpus_documents")
        .help("Documents currently indexed.")
        .register(registry)

    private val corpusChunks: Gauge = Gauge.builder()
        .name("seshat_corpus_chunks")
        .help("Chunks currently indexed.")
        .register(registry)

    private val ready: Gauge = Gauge.builder()
        .name("seshat_ready")
        .help("1 when the gateway has reached both backends at least once.")
        .register(registry)

    init {
        if (enabled) {
            // Heap, GC, threads, class loading, process CPU and open files.
            JvmMetrics.builder().register(registry)
            GaugeWithCallback.builder()
                .name("seshat_build_info")
                .help("Always 1; the labels carry the version.")
                .labelNames("jvm")
                .callback { it.call(1.0, System.getProperty("java.version") ?: "unknown") }
                .register(registry)
        }
    }

    // --- recording ------------------------------------------------------------

    fun request(route: String, method: String, status: Int, seconds: Double) {
        if (!enabled) return
        requests.labelValues(route, method, statusClass(status)).inc()
        requestSeconds.labelValues(route).observe(seconds)
    }

    fun chatTurn(outcome: String, seconds: Double) {
        if (!enabled) return
        chatTurns.labelValues(outcome).inc()
        chatSeconds.observe(seconds)
    }

    fun toolCall(tool: String, ok: Boolean, seconds: Double) {
        if (!enabled) return
        toolCalls.labelValues(tool, if (ok) "ok" else "error").inc()
        toolSeconds.labelValues(tool).observe(seconds)
    }

    fun embedCall(outcome: String) {
        if (enabled) embedRequests.labelValues(outcome).inc()
    }

    fun documentIndexed(outcome: String) {
        if (enabled) indexed.labelValues(outcome).inc()
    }

    fun auditWritten(n: Int) {
        if (enabled) auditWritten.inc(n.toDouble())
    }

    fun auditDropped() {
        if (enabled) auditDropped.inc()
    }

    fun auditQueueDepth(depth: Int) {
        if (enabled) auditQueue.set(depth.toDouble())
    }

    fun corpus(documents: Long, chunks: Long) {
        if (!enabled) return
        corpusDocuments.set(documents.toDouble())
        corpusChunks.set(chunks.toDouble())
    }

    fun ready(isReady: Boolean) {
        if (enabled) ready.set(if (isReady) 1.0 else 0.0)
    }

    // --- exposition -----------------------------------------------------------

    fun scrape(): ByteArray = ByteArrayOutputStream(16 * 1024).use { out ->
        writer.write(out, registry.scrape(), EscapingScheme.UNDERSCORE_ESCAPING)
        out.toByteArray()
    }

    val contentType: String get() = writer.contentType

    companion object {
        /** 2xx, 4xx, 5xx — not the exact code. Three values per route instead of
         *  a dozen, and nothing is lost: the exact status is in the log line and
         *  in the audit row, where it can be read next to what caused it. */
        fun statusClass(status: Int): String = when {
            status in 200..299 -> "2xx"
            status in 300..399 -> "3xx"
            status in 400..499 -> "4xx"
            status >= 500 -> "5xx"
            else -> "other"
        }

        /**
         * A request path reduced to a bounded label.
         *
         * By allow-list, and the default is the literal string "other" rather
         * than the path — because the whole point is that no caller can invent a
         * new label value. A route added later and forgotten shows up as
         * `other`, which is a gap in a dashboard; the alternative is a caller
         * hitting `/a`, `/b`, `/c`… and growing the time series without limit.
         */
        fun routeLabel(path: String): String = when {
            path == "/health" -> "/health"
            path == "/metrics" -> "/metrics"
            path == "/config" -> "/config"
            path == "/chat" -> "/chat"
            path == "/mcp" -> "/mcp"
            path == "/upload" -> "/upload"
            path == "/reindex" -> "/reindex"
            path.startsWith("/chunk/") -> "/chunk/:id"
            path.startsWith("/admin/") -> "/admin/" + path.removePrefix("/admin/")
                .substringBefore('/').take(20).filter { it.isLetterOrDigit() || it == '.' }
            else -> "other"
        }
    }
}
