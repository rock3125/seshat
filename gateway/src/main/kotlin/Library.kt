import org.slf4j.LoggerFactory
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.io.path.extension
import kotlin.io.path.isRegularFile
import kotlin.io.path.name

/**
 * The library folder: drop a text file in — or upload one — and ask questions
 * about it.
 *
 * Scans on boot and every LIBRARY_SCAN_MINUTES (one minute by default). The
 * sha-256 of each file is recorded in `document`, so a rescan of an unchanged
 * corpus reads the files, hashes them, and makes no API call and no write —
 * which is what makes a poll that frequent cheap enough to be the whole
 * background ingestion mechanism. A file arriving through [upload] does not
 * wait for a tick: it is written and indexed in the request.
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

    /** Anything that is not already text becomes text here, on the way in. */
    private val extract = Extract(cfg)

    /** Semantic bunching by default; see Semantic.kt for what that costs. */
    private val chunking = Chunking(cfg, embeddings)

    /**
     * One indexer at a time. A lock rather than a flag because the three ways
     * in want three different answers to "someone else is working": the timer
     * skips its tick, a reindex refuses, and an upload waits (see [ingest]).
     */
    private val indexing = ReentrantLock()

    @Volatile var lastScan: Result? = null
        private set

    data class Result(
        val indexed: Int, val unchanged: Int, val removed: Int,
        val skipped: Int, val failed: Int, val seconds: Double,
    )

    /** A file the caller may not put in the library, with the reason to show
     *  them. Everything it covers is decided before anything is written. */
    class Rejected(message: String) : RuntimeException(message)

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
        if (!indexing.tryLock()) {
            log.info("indexing already in progress — skipping this tick")
            return lastScan ?: Result(0, 0, 0, 0, 0, 0.0)
        }
        try {
            return scanLocked()
        } finally {
            indexing.unlock()
        }
    }

    /**
     * The scan itself, with the caller already holding [indexing]. Split out so
     * [reindex] can run a folder pass inside its own guard rather than dropping
     * the lock between the two halves of a repair.
     */
    private fun scanLocked(label: String = "library scan", always: Boolean = false): Result {
        val startedAt = System.nanoTime()
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
            if (file.extension.lowercase() !in TEXT_EXTENSIONS) {
                log.debug("skipping {} — not a text extension", relative)
                skipped++
                continue
            }
            seen.add(relative)
            try {
                when (indexOne(file, relative, known[relative], avgdl).outcome) {
                    Outcome.INDEXED -> indexed++
                    Outcome.UNCHANGED -> unchanged++
                    Outcome.SKIPPED -> { skipped++; seen.remove(relative) }
                }
            } catch (e: Exception) {
                failed++
                log.error("indexing {} failed: {}", relative, e.toString())
            }
        }

        // Both stores, in that order: Qdrant's points are keyed by document_id
        // and would be unreachable orphans if the document row went first and
        // the vector delete then failed.
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
        // "nothing happened" is how a log stops being read. A reindex was
        // asked for by a person, so it reports either way.
        if (always || isFirstScan || indexed > 0 || removed > 0 || failed > 0) {
            log.info("{}: {} indexed, {} unchanged, {} removed, {} skipped, {} failed in {}s",
                label, indexed, unchanged, removed, skipped, failed, "%.1f".format(result.seconds))
            if (isFirstScan && indexed == 0 && unchanged > 0) {
                log.info("corpus already indexed — {} document(s) unchanged, nothing re-embedded",
                    unchanged)
            }
        }
        return result
    }

    private enum class Outcome { INDEXED, UNCHANGED, SKIPPED }

    /** What one file cost: the verdict, and the paragraphs it now has (0 unless
     *  it was actually re-chunked). */
    private data class Indexed(val outcome: Outcome, val chunks: Int)

    private fun indexOne(file: Path, relative: String, known: Db.DocRow?, avgdl: Double): Indexed {
        val bytes = Files.readAllBytes(file)
        val sha = sha256(bytes)
        // Unchanged means BOTH the bytes and the chunker: editing
        // CHUNK_MIN_CHARS in .env has to re-chunk a corpus whose files nobody
        // touched, or the corpus quietly becomes a mixture of two chunkings.
        if (known != null && known.sha256 == sha && known.chunker == chunking.signature) {
            return Indexed(Outcome.UNCHANGED, known.chunkCount)
        }

        val body = decodeText(bytes) ?: run {
            log.warn("skipping {} — not decodable as UTF-8 text", relative)
            return Indexed(Outcome.SKIPPED, 0)
        }

        val split = chunking.split(body)
        val chunks = split.chunks
        if (chunks.isEmpty()) {
            log.warn("skipping {} — no text content", relative)
            return Indexed(Outcome.SKIPPED, 0)
        }

        val title = Chunker.titleOf(body, file.name)

        // Postgres first, and it is the transaction that assigns the chunk ids
        // the vectors are then stored under. If embedding fails after this
        // point the chunks exist without vectors — recoverable by `POST
        // /reindex`, and the sha-256 is already committed so the next scan
        // won't loop on the same file forever.
        val (documentId, chunkIds) = db.replaceDocument(
            relative, title, sha, bytes.size.toLong(), chunks, split.signature)

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
        return Indexed(Outcome.INDEXED, chunks.size)
    }

    // --- uploads ---------------------------------------------------------------

    /**
     * What became of one uploaded file.
     *
     * [path] is what it is stored and indexed as, which is not [source] when
     * Tika converted it. [chunks] is null when indexing was deferred — the
     * document is on disk either way.
     */
    data class Upload(
        val source: String, val path: String, val bytes: Long, val replaced: Boolean,
        val status: String, val chunks: Int?, val convertedFrom: String?, val truncated: Boolean,
    )

    /**
     * Take one uploaded file into the library folder and index it immediately.
     *
     * ANY format is accepted. A file that is already text is stored byte for
     * byte; anything else — a PDF, a Word document, a spreadsheet, an email —
     * goes through Tika on the way in and is stored as the text it contained,
     * under the same name with `.txt` on the end. That is what keeps the folder
     * what the rest of the system assumes it is: one text document per file,
     * greppable, diffable, and re-indexable without the converter having to run
     * again.
     *
     * The original binary is NOT kept. It would be a second copy of a document
     * the uploader already has, sitting in a folder whose every other file is
     * indexable text, and the deletion rules would then have to reason about
     * pairs of files instead of files.
     *
     * A name that already exists is REPLACED, because the folder is a folder:
     * two copies of one document under `notes.md` and `notes-2.md` would both
     * be indexed, and every future search would return the pair. The response
     * says which of the two happened.
     */
    fun upload(name: String, bytes: ByteArray): Upload {
        val safe = safeName(name)
        if (bytes.isEmpty()) throw Rejected("$safe is empty")

        val root = Path.of(cfg.libraryDir)
        if (!Files.isDirectory(root)) {
            throw IllegalStateException("the library folder ${cfg.libraryDir} is not mounted")
        }

        // A text file is stored as it arrived — the bytes ARE the document, and
        // running a converter over them could only lose formatting the reader
        // wrote on purpose. Everything else, including a `.txt` that turns out
        // not to be UTF-8 (a Latin-1 export, a UTF-16 log), goes to Tika, which
        // detects the encoding as readily as it detects the format.
        val textName = safe.substringAfterLast('.', "").lowercase() in TEXT_EXTENSIONS
        val asIs = if (textName) decodeText(bytes) else null

        val stored: String
        val content: ByteArray
        var convertedFrom: String? = null
        var truncated = false

        if (asIs != null) {
            stored = safe
            content = bytes
        } else {
            val result = extract.from(bytes, safe)
            if (result.text.isBlank()) {
                throw Rejected(
                    "no text could be read out of $safe " +
                        "(${Extract.formatName(result.mediaType.ifBlank { extract.mediaType(bytes, safe) })}) " +
                        "— a scanned page needs OCR, which this build does not have",
                )
            }
            // A text-named file keeps its name; everything else gains .txt, so
            // the scanner sees a text document and the name still says where it
            // came from: report.pdf -> report.pdf.txt.
            stored = if (textName) safe else "$safe.txt"
            content = result.text.toByteArray(Charsets.UTF_8)
            convertedFrom = Extract.formatName(result.mediaType)
            truncated = result.truncated
        }

        val target = root.resolve(stored).normalize()
        // Belt and braces over safeName: whatever the name decoded to, the file
        // it names lands directly in the library folder or not at all.
        if (target.parent != root.normalize()) throw Rejected("'$name' is not a plain file name")

        val replaced = Files.exists(target)
        writeAtomically(root, target, content)

        val ingested = ingest(stored)
        val status = when (ingested.outcome) {
            Ingest.INDEXED -> "indexed"
            Ingest.UNCHANGED -> "unchanged"       // byte-identical to what was already there
            Ingest.EMPTY -> "no text to index"
            Ingest.BUSY -> "queued"               // the next scan, within the minute
        }
        log.info("upload {} ({} bytes{}) — stored as {}, {}{}",
            safe, bytes.size, convertedFrom?.let { ", converted from $it" } ?: "",
            stored, status, if (replaced) ", replacing the previous copy" else "")
        return Upload(
            source = safe, path = stored, bytes = content.size.toLong(), replaced = replaced,
            status = status, chunks = if (ingested.outcome == Ingest.BUSY) null else ingested.chunks,
            convertedFrom = convertedFrom, truncated = truncated,
        )
    }

    /**
     * Write into place under a dot-name and rename.
     *
     * The scanner ignores dotfiles, so a tick landing mid-write cannot index
     * half a document, and the rename is atomic within the one directory — the
     * file a reader opens is either the old one or the whole new one. The
     * permissions are widened from the 0600 a temp file is created with:
     * these files are meant to be readable by whoever mounted the folder.
     */
    private fun writeAtomically(root: Path, target: Path, bytes: ByteArray) {
        val tmp = Files.createTempFile(root, ".upload-", ".part")
        try {
            Files.write(tmp, bytes)
            runCatching {
                Files.setPosixFilePermissions(tmp, PosixFilePermissions.fromString("rw-r--r--"))
            }
            Files.move(tmp, target,
                StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (e: Exception) {
            runCatching { Files.deleteIfExists(tmp) }
            throw e
        }
    }

    enum class Ingest { INDEXED, UNCHANGED, EMPTY, BUSY }

    data class Ingested(val outcome: Ingest, val chunks: Int)

    /**
     * Index one file in the folder now, waiting up to [waitSeconds] for a scan
     * or a reindex to finish rather than failing on the spot.
     *
     * Waiting is the right answer here and only here: the caller is a person
     * watching an upload, and the alternative to a few seconds of patience is
     * telling them their document is not searchable yet. If the wait does run
     * out nothing is lost — the bytes are already on disk, so the next tick
     * indexes them and BUSY says exactly that.
     */
    fun ingest(relative: String, waitSeconds: Long = 30): Ingested {
        if (!indexing.tryLock(waitSeconds, TimeUnit.SECONDS)) {
            log.info("indexing busy — {} will be picked up by the next scan", relative)
            return Ingested(Ingest.BUSY, 0)
        }
        try {
            val file = Path.of(cfg.libraryDir).resolve(relative)
            if (!file.isRegularFile()) return Ingested(Ingest.BUSY, 0)
            val result = indexOne(file, relative, db.documents()[relative], db.averageChunkTokens())
            return when (result.outcome) {
                Outcome.INDEXED -> Ingested(Ingest.INDEXED, result.chunks)
                Outcome.UNCHANGED -> Ingested(Ingest.UNCHANGED, result.chunks)
                Outcome.SKIPPED -> Ingested(Ingest.EMPTY, 0)
            }
        } finally {
            indexing.unlock()
        }
    }

    data class Reindex(val scan: Result, val chunks: Int, val seconds: Double)

    /**
     * The repair path, in two halves.
     *
     * First a full folder pass, so a reindex reconciles the corpus rather than
     * faithfully re-embedding a stale one: a file that appeared while the
     * scanner was wedged (or that failed to index on its scan) gets picked up,
     * an edited file is re-chunked, and a document whose file is gone is
     * unindexed from BOTH stores — its points out of Qdrant and its row out of
     * Postgres, chunks following by cascade. That deletion honours
     * LIBRARY_MIRROR: with the mirror off the folder is not the source of
     * truth, and a reindex is not the place to override that.
     *
     * Then every chunk still in Postgres is re-embedded and re-upserted. This
     * is what fixes vectors lost to a dropped Qdrant volume or an embedding
     * failure part-way through a scan, and it recomputes every BM25 length
     * normalisation against the current corpus-wide average (see Bm25's note on
     * avgdl drift) — which is why avgdl is read after the scan, not before:
     * anything the scan just added or removed has already moved that mean.
     *
     * A document the scan itself indexed is therefore embedded twice. That is
     * the price of one ordering that is always right, and the second pass is
     * the one whose avgdl is correct.
     */
    fun reindex(): Reindex {
        if (!indexing.tryLock()) {
            throw IllegalStateException("indexing is already running — try again shortly")
        }
        val startedAt = System.nanoTime()
        try {
            val scan = scanLocked(label = "reindex scan", always = true)

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

            val seconds = (System.nanoTime() - startedAt) / 1e9
            log.info("reindex complete: {} chunk(s) re-embedded, {} document(s) newly indexed, " +
                "{} removed in {}s", count, scan.indexed, scan.removed, "%.1f".format(seconds))
            return Reindex(scan, count, seconds)
        } finally {
            indexing.unlock()
        }
    }

    companion object {
        /** Extensions that may contain text. Anything else is not opened at
         *  all, and is not accepted from an upload. */
        val TEXT_EXTENSIONS = setOf(
            "txt", "text", "md", "markdown", "rst", "log", "csv", "tsv", "json",
            "yaml", "yml", "adoc", "org",
        )

        /**
         * The bare file name an upload may be stored under, or [Rejected] with
         * the reason.
         *
         * The FORMAT is not tested here and no longer can be: Tika converts
         * whatever arrives, so the extension is a hint about how to read the
         * bytes rather than permission to accept them. What is tested is the
         * name as a name — because this is the one place an HTTP caller chooses
         * where a byte lands on disk, and it is treated as hostile. Every
         * directory component is dropped (both separators: a Windows client
         * sends backslashes), and what is left has to be a single ordinary
         * file name. A leading dot is out because the scanner skips dotfiles,
         * which would leave an upload that succeeded and never appeared.
         */
        fun safeName(raw: String): String {
            val base = raw.trim().replace('\\', '/').substringAfterLast('/').trim()
            if (base.isEmpty() || base == "." || base == "..") throw Rejected("a file name is required")
            if (base.startsWith(".")) throw Rejected("'$base' starts with a dot — rename it")
            if (base.any { it.isISOControl() }) throw Rejected("the file name contains a control character")
            if (base.toByteArray(Charsets.UTF_8).size > 200) throw Rejected("the file name is too long")
            return base
        }

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
