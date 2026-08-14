import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Semantic bunching, with the embedding model stubbed out.
 *
 * The vectors here are two orthogonal "topics" — A = (1,0), B = (0,1) — so
 * cosine is exactly 1 within a topic and exactly 0 across one. That makes every
 * assertion below about the RULES (minimum size, maximum size, where a boundary
 * lands) rather than about how a particular embedding model happens to score a
 * particular sentence, which is not a thing a unit test can pin down.
 */
class SemanticTest {

    private val a = floatArrayOf(1f, 0f)
    private val b = floatArrayOf(0f, 1f)

    private fun text(n: Int, word: String = "alpha") =
        (word.repeat(n / word.length + 1)).take(n)

    private fun pieces(vararg texts: String) =
        texts.map { Semantic.Piece(it, false) }

    @Test
    fun `splits where the subject changes`() {
        val ps = pieces(text(100, "alpha"), text(100, "alpha"), text(100, "alpha"),
            text(100, "beta"), text(100, "beta"), text(100, "beta"))
        val vs = listOf(a, a, a, b, b, b)

        val out = Semantic.bunch(ps, vs, minChars = 200, maxChars = 3_000, threshold = 0.5)

        assertEquals(2, out.size)
        assertTrue(out[0].startsWith("alpha") && !out[0].contains("beta"))
        assertTrue(out[1].startsWith("beta") && !out[1].contains("alpha"))
    }

    @Test
    fun `a bunch under the minimum takes the next sentence whatever the similarity says`() {
        // The case the 200-character floor exists for: a heading embeds to a
        // vector unrelated to the passage it introduces, and on its own it
        // retrieves nothing.
        val ps = pieces("3.2 Dosage", text(300, "beta"))
        val out = Semantic.bunch(ps, listOf(a, b), minChars = 200, maxChars = 3_000, threshold = 0.9)

        assertEquals(1, out.size)
        assertTrue(out[0].startsWith("3.2 Dosage"))
        assertTrue(out[0].contains("beta"))
    }

    @Test
    fun `the maximum still cuts a document that is similar to itself all the way down`() {
        val ps = pieces(text(900), text(900), text(900), text(900), text(900))
        val out = Semantic.bunch(ps, List(5) { a }, minChars = 200, maxChars = 2_000, threshold = 0.5)

        assertTrue(out.size > 1, "a uniform document must still be cut at the cap")
        assertTrue(out.all { it.length <= 2_000 }, "no bunch may pass the cap: ${out.map { it.length }}")
    }

    @Test
    fun `a trailing scrap is folded into the bunch before it`() {
        // Nothing follows it to grow into, so it would otherwise be indexed as
        // a 40-character chunk.
        val ps = pieces(text(300, "alpha"), text(40, "beta"))
        val out = Semantic.bunch(ps, listOf(a, b), minChars = 200, maxChars = 3_000, threshold = 0.9)

        assertEquals(1, out.size)
        assertTrue(out[0].contains("alpha") && out[0].contains("beta"))
    }

    @Test
    fun `paragraph breaks survive the round trip`() {
        val ps = listOf(
            Semantic.Piece("First half of the passage.", true),
            Semantic.Piece("Second half of the same passage.", false),
            Semantic.Piece("A new paragraph entirely.", true),
        )
        val out = Semantic.bunch(ps, listOf(a, a, a), minChars = 1, maxChars = 3_000, threshold = 0.5)

        assertEquals(1, out.size)
        assertTrue(out[0].contains("passage. Second half"), "same paragraph joins with a space")
        assertTrue(out[0].contains("passage.\n\nA new paragraph"), "a new paragraph keeps its break")
    }

    @Test
    fun `sentences are the unit, and paragraph starts are marked`() {
        val ps = Semantic.pieces(
            "One sentence here. Two sentences here.\n\nA new paragraph starts.", 3_000)

        assertEquals(3, ps.size)
        assertEquals(listOf(true, false, true), ps.map { it.startsParagraph })
        assertEquals("Two sentences here.", ps[1].text)
    }

    @Test
    fun `a run with no sentence end is wrapped at whitespace, not left oversized`() {
        val unpunctuated = List(300) { "word$it" }.joinToString(" ")
        val ps = Semantic.pieces(unpunctuated, 200)

        assertTrue(ps.size > 1)
        assertTrue(ps.all { it.text.length <= 200 })
    }

    @Test
    fun `a single token longer than the cap is left whole`() {
        // A base64 blob or a spaceless CSV row. Cutting mid-token would produce
        // two strings that match nothing at all, which is worse than one
        // oversized chunk the embedding client truncates.
        val blob = "x".repeat(500)
        val ps = Semantic.pieces(blob, 200)

        assertEquals(1, ps.size)
        assertEquals(500, ps[0].text.length)
    }

    @Test
    fun `cosine is scale-invariant, so a centroid does not need dividing`() {
        assertEquals(1.0, Semantic.cosine(doubleArrayOf(3.0, 0.0), a), 1e-9)
        assertEquals(0.0, Semantic.cosine(doubleArrayOf(3.0, 0.0), b), 1e-9)
        assertEquals(0.0, Semantic.cosine(doubleArrayOf(0.0, 0.0), a), 1e-9)
    }
}
