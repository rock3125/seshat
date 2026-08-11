import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class Bm25Test {

    @Test
    fun `tokenizing lowercases, drops stopwords and keeps digits`() {
        assertEquals(
            listOf("remedy", "obstruction", "heart", "4", "days"),
            Bm25.tokenize("The remedy for an obstruction of the heart, 4 days."),
        )
    }

    @Test
    fun `single letters go but single digits stay`() {
        // "a" and "b" are debris from list bullets; "7" is exactly the kind of
        // exact token BM25 exists to match.
        assertEquals(listOf("7", "doses"), Bm25.tokenize("a b 7 doses"))
    }

    @Test
    fun `accented and non-Latin text tokenizes rather than vanishing`() {
        assertEquals(listOf("café", "műhely"), Bm25.tokenize("café műhely"))
        assertEquals(listOf("日本語"), Bm25.tokenize("日本語"))
    }

    @Test
    fun `a document vector has one entry per distinct term`() {
        val v = Bm25.document("heart heart heart remedy", avgdl = 4.0)
        assertEquals(2, v.indices.size)
        assertEquals(v.indices.size, v.values.size)
        assertEquals(v.indices.toSet().size, v.indices.size, "duplicate indices are rejected by Qdrant")
    }

    @Test
    fun `term frequency saturates`() {
        val avgdl = 20.0
        // Same document length, more repetitions of the term: the weight rises
        // but far less than linearly (that is the k1 term doing its job).
        fun weight(repeats: Int): Float {
            val text = List(repeats) { "heart" }.joinToString(" ") +
                " " + List(20 - repeats) { "filler$it" }.joinToString(" ")
            val v = Bm25.document(text, avgdl)
            return v.values[v.indices.indexOf(Bm25.index("heart"))]
        }
        val one = weight(1)
        val four = weight(4)
        assertTrue(four > one, "more occurrences should score higher")
        assertTrue(four < 4 * one, "term frequency must saturate, not scale linearly")
    }

    @Test
    fun `a long document is penalised against a short one for the same term count`() {
        val short = Bm25.document("heart remedy", avgdl = 10.0)
        val long = Bm25.document("heart remedy " + (1..40).joinToString(" ") { "filler$it" }, avgdl = 10.0)
        val shortWeight = short.values[short.indices.indexOf(Bm25.index("heart"))]
        val longWeight = long.values[long.indices.indexOf(Bm25.index("heart"))]
        assertTrue(longWeight < shortWeight, "length normalisation should discount the long document")
    }

    @Test
    fun `the query side carries presence only — Qdrant supplies idf`() {
        val q = Bm25.query("the heart and the remedy")
        assertEquals(listOf(1.0f, 1.0f), q.values)
        assertEquals(setOf(Bm25.index("heart"), Bm25.index("remedy")), q.indices.toSet())
    }

    @Test
    fun `text with no terms produces an empty vector rather than throwing`() {
        assertTrue(Bm25.document("!!! ... ---", avgdl = 10.0).isEmpty)
        assertTrue(Bm25.query("the and of").isEmpty)
    }

    @Test
    fun `indices are non-negative — Qdrant sparse indices are unsigned`() {
        // hashCode() is signed, and several ordinary words hash negative.
        for (term in listOf("heart", "remedy", "obstruction", "polyphyletic", "zzz", "a1")) {
            assertTrue(Bm25.index(term) >= 0, "$term hashed negative")
        }
    }
}
