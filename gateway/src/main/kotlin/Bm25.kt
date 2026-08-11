/**
 * BM25 as a Qdrant sparse vector.
 *
 * BM25 factors cleanly into a per-document part and a per-query part:
 *
 *     score(d, q) = Σ  idf(t) · tf_norm(t, d)
 *                  t∈q
 *
 * and Qdrant scores a sparse vector by dot product, so the two halves can live
 * on the two sides of the query:
 *
 *   document vector   index = hash(term), value = tf_norm(term, chunk)
 *   query vector      index = hash(term), value = 1.0
 *   idf               supplied by Qdrant itself — the `bm25` sparse vector is
 *                     configured with Modifier.Idf (see Store.ensureCollection),
 *                     which makes Qdrant compute inverse document frequency
 *                     from its own live corpus statistics at query time.
 *
 * Letting Qdrant own idf is what keeps this honest as documents arrive. The
 * obvious alternative — baking idf into the stored vectors — is wrong the
 * moment the corpus grows, because every previously-indexed chunk then carries
 * an idf computed against a corpus that no longer exists, and fixing it means
 * re-encoding the entire collection on every new file.
 *
 * `avgdl` is the one statistic that does get baked in, at index time. It moves
 * slowly (it is a mean over every chunk) and it only rescales length
 * normalisation, so drift costs a little ranking accuracy rather than
 * correctness. A full reindex (`POST /reindex`) recomputes it.
 *
 * Terms are hashed to 31-bit indices rather than dictionary-mapped, so no term
 * table has to be kept in sync between indexing and querying. Two distinct
 * terms can collide onto one index; at 2^31 slots against a vocabulary in the
 * tens of thousands that is rare enough to be a ranking nuisance rather than a
 * correctness problem, and it buys a completely stateless encoder.
 */
object Bm25 {

    /** Term-frequency saturation. 1.2 is the standard default: past ~3
     *  occurrences, more repetitions of a term barely raise the score. */
    const val K1 = 1.2

    /** Length normalisation strength. 0.75 is the standard default. */
    const val B = 0.75

    /** Words carrying no retrieval signal. Deliberately short — an aggressive
     *  list starts eating terms that matter in a specialist corpus ("no",
     *  "not", "all" change the meaning of a clinical or legal sentence). */
    private val STOPWORDS = setOf(
        "a", "an", "and", "are", "as", "at", "be", "but", "by", "for", "from",
        "has", "have", "he", "her", "his", "in", "is", "it", "its", "of", "on",
        "or", "she", "that", "the", "their", "them", "they", "this", "to", "was",
        "were", "will", "with", "you", "your",
    )

    /** Letter/digit runs, lowercased. Unicode-aware, so accented text and
     *  non-Latin scripts tokenize rather than vanishing. Single characters go
     *  (they are punctuation debris or list bullets far more often than terms);
     *  digits are kept at any length, because "2024" and "7" are exactly the
     *  kind of exact token BM25 exists to match. */
    private val TERM = Regex("[\\p{L}\\p{N}]+")

    fun tokenize(text: String): List<String> =
        TERM.findAll(text.lowercase())
            .map { it.value }
            .filter { it.length > 1 || it[0].isDigit() }
            .filter { it !in STOPWORDS }
            .toList()

    /** A sparse vector in the shape Qdrant's gRPC client wants. */
    data class Sparse(val indices: List<Int>, val values: List<Float>) {
        val isEmpty get() = indices.isEmpty()
    }

    /** 31-bit non-negative index for a term. Qdrant sparse indices are uint32. */
    fun index(term: String): Int = term.hashCode() and 0x7fff_ffff

    /**
     * The document side: BM25's tf_norm per distinct term.
     *
     *     tf · (k1 + 1) / (tf + k1 · (1 − b + b · dl / avgdl))
     */
    fun document(text: String, avgdl: Double): Sparse {
        val tokens = tokenize(text)
        if (tokens.isEmpty()) return Sparse(emptyList(), emptyList())
        val dl = tokens.size.toDouble()
        val norm = K1 * (1.0 - B + B * dl / avgdl.coerceAtLeast(1.0))

        // Distinct terms only: repeats are already carried by tf, and a
        // duplicate index in a sparse vector is not something Qdrant accepts.
        val counts = LinkedHashMap<String, Int>()
        for (t in tokens) counts.merge(t, 1, Int::plus)

        val indices = ArrayList<Int>(counts.size)
        val values = ArrayList<Float>(counts.size)
        for ((term, tf) in counts) {
            indices.add(index(term))
            values.add((tf * (K1 + 1.0) / (tf + norm)).toFloat())
        }
        return dedupe(indices, values)
    }

    /** The query side: presence only. Qdrant multiplies each by that term's
     *  live idf, which is the half of BM25 that is not stored. */
    fun query(text: String): Sparse {
        val terms = tokenize(text).distinct()
        return dedupe(terms.map(::index), List(terms.size) { 1.0f })
    }

    /** Two different terms can hash to the same index (see the class comment).
     *  Keep the larger weight rather than emitting the index twice — Qdrant
     *  rejects a sparse vector with duplicate indices, so this is a correctness
     *  guard, not a tidiness one. */
    private fun dedupe(indices: List<Int>, values: List<Float>): Sparse {
        val best = LinkedHashMap<Int, Float>(indices.size)
        for (i in indices.indices) best.merge(indices[i], values[i], ::maxOf)
        return Sparse(best.keys.toList(), best.values.toList())
    }
}
