# RBAC, auditing and consolidated observability — implementation plan

Scope, as asked:

- RBAC, with `rock` as the default administrator.
- Administrators get logging and auditing.
- Every user action is audited.
- Logs from **all** containers are shown in one place, inside the Seshat UI.
- Loki and Prometheus record system logs and performance.
- `docker-compose.yml` captures all of it.
- The UI gets a Chat / Admin tab, for administrators only.
- **Every container logs JSON**, so the data is structured before it is
  collected rather than parsed back out of prose afterwards.
- **Log viewing and auditing share one filter contract** — severity, who, when,
  and a text search — and both display structured records, not lines.

This is a plan, not a diff. Read §2 before starting — three of those decisions
change what the rest of the phases look like.

---

## 1. What already exists

More than half the RBAC is already built, and the plan is to extend it rather
than introduce a second scheme beside it.

| Piece | Where | State |
|---|---|---|
| Realm roles `use-ui`, `admin` | `keycloak/seshat-realm.json` | Exists |
| Groups `/readers`, `/admins` | same | Exists |
| Role extraction from the token | `Auth.kt` — `Principal.roles`, `.isAdmin` | Exists |
| `use-ui` enforced on every route | `Http.kt:requireUser` | Exists |
| `admin` enforced on `/reindex` and `/upload` | `Http.kt:reindexRoute`, `mayUpload` | Exists |
| Role-aware UI rendering | `auth/keycloak.ts:hasRole`, `/config.upload.allowed` | Exists |
| Audit trail | — | **Missing** |
| Metrics of any kind | — | **Missing** |
| Log aggregation | `docker compose logs`, per container | **Missing** |
| Admin surface in the UI | — | **Missing** |

Two defects to fix on the way past:

1. **`guest` is in `/admins`** (`seshat-realm.json`). It carries the `admin`
   role, so today the guest account can reindex the corpus and upload to it —
   and after this work it would also read everyone's audit trail. It belongs in
   `/readers`.
2. **Seed passwords are plaintext in the realm JSON**, which is a tracked file.
   Not caused by this work, but this work makes the `admin` role considerably
   more valuable, so it is the right moment to move both to `.env` variables
   (`${SEED_ADMIN_PASSWORD}` — Keycloak's realm import does expand `${}` from
   the environment) and require a rotation on first login.

---

## 2. Decisions to make first

Each has a recommendation. Nothing downstream is blocked on agreeing with the
recommendation, but everything downstream is blocked on *a* choice.

### 2.1 Does the audit trail record what people asked?

`Http.kt:chatRoute` carries a deliberate design statement: *"no chat transcript
is ever written to disk server-side."* The browser owns history; Postgres holds
only chunks. "Audit all user actions" reverses that — a chat turn is the
principal user action, and an audit record of `chat.turn` with no prompt is an
audit of nothing.

**Recommendation:** audit the turn always, and gate the prompt text on a
setting.

- Default `AUDIT_CHAT_PROMPTS=off` — record the turn, the user, the duration,
  the tool calls it made, and a sha-256 prefix plus the character count of the
  prompt. That is enough to prove a turn happened, correlate it with retrieval,
  and detect a repeat, without storing the question.
- `AUDIT_CHAT_PROMPTS=on` records the prompt verbatim.

Either way the **search queries the model issued** are recorded, because those
are tool calls against the shared corpus and they are the thing an administrator
actually needs to see. Say so plainly in the UI: administrators can read what
everyone searched for.

### 2.2 How does the UI get logs — proxy through the gateway, or ship Grafana?

**Recommendation: proxy through the gateway.** The gateway holds a fixed
upstream URL and a small vocabulary of named queries; the browser never sends
LogQL or PromQL.

Grafana would be quicker to stand up and would immediately look wrong: a second
visual language, a second auth story (Grafana's own users, or an OIDC client and
a role mapping), a second published path, and an iframe the CSP has to allow.
The gateway already has bearer verification, an SSE class, and one origin. Add
Grafana later as an *operator* tool if you want ad-hoc PromQL — behind
`/seshat/grafana/`, admin-only — but the Admin tab should not be an iframe.

The cost of the recommendation is real and worth stating: every panel in the
Admin UI needs a named query written on the server. That is roughly a dozen
queries, listed in §6.

### 2.3 What collects container logs into Loki?

Three options, and the wrong one is easy to pick:

| Option | Verdict |
|---|---|
| Loki Docker **log-driver plugin** | Rejected. Requires `docker plugin install` on the host before `docker compose up` works at all, which breaks the one-command start the README promises. |
| **Promtail** | Rejected. Deprecated by Grafana; replaced by Alloy. Do not start new work on it. |
| **Grafana Alloy** with `discovery.docker` over the Docker socket | **Recommended.** One container, reads `/var/run/docker.sock` read-only, discovers every container in the project, tails its json-file logs, labels each stream with the compose service name. |

The socket mount is a genuine privilege boundary — read access to the Docker
socket is close to root on the host. Mount it `:ro`, keep Alloy on the internal
network with nothing published, and note it in the README next to the same
warning cAdvisor will need. On Fedora/RHEL the mount needs the `z` relabel the
compose file already applies elsewhere.

### 2.4 Prometheus client library, or hand-rolled exposition?

The gateway is deliberately dependency-light (`java.net.http`, `org.json`,
`com.sun.net.httpserver`). A Prometheus text-format endpoint is about 120 lines
of counters, gauges and a bucketed histogram.

**Recommendation: take the library** —
`io.prometheus:prometheus-metrics-core` plus
`prometheus-metrics-instrumentation-jvm`. It brings heap, GC, thread and
class-loading metrics that would otherwise all be hand-written against
`ManagementFactory`, and the exposition format has edge cases (escaping, `_total`
suffixes, exemplars) that are not worth rediscovering. Register it on the
existing `HttpServer` at `/metrics`; do not let it start a second server.

### 2.5 JSON logs everywhere — and what that costs

Every service emits JSON on stdout. Nothing in the pipeline parses prose, and no
regex stands between a log line and a filter.

This overturns a decision the codebase states out loud. `logback.xml` says:
*"Plain lines on stdout: `docker compose logs` is the reader, and it wants
something a human can scan, not JSON."* That was right when `docker compose logs`
was the only reader. It is no longer — the Admin tab is, and it wants fields.

The trade is real: `docker compose logs gateway` becomes much less pleasant at a
terminal. Two mitigations, and it is worth taking both.

- **`LOG_FORMAT=json|text`**, defaulting to `json` in compose and to `text` when
  unset, so `./gradlew run` and a bare `java -jar` are still readable and only
  the containerised path is machine-first.
- Say in the README that `docker compose logs gateway | jq -r '"\(.ts) \(.level) \(.logger) \(.msg)"'`
  restores the old view. One line, and it belongs next to the change.

**Recommendation for the gateway encoder:**
`net.logstash.logback:logstash-logback-encoder`. Logback 1.5 ships its own
`JsonEncoder`, but its output nests the MDC and the throwable under structures
that need unwrapping downstream; the logstash encoder emits flat, predictable
keys and puts MDC entries at the top level, which is exactly what Loki's
`stage.json` wants to read.

One agreed field set, used by every service that we control:

```json
{ "ts": "2026-08-17T11:29:04.117Z", "level": "ERROR", "service": "gateway",
  "logger": "Library", "msg": "…", "user": "rock", "req": "b1c4…",
  "route": "/upload", "status": 500, "duration_ms": 412, "err": "…" }
```

`ts`, `level`, `service` and `msg` are the contract — the filters in §6 depend on
those four and nothing else, so a service that cannot produce the rest still
filters correctly. `user` and `req` come from MDC, set once in `Http.handle`
alongside the audit hook, which means every line logged during a request carries
who caused it. That is the single highest-value field in this whole plan: it is
what turns "an error happened" into "this error happened to Rock, on this
request, and here is the audit row beside it".

Services whose log format we do **not** control — cAdvisor is the only one — get
tagged `format=unstructured` by Alloy and are rendered as a plain message with
`level` inferred. Do not pretend otherwise in the UI.

## 3. Phase 1 — RBAC

Small phase. The model is already right; it needs one more role and a fix.

**`keycloak/seshat-realm.json`**

- Add realm role `admin-observability` — *"May read the audit trail and the
  consolidated logs and metrics."*
- Make `admin` a composite that includes `use-ui` and `admin-observability`, so
  the existing `admin` grant keeps working and `rock` needs no new assignment.
  Splitting the role now means a future "auditor who cannot reindex" account is
  an assignment, not a migration.
- Move `guest` from `/admins` to `/readers`.
- Replace both plaintext `credentials[].value` with `${SEED_ADMIN_PASSWORD}` /
  `${SEED_GUEST_PASSWORD}`, and add both to `.env.example` with a "change these"
  note beside the Keycloak block that already exists.
- Enable event storage on the realm so §4's optional login-event ingestion has
  something to read: `"eventsEnabled": true`, `"eventsExpiration": 2592000`,
  `"adminEventsEnabled": true`.

**`gateway/src/main/kotlin/Auth.kt`**

- `Principal` gains `subject` (`sub` — stable across a username change) and
  `sessionId` (`sid` — correlates every action in one browser session).
- Add `val mayAudit get() = "admin-observability" in roles || "admin" in roles`.
- Keep `isAdmin` as it is. Two named capabilities, not a role string comparison
  scattered through the routes.

**`gateway/src/main/kotlin/Http.kt`**

- Add `requireAdmin(ex)` and `requireAuditor(ex)` beside `requireUser`, each
  returning `Principal?` and having already sent the 403.
- The auth-off path (`requireUser` returns an anonymous admin when
  `KEYCLOAK_ISSUER` is blank) must keep working — bare local development stays
  possible, and the anonymous principal gains both new capabilities.
- `/config` gains an `admin` object: `{ "is_admin": bool, "may_audit": bool,
  "features": { "logs": bool, "metrics": bool, "audit": bool } }`. The feature
  flags are false when the corresponding upstream is not configured, so the UI
  never renders a tab for a panel that will 503. Same principle as
  `upload.allowed` today: the server decides, once, where it enforces.

**Tests** (`GatewayGuardsTest.kt` is the existing home for this shape)

- A token with `use-ui` alone is refused on every `/admin/*` route.
- A token with `admin` is admitted.
- Auth off ⇒ admitted, and `/config` reports both capabilities true.

---

## 4. Phase 2 — the audit trail

### Schema

Appended to `Db.SCHEMA`, which already migrates by `create table if not exists`
and `alter table … add column if not exists` — so this needs no migration tool.

```sql
create table if not exists audit (
    id          bigserial   primary key,
    at          timestamptz not null default now(),
    username    text        not null,
    subject     text        not null default '',
    session     text        not null default '',
    request_id  text        not null default '',   -- joins to the log lines
    action      text        not null,
    target      text        not null default '',
    outcome     text        not null,          -- ok | denied | error
    status      int         not null default 0,
    ip          text        not null default '',
    duration_ms int         not null default 0,
    detail      jsonb       not null default '{}'
);

create index if not exists audit_at_idx        on audit (at desc);
create index if not exists audit_user_at_idx   on audit (username, at desc);
create index if not exists audit_action_at_idx on audit (action, at desc);
create index if not exists audit_req_idx       on audit (request_id);
```

`request_id` is the same value that goes into the MDC as `req` and therefore
into every log line the request produced (§2.5). One id, generated once per
request in `Http.handle`, is what lets the Admin UI put an audit row and its log
lines side by side. It costs a `UUID` and a column.

`detail` is JSONB rather than more columns because the interesting field differs
per action — a chunk id, a file size, a LogQL range, a rejection reason — and a
table of twenty mostly-null columns is worse than one document.

### `Audit.kt` (new)

```
class Audit(cfg: Config, db: Db) {
    fun record(principal: Principal?, action: String, target: String = "",
               outcome: Outcome, status: Int = 0, ip: String = "",
               durationMs: Int = 0, detail: JSONObject = JSONObject())
    fun query(filter: Filter): Page      // for GET /admin/audit
    fun start()                          // writer thread + retention sweep
}
```

- A bounded `ArrayBlockingQueue<Row>(4096)` and one daemon writer thread doing a
  multi-row insert every 200ms or every 200 rows — the same batching shape
  `Db.replaceDocument` already uses for chunks. **Auditing must never block a
  chat turn**, and it must never be the thing that takes the service down when
  Postgres is slow.
- On overflow: drop, log a warning, and increment `seshat_audit_dropped_total`.
  A silent drop in an audit trail is the worst of both worlds, so it is visible
  in the metrics *and* in the Admin UI as a banner. If the deployment needs a
  trail with no gaps, `AUDIT_BLOCKING=on` makes the queue back-pressure instead
  of dropping — slower under load, complete.
- Retention: a daily `delete from audit where at < now() - interval` driven by
  `AUDIT_RETENTION_DAYS` (default 90). Log the row count deleted.

### Where records are made

Most of it is one hook. `Http.handle` already wraps every route with CORS,
error trapping and close — it is the natural place to time the request and write
the record, so no route can be added later that forgets to audit itself.

| Action | Where | `target` | `detail` |
|---|---|---|---|
| `session.start` | `requireUser`, first time a `sid` is seen | — | roles, user agent |
| `auth.denied` | `requireUser` on 401/403 | route | rejection reason (never the token) |
| `chat.turn` | `chatRoute`, on completion | prompt hash or text per §2.1 | chars, history size, tool calls, tokens, duration |
| `tool.search` | `Tools.call` | the query string | mode, candidates, hit count |
| `tool.load_chunk` | `Tools.call` | chunk id | window size |
| `chunk.view` | `chunkRoute` | chunk id | — |
| `library.upload` | `uploadRoute` | file name | bytes, converted-from, replaced, status |
| `library.upload.denied` | `uploadRoute` | file name | reason |
| `library.reindex` | `reindexRoute` | — | chunks re-embedded, docs added/removed |
| `admin.audit.read` | `/admin/audit` | filter summary | rows returned |
| `admin.logs.query` | `/admin/logs` | service + filter | range, rows |
| `admin.metrics.query` | `/admin/metrics` | named query | range |
| `mcp.call` | `mcpRoute` | tool name | — |

Two rules that are easy to get wrong and expensive to fix later:

- **`GET /config` is not audited.** The UI polls it every 60 seconds
  (`App.tsx`), and per-user-per-minute rows would bury everything else within a
  day. `AUDIT_READS=on` turns it on for a deployment that must have it.
- **Never write a bearer token, a password, an upload's bytes, or a Gemini API
  key into `detail`.** Add a test that asserts the serialized detail of an
  upload record contains none of the request body.

`Tools.call` is invoked both in-process by `Chat.kt` and over `POST /mcp`, so it
needs the calling principal threaded through as a parameter — currently it has
none. That is the one non-trivial signature change in this phase.

---

## 5. Phase 3 — the observability stack in compose

Four new services. None publishes a port; all sit on the compose network exactly
as Postgres and Qdrant do today.

```
observability/
  loki-config.yml
  prometheus.yml
  alloy-config.alloy
```

### Services

**`loki`** — `grafana/loki:3.x`, single-binary filesystem mode, `retention_period`
from `LOG_RETENTION_DAYS` (default 14). Volume `loki:/loki`. Health check on
`/ready`.

**`alloy`** — `grafana/alloy:latest`. Mounts `/var/run/docker.sock:ro,z` and the
config `:ro,z`. Its pipeline:

```
discovery.docker            every container in the project
  → discovery.relabel       compose service name → the `service` label
  → loki.source.docker
  → loki.process            stage.json → level, logger, user, req, route,
                            status, duration_ms; stage.timestamp from `ts`;
                            stage.labels for `level`; stage.structured_metadata
                            for the rest
  → loki.write              http://loki:3100/loki/api/v1/push
```

Three points that decide whether this works:

- **Labels: `service` and `level` only.** Everything else becomes Loki
  *structured metadata* (Loki 3.x), which is filterable without being indexed.
  A high-cardinality label — container id, request id, username — turns a small
  deployment into a slow one, and it is the single most common way to make Loki
  unusable. `user` is genuinely tempting as a label and must not be one.
- **`stage.timestamp` reads the `ts` field**, so a line's time is when the
  service emitted it, not when Alloy read it. Without this a burst of logs after
  a restart all arrive with the same timestamp and the date filters lie.
- **A `stage.json` that fails must not drop the line.** Configure the fallback
  path so an unparseable line is still shipped with `format="unstructured"` and
  the whole line as `msg` — otherwise the one service that regresses to plain
  text goes silently missing from the log view, which is the worst possible
  failure for an audit tool.

#### Making each service emit JSON

| Service | How |
|---|---|
| `gateway` | `logback.xml` + logstash encoder, `LOG_FORMAT=json` (§2.5) |
| `ui` (nginx) | `log_format json_combined escape=json '{…}'` in `ui/nginx.conf`, and `access_log /dev/stdout json_combined`. `escape=json` is mandatory — without it a request path containing a quote produces invalid JSON. Error log stays as it is; nginx cannot emit a JSON error log, so Alloy tags it unstructured. |
| `keycloak` | `KC_LOG_CONSOLE_OUTPUT: json` |
| `postgres` | `command: postgres -c log_destination=jsonlog -c logging_collector=off` (`jsonlog` is Postgres 15+, so 18 is fine) |
| `qdrant` | `QDRANT__LOGGER__STDOUT__FORMAT: json`. **Verify on v1.15.1** — the structured-logger config is comparatively recent; if it is not honoured, leave it and let Alloy tag it unstructured rather than writing a regex. |
| `loki` | `log_format: json` in `loki-config.yml` |
| `prometheus` | `--log.format=json` |
| `alloy` | `--log.format=json` |
| `cadvisor` | Not possible — glog. Tagged `format=unstructured`. |

The field names differ per service (nginx's `time_local`, Postgres's
`timestamp`, Keycloak's `timestamp`/`loggerName`). Normalise them **in Alloy**,
in one `stage.json` per service group, so that everything downstream —
the gateway's query builder, the API contract, the UI — only ever sees `ts`,
`level`, `service`, `msg`. Doing this normalisation later, in the gateway or the
browser, means writing it twice and getting it inconsistent.

Severity is likewise not uniform: Postgres says `LOG`/`WARNING`/`FATAL`, nginx's
access log has no level at all, Keycloak and the gateway say `INFO`/`WARN`/`ERROR`.
Map them all to one ladder — `debug < info < warn < error < fatal` — in the same
Alloy stage, and keep the original in structured metadata as `level_raw` so
nothing is lost.

**`prometheus`** — `prom/prometheus:latest`, `--storage.tsdb.retention.time` from
`METRICS_RETENTION_DAYS` (default 15). Volume `prometheus:/prometheus`. Scrape
targets:

| Job | Target | Needs |
|---|---|---|
| `gateway` | `gateway:8090/metrics` | Phase 4 |
| `qdrant` | `qdrant:6333/metrics` | nothing — Qdrant exposes it already |
| `keycloak` | `keycloak:9000/seshat/auth/metrics` | `KC_METRICS_ENABLED: "true"` |
| `cadvisor` | `cadvisor:8080/metrics` | the service below |
| `loki` | `loki:3100/metrics` | nothing |
| `prometheus` | itself | nothing |

The Keycloak path repeats the lesson already written into the compose file's
health check: the management interface inherits `KC_HTTP_RELATIVE_PATH`, so the
metrics path carries the `/seshat/auth` prefix. `:9000/metrics` returns 404 and
looks like the feature is off.

**`cadvisor`** — `gcr.io/cadvisor/cadvisor:latest`. Per-container CPU, memory,
network and disk for every service including nginx and Postgres, which is why
neither needs its own exporter in phase 1. It is the fussiest container here:
on a cgroup-v2 SELinux host (this one is Fedora) it needs `/`, `/var/run`,
`/sys`, `/var/lib/docker` and `/dev/disk` mounted read-only, and typically
`privileged: true`. **Verify it on the actual host before building anything on
top of it** — if it will not start cleanly, fall back to gateway-side metrics
plus Loki, and add `postgres_exporter` and `nginx-prometheus-exporter` instead.

### Every service gets log rotation

```yaml
x-logging: &logging
  driver: json-file
  options: { max-size: "10m", max-file: "3" }
```

…applied via `logging: *logging` on all nine services. Without it the json-file
logs Alloy tails grow without bound, and the first symptom is a full disk.

### Cost, stated plainly

This takes the stack from 5 containers to 9 and adds roughly 700MB–1GB of
resident memory and two more disk-backed volumes. On a laptop that is noticeable.
Consider a compose **profile** (`--profile observe`) so the base stack stays as
light as it is now and the observability half is opted into — at the cost of the
Admin tab reporting its features unavailable when it is not running, which
`/config.admin.features` already handles by design.

---

## 6. Phase 4 — the admin API

New file `Admin.kt`, wired into `Http.start()` as `/admin/` contexts. Every
route: `requireAuditor` first, audit record last.

```
GET /admin/logs?level&user&service&from&to&q&limit&cursor
GET /admin/logs/tail?level&user&service&q          SSE, reusing the Sse class
GET /admin/logs/facets                             the values the filters offer
GET /admin/audit?level&user&action&outcome&from&to&q&limit&cursor
GET /admin/audit.csv?…                             same filters, streamed export
GET /admin/logs.csv?…                              same filters, streamed export
GET /admin/metrics?panel=<name>&from&to
GET /admin/services                                per-service up/down + errors
```

### One filter contract, both views

Logs and audit answer the same four questions, so they take the same parameters
and the UI uses one filter component for both (§7). This is the contract:

| Parameter | Meaning | Applies to | Notes |
|---|---|---|---|
| `level` | minimum severity, `debug\|info\|warn\|error\|fatal` | logs | A **floor**, not an equality — `level=warn` returns warnings *and* errors. Getting this wrong is the classic log-viewer bug: a reader filters to `warn` to find trouble and the errors disappear. |
| `level` | `ok\|denied\|error` | audit | The audit table's `outcome`, exposed under the same parameter name so the shared filter bar needs no special case. `outcome` stays available as an explicit alias. |
| `user` | username, exact or `*` | both | Logs: the `user` structured-metadata field, present on every line emitted during a request (§2.5). Audit: the `username` column. |
| `service` | compose service name | logs | Multi-select; absent means all nine. |
| `action` | `chat.turn`, `library.upload`, … | audit | Multi-select, from a fixed enum. |
| `from`, `to` | RFC 3339 instants | both | Absolute on the wire. The UI's "last 15 minutes" presets resolve to instants **in the browser** and send those, so a slow render or a paused tab cannot silently shift the window. |
| `q` | free-text substring | both | Logs: a Loki `\|=` line filter, escaped. Audit: `ilike` against `target` and `detail::text`, parameterised. |
| `limit`, `cursor` | paging | both | `limit` capped at 1000; `cursor` is the last row's `(ts, id)`, so paging is stable while new rows arrive. |

The browser never sends LogQL or PromQL. `level=warn&user=rock&service=gateway&q=timeout`
becomes, in Kotlin:

```
{service="gateway"} | json | level=~"WARN|ERROR|FATAL" | user="rock" |= "timeout"
```

…with `q` escaped before it goes anywhere near that string, and `user` validated
against a username charset rather than interpolated. Same for metrics: `panel=cpu`
maps to a PromQL query written in Kotlin, not sent from React. Unit-test the
builder against a `q` containing `"`, `\` and a newline — that test is the whole
defence.

`GET /admin/logs/facets` returns the values the filter controls should offer:
the services currently shipping logs (Loki's label-values API), the distinct
usernames seen in the window, and the action enum. Populating a dropdown from
real data beats a hard-coded list that drifts, and it costs one cached call.

Named panels for phase 4 — this list is the Admin UI's whole data contract:

| Panel | PromQL, roughly |
|---|---|
| `cpu` | `rate(container_cpu_usage_seconds_total{...}[5m])` by service |
| `memory` | `container_memory_working_set_bytes` by service |
| `http_rate` | `rate(seshat_http_requests_total[5m])` by route |
| `http_errors` | same, `status=~"5.."` |
| `http_latency` | `histogram_quantile(0.95, ...seshat_http_request_seconds_bucket...)` |
| `chat_turns` | `rate(seshat_chat_turns_total[15m])` |
| `chat_latency` | p50/p95 of `seshat_chat_turn_seconds` |
| `embed_calls` | `rate(seshat_embed_requests_total[15m])`, split by outcome (429s matter) |
| `index_queue` | `seshat_index_pending` gauge |
| `qdrant_points` | Qdrant's own collection gauge |
| `db_pool` | Hikari active/idle, exposed as gauges |
| `audit_dropped` | `seshat_audit_dropped_total` |

### Guard rails on the proxy

- Fixed upstream URLs from config (`LOKI_URL`, `PROMETHEUS_URL`). Nothing from
  the request selects a host — otherwise this is an SSRF endpoint wearing an
  admin badge.
- Cap `limit` (≤ 1000) and the time range (≤ 7 days) server-side.
- Upstream timeout of 15s, and a clear 503 body when Loki or Prometheus is
  absent — not a 500.
- `/admin/logs/tail` polls `query_range` on a 2s cadence and pushes SSE, rather
  than proxying Loki's WebSocket tail. One protocol on this hop, and the `Sse`
  class already handles the client-disconnect case.

### Gateway metrics (`Metrics.kt`, new)

Registered at process start, exposed at `GET /metrics`, **unauthenticated but
only reachable on the compose network** — nginx must not proxy `/seshat/api/metrics`.
Add an explicit `location = /seshat/api/metrics { return 404; }` to
`ui/nginx.conf` rather than relying on nobody noticing; the gateway's port is
not published, but the proxy is, and that is the whole path.

Instrument: HTTP requests by route/status/method, request duration histogram,
chat turns and duration, tool calls by name and outcome, embedding requests by
outcome, index queue depth, documents and chunks as gauges, audit queue depth
and drops, plus the JVM defaults from the instrumentation module.

---

## 7. Phase 5 — the Admin tab

### The switch

`uiSlice.ts` gains `view: 'chat' | 'admin'` (persisted with the rest of the UI
state) and `adminTab: 'logs' | 'metrics' | 'audit' | 'services'`.

`App.tsx` currently renders `<Rail>` + `<main>` with the header, transcript and
composer hard-wired. Split it: the header keeps the rail toggle, eyebrow and
theme control; a segmented control renders between the eyebrow and the spacer
**only when `config.admin.is_admin`**; the body of `<main>` becomes
`view === 'chat' ? <ChatView/> : <AdminView/>`.

Guard against the stale-state case: an account whose `admin` role is revoked
still has `view: 'admin'` in localStorage. When `config.admin.is_admin` is
false, force the view back to chat on every config refresh — not just on first
load.

The rail stays visible in both views (threads are still there when you come
back), and the sources drawer is chat-only.

### New files

```
ui/src/features/admin/
  AdminView.tsx        the sub-tab shell
  FilterBar.tsx        severity · who · when · service/action · search — SHARED
  RecordTable.tsx      the structured record view — SHARED
  LogsPanel.tsx        FilterBar + RecordTable + live tail
  AuditPanel.tsx       FilterBar + RecordTable + CSV export
  MetricsPanel.tsx     the named panels, as small multiples
  ServicesPanel.tsx    one card per container: up/down, restarts, error rate
  filters.ts           the filter state, its URL encoding, its presets
  api.ts               typed fetches against /admin/*
  types.ts
ui/src/components/Sparkline.tsx
```

### The shared filter bar

One component, one state shape, used by Logs and Audit alike — because a reader
who has learnt the filters in one view has learnt them in the other, and because
two implementations of a date-range control will diverge within a month.

```ts
interface Filter {
  level: Level | null              // floor for logs, outcome for audit
  users: string[]                  // empty = everyone
  services: string[]               // logs only
  actions: string[]                // audit only
  range: Preset | { from: string; to: string }
  q: string
}
```

- **Severity** — a five-step segmented control, not a dropdown. It is the
  control that gets used most, it has five fixed values, and one click should
  move it. Label it as a floor (`warn and above`) so the semantics in §6 are
  visible rather than assumed.
- **Who** — a multi-select populated from `/admin/logs/facets`, with free text
  for a name not in the window. Chips, so several users can be watched at once.
- **When** — presets (15m, 1h, 6h, 24h, 7d) plus a custom absolute range. The
  presets resolve to instants in the browser at query time (§6). Show the
  resolved absolute range under the control, always: "last 1 hour" that silently
  means something different on a stale tab is how people misread an incident.
- **Where** — services for logs, actions for audit, same chip control.
- **Search** — debounced 300ms, and never fired on an empty string.

The filter serialises into the URL query string. An administrator investigating
something needs to be able to send a colleague the exact view they are looking
at, and that costs one `useSearchParams`-shaped hook. It also makes the browser
back button do the obvious thing.

### Structured display, not lines

`RecordTable.tsx` renders a record, not a string. Both views share it:

- **Fixed columns** — time, severity (as a colour-coded chip), who, service or
  action, message or target. Right-aligned monospace for the timestamp, so
  scanning a column of times actually works.
- **A row expands** to a key/value table of every remaining field: `route`,
  `status`, `duration_ms`, `req`, `logger`, `err` and stack for a log; the whole
  `detail` JSONB for an audit record. Rendered as a definition list, never as a
  pretty-printed JSON blob — the codebase already holds this line elsewhere
  ("the UI never shows a raw JSON blob to a reader", `types.ts:Trace`).
- **Correlated by `req`** — clicking the request id filters both views to it. A
  chat turn that failed is then one audit row and the four log lines it produced,
  together. This is the payoff for putting `req` and `user` in MDC back in §2.5,
  and it is worth building even though nothing else needs it.
- **Unstructured rows** (cAdvisor, nginx's error log) render with the columns
  they have and the raw line as the message, visibly marked. No guessing.
- **Empty and truncated states are explicit**: "no records match these filters"
  with a one-click widen, and "showing the newest 1000 of N — narrow the range"
  rather than a silently short list.

### Two more UI decisions

**No chart library.** The metrics panels want small, dense,
single-series-over-time plots. A hand-rolled `<Sparkline points={…}/>` in SVG is
~40 lines, matches the inline-SVG idiom the architecture document already uses,
adds no dependency, and themes off the existing CSS custom properties. Recharts
would add ~500KB to a bundle that currently ships React, Redux and
react-markdown and nothing else.

**The record list is virtualised, not a plain `<table>`.** A 1000-row result set
in React rows is a visible stall. Fixed row height, windowed rendering,
newest-first, with the live tail prepending — and a "paused, N new records" chip
when the reader has scrolled up, because auto-scrolling out from under someone
reading an error is the thing every log viewer gets wrong.

Live tail uses `EventSource`… except `EventSource` cannot send an
`Authorization` header. `chatStream.ts` already solves this for `/chat` by
reading the SSE stream out of `fetch`; reuse that reader rather than reaching
for `EventSource` and then discovering the problem.

---

## 8. Phase 6 — documentation and tests

- **`docs/seshat-architecture.html`** — this is a five-container document with
  nine hand-authored SVG diagrams. It becomes a nine-container document. The
  Level 1 and Level 2 figures both need the new services drawn in, the settings
  table needs the new environment variables, and there is a new figure worth
  having: the audit record's path from route to Postgres to the Admin tab. Budget
  real time for the SVG work; the coordinate-shifting approach from the last
  edit (script the shift, assert on the diagram, diff, then render the affected
  pages to PNG and look at them) applies again. Rebuild the PDF and re-publish
  to `/var/www/html/`.
- **`README.md`** — new section on the observability stack, the Docker-socket
  and cAdvisor privileges, the retention settings, and what the Admin tab shows.
- **`.env.example`** — a new block, in the existing commented style, for
  `LOKI_URL`, `PROMETHEUS_URL`, `LOG_RETENTION_DAYS`, `METRICS_RETENTION_DAYS`,
  `AUDIT_RETENTION_DAYS`, `AUDIT_CHAT_PROMPTS`, `AUDIT_READS`, `AUDIT_BLOCKING`,
  `LOG_FORMAT`, `SEED_ADMIN_PASSWORD`, `SEED_GUEST_PASSWORD`.
- **Tests.** The CI comment is explicit that the suites must not need
  `docker compose up`, so keep to pure functions. Gateway: LogQL and PromQL
  construction from a `Filter` (including a `q` containing `"`, `\` and a
  newline, and a `user` containing a LogQL metacharacter), the severity floor
  expanding to the right set of levels, the audit filter → SQL builder with
  parameterised `q`, cursor paging across equal timestamps, redaction of the
  detail object, `trimHistory`-style bounds on `limit` and range, the role
  guards. UI: the sparkline path builder, the filter reducer, filter ⇄ URL
  round-tripping, and preset → absolute-instant resolution. No new CI jobs —
  both existing jobs pick these up.
- **One thing the tests cannot cover**, so verify it by hand once and write down
  the result: that each of the nine services actually emits the JSON shape §5
  claims it does. `docker compose logs <svc> --tail 5 | jq .` per service is the
  check, and Qdrant and Postgres are the two most likely to disappoint.

---

## 9. What will bite

1. **cAdvisor on this host.** Fedora, cgroup v2, SELinux enforcing. Prove it
   starts before designing panels that depend on it (§5).
2. **The Docker socket mount** is a real privilege grant to the Alloy container.
   Read-only, unpublished, documented.
3. **Audit volume.** A busy day of chat turns and tool calls is thousands of
   rows. The indexes above are the ones the Admin filters actually use; adding
   more later is cheap, and `select * from audit order by at desc` with no index
   is the thing that makes the tab feel broken.
4. **The privacy reversal in §2.1** is the decision most worth being deliberate
   about. It is a change to what the system promises its users, not just a
   feature.
5. **Two sources of "who did what".** Keycloak has its own event log
   (enabled in phase 1); the gateway now has its own. They will disagree at the
   edges — a login Keycloak recorded that never reached the gateway, a token
   still valid after a session Keycloak ended. Decide which is authoritative
   (recommendation: the gateway's, for *actions*; Keycloak's, for
   *authentication*) and say so in the UI's column headers rather than letting a
   reader assume.
6. **`Tools.call` gains a principal parameter**, which touches `Chat.kt`,
   `Http.kt` and the MCP path. It is mechanical but it is the one change that
   ripples.
7. **JSON logs make `docker compose logs` worse** for the person at the
   terminal, which is a daily cost paid for an occasional benefit. `LOG_FORMAT`
   and the `jq` line in the README (§2.5) are not optional politeness — without
   them this change will be quietly reverted the first time someone debugs a
   startup failure.
8. **Field-name normalisation is the fragile seam.** Nine services, nine
   opinions about what the timestamp field is called. It lives in exactly one
   place (Alloy) by design; the failure mode when it is wrong is a log view that
   looks fine and is missing one service, so the by-hand check in §8 matters
   more than it sounds.
9. **The severity floor.** `level=warn` must include errors. Say it in the UI,
   test it in the query builder, and do not let it become an equality filter in
   a later refactor — a log viewer that hides errors when you filter for trouble
   is worse than no log viewer.

---

## 10. File-by-file summary

**New**

```
gateway/src/main/kotlin/Audit.kt
gateway/src/main/kotlin/Admin.kt
gateway/src/main/kotlin/Metrics.kt
gateway/src/main/kotlin/Observability.kt      Loki + Prometheus clients
gateway/src/test/kotlin/AuditTest.kt
gateway/src/test/kotlin/AdminQueryTest.kt
gateway/src/test/kotlin/LogQueryTest.kt        LogQL/PromQL builder + escaping
observability/loki-config.yml
observability/prometheus.yml
observability/alloy-config.alloy              discovery, JSON parsing, level map
ui/src/features/admin/{AdminView,LogsPanel,MetricsPanel,AuditPanel,ServicesPanel}.tsx
ui/src/features/admin/{FilterBar,RecordTable}.tsx
ui/src/features/admin/{filters,api,types}.ts
ui/src/features/admin/filters.test.ts
ui/src/components/Sparkline.tsx
```

**Modified**

```
gateway/src/main/kotlin/Auth.kt        subject, sessionId, mayAudit
gateway/src/main/kotlin/Config.kt      ~10 new settings
gateway/src/main/kotlin/Db.kt          audit table, insert, query, retention
gateway/src/main/kotlin/Http.kt        requireAdmin/requireAuditor, audit hook,
                                       /admin routes, /metrics, /config.admin
gateway/src/main/kotlin/Tools.kt       principal parameter, tool-call audit
gateway/src/main/kotlin/Chat.kt        pass the principal through
gateway/src/main/kotlin/Main.kt        construct Audit and Metrics
gateway/src/main/resources/logback.xml JSON encoder, LOG_FORMAT switch,
                                       MDC user + req in every line
gateway/build.gradle.kts               prometheus-metrics-core + jvm
                                       instrumentation, logstash-logback-encoder
docker-compose.yml                     4 services, 2 volumes, logging defaults,
                                       KC_METRICS_ENABLED, per-service JSON log
                                       settings (§5), new gateway env
keycloak/seshat-realm.json             composite role, guest → /readers,
                                       seeded passwords from env, events on
ui/nginx.conf                          json_combined log_format with escape=json,
                                       block /seshat/api/metrics
ui/src/App.tsx                         view switch + the tab control
ui/src/store/uiSlice.ts                view, adminTab
ui/src/types.ts                        ServerConfig.admin
ui/src/index.css                       admin panel styles
.env.example                           new block
README.md                              new section
docs/seshat-architecture.html          two figures redrawn, one added, tables
```

Rough order of work: §3 → §4 → §5 → §6 → §7 → §8. Phases 3 and 5 are
independent of each other and can be done in either order; phase 6 depends on
both.
