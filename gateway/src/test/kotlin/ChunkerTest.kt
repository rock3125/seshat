import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ChunkerTest {

    private fun para(n: Int) = "Sentence one of paragraph $n, long enough to stand on its own. " +
        "Sentence two of paragraph $n, also of a reasonable length for retrieval."

    @Test
    fun `splits on a blank line`() {
        val chunks = Chunker.split("${para(1)}\n\n${para(2)}")
        assertEquals(2, chunks.size)
        assertEquals(listOf(0, 1), chunks.map { it.ordinal })
    }

    @Test
    fun `splits on CRLF blank lines and on runs of more than two breaks`() {
        val chunks = Chunker.split("${para(1)}\r\n\r\n${para(2)}\n\n\n\n${para(3)}")
        assertEquals(3, chunks.size)
    }

    @Test
    fun `a single newline does not split a paragraph`() {
        // Hard-wrapped prose is one paragraph, not five.
        val wrapped = "The first line of a wrapped paragraph,\nthe second line,\n" +
            "the third line, and enough text after it that the whole thing clears the minimum."
        assertEquals(1, Chunker.split(wrapped).size)
    }

    @Test
    fun `a short heading is glued to the paragraph it introduces`() {
        val chunks = Chunker.split("3.2 Dosage\n\n${para(1)}")
        assertEquals(1, chunks.size)
        assertTrue(chunks[0].text.startsWith("3.2 Dosage"))
        assertTrue(chunks[0].text.contains("Sentence one"))
    }

    @Test
    fun `a trailing short run joins the previous chunk rather than vanishing`() {
        val chunks = Chunker.split("${para(1)}\n\nEnd.")
        assertEquals(1, chunks.size)
        assertTrue(chunks[0].text.endsWith("End."))
    }

    @Test
    fun `an oversized paragraph is cut on sentence boundaries`() {
        val long = (1..400).joinToString(" ") { "This is sentence number $it in a very long run." }
        val chunks = Chunker.split(long)
        assertTrue(chunks.size > 1, "expected the cap to split it, got ${chunks.size}")
        assertTrue(chunks.all { it.text.length <= Chunker.MAX_CHARS })
        // Nothing lost: every sentence still appears somewhere.
        val rejoined = chunks.joinToString(" ") { it.text }
        assertTrue(rejoined.contains("sentence number 1 in"))
        assertTrue(rejoined.contains("sentence number 400 in"))
    }

    @Test
    fun `ordinals are contiguous from zero — load_chunk windows depend on it`() {
        val body = (1..6).joinToString("\n\n") { para(it) }
        val chunks = Chunker.split(body)
        assertEquals(chunks.indices.toList(), chunks.map { it.ordinal })
    }

    @Test
    fun `empty and whitespace-only input yields no chunks`() {
        assertEquals(0, Chunker.split("").size)
        assertEquals(0, Chunker.split("   \n\n  \t \n\n ").size)
    }

    @Test
    fun `title comes from a heading line, or falls back to the file name`() {
        assertEquals("The Ebers Papyrus", Chunker.titleOf("# The Ebers Papyrus\n\nBody.", "e.txt"))
        // A first line that is a real sentence is body text, not a title.
        assertEquals("e.txt", Chunker.titleOf("This is the opening sentence.", "e.txt"))
    }

    @Test
    fun `binary content is refused, valid UTF-8 is accepted`() {
        assertNull(Library.decodeText(byteArrayOf(0x50, 0x4B, 0x03, 0x04, 0x00, 0x01)))  // NUL
        assertNull(Library.decodeText(byteArrayOf(0xC3.toByte(), 0x28)))                  // bad UTF-8
        assertNull(Library.decodeText(ByteArray(0)))
        assertEquals("héllo", Library.decodeText("héllo".toByteArray(Charsets.UTF_8)))
    }
}
