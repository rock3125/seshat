import org.json.JSONObject
import org.junit.jupiter.api.AfterAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * What happens to the audit trail when Postgres cannot keep up.
 *
 * The rule the design turns on: auditing must never break the thing it audits.
 * A record is handed to a bounded queue and one writer thread drains it, so a
 * slow or absent database costs records rather than requests — and the loss is
 * COUNTED and logged rather than silent, because an audit trail that quietly
 * has holes in it is worse than one that says where they are.
 *
 * The writer is deliberately never started here. That is what makes this
 * testable without a database: `record` only enqueues, so the queue can be
 * driven to its capacity and past it, and the drop can be observed where an
 * operator would observe it — in the metrics.
 */
class AuditQueueTest {

    private fun audit(enabled: Boolean = true, blocking: Boolean = false): Pair<Audit, Metrics> {
        val metrics = Metrics(true)
        val cfg = Config.fromEnv().copy(auditEnabled = enabled, auditBlocking = blocking)
        return Audit(cfg, db, metrics) to metrics
    }

    /** One unlabelled series' value. Never null in practice: a counter with no
     *  labels has a sample from the moment it is constructed, so "nothing was
     *  recorded" reads as 0.0 rather than as an absent series. */
    private fun gauge(metrics: Metrics, name: String): Double =
        String(metrics.scrape()).lines().map { it.trim() }
            .first { it.startsWith("$name ") }
            .substringAfterLast(' ').toDouble()

    private fun fill(audit: Audit, count: Int, action: String = Audit.CHAT_TURN) {
        repeat(count) {
            audit.record(who = null, action = action, outcome = Audit.Outcome.OK, target = "row $it")
        }
    }

    @Test
    fun `a recorded action is queued, and the depth is reported`() {
        val (audit, metrics) = audit()

        fill(audit, 3)

        assertEquals(3.0, gauge(metrics, "seshat_audit_queue_depth"))
        assertEquals(0.0, gauge(metrics, "seshat_audit_dropped_total"))
    }

    @Test
    fun `the queue is bounded, and everything past the bound is counted as dropped`() {
        // 4096 rows in, 100 more offered. The extra hundred must not block the
        // caller, must not throw, and must be counted.
        val (audit, metrics) = audit()

        fill(audit, 4_096)
        assertEquals(0.0, gauge(metrics, "seshat_audit_dropped_total"), "dropped before it was full")

        fill(audit, 100)

        assertEquals(100.0, gauge(metrics, "seshat_audit_dropped_total"))
        assertEquals(4_096.0, gauge(metrics, "seshat_audit_queue_depth"), "the queue grew past its bound")
    }

    @Test
    fun `recording is cheap enough to sit in a request path`() {
        // Not a benchmark — an order-of-magnitude guard. `record` is called from
        // a finally block on every single request, including failing ones, so if
        // it ever grows an I/O call this is where that shows up.
        val (audit, _) = audit()

        val started = System.nanoTime()
        fill(audit, 4_096)
        val millis = (System.nanoTime() - started) / 1_000_000

        assertTrue(millis < 2_000, "4096 records took ${millis}ms")
    }

    @Test
    fun `with auditing off nothing is recorded at all`() {
        // AUDIT_ENABLED=off has to mean off: no queue, no metrics, no cost.
        val (audit, metrics) = audit(enabled = false)

        fill(audit, 10)

        assertEquals(0.0, gauge(metrics, "seshat_audit_queue_depth"))
        assertEquals(0.0, gauge(metrics, "seshat_audit_dropped_total"))
    }

    @Test
    fun `blocking mode queues without dropping while there is room`() {
        // AUDIT_BLOCKING=on trades a request's latency for a complete trail. The
        // full-queue half of that is a `put` that blocks by design, so it is not
        // exercised here — a test that hangs on purpose is not worth the risk of
        // one that hangs by accident.
        val (audit, metrics) = audit(blocking = true)

        fill(audit, 500)

        assertEquals(500.0, gauge(metrics, "seshat_audit_queue_depth"))
        assertEquals(0.0, gauge(metrics, "seshat_audit_dropped_total"))
    }

    @Test
    fun `a record is trimmed on the way in, not on the way out`() {
        // The target of a search is a user-supplied query. Trimming at write
        // time would mean the oversized value had already been carried through
        // the queue and into a bind parameter.
        val (audit, metrics) = audit()

        audit.record(
            who = null,
            action = Audit.TOOL_SEARCH,
            outcome = Audit.Outcome.OK,
            target = "q".repeat(10_000),
            ip = "x".repeat(200),
            detail = JSONObject().put("note", "y".repeat(20_000)),
        )

        assertEquals(1.0, gauge(metrics, "seshat_audit_queue_depth"))
    }

    @Test
    fun `an action with no principal is recorded as such rather than refused`() {
        // The unauthenticated 401 is exactly the row an operator wants, so a
        // null principal must not be a reason to skip the record.
        val (audit, metrics) = audit()

        audit.record(who = null, action = Audit.AUTH_DENIED, outcome = Audit.Outcome.DENIED, status = 401)

        assertEquals(1.0, gauge(metrics, "seshat_audit_queue_depth"))
    }

    @Test
    fun `nothing in the recording path throws, whatever it is handed`() {
        // `record` is called from failure paths, which is where an exception
        // thrown by the auditor would do the most damage.
        val (audit, metrics) = audit()

        audit.record(
            who = Principal("rock", "Rock", setOf("admin"), "sub", "sid"),
            action = "",
            outcome = Audit.Outcome.ERROR,
            target = "",
            status = -1,
            ip = "",
            requestId = "",
            durationMs = Int.MAX_VALUE,
            detail = JSONObject().put("nested", JSONObject().put("token", "Bearer secret")),
        )

        // Reaching this line is most of the assertion; the row being queued is
        // the rest of it.
        assertEquals(1.0, gauge(metrics, "seshat_audit_queue_depth"))
    }

    private companion object {
        /** Never queried: the writer thread that would use it is not started. */
        val db = Db(Config.fromEnv())

        @AfterAll
        @JvmStatic
        fun releaseThePool() = db.close()
    }
}
