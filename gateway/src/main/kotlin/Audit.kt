import org.json.JSONObject
import org.slf4j.LoggerFactory
import java.security.MessageDigest
import java.sql.Timestamp
import java.time.Instant
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * The audit trail: one row per user action, written asynchronously, read by the
 * admin API.
 *
 * Three rules shape everything here.
 *
 * **Auditing must never break the thing it audits.** A record goes onto a
 * bounded queue and a single writer thread batches it into Postgres. A chat
 * turn never waits on this, and a slow or absent database costs records rather
 * than requests.
 *
 * **A gap must never be silent.** When the queue is full a record is dropped,
 * counted in `seshat_audit_dropped_total`, and logged — and the Admin tab shows
 * the count as a banner rather than burying it in a chart. Where that trade is
 * unacceptable, `AUDIT_BLOCKING=on` makes the queue apply back-pressure
 * instead: slower under load, complete.
 *
 * **Nothing secret goes in.** [sanitize] is applied to every detail object on
 * the way in, by key and by length, so a route that carelessly passes its whole
 * request through cannot leak a token into a table that administrators read.
 *
 * Records carry `request_id`, which is also the MDC `req` on every log line the
 * same request produced. That one shared id is what lets the Admin tab put an
 * audit row and its log lines side by side.
 */
class Audit(
    private val cfg: Config,
    private val db: Db,
    private val metrics: Metrics,
) : AutoCloseable {

    private val log = LoggerFactory.getLogger("Audit")

    enum class Outcome {
        OK, DENIED, ERROR;

        val wire: String get() = name.lowercase()

        companion object {
            fun from(s: String): Outcome? =
                entries.firstOrNull { it.name.equals(s.trim(), ignoreCase = true) }
        }
    }

    /** One row, as written and as read back. */
    data class Row(
        val id: Long = 0,
        val at: Instant,
        val username: String,
        val subject: String,
        val session: String,
        val requestId: String,
        val action: String,
        val target: String,
        val outcome: Outcome,
        val status: Int,
        val ip: String,
        val durationMs: Int,
        val detail: JSONObject,
    ) {
        fun toJson(): JSONObject = JSONObject()
            .put("id", id)
            .put("ts", at.toString())
            .put("user", username)
            .put("subject", subject)
            .put("session", session)
            .put("req", requestId)
            .put("action", action)
            .put("target", target)
            .put("outcome", outcome.wire)
            .put("status", status)
            .put("ip", ip)
            .put("duration_ms", durationMs)
            .put("detail", detail)
    }

    /**
     * What the admin API may ask for. Deliberately the same four dimensions the
     * log query takes — severity, who, when, what — so the UI can drive both
     * with one filter component.
     */
    data class Filter(
        val from: Instant? = null,
        val to: Instant? = null,
        val users: List<String> = emptyList(),
        val actions: List<String> = emptyList(),
        val outcomes: List<Outcome> = emptyList(),
        val requestId: String = "",
        val q: String = "",
        val limit: Int = 200,
        val cursor: Cursor? = null,
    )

    /** Where the last page stopped. Both halves are needed: two rows written in
     *  the same millisecond are ordinary, and paging on the timestamp alone
     *  either repeats one or loses one. */
    data class Cursor(val at: Instant, val id: Long) {
        override fun toString() = "${at.toEpochMilli()}.$id"

        companion object {
            fun parse(s: String?): Cursor? {
                val raw = s?.trim().orEmpty().ifBlank { return null }
                val dot = raw.lastIndexOf('.')
                if (dot <= 0) return null
                val millis = raw.take(dot).toLongOrNull() ?: return null
                val id = raw.substring(dot + 1).toLongOrNull() ?: return null
                return Cursor(Instant.ofEpochMilli(millis), id)
            }
        }
    }

    data class Page(val rows: List<Row>, val next: Cursor?)

    // --- writing --------------------------------------------------------------

    private val queue = ArrayBlockingQueue<Row>(QUEUE_CAPACITY)

    @Volatile private var running = false
    private var writer: Thread? = null
    private var sweeper: Thread? = null

    /** Record an action. Cheap, non-blocking, and safe to call from anywhere —
     *  including from inside a failure path, which is where it matters most. */
    fun record(
        who: Principal?,
        action: String,
        outcome: Outcome,
        target: String = "",
        status: Int = 0,
        ip: String = "",
        requestId: String = "",
        durationMs: Int = 0,
        detail: JSONObject = JSONObject(),
    ) {
        if (!cfg.auditEnabled) return
        val row = Row(
            at = Instant.now(),
            username = who?.username ?: "-",
            subject = who?.subject.orEmpty(),
            session = who?.sessionId.orEmpty(),
            requestId = requestId,
            action = action,
            target = target.take(MAX_TARGET_CHARS),
            outcome = outcome,
            status = status,
            ip = ip.take(64),
            durationMs = durationMs,
            detail = sanitize(detail),
        )

        if (cfg.auditBlocking) {
            // Back-pressure, as configured. Interruption is not swallowed: the
            // thread being shut down mid-put should not be turned into a
            // silently missing record.
            try {
                queue.put(row)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        } else if (!queue.offer(row)) {
            metrics.auditDropped()
            // Rate-limited: a full queue produces a flood, and a flood of
            // "dropped" lines is itself a way to lose the trail.
            val now = System.currentTimeMillis()
            if (now - lastDropWarning > 10_000) {
                lastDropWarning = now
                log.warn(
                    "audit queue full — dropping records. Set AUDIT_BLOCKING=on to " +
                        "back-pressure instead of dropping, or look at why Postgres is slow.",
                )
            }
        }
        metrics.auditQueueDepth(queue.size)
    }

    @Volatile private var lastDropWarning = 0L

    fun start() {
        if (!cfg.auditEnabled) {
            log.warn("audit OFF — no record of user actions is being kept (AUDIT_ENABLED=off)")
            return
        }
        running = true

        writer = Thread({
            val batch = ArrayList<Row>(BATCH_MAX)
            while (running || queue.isNotEmpty()) {
                try {
                    // One blocking take so an idle service is not a spin, then a
                    // drain: the common case at load is that many rows are
                    // already waiting and belong in one statement.
                    val first = queue.poll(FLUSH_MS, TimeUnit.MILLISECONDS) ?: continue
                    batch.add(first)
                    queue.drainTo(batch, BATCH_MAX - 1)
                    insert(batch)
                    metrics.auditWritten(batch.size)
                    metrics.auditQueueDepth(queue.size)
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    break
                } catch (e: Exception) {
                    // The batch is lost. Logging it at ERROR with the root cause
                    // is the only thing left to do that is better than pretending
                    // it was written.
                    log.error("could not write {} audit record(s): {}",
                        batch.size, Db.rootCause(e))
                } finally {
                    batch.clear()
                }
            }
        }, "audit-writer").apply { isDaemon = true; start() }

        sweeper = Thread({
            while (running) {
                try {
                    Thread.sleep(SWEEP_INTERVAL_MS)
                    val removed = deleteOlderThan(cfg.auditRetentionDays)
                    if (removed > 0) {
                        log.info("audit retention: {} record(s) older than {} day(s) removed",
                            removed, cfg.auditRetentionDays)
                    }
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    break
                } catch (e: Exception) {
                    log.warn("audit retention sweep failed: {}", Db.rootCause(e))
                }
            }
        }, "audit-retention").apply { isDaemon = true; start() }

        log.info("audit ON — retention {} day(s), chat prompts {}, reads {}, {} on overflow",
            cfg.auditRetentionDays,
            if (cfg.auditChatPrompts) "recorded" else "hashed",
            if (cfg.auditReads) "recorded" else "skipped",
            if (cfg.auditBlocking) "back-pressure" else "drop")
    }

    /** Drain what is queued, then stop. Called from the shutdown hook, before
     *  the pool this writes through is closed. */
    override fun close() {
        running = false
        writer?.join(5_000)
        sweeper?.interrupt()
    }

    private fun insert(rows: List<Row>) {
        if (rows.isEmpty()) return
        db.tx { c ->
            val values = rows.joinToString(",") { "(?,?,?,?,?,?,?,?,?,?,?,?::jsonb)" }
            c.prepareStatement(
                "insert into audit (at, username, subject, session, request_id, action, " +
                    "target, outcome, status, ip, duration_ms, detail) values $values",
            ).use { st ->
                var p = 1
                for (r in rows) {
                    st.setTimestamp(p++, Timestamp.from(r.at))
                    st.setString(p++, r.username)
                    st.setString(p++, r.subject)
                    st.setString(p++, r.session)
                    st.setString(p++, r.requestId)
                    st.setString(p++, r.action)
                    st.setString(p++, r.target)
                    st.setString(p++, r.outcome.wire)
                    st.setInt(p++, r.status)
                    st.setString(p++, r.ip)
                    st.setInt(p++, r.durationMs)
                    st.setString(p++, r.detail.toString())
                }
                st.executeUpdate()
            }
        }
    }

    private fun deleteOlderThan(days: Int): Int = db.tx { c ->
        c.prepareStatement("delete from audit where at < now() - make_interval(days => ?)").use { st ->
            st.setInt(1, days)
            st.executeUpdate()
        }
    }

    // --- reading --------------------------------------------------------------

    fun query(filter: Filter): Page {
        val where = whereClause(filter)
        val limit = filter.limit.coerceIn(1, MAX_LIMIT)
        val sql = "select id, at, username, subject, session, request_id, action, target, " +
            "outcome, status, ip, duration_ms, detail from audit ${where.sql} " +
            "order by at desc, id desc limit ?"

        val rows = db.read { c ->
            c.prepareStatement(sql).use { st ->
                var p = 1
                for (arg in where.args) bind(st, p++, arg)
                // One more than asked for, so "is there another page" is an
                // observation rather than a second count(*) over the same
                // predicate.
                st.setInt(p, limit + 1)
                st.executeQuery().use { rs ->
                    buildList { while (rs.next()) add(readRow(rs)) }
                }
            }
        }
        val page = rows.take(limit)
        val next = if (rows.size > limit) page.lastOrNull()?.let { Cursor(it.at, it.id) } else null
        return Page(page, next)
    }

    /** The distinct users and actions present in a window — what the filter
     *  dropdowns offer. Populated from the data rather than hard-coded, so the
     *  list cannot drift away from what is actually there. */
    fun facets(from: Instant?, to: Instant?): JSONObject {
        val where = whereClause(Filter(from = from, to = to))
        val users = db.read { c ->
            c.prepareStatement(
                "select username, count(*) as n from audit ${where.sql} " +
                    "group by username order by n desc limit 200",
            ).use { st ->
                var p = 1
                for (arg in where.args) bind(st, p++, arg)
                st.executeQuery().use { rs ->
                    buildList<String> { while (rs.next()) add(rs.getString("username")) }
                }
            }
        }
        val actions = db.read { c ->
            c.prepareStatement(
                "select action, count(*) as n from audit ${where.sql} " +
                    "group by action order by n desc limit 200",
            ).use { st ->
                var p = 1
                for (arg in where.args) bind(st, p++, arg)
                st.executeQuery().use { rs ->
                    buildList<String> { while (rs.next()) add(rs.getString("action")) }
                }
            }
        }
        return JSONObject()
            .put("users", users)
            // The union of what has happened and what CAN happen: an action
            // nobody has performed yet still belongs in the dropdown, or the
            // filter cannot be used to prove that it never happened.
            .put("actions", (actions + ACTIONS).distinct().sorted())
            .put("outcomes", Outcome.entries.map { it.wire })
    }

    private fun readRow(rs: java.sql.ResultSet) = Row(
        id = rs.getLong("id"),
        at = rs.getTimestamp("at").toInstant(),
        username = rs.getString("username"),
        subject = rs.getString("subject") ?: "",
        session = rs.getString("session") ?: "",
        requestId = rs.getString("request_id") ?: "",
        action = rs.getString("action"),
        target = rs.getString("target") ?: "",
        outcome = Outcome.from(rs.getString("outcome")) ?: Outcome.OK,
        status = rs.getInt("status"),
        ip = rs.getString("ip") ?: "",
        durationMs = rs.getInt("duration_ms"),
        detail = runCatching { JSONObject(rs.getString("detail") ?: "{}") }
            .getOrElse { JSONObject() },
    )

    private fun bind(st: java.sql.PreparedStatement, index: Int, arg: Any) = when (arg) {
        is Instant -> st.setTimestamp(index, Timestamp.from(arg))
        is Int -> st.setInt(index, arg)
        is Long -> st.setLong(index, arg)
        is Array<*> -> st.setArray(index, st.connection.createArrayOf("text", arg))
        else -> st.setString(index, arg.toString())
    }

    companion object {
        private const val QUEUE_CAPACITY = 4096
        private const val BATCH_MAX = 200
        private const val FLUSH_MS = 200L
        private const val SWEEP_INTERVAL_MS = 24 * 60 * 60 * 1000L
        private const val MAX_TARGET_CHARS = 500
        private const val MAX_DETAIL_STRING = 2_000
        private const val MAX_DETAIL_KEYS = 40

        /** The ceiling on one page, whatever the caller asks for. */
        const val MAX_LIMIT = 1000

        /**
         * The action vocabulary. A fixed list rather than free strings, because
         * it is a filter dropdown and an enum in the UI — a typo'd action name
         * is a row nobody will ever find again.
         */
        const val SESSION_START = "session.start"
        const val AUTH_DENIED = "auth.denied"
        const val CHAT_TURN = "chat.turn"
        const val TOOL_SEARCH = "tool.search"
        const val TOOL_LOAD_CHUNK = "tool.load_chunk"
        const val CHUNK_VIEW = "chunk.view"
        const val CONFIG_READ = "config.read"
        const val UPLOAD = "library.upload"
        const val REINDEX = "library.reindex"
        const val MCP_CALL = "mcp.call"
        const val ADMIN_AUDIT_READ = "admin.audit.read"
        const val ADMIN_LOGS_QUERY = "admin.logs.query"
        const val ADMIN_METRICS_QUERY = "admin.metrics.query"
        const val ADMIN_SERVICES_READ = "admin.services.read"

        val ACTIONS = listOf(
            SESSION_START, AUTH_DENIED, CHAT_TURN, TOOL_SEARCH, TOOL_LOAD_CHUNK,
            CHUNK_VIEW, CONFIG_READ, UPLOAD, REINDEX, MCP_CALL,
            ADMIN_AUDIT_READ, ADMIN_LOGS_QUERY, ADMIN_METRICS_QUERY, ADMIN_SERVICES_READ,
        )

        /**
         * Keys that must never reach the table, matched as substrings and case
         * insensitively.
         *
         * A deny-list is the weaker of the two designs and it is the right one
         * here: the alternative is an allow-list per action, which means every
         * new field is invisible until someone remembers to permit it, and the
         * failure mode of THAT is an audit trail quietly missing the detail
         * somebody added on purpose. This fails the other way — loudly, in a
         * test — and the test is `AuditTest`.
         */
        private val FORBIDDEN = listOf(
            "authorization", "token", "password", "passwd", "secret",
            "api_key", "apikey", "credential", "cookie", "bearer",
        )

        /**
         * A detail object with nothing secret and nothing enormous in it.
         *
         * Applied to every record on the way in rather than trusted at each call
         * site — there are a dozen call sites and there will be more, and this
         * is the kind of rule that holds only when it is impossible to forget.
         */
        fun sanitize(detail: JSONObject): JSONObject {
            val out = JSONObject()
            var kept = 0
            for (key in detail.keySet().sorted()) {
                if (kept >= MAX_DETAIL_KEYS) break
                val lower = key.lowercase()
                if (FORBIDDEN.any { it in lower }) {
                    out.put(key, "[redacted]")
                    kept++
                    continue
                }
                when (val v = detail.opt(key)) {
                    null, JSONObject.NULL -> {}
                    is String -> {
                        out.put(key, if (v.length > MAX_DETAIL_STRING) {
                            v.take(MAX_DETAIL_STRING) + "…[${v.length} chars]"
                        } else v)
                        kept++
                    }
                    is JSONObject -> { out.put(key, sanitize(v)); kept++ }
                    else -> { out.put(key, v); kept++ }
                }
            }
            return out
        }

        /**
         * A prompt reduced to something that identifies it without revealing it:
         * the first 16 hex characters of its sha-256.
         *
         * Enough to notice the same question asked twice, or to confirm that a
         * particular prompt was the one that ran, given the prompt. Not enough
         * to read anybody's questions out of the table. See AUDIT_CHAT_PROMPTS.
         */
        fun digest(text: String): String =
            MessageDigest.getInstance("SHA-256")
                .digest(text.toByteArray(Charsets.UTF_8))
                .take(8)
                .joinToString("") { "%02x".format(it) }

        /** A username, or any other identifier that goes into SQL as a value —
         *  bound, never interpolated, but still worth bounding so a megabyte of
         *  query string cannot become a megabyte of index probe. */
        fun cleanIdentifier(s: String): String = s.trim().take(128)

        internal data class Where(val sql: String, val args: List<Any>)

        /**
         * The filter as a parameterised `where`.
         *
         * Every value is a bind parameter — `q` included, which is the one that
         * carries user text and therefore the one worth being explicit about.
         * The clause is built as a list and joined, so an absent filter
         * contributes nothing rather than a `1=1`.
         */
        internal fun whereClause(f: Filter): Where {
            val clauses = mutableListOf<String>()
            val args = mutableListOf<Any>()

            // `args.add(…)` and never `args += …`: with an Array on the right,
            // `+=` resolves to List.plus(Array) — which builds a NEW list and
            // then fails to assign it to a val. It is a compile error here and
            // it would have been a silently empty filter if the list were a var.
            f.from?.let { clauses += "at >= ?"; args.add(it) }
            f.to?.let { clauses += "at <= ?"; args.add(it) }

            if (f.users.isNotEmpty()) {
                clauses += "username = any (?)"
                args.add(f.users.map(::cleanIdentifier).toTypedArray())
            }
            if (f.actions.isNotEmpty()) {
                clauses += "action = any (?)"
                args.add(f.actions.map(::cleanIdentifier).toTypedArray())
            }
            if (f.outcomes.isNotEmpty()) {
                clauses += "outcome = any (?)"
                args.add(f.outcomes.map { it.wire }.toTypedArray())
            }
            if (f.requestId.isNotBlank()) {
                clauses += "request_id = ?"
                args.add(cleanIdentifier(f.requestId))
            }
            if (f.q.isNotBlank()) {
                // ILIKE against the two free-text columns. `detail::text` is a
                // sequential scan and is accepted: the trail is bounded by
                // retention, the range filter runs first on an index, and the
                // alternative is a full-text index on a table that is written
                // far more often than it is read.
                clauses += "(target ilike ? or detail::text ilike ? or action ilike ?)"
                val like = "%" + f.q.trim().take(200).replace("\\", "\\\\")
                    .replace("%", "\\%").replace("_", "\\_") + "%"
                repeat(3) { args.add(like) }
            }
            f.cursor?.let {
                // Row comparison, matching the (at desc, id desc) index exactly.
                clauses += "(at, id) < (?, ?)"
                args.add(it.at); args.add(it.id)
            }

            return Where(
                if (clauses.isEmpty()) "" else "where " + clauses.joinToString(" and "),
                args,
            )
        }
    }
}
