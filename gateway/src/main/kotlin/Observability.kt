import org.json.JSONArray
import org.json.JSONObject
import org.slf4j.LoggerFactory
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.time.Instant

/**
 * One severity ladder for nine services that each have their own.
 *
 * Postgres says LOG/WARNING/FATAL, nginx's access log says nothing at all,
 * Keycloak and the gateway say INFO/WARN/ERROR. Alloy maps them all onto these
 * five on the way into Loki, and the audit trail's outcomes sit beside them so
 * the UI can drive both views with one control.
 */
enum class Level {
    DEBUG, INFO, WARN, ERROR, FATAL;

    val wire: String get() = name.lowercase()

    companion object {
        fun from(s: String?): Level? =
            entries.firstOrNull { it.name.equals(s?.trim(), ignoreCase = true) }

        /**
         * Everything at [floor] or above.
         *
         * A FLOOR, not an equality — `level=warn` returns warnings *and* errors
         * *and* fatals. This is the one rule in the whole log view worth being
         * loud about: a reader filters to `warn` precisely because they are
         * looking for trouble, and a filter that then hides the errors is worse
         * than no filter. Tested, and it should stay tested.
         */
        fun atOrAbove(floor: Level): List<Level> = entries.filter { it.ordinal >= floor.ordinal }
    }
}

/** What the log view asks for — the same four dimensions [Audit.Filter] takes. */
data class LogFilter(
    val level: Level? = null,
    val services: List<String> = emptyList(),
    val users: List<String> = emptyList(),
    val requestId: String = "",
    val q: String = "",
    val from: Instant,
    val to: Instant,
    val limit: Int = 200,
)

/**
 * LogQL construction. Pure, and separated from the client so it can be tested
 * without a Loki.
 *
 * The browser never sends LogQL. It sends `level=warn&user=rock&q=timeout` and
 * this builds the query — which means user text reaches a query language, which
 * means escaping is the entire security story of this file. Loki's matchers are
 * Go string literals and RE2 regexes; neither supports `\Q…\E`, so both escapes
 * are done by hand below and both are tested against a value containing a
 * quote, a backslash and a newline.
 */
object LogQl {

    /** The stream selector plus every filter, as one query. */
    fun query(f: LogFilter): String {
        val selectors = mutableListOf<String>()

        // `service` and `level` are the only Loki LABELS — see the Alloy config.
        // Everything else is structured metadata, filtered after the selector.
        if (f.services.isNotEmpty()) {
            selectors += "service=~${quote(alternation(f.services))}"
        } else {
            // A stream selector may not be empty, and `{}` is a parse error. A
            // matcher that every stream satisfies is the idiomatic "everything".
            selectors += "service=~\".+\""
        }
        f.level?.let {
            selectors += "level=~${quote(alternation(Level.atOrAbove(it).map(Level::wire)))}"
        }

        val sb = StringBuilder("{").append(selectors.joinToString(", ")).append("}")

        // Structured-metadata filters. These come before the line filter so Loki
        // discards whole entries on a label test before it looks at any text.
        if (f.users.isNotEmpty()) sb.append(" | user=~").append(quote(alternation(f.users)))
        if (f.requestId.isNotBlank()) sb.append(" | req=").append(quote(clean(f.requestId)))

        // The line filter last: a substring test against the raw line, which is
        // the JSON the service emitted, so it matches a field name or a value
        // alike. Deliberately `|=` (contains) rather than a regex — a reader
        // typing into a search box means "contains", and a stray `(` should not
        // be a 400.
        if (f.q.isNotBlank()) sb.append(" |= ").append(quote(f.q.trim().take(500)))

        return sb.toString()
    }

    /**
     * A Go/Loki double-quoted string literal.
     *
     * Backslash first — escaping the quote before the backslash would double the
     * escape character it just inserted, which is the classic way to write an
     * escaping function that appears to work.
     */
    fun quote(s: String): String = buildString {
        append('"')
        for (c in s) when (c) {
            '\\' -> append("\\\\")
            '"' -> append("\\\"")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> if (c.code < 0x20) append("\\x%02x".format(c.code)) else append(c)
        }
        append('"')
    }

    /** `a|b|c`, with every RE2 metacharacter in each value escaped. */
    fun alternation(values: List<String>): String =
        values.filter { it.isNotBlank() }.joinToString("|") { escapeRe(clean(it)) }

    /** RE2 has no \Q…\E, so every metacharacter is escaped by hand. */
    fun escapeRe(s: String): String = buildString {
        for (c in s) {
            if (c in ".^$*+?()[]{}|\\/-") append('\\')
            append(c)
        }
    }

    /** A label value from a filter control: bounded, and with the characters
     *  that have no business in a service or user name removed outright. */
    fun clean(s: String): String =
        s.trim().take(128).filter { it.isLetterOrDigit() || it in "-_.:@/" }
}

/** One named metrics panel. The UI asks for it by name and never composes
 *  PromQL of its own. */
data class Panel(
    val name: String,
    val title: String,
    /** How the UI should format a value: `percent`, `bytes`, `rate`, `seconds`,
     *  `count`. */
    val unit: String,
    val query: String,
    /** The label whose value names each series in the legend. */
    val legend: String,
)

/**
 * The whole metrics vocabulary, written here in Kotlin rather than sent from
 * React.
 *
 * This is the cost of not shipping Grafana, paid once: twelve queries, each of
 * which is a decision about what is worth looking at. The benefit is that the
 * Admin tab cannot be turned into an arbitrary PromQL console by anything the
 * browser sends, and that every panel keeps working when the UI is rebuilt.
 *
 * `$W` is substituted with a rate window chosen from the requested time range —
 * a `[5m]` rate over a 7-day window is a sawtooth, and a `[1h]` rate over 15
 * minutes is a flat line.
 */
object Panels {

    val all: List<Panel> = listOf(
        Panel("cpu", "CPU by service", "rate",
            """sum by (service) (rate(container_cpu_usage_seconds_total{service!=""}[${'$'}W]))""",
            "service"),
        Panel("memory", "Memory by service", "bytes",
            """sum by (service) (container_memory_working_set_bytes{service!=""})""",
            "service"),
        Panel("http_rate", "Requests per second", "rate",
            """sum by (route) (rate(seshat_http_requests_total[${'$'}W]))""",
            "route"),
        Panel("http_errors", "Failed requests per second", "rate",
            """sum by (route) (rate(seshat_http_requests_total{status=~"4xx|5xx"}[${'$'}W]))""",
            "route"),
        Panel("http_latency", "Request latency, p95", "seconds",
            """histogram_quantile(0.95, sum by (le, route) """ +
                """(rate(seshat_http_request_seconds_bucket[${'$'}W])))""",
            "route"),
        Panel("chat_turns", "Chat turns per second", "rate",
            """sum by (outcome) (rate(seshat_chat_turns_total[${'$'}W]))""",
            "outcome"),
        Panel("chat_latency", "Chat turn duration, p95", "seconds",
            """histogram_quantile(0.95, sum by (le) (rate(seshat_chat_turn_seconds_bucket[${'$'}W])))""",
            ""),
        Panel("tool_calls", "Tool calls per second", "rate",
            """sum by (tool) (rate(seshat_tool_calls_total[${'$'}W]))""",
            "tool"),
        // Worth its own panel rather than a slice of tool_calls: a free-tier key
        // answers 429 under concurrency, the retry backoff then makes the whole
        // indexing pass slower, and this is the only place that shows it.
        Panel("embed_calls", "Embedding calls per second, by outcome", "rate",
            """sum by (outcome) (rate(seshat_embed_requests_total[${'$'}W]))""",
            "outcome"),
        // A NAME REGEX, NOT `or`. PromQL's `or` is a set union that keeps the
        // left operand wherever the label sets MATCH — and these two both carry
        // no labels at all, so `a or b` silently returns only `a`. The panel
        // renders, the query is valid, and half the data is missing with
        // nothing to say so. Same trap in jvm_threads and audit_queue below.
        Panel("corpus", "Corpus size", "count",
            """{__name__=~"seshat_corpus_(documents|chunks)"}""",
            "__name__"),
        // `jvm_memory_used_bytes{area="heap"}`, and both halves of that are
        // worth pinning down: the prometheus-metrics-instrumentation-jvm names
        // are NOT Micrometer's (no `jvm_memory_type`, no `jvm_memory_pool_name`),
        // and guessing produces a panel that renders, queries cleanly and
        // returns nothing at all — which looks like an idle service rather than
        // a wrong query. Confirm against `curl gateway:8090/metrics` if this
        // ever goes blank.
        Panel("jvm_heap", "Gateway memory in use", "bytes",
            """sum by (area) (jvm_memory_used_bytes)""",
            "area"),
        Panel("jvm_threads", "Gateway threads", "count",
            """{__name__=~"jvm_threads_(current|daemon)"}""",
            "__name__"),
        Panel("jvm_gc", "Time in garbage collection", "rate",
            """sum by (gc) (rate(jvm_gc_collection_seconds_sum[${'$'}W]))""",
            "gc"),
        // `label_replace` because `rate()` DROPS `__name__`: after it, these two
        // counters have identical (empty) label sets, and Prometheus refuses the
        // union with "vector cannot contain metrics with the same labelset".
        // Naming each side gives them something to differ by — and gives the
        // legend something to say besides the raw metric name.
        //
        // `dropped` above zero is the one number on this page that means
        // something is wrong: the audit trail has a gap. See AUDIT_BLOCKING.
        Panel("audit_queue", "Audit records written and dropped", "rate",
            """label_replace(sum(rate(seshat_audit_records_total[${'$'}W])), """ +
                """"kind", "written", "", "") or """ +
                """label_replace(sum(rate(seshat_audit_dropped_total[${'$'}W])), """ +
                """"kind", "dropped", "", "")""",
            "kind"),
    )

    private val byName = all.associateBy { it.name }

    fun get(name: String): Panel? = byName[name.trim()]

    /**
     * The rate window for a range, and the step between points.
     *
     * The window is at least four scrape intervals so a rate always has samples
     * to work with, and the step aims at ~180 points — enough to see shape,
     * few enough that the response is small and the sparkline does not have to
     * decimate it.
     */
    fun window(seconds: Long): String = when {
        seconds <= 900 -> "1m"
        seconds <= 3 * 3600 -> "5m"
        seconds <= 24 * 3600 -> "15m"
        seconds <= 7 * 24 * 3600 -> "1h"
        else -> "6h"
    }

    fun step(seconds: Long): Long = maxOf(15L, seconds / 180)
}

/**
 * The clients for Loki and Prometheus, and the normalisation of what they
 * return into the one record shape the Admin UI reads.
 *
 * Both upstream URLs come from configuration and nothing in a request can
 * change them. That is deliberate and it is the difference between a log proxy
 * and an SSRF endpoint wearing an admin badge: the caller chooses the filter,
 * never the host.
 */
class Observability(private val cfg: Config) {

    private val log = LoggerFactory.getLogger("Observability")

    private val http: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5))
        .build()

    class Unavailable(message: String) : Exception(message)

    // --- logs -----------------------------------------------------------------

    /**
     * A page of log records, newest first.
     *
     * Returns the query it ran alongside the records. The Admin UI shows it —
     * an administrator looking at a log view should be able to see exactly what
     * was asked, both to trust the answer and to reproduce it in Grafana later.
     */
    fun logs(f: LogFilter): JSONObject {
        val loki = cfg.lokiUrl.ifBlank { throw Unavailable(NO_LOKI) }
        val query = LogQl.query(f)
        val url = "$loki/loki/api/v1/query_range" +
            "?query=${enc(query)}" +
            "&start=${f.from.toEpochMilli() * 1_000_000}" +
            "&end=${f.to.toEpochMilli() * 1_000_000}" +
            "&limit=${f.limit.coerceIn(1, MAX_LOG_LIMIT)}" +
            "&direction=backward"

        val body = get(url, "Loki")
        val streams = body.optJSONObject("data")?.optJSONArray("result") ?: JSONArray()

        val records = ArrayList<JSONObject>()
        for (i in 0 until streams.length()) {
            val stream = streams.optJSONObject(i) ?: continue
            val labels = stream.optJSONObject("stream") ?: JSONObject()
            val values = stream.optJSONArray("values") ?: continue
            for (j in 0 until values.length()) {
                val entry = values.optJSONArray(j) ?: continue
                records.add(record(labels, entry))
            }
        }
        // Loki returns one array per stream, each internally ordered; across
        // streams they interleave. Sorting here is what makes "newest first"
        // true of the page rather than of each stream in it.
        records.sortByDescending { it.optString("ts") }

        return JSONObject()
            .put("query", query)
            .put("count", records.size)
            .put("records", JSONArray(records))
    }

    /**
     * One Loki entry as the record the UI renders.
     *
     * The line is JSON for every service we configure (see §5 of the plan), so
     * its fields are lifted out here — a second, independent parse from Alloy's.
     * That redundancy is on purpose: if the Alloy pipeline's `stage.json` ever
     * regresses, the log view keeps showing structured records instead of going
     * quietly blank, and the only thing lost is the ability to FILTER on those
     * fields server-side.
     */
    private fun record(labels: JSONObject, entry: JSONArray): JSONObject {
        val nanos = entry.optString(0).toLongOrNull() ?: 0L
        val line = entry.optString(1)
        val metadata = entry.optJSONObject(2) ?: JSONObject()

        val parsed = runCatching { JSONObject(line) }.getOrNull()
        val fields = JSONObject()
        var structured = false

        if (parsed != null && parsed.length() > 0) {
            structured = true
            for (k in parsed.keySet()) if (k !in LIFTED) fields.put(k, parsed.opt(k))
        }
        for (k in metadata.keySet()) fields.put(k, metadata.opt(k))
        for (k in labels.keySet()) if (k !in LABEL_KEYS) fields.put(k, labels.opt(k))

        val service = labels.optString("service").ifBlank { fields.optString("service") }
        val level = Level.from(labels.optString("level"))
            ?: Level.from(parsed?.optString("level"))
            ?: if (structured) Level.INFO else null

        return JSONObject()
            .put("ts", Instant.ofEpochMilli(nanos / 1_000_000).toString())
            .put("service", service.ifBlank { "unknown" })
            .put("level", level?.wire ?: "info")
            .put("msg", message(parsed) ?: line)
            .put("user", fields.optString("user"))
            .put("req", fields.optString("req"))
            // Marked, not guessed at. cAdvisor logs through glog and nginx's
            // error log is not JSON either; both render as a plain message with
            // this flag set, rather than as a record with invented fields.
            .put("format", if (structured) "json" else "unstructured")
            .put("fields", fields)
    }

    /**
     * The human-readable part of a structured line, wherever the service put it.
     *
     * Three services, three spellings. The gateway says `msg`; Keycloak says
     * `message`; Qdrant is tracing-rs and NESTS it as `fields.message`, which is
     * the one that matters — a reader who only checks the top level gets an
     * empty message and a log view full of blank rows that read as a bug in the
     * UI. Falling back to the whole line is the last resort and is always
     * readable, just wide.
     */
    private fun message(parsed: JSONObject?): String? {
        if (parsed == null) return null
        parsed.optString("msg").takeIf { it.isNotBlank() }?.let { return it }
        parsed.optString("message").takeIf { it.isNotBlank() }?.let { return it }
        return parsed.optJSONObject("fields")?.optString("message")?.takeIf { it.isNotBlank() }
    }

    /** The values the filter dropdowns offer, straight out of Loki. */
    fun logFacets(from: Instant, to: Instant): JSONObject {
        val loki = cfg.lokiUrl.ifBlank { throw Unavailable(NO_LOKI) }
        fun values(label: String): List<String> = runCatching {
            val url = "$loki/loki/api/v1/label/$label/values" +
                "?start=${from.toEpochMilli() * 1_000_000}&end=${to.toEpochMilli() * 1_000_000}"
            val arr = get(url, "Loki").optJSONArray("data") ?: JSONArray()
            (0 until arr.length()).map { arr.getString(it) }.sorted()
        }.getOrElse {
            log.debug("could not read Loki label '{}': {}", label, it.toString())
            emptyList()
        }
        return JSONObject()
            .put("services", values("service"))
            .put("levels", Level.entries.map { it.wire })
    }

    // --- metrics --------------------------------------------------------------

    /** One named panel over a time range, as a series-per-legend-value. */
    fun panel(panel: Panel, from: Instant, to: Instant): JSONObject {
        val prom = cfg.prometheusUrl.ifBlank { throw Unavailable(NO_PROMETHEUS) }
        val seconds = maxOf(60L, to.epochSecond - from.epochSecond)
        val query = panel.query.replace("\$W", Panels.window(seconds))
        val url = "$prom/api/v1/query_range" +
            "?query=${enc(query)}" +
            "&start=${from.epochSecond}&end=${to.epochSecond}&step=${Panels.step(seconds)}"

        val result = get(url, "Prometheus").optJSONObject("data")?.optJSONArray("result")
            ?: JSONArray()

        val series = JSONArray()
        for (i in 0 until result.length()) {
            val s = result.optJSONObject(i) ?: continue
            val metric = s.optJSONObject("metric") ?: JSONObject()
            val values = s.optJSONArray("values") ?: JSONArray()
            val points = JSONArray()
            for (j in 0 until values.length()) {
                val p = values.optJSONArray(j) ?: continue
                val v = p.optString(1).toDoubleOrNull()
                // Prometheus renders a gap as the literal "NaN". Passed through
                // as a number it becomes `null` in JSON (org.json refuses
                // non-finite doubles) and the sparkline draws a line straight
                // across the outage — so gaps are kept as explicit nulls.
                points.put(JSONArray()
                    .put(p.optLong(0))
                    .put(if (v == null || v.isNaN() || v.isInfinite()) JSONObject.NULL else v))
            }
            series.put(JSONObject()
                .put("name", metric.optString(panel.legend).ifBlank {
                    metric.optString("__name__").ifBlank { panel.title }
                })
                .put("labels", metric)
                .put("points", points))
        }

        return JSONObject()
            .put("panel", panel.name)
            .put("title", panel.title)
            .put("unit", panel.unit)
            .put("query", query)
            .put("series", series)
    }

    /** Which scrape targets Prometheus can currently reach — the Services tab. */
    fun services(): JSONObject {
        val prom = cfg.prometheusUrl.ifBlank { throw Unavailable(NO_PROMETHEUS) }
        val result = get("$prom/api/v1/query?query=${enc("up")}", "Prometheus")
            .optJSONObject("data")?.optJSONArray("result") ?: JSONArray()

        val out = JSONArray()
        for (i in 0 until result.length()) {
            val s = result.optJSONObject(i) ?: continue
            val metric = s.optJSONObject("metric") ?: JSONObject()
            val value = s.optJSONArray("value")?.optString(1)?.toDoubleOrNull() ?: 0.0
            out.put(JSONObject()
                .put("job", metric.optString("job"))
                .put("instance", metric.optString("instance"))
                .put("up", value >= 1.0))
        }
        return JSONObject().put("targets", out)
    }

    // --- plumbing -------------------------------------------------------------

    private fun get(url: String, upstream: String): JSONObject {
        val req = HttpRequest.newBuilder(URI.create(url))
            .timeout(Duration.ofSeconds(UPSTREAM_TIMEOUT_SECONDS))
            .header("Accept", "application/json")
            .GET().build()
        val res = try {
            http.send(req, HttpResponse.BodyHandlers.ofString())
        } catch (e: Exception) {
            // Distinguished from a 500 on purpose: an absent Loki is an
            // operational fact the Admin tab can state plainly, not a bug in
            // the gateway for the reader to go looking for.
            throw Unavailable("$upstream is not reachable — ${e.javaClass.simpleName}")
        }
        if (res.statusCode() !in 200..299) {
            // Upstream error text is quoted back because it is genuinely useful
            // ("parse error at line 1: unexpected }") and the caller is already
            // an administrator.
            throw Unavailable("$upstream returned HTTP ${res.statusCode()}: " +
                res.body().take(400).replace('\n', ' '))
        }
        return runCatching { JSONObject(res.body()) }
            .getOrElse { throw Unavailable("$upstream returned a body that is not JSON") }
    }

    private fun enc(s: String) = URLEncoder.encode(s, Charsets.UTF_8)

    companion object {
        const val MAX_LOG_LIMIT = 1000
        private const val UPSTREAM_TIMEOUT_SECONDS = 15L

        private const val NO_LOKI =
            "log search is not configured on this deployment — LOKI_URL is unset. " +
                "Start the stack with `docker compose --profile observe up -d`."
        private const val NO_PROMETHEUS =
            "metrics are not configured on this deployment — PROMETHEUS_URL is unset. " +
                "Start the stack with `docker compose --profile observe up -d`."

        /** Fields promoted to top-level columns, so they are not repeated in the
         *  expandable detail beneath them. */
        private val LIFTED = setOf("ts", "level", "msg", "message", "service")
        private val LABEL_KEYS = setOf("service", "level")
    }
}
