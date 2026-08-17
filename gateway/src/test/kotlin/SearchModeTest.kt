import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The `mode` argument of the search tool, as the MODEL sends it.
 *
 * Nothing type-checks this on the way in: it arrives as a string in a JSON tool
 * call the model composed, so the synonyms are there because a model reaching
 * for "semantic" or "bm25" is reaching for something real, and the throw is
 * there because silently falling back to hybrid would hide the fact that the
 * mode it asked for was never applied.
 */
class SearchModeTest {

    @Test
    fun `an absent mode is hybrid — the default the tool description promises`() {
        assertEquals(Mode.HYBRID, Mode.from(null))
        assertEquals(Mode.HYBRID, Mode.from(""))
        assertEquals(Mode.HYBRID, Mode.from("   "))
        assertEquals(Mode.HYBRID, Mode.from("hybrid"))
    }

    @Test
    fun `the dense synonyms all mean meaning`() {
        for (word in listOf("dense", "vector", "semantic")) {
            assertEquals(Mode.DENSE, Mode.from(word), word)
        }
    }

    @Test
    fun `the sparse synonyms all mean exact terms`() {
        for (word in listOf("keyword", "bm25", "sparse")) {
            assertEquals(Mode.KEYWORD, Mode.from(word), word)
        }
    }

    @Test
    fun `case and surrounding whitespace do not matter`() {
        assertEquals(Mode.DENSE, Mode.from("  Dense  "))
        assertEquals(Mode.KEYWORD, Mode.from("BM25"))
        assertEquals(Mode.HYBRID, Mode.from("\tHybrid\n"))
    }

    @Test
    fun `an unknown mode is refused, and the refusal lists what would have worked`() {
        // The message goes back to the model as the tool result, so it has to be
        // usable as an instruction — a bare "bad mode" would just get retried.
        val e = assertFailsWith<IllegalArgumentException> { Mode.from("fuzzy") }

        assertTrue("fuzzy" in e.message!!, e.message!!)
        for (valid in listOf("hybrid", "dense", "keyword")) {
            assertTrue(valid in e.message!!, "the message should name '$valid': ${e.message}")
        }
    }

    @Test
    fun `every mode the enum has is reachable by name`() {
        // A mode added later without a wire word for it would be unreachable,
        // which is the sort of thing only a test notices.
        val reachable = listOf("hybrid", "dense", "keyword").map { Mode.from(it) }.toSet()
        assertEquals(Mode.entries.toSet(), reachable)
    }
}
