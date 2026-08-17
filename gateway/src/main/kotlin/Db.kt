import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.slf4j.LoggerFactory
import java.sql.Connection
import java.sql.ResultSet

/**
 * Postgres: the chunk store, the document registry the library scanner diffs a
 * folder against, and the audit trail.
 *
 * `document` is one row per file in the library
 * (its sha-256 is what makes a rescan a no-op for unchanged files); `chunk` is
 * one row per paragraph, and is the authoritative text — Qdrant holds only
 * vectors and the ids needed to get back here, so the corpus can be re-indexed
 * from Postgres alone without re-reading a single file.
 *
 * `chunk.id` is also the Qdrant point id. One integer identifies a paragraph in
 * both stores, which is why a search hit can be turned back into text with a
 * single primary-key lookup.
 *
 * `audit` is the third table and belongs to a different job entirely — it is
 * written by [Audit], read by the admin API, and touched by nothing that serves
 * a chat turn. It lives here only because the schema is created in one place.
 */
class Db(cfg: Config) : AutoCloseable {
    private val log = LoggerFactory.getLogger("Db")
    private val ds: HikariDataSource

    init {
        val hc = HikariConfig().apply {
            jdbcUrl = toJdbcUrl(cfg.databaseUrl)
            driverClassName = "org.postgresql.Driver"
            maximumPoolSize = 8
            minimumIdle = 1
            poolName = "seshat"
            connectionTimeout = 10_000
            // The scanner's per-document transaction is short; the pool exists
            // to stop chat traffic paying a TCP+TLS handshake per lookup.
            initializationFailTimeout = -1   // start even if Postgres is a moment behind
        }
        ds = HikariDataSource(hc)
    }

    /** Wait for Postgres, then create the schema. Idempotent: `docker compose
     *  up` on an existing volume changes nothing. */
    fun migrate() {
        var attempt = 0
        while (true) {
            try {
                tx { c ->
                    c.createStatement().use { it.execute(SCHEMA) }
                }
                log.info("schema ready")
                return
            } catch (e: Exception) {
                attempt++
                // The ROOT cause, not Hikari's wrapper. A wrong password
                // surfaces as "Connection is not available, request timed out
                // after 10002ms" — which reads as a network problem and sends
                // you looking at the wrong thing entirely. The cause underneath
                // says "password authentication failed for user".
                if (attempt % 6 == 1) log.warn("waiting for Postgres: {}", rootCause(e))
                if (attempt > 120) throw e
                Thread.sleep(2_000)
            }
        }
    }

    fun <T> tx(block: (Connection) -> T): T = ds.connection.use { c ->
        c.autoCommit = false
        try {
            val out = block(c)
            c.commit()
            out
        } catch (e: Exception) {
            runCatching { c.rollback() }
            throw e
        }
    }

    fun <T> read(block: (Connection) -> T): T = ds.connection.use(block)

    // --- documents ------------------------------------------------------------

    data class DocRow(
        val id: Long, val path: String, val title: String,
        val sha256: String, val chunkCount: Int, val chunker: String,
        /** File size and last-modified time as they were when this row was
         *  written — the scanner's fast path, see Library.indexOne. */
        val bytes: Long, val mtime: Long,
    )

    private val documentColumns =
        "select id, path, title, sha256, chunk_count, chunker, bytes, mtime from document"

    private fun ResultSet.toDoc() = DocRow(
        id = getLong("id"), path = getString("path"), title = getString("title"),
        sha256 = getString("sha256"), chunkCount = getInt("chunk_count"),
        chunker = getString("chunker") ?: "",
        bytes = getLong("bytes"), mtime = getLong("mtime"),
    )

    /** Every document currently registered, by relative path. */
    fun documents(): Map<String, DocRow> = read { c ->
        c.prepareStatement(documentColumns).use { st ->
            st.executeQuery().use { rs ->
                buildMap { while (rs.next()) put(rs.getString("path"), rs.toDoc()) }
            }
        }
    }

    /** One document by its relative path.
     *
     *  An upload needs exactly one row, and reaching it through [documents] —
     *  which is what it used to do — meant reading the whole registry to look
     *  at one line of it, on every single upload. */
    fun document(path: String): DocRow? = read { c ->
        c.prepareStatement("$documentColumns where path = ?").use { st ->
            st.setString(1, path)
            st.executeQuery().use { rs -> if (rs.next()) rs.toDoc() else null }
        }
    }

    /**
     * Replace one document's chunks wholesale, in a single transaction:
     * upsert the document row, delete its old chunks, insert the new ones.
     * Returns (documentId, chunkIds) — the ids the caller then embeds and
     * upserts into Qdrant under the same numbers.
     *
     * Whole-document replacement rather than a diff because a paragraph split
     * shifts every later ordinal anyway: an "unchanged" paragraph three edits
     * down the file is a different chunk in every way that matters here.
     */
    fun replaceDocument(
        path: String, title: String, sha256: String, bytes: Long, mtime: Long,
        chunks: List<Chunker.Chunk>, chunker: String,
    ): Pair<Long, List<Long>> = tx { c ->
        val docId = c.prepareStatement(
            """
            insert into document (path, title, sha256, bytes, mtime, chunk_count, chunker, indexed_at)
            values (?, ?, ?, ?, ?, ?, ?, now())
            on conflict (path) do update
              set title = excluded.title, sha256 = excluded.sha256,
                  bytes = excluded.bytes, mtime = excluded.mtime,
                  chunk_count = excluded.chunk_count,
                  chunker = excluded.chunker, indexed_at = now()
            returning id
            """,
        ).use { st ->
            st.setString(1, path); st.setString(2, title); st.setString(3, sha256)
            st.setLong(4, bytes); st.setLong(5, mtime)
            st.setInt(6, chunks.size); st.setString(7, chunker)
            st.executeQuery().use { rs -> rs.next(); rs.getLong(1) }
        }

        c.prepareStatement("delete from chunk where document_id = ?").use { st ->
            st.setLong(1, docId); st.executeUpdate()
        }

        // One statement per BATCH of chunks, not per chunk. A 300-paragraph
        // document was 300 sequential round trips inside the transaction; a
        // multi-row insert makes it a handful. The batches are bounded because
        // Postgres caps a statement at 65535 bind parameters and this binds
        // four per chunk — 4000 rows is comfortably inside that and keeps any
        // one statement a sane size.
        // RETURNING is read back BY ORDINAL rather than by row order. Postgres
        // does emit the rows in the order the VALUES were written, but that is
        // not a guarantee the documentation makes — and the caller pairs these
        // ids with chunk texts to build the vectors, so a reordering here would
        // silently attach every paragraph's embedding to the wrong paragraph.
        // The ordinal is already unique within the document; using it costs one
        // map and removes the assumption entirely.
        val byOrdinal = HashMap<Int, Long>(chunks.size)
        for (batch in chunks.chunked(4_000)) {
            val values = batch.joinToString(",") { "(?, ?, ?, ?)" }
            c.prepareStatement(
                "insert into chunk (document_id, ordinal, text, tokens) " +
                    "values $values returning id, ordinal",
            ).use { st ->
                var p = 1
                for (ch in batch) {
                    st.setLong(p++, docId); st.setInt(p++, ch.ordinal)
                    st.setString(p++, ch.text); st.setInt(p++, ch.tokens)
                }
                st.executeQuery().use { rs ->
                    while (rs.next()) byOrdinal[rs.getInt("ordinal")] = rs.getLong("id")
                }
            }
        }
        val ids = chunks.map {
            byOrdinal[it.ordinal] ?: error("no id returned for ordinal ${it.ordinal} of $path")
        }
        docId to ids
    }

    /** Record the file's current size and mtime without touching its chunks.
     *
     *  For a file whose CONTENT is unchanged but whose stat is not — touched,
     *  copied, or indexed before mtime was recorded at all. Re-embedding it
     *  would be pure waste; leaving the stat stale would mean hashing it again
     *  on every tick for ever. */
    fun touchDocument(id: Long, bytes: Long, mtime: Long) = tx { c ->
        c.prepareStatement("update document set bytes = ?, mtime = ? where id = ?").use { st ->
            st.setLong(1, bytes); st.setLong(2, mtime); st.setLong(3, id)
            st.executeUpdate()
        }
    }

    fun deleteDocument(id: Long) = tx { c ->
        c.prepareStatement("delete from document where id = ?").use { st ->
            st.setLong(1, id); st.executeUpdate()
        }
    }

    /** Mean chunk length in tokens — BM25's `avgdl`. 1.0 when the corpus is
     *  empty, so the very first document's length normalisation is a no-op
     *  rather than a divide-by-zero. */
    fun averageChunkTokens(): Double = read { c ->
        c.prepareStatement("select coalesce(avg(tokens), 0) from chunk").use { st ->
            st.executeQuery().use { rs -> rs.next(); rs.getDouble(1).takeIf { it > 0 } ?: 1.0 }
        }
    }

    // --- chunks ---------------------------------------------------------------

    data class ChunkRow(
        val id: Long, val documentId: Long, val ordinal: Int,
        val text: String, val path: String, val title: String,
    )

    private fun ResultSet.toChunk() = ChunkRow(
        id = getLong("id"), documentId = getLong("document_id"), ordinal = getInt("ordinal"),
        text = getString("text"), path = getString("path"), title = getString("title"),
    )

    /** The one projection every chunk read shares — a chunk is never useful
     *  without the document it came from. */
    private val select = """
        select ch.id, ch.document_id, ch.ordinal, ch.text, d.path, d.title
        from chunk ch join document d on d.id = ch.document_id
    """

    /** Chunks by id, returned in the order asked for (search-result order) and
     *  silently short of any id that has since been deleted. */
    fun chunksByIds(ids: List<Long>): List<ChunkRow> {
        if (ids.isEmpty()) return emptyList()
        val byId = read { c ->
            c.prepareStatement("$select where ch.id = any (?)").use { st ->
                st.setArray(1, c.createArrayOf("bigint", ids.toTypedArray()))
                st.executeQuery().use { rs ->
                    buildMap { while (rs.next()) put(rs.getLong("id"), rs.toChunk()) }
                }
            }
        }
        return ids.mapNotNull { byId[it] }
    }

    fun chunk(id: Long): ChunkRow? = chunksByIds(listOf(id)).firstOrNull()

    /** A window of consecutive chunks from one document, by ordinal — what
     *  `load_chunk` widens a hit with. */
    fun window(documentId: Long, fromOrdinal: Int, toOrdinal: Int): List<ChunkRow> = read { c ->
        c.prepareStatement(
            "$select where ch.document_id = ? and ch.ordinal between ? and ? order by ch.ordinal",
        ).use { st ->
            st.setLong(1, documentId); st.setInt(2, fromOrdinal); st.setInt(3, toOrdinal)
            st.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.toChunk()) } }
        }
    }

    /** Every chunk in the corpus, streamed in id order — the reindex path. */
    fun forEachChunk(batch: Int = 500, block: (List<ChunkRow>) -> Unit) {
        var after = 0L
        while (true) {
            val rows = read { c ->
                c.prepareStatement("$select where ch.id > ? order by ch.id limit ?").use { st ->
                    st.setLong(1, after); st.setInt(2, batch)
                    st.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.toChunk()) } }
                }
            }
            if (rows.isEmpty()) return
            block(rows)
            after = rows.last().id
        }
    }

    data class Stats(val documents: Long, val chunks: Long, val bytes: Long)

    fun stats(): Stats = read { c ->
        c.prepareStatement(
            """
            select (select count(*) from document), (select count(*) from chunk),
                   (select coalesce(sum(bytes), 0) from document)
            """,
        ).use { st ->
            st.executeQuery().use { rs ->
                rs.next(); Stats(rs.getLong(1), rs.getLong(2), rs.getLong(3))
            }
        }
    }

    override fun close() = ds.close()

    companion object {
        /** The deepest message in a cause chain, with the wrapper's own for
         *  context. Guarded against a self-referential chain, which some
         *  drivers do produce. */
        fun rootCause(e: Throwable): String {
            var cause: Throwable = e
            var depth = 0
            while (cause.cause != null && cause.cause !== cause && depth++ < 10) {
                cause = cause.cause!!
            }
            val deepest = "${cause.javaClass.simpleName}: ${cause.message ?: "(no detail)"}"
            return if (cause === e) deepest else "${e.message} — caused by $deepest"
        }

        /**
         * `postgresql://user:pass@host:5432/db` (the URL shape every other
         * service in the compose file uses) into the `jdbc:postgresql://` form
         * with credentials as query parameters, which the JDBC driver wants.
         */
        fun toJdbcUrl(url: String): String {
            if (url.startsWith("jdbc:")) return url
            val uri = java.net.URI(url)
            val userInfo = uri.userInfo?.split(':', limit = 2)
            val host = uri.host ?: "postgres"
            val port = if (uri.port > 0) uri.port else 5432
            val db = uri.path.trimStart('/').ifBlank { "seshat" }
            val params = buildList {
                userInfo?.getOrNull(0)?.let { add("user=" + enc(it)) }
                userInfo?.getOrNull(1)?.let { add("password=" + enc(it)) }
                uri.query?.takeIf { it.isNotBlank() }?.let { add(it) }
            }
            return "jdbc:postgresql://$host:$port/$db" +
                if (params.isEmpty()) "" else "?" + params.joinToString("&")
        }

        private fun enc(s: String) = java.net.URLEncoder.encode(s, Charsets.UTF_8)

        private val SCHEMA = """
            create table if not exists document (
                id          bigserial primary key,
                path        text        not null unique,
                title       text        not null,
                sha256      text        not null,
                bytes       bigint      not null,
                chunk_count int         not null default 0,
                indexed_at  timestamptz not null default now()
            );

            create table if not exists chunk (
                id          bigserial primary key,
                document_id bigint not null references document(id) on delete cascade,
                ordinal     int    not null,
                text        text   not null,
                tokens      int    not null,
                unique (document_id, ordinal)
            );

            -- `unique (document_id, ordinal)` above already builds a btree on
            -- exactly these columns, so the index that used to be declared here
            -- was a second copy of it: paid for on every insert, read by
            -- nothing the first one could not answer.
            drop index if exists chunk_document_ordinal_idx;

            -- Which chunker produced this document's chunks, and with what
            -- settings. A file whose bytes are unchanged but whose chunker
            -- signature is not counts as changed, so editing CHUNK_MIN_CHARS in
            -- .env re-chunks the corpus on the next scan instead of leaving it
            -- half one shape and half another. Added by ALTER rather than in
            -- the CREATE above: the table already exists on every deployment
            -- that predates semantic chunking.
            alter table document add column if not exists chunker text not null default '';

            -- The file's last-modified time in epoch millis, as it was when
            -- this row was written. With `bytes` it is the scanner's fast path:
            -- a file whose size and mtime both match what was indexed is not
            -- opened at all (see Library.indexOne). Defaulting to 0 means every
            -- pre-existing row misses that test once and is hashed exactly one
            -- more time, which is the safe direction to be wrong in.
            alter table document add column if not exists mtime bigint not null default 0;

            -- The audit trail: one row per user action, written by Audit.
            --
            -- `detail` is jsonb rather than twenty more columns because the
            -- interesting field differs per action — a chunk id, a file size, a
            -- rejection reason, a log query — and a table that is mostly nulls
            -- is worse than one document per row.
            --
            -- `request_id` is the same value that goes into the MDC as `req`,
            -- and therefore onto every log line the request produced. One id is
            -- what lets the Admin tab put an audit row and its log lines side by
            -- side; it costs a UUID and a column.
            create table if not exists audit (
                id          bigserial   primary key,
                at          timestamptz not null default now(),
                username    text        not null,
                subject     text        not null default '',
                session     text        not null default '',
                request_id  text        not null default '',
                action      text        not null,
                target      text        not null default '',
                outcome     text        not null,
                status      int         not null default 0,
                ip          text        not null default '',
                duration_ms int         not null default 0,
                detail      jsonb       not null default '{}'
            );

            -- Every filter the admin API offers, in index form. `(at desc, id
            -- desc)` rather than `(at desc)` alone because the cursor pages on
            -- both — two rows written in the same millisecond are common, and
            -- paging on the timestamp alone either repeats one or skips one.
            create index if not exists audit_at_idx        on audit (at desc, id desc);
            create index if not exists audit_user_at_idx   on audit (username, at desc, id desc);
            create index if not exists audit_action_at_idx on audit (action, at desc, id desc);
            create index if not exists audit_req_idx       on audit (request_id);
        """.trimIndent()
    }
}
