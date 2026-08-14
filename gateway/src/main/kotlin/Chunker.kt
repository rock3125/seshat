/**
 * Text file -> paragraphs. The FALLBACK chunker.
 *
 * Semantic bunching (Semantic.kt) is what normally runs; this is what indexes a
 * document when SEMANTIC_CHUNKING is off, when a file is one sentence long, or
 * when the embedding API is unreachable in the middle of a scan — a document
 * chunked by paragraph beats a document not indexed at all, and the chunker
 * signature on the row means the next scan re-chunks it properly.
 *
 * The rule is literal: split along paragraphs, meaning two or more consecutive
 * line breaks. That rule alone produces two shapes of bad chunk, so two
 * corrections sit on top of it and nothing else does:
 *
 *   too small  A heading, a date line or a single-line list item is its own
 *              "paragraph" under the split rule, and on its own it retrieves
 *              nothing — "3.2 Dosage" matches a query about dosage and then
 *              carries no answer. Runs shorter than [MIN_CHARS] are glued onto
 *              the paragraph that follows them, which is exactly the text the
 *              heading was introducing.
 *   too large  A 30k-character paragraph (minutes, a transcript, a wall of
 *              prose written without blank lines) embeds to a single vector
 *              that means nothing in particular. Anything over [MAX_CHARS] is
 *              cut on sentence boundaries.
 *
 * No overlap and no structure parser, here or in the semantic chunker. The
 * interface both produce — a list of ordinals with text — is what either would
 * slot into.
 */
object Chunker {

    /** Below this, a paragraph cannot stand alone as a retrieval unit. */
    const val MIN_CHARS = 90

    /** Above this, one paragraph is doing too many jobs to embed as one vector. */
    const val MAX_CHARS = 3_000

    data class Chunk(val ordinal: Int, val text: String, val tokens: Int)

    /** Two or more line breaks, blank-but-for-whitespace lines included, and
     *  CRLF as readily as LF — a library folder collects files from everywhere. */
    private val PARAGRAPH_BREAK = Regex("(?:\\r\\n|\\r|\\n)[ \\t]*(?:\\r\\n|\\r|\\n)+")

    /** Sentence end followed by whitespace — used only to cut oversized runs. */
    private val SENTENCE_END = Regex("(?<=[.!?])\\s+")

    fun split(body: String, minChars: Int = MIN_CHARS, maxChars: Int = MAX_CHARS): List<Chunk> {
        val paragraphs = PARAGRAPH_BREAK.split(body)
            .map { it.trim() }
            .filter { it.isNotEmpty() }

        // Glue short runs forward onto the next real paragraph. A short run at
        // the very end of a file has nothing to attach to, so it joins the
        // previous chunk instead of being dropped — a closing line is still text.
        val merged = ArrayList<String>()
        val pending = StringBuilder()
        for (p in paragraphs) {
            if (pending.isNotEmpty()) pending.append("\n\n")
            pending.append(p)
            if (pending.length >= minChars) {
                merged.add(pending.toString())
                pending.setLength(0)
            }
        }
        if (pending.isNotEmpty()) {
            if (merged.isEmpty()) merged.add(pending.toString())
            else merged[merged.lastIndex] = merged.last() + "\n\n" + pending
        }

        val out = ArrayList<Chunk>()
        for (text in merged) {
            for (piece in capped(text, maxChars)) {
                out.add(Chunk(out.size, piece, Bm25.tokenize(piece).size))
            }
        }
        return out
    }

    /** One paragraph as one piece, or several sentence-aligned pieces if it is
     *  over [MAX_CHARS]. A single sentence longer than the cap (no punctuation
     *  at all — a CSV row, a base64 blob) is emitted whole rather than cut
     *  mid-word: better one oversized chunk than a chunk that ends in gibberish. */
    private fun capped(text: String, maxChars: Int): List<String> {
        if (text.length <= maxChars) return listOf(text)
        val out = ArrayList<String>()
        val buf = StringBuilder()
        for (sentence in SENTENCE_END.split(text)) {
            if (buf.isNotEmpty() && buf.length + 1 + sentence.length > maxChars) {
                out.add(buf.toString().trim())
                buf.setLength(0)
            }
            if (buf.isNotEmpty()) buf.append(' ')
            buf.append(sentence)
        }
        if (buf.isNotEmpty()) out.add(buf.toString().trim())
        return out.filter { it.isNotEmpty() }
    }

    /** The document's title: its first non-blank line if that line reads like a
     *  heading (short, no sentence-ending punctuation), else the file name. */
    fun titleOf(body: String, fileName: String): String {
        val first = body.lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty()
        val looksLikeHeading = first.isNotEmpty() && first.length <= 120 && !first.endsWith(".")
        return if (looksLikeHeading) first.trimStart('#', ' ') else fileName
    }
}
