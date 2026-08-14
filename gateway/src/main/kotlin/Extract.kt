import org.apache.tika.metadata.Metadata
import org.apache.tika.metadata.TikaCoreProperties
import org.apache.tika.parser.AutoDetectParser
import org.apache.tika.parser.ParseContext
import org.apache.tika.sax.BodyContentHandler
import org.slf4j.LoggerFactory
import java.io.ByteArrayInputStream

/**
 * Anything -> text, via Apache Tika.
 *
 * The library indexes text and only text. Tika is what makes that a storage
 * decision rather than a restriction on what you may hand it: a PDF, a Word
 * document, a spreadsheet, an email, a slide deck — all of them arrive here as
 * bytes and leave as the text they contain, which is then chunked, embedded and
 * indexed exactly like a file that was born as `.md`.
 *
 * Three things worth knowing about what comes back:
 *
 *   Layout is gone. Tika returns the reading order of the document body, not
 *   its geometry. That is the right input for retrieval — a two-column PDF
 *   whose columns interleave line by line would embed as noise — but it means
 *   the converted text is a rendering of the document, not a copy of it.
 *
 *   No OCR. A scanned page is an image inside a PDF, and Tika only reads it
 *   with Tesseract installed alongside. It isn't, so a scan converts to nothing
 *   and the upload says so instead of silently indexing an empty document.
 *
 *   The parsers come from META-INF/services. The fat jar merges those files
 *   rather than choosing between them (see build.gradle.kts); the failure mode
 *   if that ever regresses is not a crash but a quietly shorter list of
 *   formats, so it is worth knowing the two are connected.
 */
class Extract(private val cfg: Config) {
    private val log = LoggerFactory.getLogger("Extract")

    /** Extracted text, with what the file turned out to be. */
    data class Result(val text: String, val mediaType: String, val truncated: Boolean)

    /**
     * The text inside [bytes]. [fileName] is a hint, not the decision — Tika
     * detects from content first, so a `.doc` that is really a PDF parses as a
     * PDF instead of failing.
     */
    fun from(bytes: ByteArray, fileName: String): Result {
        val handler = BodyContentHandler(cfg.extractMaxChars)
        val metadata = Metadata().apply {
            set(TikaCoreProperties.RESOURCE_NAME_KEY, fileName)
        }
        var truncated = false
        try {
            AutoDetectParser().parse(ByteArrayInputStream(bytes), handler, metadata, ParseContext())
        } catch (e: org.xml.sax.SAXException) {
            // The write limit is reported by throwing, with everything written
            // so far still in the handler. A document that long is a document
            // whose tail is not going to change an answer — take the head and
            // say it was cut rather than failing the upload.
            if (!isWriteLimit(e)) throw e
            truncated = true
            log.info("{} exceeded the {}-character extraction limit — indexing the first part",
                fileName, cfg.extractMaxChars)
        }

        val media = metadata.get("Content-Type")?.substringBefore(';')?.trim().orEmpty()
        // PDF and Word text is thick with non-breaking spaces, which tokenize
        // as part of the word beside them and quietly cost keyword matches.
        val text = handler.toString().replace('\u00A0', ' ').trim()
        log.info("extracted {} character(s) from {} ({})", text.length, fileName,
            media.ifBlank { "unknown type" })
        return Result(text, media, truncated)
    }

    /** What Tika thinks the bytes are, without parsing them. Used for the label
     *  on an upload, so it is worth nothing more than a best effort. */
    fun mediaType(bytes: ByteArray, fileName: String): String = try {
        val metadata = Metadata().apply { set(TikaCoreProperties.RESOURCE_NAME_KEY, fileName) }
        org.apache.tika.config.TikaConfig.getDefaultConfig().detector
            .detect(ByteArrayInputStream(bytes), metadata).toString()
    } catch (e: Exception) {
        ""
    }

    private fun isWriteLimit(e: Throwable): Boolean {
        var cause: Throwable? = e
        var depth = 0
        while (cause != null && depth++ < 10) {
            if (cause.javaClass.simpleName == "WriteLimitReachedException") return true
            if (cause.message?.contains("write limit", ignoreCase = true) == true) return true
            cause = cause.cause
        }
        return false
    }

    companion object {
        /**
         * A human name for a media type, for the one line the UI shows about a
         * converted file. Anything not listed falls back to the subtype, which
         * is already readable more often than not.
         */
        fun formatName(mediaType: String): String = when (mediaType.substringBefore(';').trim()) {
            "application/pdf" -> "PDF"
            "application/msword" -> "Word"
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document" -> "Word"
            "application/vnd.ms-excel" -> "Excel"
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" -> "Excel"
            "application/vnd.ms-powerpoint" -> "PowerPoint"
            "application/vnd.openxmlformats-officedocument.presentationml.presentation" -> "PowerPoint"
            "application/vnd.oasis.opendocument.text" -> "OpenDocument"
            "application/rtf", "text/rtf" -> "RTF"
            "application/epub+zip" -> "EPUB"
            "message/rfc822" -> "email"
            "text/html", "application/xhtml+xml" -> "HTML"
            // Reached when a text file was not valid UTF-8 and Tika transcoded
            // it — a Latin-1 export, a UTF-16 log.
            "text/plain" -> "text"
            "" -> "document"
            else -> mediaType.substringAfterLast('/').substringBefore(';')
                .removePrefix("x-").removePrefix("vnd.").ifBlank { "document" }
        }
    }
}
