# Seshat

Ask a private folder of documents, and get answers cited back to the passage
they came from.

Seshat was the Egyptian goddess of writing, measurement and the record —
Mistress of the House of Books. She did not heal, judge or rule. She wrote
things down, and that is the whole design brief here: the system will tell you
what the library says and where it says it, and it will tell you when the
library does not say anything at all.

```
cp .env.example .env          # then set GEMINI_API_KEY
docker compose up -d --build
open http://localhost:8800/seshat/
```

Sign in as **peter@peter.co.nz**. Drag documents onto the window — any format:
PDFs, Word files and spreadsheets are converted to text by Apache Tika on the
way in, and everything is searchable before the upload finishes. Text files
dropped into `library/` by hand are picked up within a minute.

---

## What it is

Nine containers, and one published port.

| Service | What it is | Why |
|---|---|---|
| `ui` | nginx | The only public entrance. Serves the SPA and reverse-proxies the other two browser-facing services, so everything is one origin under `/seshat`. |
| `gateway` | one Kotlin fat jar | Both the MCP server and the chat gateway. Reads the library, chunks it, indexes it, searches it, and streams Gemini's answers over those same tools. Also keeps the audit trail and serves the admin API. |
| `postgres` | Postgres 18 | The chunks, the document registry the scanner diffs a folder against, and the audit trail. |
| `qdrant` | Qdrant | The vectors: dense (Gemini embeddings) and sparse (BM25). |
| `keycloak` | Keycloak 26 | Identity. The gateway verifies its tokens; the SPA redirects to it. |
| `loki` | Loki | The log store. |
| `alloy` | Grafana Alloy | Tails every container above and ships it to Loki. |
| `prometheus` | Prometheus | Scrapes everything that exposes metrics. |
| `cadvisor` | cAdvisor | Per-container CPU, memory, network and disk, for Prometheus. |

The last four are the [Admin tab](#administration-auditing-logs-and-metrics)'s
supply. They publish nothing: the browser reaches logs and metrics through the
gateway's admin API, which checks a Keycloak role first. They add roughly
700MB–1GB of resident memory between them; `docker compose stop loki alloy
prometheus cadvisor` with `LOKI_URL=` and `PROMETHEUS_URL=` blank in `.env`
leaves the audit trail working and hides the rest of the tab.

The gateway being one process rather than two is the main structural decision.
An MCP server and a chat gateway that calls MCP tools need exactly the same
things — the corpus, the vector store, the embedding model, the auth check — so
splitting them would mean running two jars to duplicate one set of connections
and adding an HTTP hop between a chat turn and its own retrieval. The tools are
defined once in `Tools.kt` and reached two ways: over `POST /mcp` by any MCP
client, and in-process by the chat agent.

```
  upload, any format          library/*.txt
      │  Tika ──▶ text             │  sha-256 diff, every minute
      └──────────────┬─────────────┘
                     ▼
┌───────────────┐  chunks, min 200 chars ───────────▶ ┌────────────┐
│  chunk        │  cut where the meaning changes      │  Postgres  │  the text
│  embed        │  dense (Gemini) + sparse (BM25) ──▶ │   Qdrant   │  the vectors
└───────────────┘                                     └─────┬──────┘
                                                            │
              ┌──────────── search / load_chunk ────────────┘
              ▼
      ┌───────────────┐   POST /mcp    ─────▶  any MCP client
      │  Tools.kt     │
      └───────┬───────┘   in-process   ─────▶  Gemini ──▶ SSE ──▶ the chat UI
              └── one definition, two callers
```

## The two tools

| Tool | What it does |
|---|---|
| `search` | Hybrid retrieval over the corpus. Fuses BM25 keyword matching with dense vector similarity, server-side, with reciprocal rank fusion. `mode` selects `hybrid` (default), `dense` or `keyword`. |
| `load_chunk` | One paragraph by id, optionally with `before`/`after` neighbours from the same document. What a citation opens, and what the model calls when a hit is cut off mid-thought. |

### Pointing an MCP client at it

Publish the gateway's port (uncomment it in `docker-compose.yml`) and point any
Streamable-HTTP MCP client at `http://localhost:8090/mcp`. The handshake —
`initialize`, `tools/list`, `ping` — is open, so a client can connect and
discover the catalogue; `tools/call` needs a Keycloak bearer token with the
`use-ui` role.

## How retrieval works

**Chunking is semantic: the cut goes where the meaning changes.** A document is
split into sentences, every sentence is embedded, and adjacent sentences are
bunched while they keep talking about the same thing — cosine similarity between
the next sentence and the running centroid of the bunch, against
`SEMANTIC_THRESHOLD` (0.75). The centroid rather than the previous sentence is
what makes it stable: one short aside inside a passage would drag a pairwise
comparison across the threshold and split a paragraph that was never going to
end.

Two bounds keep that from degenerating, and both are in `.env`:
`CHUNK_MIN_CHARS` (**200**) is a floor a bunch is not allowed to be under — below
it the next sentence joins whatever the similarity says, because a heading or a
date line on its own embeds to a vector that retrieves either nothing or
everything. `CHUNK_MAX_CHARS` (3,000) is a ceiling — a glossary is similar to
itself all the way down, and without a cap it would be one vector meaning
nothing in particular.

The cost is an embedding call per sentence at index time on top of the one per
finished chunk; you cannot know where the meaning turns without embedding both
sides of the turn. It is paid once per document version. Each document records
the chunker and settings it was built with, so **changing any of those settings
re-chunks the corpus on the next scan** rather than leaving it half one shape
and half another. `SEMANTIC_CHUNKING=off` falls back to the paragraph rule
(split on blank lines, glue runs under 90 characters onto what follows, cut over
the maximum at sentence boundaries), which is also what runs if the embedding
API is unreachable mid-index.

**BM25 is split across the two ends of the query.** Score factors as
`Σ idf(t) · tf_norm(t, d)`, and Qdrant scores sparse vectors by dot product, so
the stored vector carries term frequency and length normalisation while the
query vector carries presence. Inverse document frequency comes from Qdrant
itself, live, via the sparse vector's `Modifier.Idf`. That is what keeps the
index honest as documents arrive — baking idf into stored vectors means every
existing chunk carries statistics for a corpus that no longer exists.

**Postgres is the only copy of the text.** Qdrant holds vectors and ids;
`chunk.id` is also the Qdrant point id, so one integer names a paragraph in both
stores. The vector index can therefore be dropped and rebuilt from Postgres
alone — that is the second half of `POST /reindex` — and no answer ever depends
on two copies of the same text agreeing.

**There is no reranker.** A cross-encoder rerank would measurably improve
ordering, and it needs a local model — the one dependency this build exists to
avoid. RRF over the two retrievers is the ranking.

## Where the text comes from

`library/` on the host: one text document per file, which is what makes the
corpus greppable, diffable and re-indexable without anything having to be
re-parsed.

**Upload any format.** Apache Tika converts whatever arrives that is not already
text — PDF, Word, Excel, PowerPoint, OpenDocument, RTF, EPUB, HTML, email — and
stores the text it found under the same name with `.txt` on the end
(`report.pdf` → `report.pdf.txt`). A text file is stored byte for byte instead,
except when it is not valid UTF-8, in which case Tika detects its encoding and
transcodes it. The original binary is not kept: it is a second copy of a
document the uploader already has, and it would make every deletion rule reason
about pairs of files. Two limits worth knowing: layout is discarded (reading
order, not geometry — the right input for retrieval), and there is no OCR, so a
scanned page converts to nothing and the upload says so rather than indexing an
empty document.

Files placed in the folder **by hand** are still text-only, on an extension
(`txt`, `md`, `markdown`, `rst`, `log`, `csv`, `tsv`, `json`, `yaml`, `adoc`, …)
and then a strict UTF-8 decode. The second test is the one that matters — a PDF
renamed to `.txt` passes the first and would otherwise be indexed as kilobytes
of mojibake that pollutes every search. Skipped files are logged, not errors:
drop the PDF on the window instead and it is converted.

**The folder is diffed against the index every `LIBRARY_SCAN_MINUTES`, one
minute by default.** Files that appeared are indexed; files that are gone are
unindexed, from Postgres and Qdrant both. That cadence is affordable because
each file is hashed: an unchanged corpus costs a read and a hash per file and no
API call at all. With `LIBRARY_MIRROR=off` documents accumulate instead and
nothing is ever dropped.

**Uploads do not wait for a tick.** The rail has an *Add documents* button, and
a file dropped anywhere on the window does the same thing: it is converted if it
needs converting, written into `library/`, then chunked, embedded and indexed
inside the request — so the answer that comes back already says what it was
converted from and how many chunks it added. Several files go one at a time,
each with its own verdict in the rail. A name that already exists is replaced
rather than duplicated — the alternative is `notes.md` and `notes-2.md` both
matching every future search. Uploading needs the `admin` role unless
`UPLOAD_ADMIN_ONLY=off`, and `UPLOAD_MAX_MB` (25) caps a single file.

`POST /reindex` runs the same folder pass first — so it picks up anything the
scanner missed and drops any document whose file is gone, from Qdrant and
Postgres both — and only then re-embeds every chunk that is left. It has no
button; it is the repair path for a dropped Qdrant volume, and `curl -X POST`
with an admin token is its interface.

## Auth

Keycloak realm `seshat`, imported on first boot from
`keycloak/seshat-realm.json`. Three realm roles:

| Role | Grants |
|---|---|
| `use-ui` | Required for everything. Carried by the realm default role, so any new account can sign in and search without being granted anything. |
| `admin-observability` | The Admin tab: the audit trail, the consolidated container logs, and the metrics panels. |
| `admin` | Adding documents to the library and running `POST /reindex`. **Composite** — it carries `use-ui` and `admin-observability`, so one grant is the whole administrator. |

The two capabilities come apart on purpose. `admin-observability` alone is an
auditor who can read what everyone did but cannot change the corpus — an
assignment in Keycloak (the `/auditors` group), not a change to any code.

| User | Group | Roles |
|---|---|---|
| rock | `/admins` | use-ui, admin, admin-observability |
| guest | `/readers` | use-ui |

**The seeded passwords are not in this repository.** They are
`${SEED_ADMIN_PASSWORD}` and `${SEED_GUEST_PASSWORD}` placeholders in the realm
file, substituted at import time from `.env`. Set both there before first boot:

```bash
SEED_ADMIN_PASSWORD='...'     # quote it — compose interpolates this file, so a
SEED_GUEST_PASSWORD='...'     # bare $secret is read as a variable and arrives empty
```

Two things about that which cost an hour each if discovered later. The
substitution is `${VAR}`, and an **unset** variable is left in place as the
literal string `${SEED_ADMIN_PASSWORD}` — so the defaults in the realm file are
`change-me` and `change-me-too`, which are useless on purpose rather than
silently wrong. And Keycloak's `start-dev` keeps its database **inside the
container with no volume**, so recreating the keycloak container re-imports the
realm and re-applies these two passwords; a password changed only in the console
does not survive that.

Sign in with either the username or the email — `loginWithEmailAllowed` is on.

The Keycloak admin console is at `/seshat/auth/admin/`. Its password is `admin`
when you start the stack by hand, and a generated one when `install.sh` sets it
up (printed at the end of the install, and stored in `.env`). Change the seeded
account's password before this is reachable from anywhere real.

The SPA uses Authorization Code + PKCE as a public client; it never handles a
password. Every gateway call carries the resulting access token, and the gateway
verifies the signature against the realm JWKS, the issuer, the audience and the
expiry window before it does anything else.

**The issuer and the JWKS URL are deliberately different.** The issuer is what
Keycloak stamps into a token: the browser-facing URL, through the proxy. The
JWKS URL is where the gateway container fetches the signing keys, and it cannot
use the public URL — inside the compose network that resolves to the gateway
itself. So the issuer is `${PUBLIC_URL}/seshat/auth/realms/seshat` and the JWKS
URL goes direct to `http://keycloak:8810/...`.

## Administration: auditing, logs and metrics

Administrators get a second tab beside Chat. It is rendered only for accounts
the **server** says may have it (`GET /config` answers `admin.may_audit`), and
every route behind it re-checks the role — the UI decides what to draw, never
what is allowed.

Four panels, and one filter shared between the first two: **severity · who ·
when · where · search**. A reader who has learnt the filters in the log view has
learnt them in the audit view.

**Logs.** Every container's output in one place, from Loki. `level` is a
**floor** — `warn` returns warnings *and* errors *and* fatals, because a reader
filters to `warn` precisely when they are looking for trouble. Records are
structured, not lines: fixed columns, and a row expands to every field the
service emitted. Live tail is a toggle; scrolling away pauses it and counts what
arrived rather than yanking the view out from under you.

**Audit.** Every user action: who, when, from where, how long it took, what it
touched, and whether it was allowed. That includes refusals — someone probing an
admin API they do not hold the role for is exactly what this table is for.

**Metrics.** A dozen named panels over Prometheus. The browser asks for a panel
by *name* and never composes PromQL; the queries live in `Panels` in
`Observability.kt`. Same for LogQL — the filter goes over the wire, the query is
built server-side.

**Services.** Which scrape targets Prometheus can currently reach.

Logs and audit rows are joined by a **request id**. One id per request goes onto
the audit row and, through the MDC, onto every log line that request produced —
so clicking it in either view shows a failed action and its log lines together.

### What is recorded, and what is not

`session.start`, `auth.denied` (with the reason, never the credential),
`chat.turn`, `tool.search`, `tool.load_chunk`, `chunk.view`, `library.upload`,
`library.reindex`, `mcp.call`, and every `admin.*` read.

**Chat prompts are hashed by default.** `/chat` promises that no transcript is
written server-side — the browser owns the history — and auditing the questions
verbatim would reverse that. So the *turn* is always recorded (who, when, how
long, how many tool calls) while the prompt is kept as a sha-256 prefix and a
character count. `AUDIT_CHAT_PROMPTS=on` records them in full; that is a change
to what the system promises its users, and they should be told.

The **search queries** the model ran *are* recorded in full either way. They run
against one shared corpus that everyone signed in can read, so what the library
was asked for is the thing an administrator actually needs to see.

`GET /config` is not audited — the UI polls it once a minute per open tab, and a
row per user per minute would bury everything that means something.
`AUDIT_READS=on` if a deployment must have it.

Nothing secret reaches the table: `Audit.sanitize` redacts by key name
(authorization, token, password, secret, api_key, …), truncates long strings and
caps the field count, on every record, so a route cannot leak a credential into
a table that administrators read. It has its own tests.

### How the logs get there

Every container emits **JSON on stdout**, so the pipeline ships fields rather
than a regex's guess at them. Grafana Alloy discovers the containers over the
Docker socket, tails them, normalises nine services' idea of a log line into
`ts` / `level` / `service` / `msg`, and writes to Loki. That normalisation lives
in `observability/alloy-config.alloy` and nowhere else — doing it downstream
would mean doing it twice and getting it inconsistent.

Two services cannot comply, and are marked `raw` in the UI rather than dressed
up with invented fields:

- **Postgres** — `log_destination=jsonlog` *requires* `logging_collector=on`,
  and the collector writes to files inside the container, so stdout goes silent.
  Structured output or collectable output; collectable wins. It stays on stderr
  with a pinned `log_line_prefix`, and Alloy parses it with the one regex in
  that file.
- **cAdvisor** — logs through glog, which has no JSON mode.

To read the containerised logs at a terminal, where JSON is a downgrade:

```bash
docker compose logs gateway | jq -r '"\(.ts) \(.level) \(.logger) \(.msg)"'
```

`LOG_FORMAT=text` restores plain lines; it is the default for a bare
`java -jar`, and compose sets `json`. An unrecognised value makes the gateway
**refuse to start** rather than run silently with no appender — `logback.xml`
names its appenders after the values this takes.

### Privileges these containers hold

Worth stating plainly, because both are real and neither is obvious:

- **alloy** mounts `/var/run/docker.sock`. Read access to the Docker socket is
  close to root on the host. It is mounted read-only, the container publishes
  nothing, and it is the standard way to discover and tail containers — the
  alternative (Loki's log-driver plugin) needs a `docker plugin install` on the
  host before `docker compose up` works at all. On SELinux hosts it also needs
  `security_opt: label=disable`, because a confined `container_t` process may
  not connect to a `container_var_run_t` socket. Without it Alloy discovers
  nothing and the log view is empty with every service healthy —
  `ausearch -m avc -ts recent | grep docker.sock` is what says so.
- **cadvisor** runs `privileged` with `/`, `/sys` and `/var/lib/docker` mounted
  read-only. It is the fussiest container in the stack on a cgroup-v2 SELinux
  host. If it will not run cleanly, stop it: only the per-container CPU and
  memory panels go empty, and everything else — including the whole log and
  audit half of the tab — is unaffected.

The gateway's `/metrics` is unauthenticated, because Prometheus holds no token
and giving it one would mean a service-account client and a credential in the
scrape config to protect a document saying how many chunks are indexed. The
control is reachability: the gateway's port is not published, and
`ui/nginx.conf` returns 404 for `/seshat/api/metrics` so the proxy that *is*
published cannot be a way in.

### Retention

`AUDIT_RETENTION_DAYS` (90) sweeps the audit table daily; `LOG_RETENTION_DAYS`
(14) is Loki's; `METRICS_RETENTION_DAYS` (15) is Prometheus'. Every container
also has `json-file` rotation at 10MB × 3, because Alloy tails those files and
without a cap the first symptom is a full disk.


## Deploying to another machine

```bash
./installer/build-installer.sh          # -> dist/seshat-1.0.0.tar.gz (~180 KB)
scp dist/seshat-1.0.0.tar.gz you@myserver:~/
```

On the target — Docker with the Compose plugin is the only prerequisite; both
images build from source inside Docker, so no JDK, Node or Gradle:

```bash
tar xzf seshat-1.0.0.tar.gz && cd seshat-1.0.0
./install.sh --public-url https://myserver --gemini-key AIza...
```

It checks prerequisites, generates passwords, adds your public URL to the
realm's redirect allow list *before* the first boot (the realm is imported once
and only once), builds, starts, verifies that the issuer Keycloak advertises
matches the one the gateway checks, and prints the nginx block to add. The
sample library ships with it, so the first sign-in has something to answer
questions about.

Full detail, including what to do when it does not work: **[installer/README.md](installer/README.md)**.

## Serving it at /seshat on a real host

The stack publishes one port — on **loopback** by default, so nginx is the only
thing that can reach it — and owns the whole `/seshat` prefix, so an outer
reverse proxy forwards one path and nothing else:

```nginx
# ^~ so a dotfile guard (location ~ /\. { deny all; }) cannot outrank this and
# 403 the /.well-known/openid-configuration that sign-in depends on.
location ^~ /seshat/ {
    proxy_pass http://127.0.0.1:8800;
    proxy_http_version 1.1;
    proxy_set_header Host              $host;
    proxy_set_header X-Real-IP         $remote_addr;
    proxy_set_header X-Forwarded-For   $proxy_add_x_forwarded_for;
    proxy_set_header X-Forwarded-Proto $scheme;   # https if you terminate TLS
    proxy_set_header X-Forwarded-Host  $host;
    proxy_set_header X-Forwarded-Port  $server_port;

    proxy_buffering off;          # the chat answer is an SSE stream
    proxy_cache off;
    proxy_read_timeout 10m;       # a turn with tool calls outlives the 60s default
    proxy_send_timeout 10m;

    client_max_body_size 20m;     # the Keycloak admin console posts big forms
}
```

Don't split this into separate blocks for `/seshat/api/` and `/seshat/auth/`:
the nginx *inside* the stack already routes all three, and `/seshat/auth` must
arrive with its path intact or Keycloak's relative-path routing breaks.

Then set `PUBLIC_URL` in `.env` to the origin the **browser** uses — e.g.
`https://myserver`. It goes into the Keycloak token issuer, so it has to match
exactly or every token is rejected. Add that origin to the client's redirect
URIs in `keycloak/seshat-realm.json` (or in the admin console, for a realm that
has already been imported).

`proxy_buffering off` is not optional. With buffering on, the outer nginx holds
the entire answer and delivers it in one lump at the end, which is
indistinguishable from a hung server for the ten seconds a real answer takes.

## Configuration

Everything is in `.env`; `.env.example` documents each entry with its default.
The model and the key are fixed there, as specified — there is no runtime
provider switch and no per-request model override.

`EMBED_DIMS` is index-breaking. Changing it means the stored vectors no longer
match the query vectors: the gateway checks the width of the collection it
finds at boot and refuses to start against one that does not match, rather than
accepting the setting and then failing on every upsert. Delete the Qdrant
volume and re-index.

`CHUNK_MIN_CHARS` governs both chunkers — the semantic one and the paragraph
fallback — and is stamped into each document's chunker signature, so changing
it re-chunks the corpus on the next scan rather than leaving it half one shape
and half another.

`SEED_ADMIN_PASSWORD` and `SEED_GUEST_PASSWORD` are read only when the realm is
first imported, and again whenever the keycloak container is recreated. Quote
any value containing a `$`.

`AUDIT_CHAT_PROMPTS` is the one setting here that changes what the system
promises the people using it, rather than how it runs. Off, a chat turn is
recorded without its question; on, the questions are readable by every
administrator. See [Administration](#administration-auditing-logs-and-metrics).

## Development

```bash
cd gateway && ./gradlew test          # chunking, semantic bunching, BM25, upload
                                      # names, request guards
cd gateway && ./gradlew fatJar        # build/libs/gateway-all.jar

cd ui && npm ci                       # first time
cd ui && npm run lint                 # eslint, type-aware
cd ui && npm test                     # citation parsing, SSE frames, and a
                                      # regression test per UI defect fixed
cd ui && npm run dev                  # :5173, proxying /seshat/api to :8090
```

Both suites are pure — no database, no vector store, no API key — because a
suite that needs `docker compose up` to say anything is a suite nobody runs
before pushing. `.github/workflows/ci.yml` runs exactly the commands above.

The Vite dev server proxies `/seshat/api` to `localhost:8090`, so the app is
same-origin in development exactly as it is in production and no code path
differs between the two.

Keycloak themes are mounted rather than baked into the image: edit
`keycloak/themes/seshat/login/resources/css/seshat.css` and restart the
`keycloak` service.

## Design

The look comes from the Se-ber identity concept, and with it one rule that
governs everything:

> **Rubric is not decoration.** In the medical papyri red ink marks what begins
> something and what must not be misread — the heading of a remedy, and the
> quantity to be given. Everything else is carbon black.

So red is: the mark's arc, section headings, citation markers, and retrieval
scores. Faience (Egyptian blue, the first synthetic pigment) is everything
interactive. Orpiment is hairlines and measurement geometry only, never type. If
two things were red, neither would be the signal.

The mark is Seshat's emblem with its arithmetic intact: seven rays at 51.43°
each under a single red arc, resting on the cord she stretched to lay out a
temple's foundations — which is also a rule under a heading. One shape, both
readings.

## What a minimum version leaves out

Named so it is a decision rather than an omission:

- **No reranker.** See above.
- **No document ACLs.** Everyone who can sign in can search everything. The
  corpus is one folder; per-document permissions need a source of truth for
  them, and a folder is not one.
- **No server-side transcripts.** Conversations live in the browser's
  localStorage. Postgres stores the chunks, per the brief — which has the
  pleasant side effect that a question is never written to a disk the person
  asking it does not own. The cost: a thread does not follow you to another
  machine, and signing out clears it.
- **No delete-from-the-UI.** Documents go in through the UI and come out by
  deleting the file — the folder stays the source of truth, and one direction of
  write is a much smaller thing to reason about than two.
- **No OCR.** Tika reads the text layer of a document; a scanned page has none,
  and reading it needs Tesseract installed beside the gateway. An upload with no
  text in it is refused with that as the reason rather than indexed empty.
- **No conversion on the scan path.** A PDF copied into `library/` by hand is
  skipped, not converted. Converting it would mean the gateway writing files
  into the folder nobody asked it to write, and then owning a rule about what
  happens to the derived text when the original is deleted. Uploads convert;
  the folder stays what it looks like.
- **Term hashing, not a dictionary.** BM25 terms hash to 31-bit indices, so
  nothing has to keep a term table in sync between indexing and querying. Two
  terms can collide; at 2³¹ slots against a vocabulary in the tens of thousands
  that is a ranking nuisance, not a correctness problem.
