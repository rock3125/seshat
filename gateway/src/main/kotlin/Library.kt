import org.slf4j.LoggerFactory
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.io.path.extension
import kotlin.io.path.isRegularFile
import kotlin.io.path.name

/**
 * The library folder: drop a text file in, ask questions about it.
 *
 * Scans on boot and every LIBRARY_SCAN_MINUTES. The sha-256 of each file is
 * recorded in `document`, so a rescan of an unchanged corpus reads the files,
 * hashes them, and makes no API call and no write — which is what makes a
 * five-minute poll cheap enough to be the whole ingestion mechanism.
 *
 * TEXT ONLY, as specified, and enforced twice: an allowed extension, and then a
 * decode check. The second test is the one that matters — a PDF renamed to
 * `.txt` passes the first and would otherwise be indexed as several kilobytes
 * of mojibake that pollutes every search. Files that fail either test are
 * skipped with a log line, not an error; a library folder with a stray image in
 * it is normal, not a fault.
 *
 * With LIBRARY_MIRROR on (the default) the folder is the source of truth:
 * deleting a file deletes its document, its chunks and its vectors on the next
 * scan. Off, documents accumulate and only ever get updated.
 */
class Library(
    private val cfg: Config,
    private val db: Db,
    private val store: Store,
    private val embeddings: Embeddings,
) {
    private val log = LoggerFactory.getLogger("Library")
    private val scanning = AtomicBoolean(false)

    /** Extensions that may contain text. Anything else is not opened at all. */
    private val textExtensions = setOf(
        "txt", "text", "md", "markdown", "rst", "log", "csv", "tsv", "json",
        "yaml", "yml", "adoc", "org",
    )

    @Volatile var lastScan: Result? = null
        private set

    data class Result(
        val indexed: Int, val unchanged: Int, val removed: Int,
        val skipped: Int, val failed: Int, val seconds: Double,
    )

    fun start() {
        val pool = Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "library-scan").apply { isDaemon = true }
        }
        pool.scheduleWithFixedDelay(
            { runCatching { scan() }.onFailure { log.error("library scan failed: {}", it.toString()) } },
            0, cfg.scanMinutes.coerceAtLeast(1), TimeUnit.MINUTES,
        )
        log.info("watching {} every {} minute(s), mirror={}",
            cfg.libraryDir, cfg.scanMinutes, cfg.libraryMirror)
    }

    /**
     * One pass over the folder. Guarded so a manual `POST /reindex` cannot run
     * concurrently with the timer and upsert the same chunk ids twice.
     */
    fun scan(): Result {
        if (!scanning.compareAndSet(false, true)) {
            log.info("scan already in progress — skipping this tick")
            return lastScan ?: Result(0, 0, 0, 0, 0, 0.0)
        }
        val startedAt = System.nanoTime()
        try {
            val root = Path.of(cfg.libraryDir)
            if (!Files.isDirectory(root)) {
                log.warn("library folder {} does not exist — nothing to index", cfg.libraryDir)
                return Result(0, 0, 0, 0, 0, 0.0)
            }

            val known = db.documents()
            val seen = HashSet<String>()
            var indexed = 0; var unchanged = 0; var skipped = 0; var failed = 0

            // avgdl once per scan, not once per document: it is a mean over the
            // whole corpus, and recomputing it per file would make a document's
            // length normalisation depend on how many files happened to be
            // processed before it.
            val avgdl = db.averageChunkTokens()

            val files = Files.walk(root).use { stream ->
                stream.filter { it.isRegularFile() }.sorted().toList()
            }

            for (file in files) {
                val relative = root.relativize(file).toString()
                if (file.name.startsWith(".")) continue          // editor swap files, .DS_Store
                if (file.extension.lowercase() !in textExtensions) {
                    log.debug("skipping {} — not a text extension", relative)
                    skipped++
                    continue
                }
                seen.add(relative)
                try {
                    when (indexOne(file, relative, known[relative], avgdl)) {
                        Outcome.INDEXED -> indexed++
                        Outcome.UNCHANGED -> unchanged++
                        Outcome.SKIPPED -> { skipped++; seen.remove(relative) }
                    }
                } catch (e: Exception) {
                    failed++
                    log.error("indexing {} failed: {}", relative, e.toString())
                }
            }

            var removed = 0
            if (cfg.libraryMirror) {
                for ((path, doc) in known) {
                    if (path in seen) continue
                    log.info("removing {} — the file is gone", path)
                    store.deleteDocument(doc.id)
                    db.deleteDocument(doc.id)
                    removed++
                }
            }

            val result = Result(
                indexed, unchanged, removed, skipped, failed,
                (System.nanoTime() - startedAt) / 1e9,
            )
            val isFirstScan = lastScan == null
            lastScan = result

            // The boot scan is always reported, even when it did nothing. On a
            // restart against an already-indexed corpus every file is unchanged
            // and there is no work to log — so staying quiet made a correct
            // start look identical to a scanner that never ran. Later no-op
            // ticks stay quiet, because one line every five minutes saying
            // "nothing happened" is how a log stops being read.
            if (isFirstScan || indexed > 0 || removed > 0 || failed > 0) {
                log.info("library scan: {} indexed, {} unchanged, {} removed, {} skipped, {} failed in {}s",
                    indexed, unchanged, removed, skipped, failed, "%.1f".format(result.seconds))
                if (isFirstScan && indexed == 0 && unchanged > 0) {
                    log.info("corpus already indexed — {} document(s) unchanged, nothing re-embedded",
                        unchanged)
                }
            }
            return result
        } finally {
            scanning.set(false)
        }
    }

    private enum class Outcome { INDEXED, UNCHANGED, SKIPPED }

    private fun indexOne(file: Path, relative: String, known: Db.DocRow?, avgdl: Double): Outcome {
        val bytes = Files.readAllBytes(file)
        val sha = sha256(bytes)
        if (known != null && known.sha256 == sha) return Outcome.UNCHANGED

        val body = decodeText(bytes) ?: run {
            log.warn("skipping {} — not decodable as UTF-8 text", relative)
            return Outcome.SKIPPED
        }

        val chunks = Chunker.split(body)
        if (chunks.isEmpty()) {
            log.warn("skipping {} — no text content", relative)
            return Outcome.SKIPPED
        }

        val title = Chunker.titleOf(body, file.name)

        // Postgres first, and it is the transaction that assigns the chunk ids
        // the vectors are then stored under. If embedding fails after this
        // point the chunks exist without vectors — recoverable by `POST
        // /reindex`, and the sha-256 is already committed so the next scan
        // won't loop on the same file forever.
        val (documentId, chunkIds) = db.replaceDocument(
            relative, title, sha, bytes.size.toLong(), chunks)

        store.deleteDocument(documentId)   // stale points from the previous version
        val dense = embeddings.documents(chunks.map { it.text })
        store.upsert(chunks.mapIndexed { i, chunk ->
            Store.Point(
                chunkId = chunkIds[i],
                documentId = documentId,
                ordinal = chunk.ordinal,
                path = relative,
                title = title,
                dense = dense[i],
                sparse = Bm25.document(chunk.text, avgdl),
            )
        })

        log.info("indexed {} — {} chunk(s)", relative, chunks.size)
        return Outcome.INDEXED
    }

    /**
     * Re-embed and re-index every chunk already in Postgres, without touching
     * the library folder. This is the repair path: it fixes vectors lost to a
     * dropped Qdrant volume or an embedding failure part-way through a scan,
     * and it recomputes every BM25 length normalisation against the current
     * corpus-wide average (see Bm25's note on avgdl drift).
     */
    fun reindex(): Int {
        if (!scanning.compareAndSet(false, true)) {
            throw IllegalStateException("a scan is already running — try again shortly")
        }
        try {
            val avgdl = db.averageChunkTokens()
            var count = 0
            db.forEachChunk { rows ->
                val dense = embeddings.documents(rows.map { it.text })
                store.upsert(rows.mapIndexed { i, row ->
                    Store.Point(
                        chunkId = row.id, documentId = row.documentId, ordinal = row.ordinal,
                        path = row.path, title = row.title,
                        dense = dense[i], sparse = Bm25.document(row.text, avgdl),
                    )
                })
                count += rows.size
                log.info("reindexed {} chunks", count)
            }
            return count
        } finally {
            scanning.set(false)
        }
    }

    companion object {
        fun sha256(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(bytes)
                .joinToString("") { "%02x".format(it) }

        /**
         * The bytes as text, or null if they are not text.
         *
         * A strict UTF-8 decode is the test: it rejects a malformed byte
         * sequence rather than substituting U+FFFD, which is what makes it
         * catch a mislabelled binary. A NUL byte is checked first because it
         * is legal UTF-8 and appears in essentially no real text file, so it
         * catches UTF-16 and the binaries that would otherwise decode.
         */
        fun decodeText(bytes: ByteArray): String? {
            if (bytes.isEmpty()) return null
            if (bytes.any { it == 0.toByte() }) return null
            val decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
            return try {
                decoder.decode(java.nio.ByteBuffer.wrap(bytes)).toString()
                    .removePrefix("﻿")   // a BOM would head the first chunk
            } catch (e: java.nio.charset.CharacterCodingException) {
                null
            }
        }
    }
}
