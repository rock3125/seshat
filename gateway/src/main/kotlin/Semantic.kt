import org.slf4j.LoggerFactory
import kotlin.math.sqrt

/**
 * Semantic chunking: cut where the meaning changes, not where the blank lines
 * are.
 *
 * A document is split into sentences, every sentence is embedded, and adjacent
 * sentences are bunched together while they keep talking about the same thing —
 * measured as the cosine similarity between the next sentence and the running
 * centroid of the bunch so far. When that similarity falls through
 * SEMANTIC_THRESHOLD the bunch is closed and the next one starts. The centroid,
 * rather than the previous sentence alone, is what makes the test stable: one
 * short aside inside a passage ("See Table 3.") drags a pairwise comparison
 * across the threshold and splits a paragraph that was never going to end.
 *
 * Two guards bound the result, and they are why this cannot degenerate:
 *
 *   CHUNK_MIN_CHARS (200)  A bunch under this length is not offered the choice:
 *                          the next sentence joins it whatever the similarity
 *                          says. A one-line heading, a date, a table caption —
 *                          each embeds to a vector that means almost nothing,
 *                          and on its own retrieves either nothing or
 *                          everything. This is the setting the brief names, and
 *                          it is in .env.
 *   CHUNK_MAX_CHARS (3000) A bunch over this length stops accepting, however
 *                          similar the next sentence is. A uniform document —
 *                          a price list, a glossary — is similar to itself all
 *                          the way down, and without a cap it would embed as
 *                          one vector that means nothing in particular.
 *
 * The cost is one embedding call per sentence at index time, on top of the one
 * per finished chunk. That is the price of the method: you cannot know where
 * the meaning turns without having embedded both sides of the turn. It is paid
 * once per document version — an unchanged file is never re-chunked — and only
 * ever while indexing, never while answering.
 */
object Semantic {

    /** One sentence, and whether it opened a new paragraph in the source. */
    data class Piece(val text: String, val startsParagraph: Boolean)

    private val PARAGRAPH_BREAK = Regex("(?:\\r\\n|\\r|\\n)[ \\t]*(?:\\r\\n|\\r|\\n)+")

    /** A sentence end followed by whitespace. Deliberately naive: it over-splits
     *  on "Dr. Smith" and the bunching then puts the halves straight back
     *  together, which is the failure mode worth having. */
    private val SENTENCE_END = Regex("(?<=[.!?。？！])[\\s\\u00A0]+")

    /**
     * The units to bunch: sentences, in reading order, each knowing whether a
     * paragraph started at it so the text can be rebuilt with its breaks.
     * Anything longer than [maxChars] with no sentence end in it (a CSV row, a
     * base64 blob) is wrapped at whitespace rather than left to become an
     * oversized chunk on its own.
     */
    fun pieces(body: String, maxChars: Int): List<Piece> {
        val out = ArrayList<Piece>()
        for (paragraph in PARAGRAPH_BREAK.split(body)) {
            val trimmed = paragraph.trim()
            if (trimmed.isEmpty()) continue
            var first = true
            for (sentence in SENTENCE_END.split(trimmed)) {
                val s = sentence.trim()
                if (s.isEmpty()) continue
                for (piece in wrap(s, maxChars)) {
                    out.add(Piece(piece, first))
                    first = false
                }
            }
        }
        return out
    }

    /** Break a run with no sentence end in it at whitespace, at [maxChars]. */
    private fun wrap(text: String, maxChars: Int): List<String> {
        if (text.length <= maxChars) return listOf(text)
        val out = ArrayList<String>()
        val buf = StringBuilder()
        for (word in text.split(' ')) {
            if (buf.isNotEmpty() && buf.length + 1 + word.length > maxChars) {
                out.add(buf.toString()); buf.setLength(0)
            }
            if (buf.isNotEmpty()) buf.append(' ')
            buf.append(word)
        }
        if (buf.isNotEmpty()) out.add(buf.toString())
        // A single word longer than the cap is emitted whole: cutting mid-token
        // produces two things that match nothing.
        return out.filter { it.isNotEmpty() }
    }

    /**
     * Bunch [pieces] into chunk texts. [vectors] must be one per piece, in the
     * same order.
     *
     * The rules, in the order they are applied to each sentence:
     *   1. A bunch shorter than [minChars] takes it, unconditionally.
     *   2. A bunch that would pass [maxChars] refuses it.
     *   3. Otherwise it is taken if cosine(centroid, sentence) >= [threshold].
     *
     * A final bunch left under [minChars] is folded into the one before it,
     * because a trailing line has nothing after it to grow into.
     */
    fun bunch(
        pieces: List<Piece>,
        vectors: List<FloatArray>,
        minChars: Int,
        maxChars: Int,
        threshold: Double,
    ): List<String> {
        require(pieces.size == vectors.size) {
            "semantic bunching needs one vector per piece (${pieces.size} pieces, ${vectors.size} vectors)"
        }
        if (pieces.isEmpty()) return emptyList()

        val out = ArrayList<String>()
        val buf = StringBuilder()
        var centroid: DoubleArray? = null

        fun open(i: Int) {
            buf.setLength(0)
            buf.append(pieces[i].text)
            centroid = DoubleArray(vectors[i].size) { vectors[i][it].toDouble() }
        }
        fun close() {
            if (buf.isNotEmpty()) out.add(buf.toString())
            buf.setLength(0)
            centroid = null
        }

        open(0)
        for (i in 1 until pieces.size) {
            val piece = pieces[i]
            val separator = if (piece.startsParagraph) "\n\n" else " "
            val grown = buf.length + separator.length + piece.text.length

            val take = when {
                buf.length < minChars -> true                  // 1. too small to stand alone
                grown > maxChars -> false                      // 2. full
                else -> cosine(centroid!!, vectors[i]) >= threshold   // 3. still the same subject
            }

            if (!take) {
                close()
                open(i)
                continue
            }
            buf.append(separator).append(piece.text)
            val c = centroid!!
            for (d in c.indices) c[d] += vectors[i][d].toDouble()
        }
        close()

        if (out.size > 1 && out.last().length < minChars) {
            val tail = out.removeAt(out.size - 1)
            out[out.size - 1] = out.last() + "\n\n" + tail
        }
        return out
    }

    /** Cosine similarity. The centroid is a running SUM, not a mean — cosine is
     *  scale-invariant, so dividing by the count would be arithmetic for its
     *  own sake. */
    fun cosine(a: DoubleArray, b: FloatArray): Double {
        if (a.size != b.size) return 0.0
        var dot = 0.0; var na = 0.0; var nb = 0.0
        for (i in a.indices) {
            val x = a[i]; val y = b[i].toDouble()
            dot += x * y; na += x * x; nb += y * y
        }
        val norm = sqrt(na) * sqrt(nb)
        return if (norm == 0.0) 0.0 else dot / norm
    }
}

/**
 * Which chunker runs, and the settings it ran with.
 *
 * The signature is written to the document row, so changing CHUNK_MIN_CHARS (or
 * turning semantic chunking off) is enough to make the next scan re-chunk the
 * whole corpus: a file whose bytes are unchanged but whose chunker is not
 * counts as changed. Re-chunking is not free — every document is embedded
 * again — but the alternative is a corpus that is half one chunking and half
 * another, with no way to tell which passage came from which.
 */
class Chunking(private val cfg: Config, private val embeddings: Embeddings) {
    private val log = LoggerFactory.getLogger("Chunking")

    /** The chunks, and the signature of the chunker that actually produced them
     *  — which is not always the configured one, see the fallback below. */
    data class Split(val chunks: List<Chunker.Chunk>, val signature: String)

    /** What a document chunked with the CURRENT settings would be stamped with. */
    val signature: String get() = if (cfg.semanticChunking) {
        "semantic/v1 min=${cfg.chunkMinChars} max=${cfg.chunkMaxChars} " +
            "t=${cfg.semanticThreshold} model=${cfg.embedModel}"
    } else {
        paragraphSignature
    }

    /**
     * CHUNK_MIN_CHARS governs BOTH chunkers.
     *
     * It used to reach only the semantic one: the fallback was called with the
     * max alone and quietly used Chunker.MIN_CHARS instead, so the setting the
     * brief names did nothing at all whenever semantic chunking was off or had
     * fallen back to paragraphs. Since the number is stamped into the signature
     * below, the two are now consistent in what they mean AND in what a change
     * to the setting costs — a re-chunk on the next scan, either way.
     */
    private val paragraphMinChars: Int get() = cfg.chunkMinChars

    private val paragraphSignature: String
        get() = "paragraph/v2 min=$paragraphMinChars max=${cfg.chunkMaxChars}"

    fun split(body: String): Split {
        if (!cfg.semanticChunking) return paragraphs(body)

        val pieces = Semantic.pieces(body, cfg.chunkMaxChars)
        // One sentence has nothing to be bunched with, and embedding it twice
        // to discover that is a wasted API call.
        if (pieces.size < 2) return paragraphs(body)

        val texts = try {
            val vectors = embeddings.documents(pieces.map { it.text })
            Semantic.bunch(pieces, vectors, cfg.chunkMinChars, cfg.chunkMaxChars, cfg.semanticThreshold)
        } catch (e: Exception) {
            // Indexing a document with paragraph chunks beats not indexing it.
            // It is stamped with the PARAGRAPH signature, so once the embedding
            // API is answering again the next scan sees a document chunked the
            // wrong way and re-chunks it — the fallback repairs itself.
            log.warn("semantic chunking failed ({}) — falling back to paragraphs", e.toString())
            return paragraphs(body)
        }

        log.debug("semantic chunking: {} sentence(s) -> {} chunk(s)", pieces.size, texts.size)
        return Split(
            texts.mapIndexed { i, text -> Chunker.Chunk(i, text, Bm25.tokenize(text).size) },
            signature,
        )
    }

    private fun paragraphs(body: String) = Split(
        Chunker.split(body, minChars = paragraphMinChars, maxChars = cfg.chunkMaxChars),
        paragraphSignature,
    )
}
