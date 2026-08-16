import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * CHUNK_MIN_CHARS has to govern BOTH chunkers.
 *
 * It used to reach only the semantic one: the paragraph fallback was called
 * with the maximum alone and silently used its own constant instead, so the
 * setting the brief names did nothing whenever semantic chunking was off — or
 * had fallen back to paragraphs because the embedding API was unreachable,
 * which is exactly when nobody is looking.
 */
class ChunkingConfigTest {

    /** The real defaults, with semantic chunking off so no embedding call is
     *  made and the fallback is what runs. */
    private fun config(minChars: Int) = Config.fromEnv().copy(
        semanticChunking = false,
        chunkMinChars = minChars,
        chunkMaxChars = 3_000,
        geminiApiKey = "",
    )

    private fun chunking(minChars: Int) = Chunking(config(minChars), Embeddings(config(minChars)))

    /** Six short paragraphs of about forty characters each. Under a 200-char
     *  minimum they glue together; under a 50-char one they mostly do not. */
    private val body = (1..6).joinToString("\n\n") {
        "Paragraph $it, which is a short line of prose."
    }

    @Test
    fun `the configured minimum reaches the paragraph chunker`() {
        val tight = chunking(50).split(body).chunks
        val loose = chunking(200).split(body).chunks

        // The regression: both of these used to come back identical, because
        // both used Chunker.MIN_CHARS and neither used the setting.
        assertTrue(
            tight.size > loose.size,
            "a 50-char minimum should split more than a 200-char one — got ${tight.size} and ${loose.size}",
        )
        assertTrue(loose.all { it.text.length >= 100 }, "chunks should be glued up towards the minimum")
    }

    @Test
    fun `the signature carries the minimum, so changing it re-chunks the corpus`() {
        val tight = chunking(50).split(body).signature
        val loose = chunking(200).split(body).signature

        assertTrue(tight.contains("min=50"), "signature should name the minimum: $tight")
        assertTrue(loose.contains("min=200"), "signature should name the minimum: $loose")
        // A document's signature is compared against the current one on every
        // scan, so two settings that chunk differently MUST stamp differently
        // or the corpus ends up half one shape and half the other.
        assertTrue(tight != loose)
    }

    @Test
    fun `the signature is stable for one setting`() {
        assertEquals(chunking(200).split(body).signature, chunking(200).split(body).signature)
    }
}
