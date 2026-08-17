import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * L2 normalisation, which is the one piece of the embedding client that is pure
 * arithmetic — and the one whose absence would be invisible.
 *
 * gemini-embedding-001 returns unit vectors only at its native 3072 dimensions.
 * At the truncated sizes this deployment uses (768), the output is NOT
 * normalised, and cosine over vectors of varying magnitude ranks partly by
 * length. Nothing would throw, no test of retrieval would fail outright, and
 * the answers would just be quietly worse — so the property is asserted here
 * directly.
 */
class EmbeddingsTest {

    private fun length(v: FloatArray): Double {
        var sum = 0.0
        for (x in v) sum += x.toDouble() * x
        return sqrt(sum)
    }

    @Test
    fun `a normalised vector has unit length`() {
        val v = Embeddings.normalise(floatArrayOf(3f, 4f))

        assertEquals(1.0, length(v), 1e-6)
        assertEquals(0.6f, v[0], 1e-6f)
        assertEquals(0.8f, v[1], 1e-6f)
    }

    @Test
    fun `direction is preserved — normalisation must not rotate anything`() {
        val v = Embeddings.normalise(floatArrayOf(1f, -2f, 3f))

        // Ratios between components are what carries the meaning.
        assertEquals(-2.0, (v[1] / v[0]).toDouble(), 1e-5)
        assertEquals(3.0, (v[2] / v[0]).toDouble(), 1e-5)
        assertTrue(v[1] < 0, "a negative component stays negative")
    }

    @Test
    fun `two vectors that differ only in magnitude become the same vector`() {
        // The point of normalising: a long passage and a short one about the
        // same thing must not be ranked apart by their lengths.
        val small = Embeddings.normalise(floatArrayOf(3f, 4f, 0f))
        val large = Embeddings.normalise(floatArrayOf(3_000f, 4_000f, 0f))

        for (i in small.indices) assertTrue(abs(small[i] - large[i]) < 1e-6f, "component $i")
    }

    @Test
    fun `a zero vector is left alone rather than filled with NaN`() {
        // Dividing by a zero norm would poison the whole collection: NaN in a
        // stored vector makes every distance to it NaN, and Qdrant's ordering
        // for those is nobody's intention.
        val v = Embeddings.normalise(floatArrayOf(0f, 0f, 0f))

        assertTrue(v.all { it == 0f }, v.joinToString())
        assertTrue(v.none { it.isNaN() })
    }

    @Test
    fun `dimensionality is untouched, and the array is normalised in place`() {
        val original = FloatArray(768) { (it % 7 + 1).toFloat() }
        val returned = Embeddings.normalise(original)

        assertEquals(768, returned.size)
        assertSame(original, returned, "documented as in-place; callers rely on the return value")
        assertEquals(1.0, length(returned), 1e-6)
    }

    @Test
    fun `a single component collapses to plus or minus one`() {
        assertEquals(1f, Embeddings.normalise(floatArrayOf(42f))[0])
        assertEquals(-1f, Embeddings.normalise(floatArrayOf(-42f))[0])
    }

    @Test
    fun `a vector large enough to overflow a float accumulator still normalises`() {
        // The sum of squares is accumulated as a Double for exactly this reason:
        // 768 components of 1e20 square to 1e40 each, which is beyond Float.
        val v = Embeddings.normalise(FloatArray(768) { 1e20f })

        assertEquals(1.0, length(v), 1e-6)
        assertTrue(v.none { it.isNaN() || it.isInfinite() })
    }
}
