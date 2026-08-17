import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Turning an uploaded file into text, and naming the format it came from.
 *
 * Tika itself is not on trial here — its parsers are. What is worth pinning is
 * the handling around them, all of which is easy to break and silent when
 * broken: the write limit that arrives as an EXCEPTION rather than a return
 * value (so the naive `catch` throws away text that was successfully read), the
 * non-breaking spaces that PDF and Word text is full of (they are not
 * whitespace to a chunker or to BM25), and content-based detection beating a
 * misleading file name.
 */
class ExtractTest {

    private val cfg = Config.fromEnv()

    private fun extract(maxChars: Int = 2_000_000) = Extract(cfg.copy(extractMaxChars = maxChars))

    private fun bytes(text: String) = text.toByteArray(Charsets.UTF_8)

    // ---- reading text out of a file -----------------------------------------

    @Test
    fun `a plain text file comes back as itself`() {
        val result = extract().from(bytes("Seshat keeps the record.\n\nAnd the second paragraph."), "note.txt")

        assertTrue("Seshat keeps the record." in result.text)
        assertTrue("second paragraph" in result.text)
        assertEquals("text/plain", result.mediaType)
        assertFalse(result.truncated)
    }

    @Test
    fun `markup is stripped rather than indexed`() {
        // A `.html` upload that kept its tags would put `<p>` and `class=` into
        // the BM25 vocabulary and into the answers.
        val result = extract().from(
            bytes("<html><body><h1>The Ebers Papyrus</h1><p>A remedy for obstruction.</p></body></html>"),
            "page.html",
        )

        assertTrue("The Ebers Papyrus" in result.text)
        assertTrue("A remedy for obstruction." in result.text)
        assertFalse("<p>" in result.text, result.text)
        assertEquals("text/html", result.mediaType)
    }

    @Test
    fun `non-breaking spaces become ordinary spaces`() {
        // Extracted PDF and Word text is full of U+00A0. It is not whitespace to
        // `split`, so a sentence that contains one arrives as a single enormous
        // token: unsearchable by term, and unsplittable by the chunker.
        // Written as escapes on purpose: a literal U+00A0 in a source file is
        // invisible to whoever reads this next.
        val result = extract().from(bytes("a\u00A0remedy\u00A0for\u00A0obstruction"), "note.txt")

        assertFalse('\u00A0' in result.text, "the non-breaking spaces survived")
        assertEquals(listOf("a", "remedy", "for", "obstruction"), result.text.trim().split(" "))
    }

    @Test
    fun `a document over the limit is truncated, and says so, keeping what was read`() {
        // The write limit is reported by THROWING out of the parse, with the text
        // so far already in the handler. Treating that as a failure would reject
        // exactly the documents most worth indexing; ignoring the flag would
        // index half a document and claim it was whole.
        val long = (1..500).joinToString(" ") { "sentence number $it." }
        val result = extract(maxChars = 200).from(bytes(long), "long.txt")

        assertTrue(result.truncated, "a document past the cap must report itself truncated")
        assertTrue(result.text.isNotEmpty(), "the part that was read must survive the write limit")
        assertTrue(result.text.length < long.length)
        assertTrue(result.text.startsWith("sentence number 1."), result.text.take(40))
    }

    @Test
    fun `a document exactly within the limit is not marked truncated`() {
        val result = extract(maxChars = 10_000).from(bytes("short enough"), "note.txt")

        assertFalse(result.truncated)
    }

    @Test
    fun `a file with nothing readable in it comes back blank`() {
        // Blank text is the signal `Library.upload` turns into "no text could be
        // read out of …" — the scanned-page case. It must arrive as an empty
        // result, not as an exception.
        val result = extract().from(bytes("<html><head><title>Ignored</title></head><body></body></html>"), "hollow.html")

        assertTrue(result.text.isBlank(), "got '${result.text}'")
        assertFalse(result.truncated)
    }

    @Test
    fun `a zero-byte file throws, which is why upload checks before converting`() {
        // Tika refuses an empty stream outright rather than returning no text,
        // so the guard in `Library.upload` (`if (bytes.isEmpty()) throw
        // Rejected`) is load-bearing: without it an empty upload becomes a 500
        // from inside a parser instead of "…is empty". Pinned here so that
        // anyone tempted to drop that check finds out from a test.
        assertFailsWith<org.apache.tika.exception.ZeroByteFileException> {
            extract().from(ByteArray(0), "empty.txt")
        }
    }

    // ---- detection ----------------------------------------------------------

    @Test
    fun `the content decides the format, not the file name`() {
        // Someone renaming a PDF to `.bin` (or a browser sending no useful name
        // at all) must not stop it being parsed as a PDF.
        val pdfish = bytes("%PDF-1.4\n1 0 obj\n<< /Type /Catalog >>\nendobj\n")

        assertEquals("application/pdf", extract().mediaType(pdfish, "mystery.bin"))
        assertEquals("PDF", Extract.formatName(extract().mediaType(pdfish, "mystery.bin")))
    }

    @Test
    fun `detection on random bytes answers something rather than throwing`() {
        val noise = ByteArray(64) { (it * 7 + 3).toByte() }

        assertTrue(extract().mediaType(noise, "noise.dat").isNotBlank())
    }

    // ---- naming the format for the reader -----------------------------------

    @Test
    fun `the formats the UI shows by name`() {
        val expected = mapOf(
            "application/pdf" to "PDF",
            "application/msword" to "Word",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document" to "Word",
            "application/vnd.ms-excel" to "Excel",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" to "Excel",
            "application/vnd.ms-powerpoint" to "PowerPoint",
            "application/vnd.openxmlformats-officedocument.presentationml.presentation" to "PowerPoint",
            "application/vnd.oasis.opendocument.text" to "OpenDocument",
            "application/rtf" to "RTF",
            "text/rtf" to "RTF",
            "application/epub+zip" to "EPUB",
            "message/rfc822" to "email",
            "text/html" to "HTML",
            "application/xhtml+xml" to "HTML",
            "text/plain" to "text",
        )
        for ((media, name) in expected) assertEquals(name, Extract.formatName(media), media)
    }

    @Test
    fun `a charset parameter is not part of the format`() {
        // Tika returns `text/html; charset=UTF-8`, and an unstripped parameter
        // would miss every mapping above and show the reader 'html; charset=UTF-8'.
        assertEquals("HTML", Extract.formatName("text/html; charset=UTF-8"))
        assertEquals("text", Extract.formatName("text/plain;charset=ISO-8859-1"))
    }

    @Test
    fun `an unlisted format falls back to something readable`() {
        assertEquals("latex", Extract.formatName("application/x-latex"))
        assertEquals("mobipocket-ebook", Extract.formatName("application/vnd.mobipocket-ebook"))
        assertEquals("csv", Extract.formatName("text/csv"))
    }

    @Test
    fun `an absent or malformed media type is called a document`() {
        // The one line the UI shows must read as a sentence even when detection
        // came back with nothing.
        assertEquals("document", Extract.formatName(""))
        assertEquals("document", Extract.formatName("application/"))
        assertEquals("document", Extract.formatName("   "))
    }
}
