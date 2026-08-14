import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * The name an upload lands under is the one place an HTTP caller picks a path
 * on the gateway's disk, so it gets its own tests: the traversal cases are the
 * point, and the rest of the rules exist so a file cannot be accepted and then
 * be invisible to the scanner that has to index it.
 */
class UploadNameTest {

    @Test
    fun `keeps an ordinary text file name`() {
        assertEquals("notes.md", Library.safeName("notes.md"))
        assertEquals("Q3 report (final).txt", Library.safeName("  Q3 report (final).txt  "))
    }

    @Test
    fun `drops every directory component, either separator`() {
        assertEquals("notes.md", Library.safeName("/etc/passwd/../notes.md"))
        assertEquals("notes.md", Library.safeName("C:\\Users\\rock\\Documents\\notes.md"))
        assertEquals("notes.md", Library.safeName("../../../../notes.md"))
    }

    @Test
    fun `refuses a traversal that would leave the library folder`() {
        // Nothing survives the basename step, so these have no name left at all.
        assertFailsWith<Library.Rejected> { Library.safeName("../..") }
        assertFailsWith<Library.Rejected> { Library.safeName("/") }
        assertFailsWith<Library.Rejected> { Library.safeName("   ") }
    }

    @Test
    fun `refuses a dotfile — the scanner would skip it and the upload would vanish`() {
        assertFailsWith<Library.Rejected> { Library.safeName(".env") }
        assertFailsWith<Library.Rejected> { Library.safeName(".hidden.md") }
    }

    @Test
    fun `accepts any format — Tika is what decides whether there is text in it`() {
        assertEquals("report.pdf", Library.safeName("report.pdf"))
        assertEquals("minutes.docx", Library.safeName("minutes.docx"))
        assertEquals("archive.tar.gz", Library.safeName("archive.tar.gz"))
        assertEquals("README", Library.safeName("README"))
    }

    @Test
    fun `a name keeps its case`() {
        assertEquals("NOTES.MD", Library.safeName("NOTES.MD"))
    }

    @Test
    fun `refuses control characters and absurd lengths`() {
        assertFailsWith<Library.Rejected> { Library.safeName("notes\u0000.md") }
        assertFailsWith<Library.Rejected> { Library.safeName("n".repeat(300) + ".md") }
    }

    @Test
    fun `a mislabelled binary is not text, whatever it is called`() {
        // The name passes; the decode is what catches it. Both tests run on an
        // upload, in that order.
        assertEquals("scan.txt", Library.safeName("scan.txt"))
        assertEquals(null, Library.decodeText(byteArrayOf(0x25, 0x50, 0x44, 0x46, 0x00, 0x01)))
    }
}
