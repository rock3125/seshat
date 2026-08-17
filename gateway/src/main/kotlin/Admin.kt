import org.json.JSONArray
import org.json.JSONObject
import org.slf4j.LoggerFactory
import java.time.Duration
import java.time.Instant
import java.time.format.DateTimeParseException

/**
 * The administrator's half of the gateway: the audit trail, the consolidated
 * logs, and the metrics panels.
 *
 * This is the logic; [Http] is the plumbing that gives it a status code. The
 * split keeps every route here a function from parameters to a JSON document,
 * which is what makes the query builders testable without a socket.
 *
 * **Logs and audit take the same filter.** Severity, who, when, what, and a
 * text search — one vocabulary, so the UI drives both views with one component
 * and an administrator who has learnt the filters once has learnt them for
 * both. The two differ in exactly one place: for logs `level` is a severity
 * floor, and for the audit trail it is an outcome, because an audit record is
 * not more or less severe, it either happened, was refused, or broke.
 *
 * **Nothing here composes a query the browser sent.** The caller chooses the
 * filter; LogQL and PromQL are built in Kotlin, from a fixed vocabulary, and
 * the upstream host comes from configuration alone.
 */
class Admin(
    private val cfg: Config,
    private val obs: Observability,
    private val audit: Audit,
) {
    private val log = LoggerFactory.getLogger("Admin")

    class BadRequest(message: String) : Exception(message)

    // --- logs -----------------------------------------------------------------

    fun logs(params: Map<String, String>): JSONObject {
        val f = logFilter(params)
        return obs.logs(f).put("range", rangeJson(f.from, f.to))
    }

    fun logFacets(params: Map<String, String>): JSONObject {
        val (from, to) = range(params)
        return obs.logFacets(from, to)
    }

    fun logsCsv(params: Map<String, String>): String {
        val records = obs.logs(logFilter(params)).optJSONArray("records") ?: JSONArray()
        return csv(
            listOf("ts", "level", "service", "user", "req", "msg"),
            (0 until records.length()).map { i ->
                val r = records.getJSONObject(i)
                listOf(
                    r.optString("ts"), r.optString("level"), r.optString("service"),
                    r.optString("user"), r.optString("req"), r.optString("msg"),
                )
            },
        )
    }

    /**
     * Live tail, as SSE.
     *
     * Polling `query_range` on a short cadence rather than proxying Loki's own
     * WebSocket tail: one streaming protocol on this hop instead of two, the
     * [Sse] class already handles the client-went-away case, and a poll that
     * misses nothing is easy to write while a bridged WebSocket that misses
     * nothing is not.
     *
     * Records are de-duplicated against a bounded set of what was just sent —
     * consecutive windows overlap by design (a log line written during the
     * round trip would otherwise fall between two queries), so overlap has to
     * be cheap and correct rather than avoided.
     */
    fun tail(params: Map<String, String>, sse: Sse) {
        val base = logFilter(params)
        var since = Instant.now().minusSeconds(30)
        val seen = LinkedHashSet<String>()

        sse.send("open", JSONObject().put("query", LogQl.query(base.copy(from = since))))

        while (true) {
            val now = Instant.now()
            val page = try {
                obs.logs(base.copy(from = since.minusSeconds(2), to = now, limit = 300))
            } catch (e: Observability.Unavailable) {
                sse.send("error", JSONObject().put("message", e.message))
                return
            }

            val records = page.optJSONArray("records") ?: JSONArray()
            // Oldest first, so a tail reads downwards like a terminal does.
            for (i in records.length() - 1 downTo 0) {
                val r = records.optJSONObject(i) ?: continue
                val key = "${r.optString("ts")}|${r.optString("service")}|${r.optString("msg").hashCode()}"
                if (!seen.add(key)) continue
                sse.send("record", r)
            }
            while (seen.size > SEEN_MAX) seen.remove(seen.first())

            since = now
            // The client disconnecting is how this ends; Sse.ClientGone
            // propagates out of send() and Http turns it into a closed exchange.
            Thread.sleep(TAIL_INTERVAL_MS)
        }
    }

    // --- the audit trail ------------------------------------------------------

    fun auditQuery(params: Map<String, String>): JSONObject {
        val f = auditFilter(params)
        val page = audit.query(f)
        return JSONObject()
            .put("count", page.rows.size)
            .put("records", JSONArray(page.rows.map { it.toJson() }))
            .put("cursor", page.next?.toString() ?: JSONObject.NULL)
            .put("range", rangeJson(f.from, f.to))
    }

    fun auditFacets(params: Map<String, String>): JSONObject {
        val (from, to) = range(params)
        return audit.facets(from, to)
    }

    fun auditCsv(params: Map<String, String>): String {
        // Export ignores the page limit and walks the cursor: an export that
        // silently stops at 200 rows is worse than no export, because it looks
        // like an answer.
        var f = auditFilter(params).copy(limit = Audit.MAX_LIMIT)
        val rows = ArrayList<Audit.Row>()
        while (rows.size < MAX_EXPORT_ROWS) {
            val page = audit.query(f)
            rows += page.rows
            val next = page.next ?: break
            f = f.copy(cursor = next)
        }
        return csv(
            listOf("ts", "user", "action", "target", "outcome", "status",
                "duration_ms", "ip", "session", "request_id", "detail"),
            rows.map {
                listOf(
                    it.at.toString(), it.username, it.action, it.target, it.outcome.wire,
                    it.status.toString(), it.durationMs.toString(), it.ip,
                    it.session, it.requestId, it.detail.toString(),
                )
            },
        )
    }

    // --- metrics --------------------------------------------------------------

    /** The panel catalogue, so the UI renders what this build actually offers
     *  rather than a list compiled into the bundle months ago. */
    fun panels(): JSONObject = JSONObject().put("panels", JSONArray(
        Panels.all.map {
            JSONObject().put("name", it.name).put("title", it.title).put("unit", it.unit)
        },
    ))

    fun metrics(params: Map<String, String>): JSONObject {
        val name = params["panel"]?.trim().orEmpty()
        val panel = Panels.get(name)
            ?: throw BadRequest(
                "unknown panel '$name' — this build offers " +
                    Panels.all.joinToString(", ") { it.name },
            )
        val (from, to) = range(params)
        return obs.panel(panel, from, to).put("range", rangeJson(from, to))
    }

    fun services(): JSONObject = obs.services()

    // --- filters --------------------------------------------------------------

    private fun logFilter(params: Map<String, String>): LogFilter {
        val (from, to) = range(params)
        val level = params["level"]?.takeIf { it.isNotBlank() && it != "all" }?.let {
            Level.from(it) ?: throw BadRequest(
                "unknown level '$it' — use " + Level.entries.joinToString("/") { l -> l.wire },
            )
        }
        return LogFilter(
            level = level,
            services = list(params["service"]),
            users = list(params["user"]),
            requestId = params["req"]?.trim().orEmpty(),
            q = params["q"]?.trim().orEmpty(),
            from = from,
            to = to,
            limit = int(params["limit"], 200).coerceIn(1, Observability.MAX_LOG_LIMIT),
        )
    }

    private fun auditFilter(params: Map<String, String>): Audit.Filter {
        val (from, to) = range(params)
        // `level` and `outcome` are the same parameter under two names: the
        // shared filter bar sends `level`, and `outcome` reads better in a
        // hand-written curl. Neither needs a special case in the UI.
        val raw = list(params["outcome"]) + list(params["level"])
        val outcomes = raw.filter { it != "all" }.map {
            Audit.Outcome.from(it) ?: throw BadRequest(
                "unknown outcome '$it' — use ok/denied/error",
            )
        }
        return Audit.Filter(
            from = from,
            to = to,
            users = list(params["user"]),
            actions = list(params["action"]),
            outcomes = outcomes,
            requestId = params["req"]?.trim().orEmpty(),
            q = params["q"]?.trim().orEmpty(),
            limit = int(params["limit"], 200).coerceIn(1, Audit.MAX_LIMIT),
            cursor = Audit.Cursor.parse(params["cursor"]),
        )
    }

    /**
     * The requested window, defaulted and bounded.
     *
     * Absolute instants on the wire, always. The UI's "last 15 minutes" presets
     * resolve to instants in the BROWSER and send those — a preset resolved
     * server-side would quietly mean something different every time a paused tab
     * was refocused, and an administrator comparing two views would be comparing
     * two different windows without being told.
     */
    private fun range(params: Map<String, String>): Pair<Instant, Instant> {
        val to = instant(params["to"]) ?: Instant.now()
        val from = instant(params["from"]) ?: to.minus(Duration.ofHours(1))
        if (!from.isBefore(to)) throw BadRequest("'from' must be before 'to'")
        if (Duration.between(from, to) > MAX_RANGE) {
            throw BadRequest(
                "the range is longer than ${MAX_RANGE.toDays()} days — narrow it, " +
                    "or export in batches",
            )
        }
        return from to to
    }

    /** The window that was actually used, echoed back. The UI shows it under
     *  the date control so "last 1 hour" is never the only thing a reader has
     *  to go on. Nullable because [Audit.Filter] models an unbounded window,
     *  even though [range] always resolves one. */
    private fun rangeJson(from: Instant?, to: Instant?) = JSONObject()
        .put("from", from?.toString() ?: JSONObject.NULL)
        .put("to", to?.toString() ?: JSONObject.NULL)

    private fun instant(raw: String?): Instant? {
        val s = raw?.trim().orEmpty().ifBlank { return null }
        // Epoch millis as well as RFC 3339: the browser has a number and curl
        // has a date, and refusing either would only mean writing the conversion
        // somewhere less well tested.
        s.toLongOrNull()?.let { return Instant.ofEpochMilli(it) }
        return try {
            Instant.parse(s)
        } catch (e: DateTimeParseException) {
            throw BadRequest("'$s' is not an RFC 3339 timestamp or epoch-millisecond value")
        }
    }

    private fun list(raw: String?): List<String> =
        raw?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }?.take(50) ?: emptyList()

    private fun int(raw: String?, default: Int): Int = raw?.trim()?.toIntOrNull() ?: default

    companion object {
        /** A week. Long enough for an investigation, short enough that one
         *  request cannot ask Loki to walk its whole retention. */
        private val MAX_RANGE: Duration = Duration.ofDays(7)

        private const val MAX_EXPORT_ROWS = 50_000
        private const val TAIL_INTERVAL_MS = 2_000L
        private const val SEEN_MAX = 2_000

        /**
         * RFC 4180. Doubled quotes, CRLF line endings, and every field quoted
         * rather than only the ones that need it — a log message contains commas
         * and newlines routinely, and "quote only when necessary" is how an
         * export ends up with a row that parses as two.
         *
         * The leading apostrophe on a value starting with `=`, `+`, `-` or `@`
         * is not decoration: without it a spreadsheet treats an audit target as
         * a formula, which is a well-worn way of turning an export into code
         * execution on the machine of whoever opens it.
         */
        fun csv(header: List<String>, rows: List<List<String>>): String = buildString {
            append(header.joinToString(",") { field(it) }).append("\r\n")
            for (row in rows) append(row.joinToString(",") { field(it) }).append("\r\n")
        }

        private fun field(value: String): String {
            val safe = if (value.isNotEmpty() && value[0] in "=+-@\t\r") "'$value" else value
            return "\"" + safe.replace("\"", "\"\"") + "\""
        }
    }
}
