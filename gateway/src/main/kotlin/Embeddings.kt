import org.json.JSONArray
import org.json.JSONObject
import org.slf4j.LoggerFactory
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore
import kotlin.math.sqrt

/**
 * Dense embeddings from the Gemini embedding API.
 *
 * The alternative — a Python sentence-transformers sidecar, which is what a
 * bigger version of this system runs — is the single thing that would break
 * "one jar, one process". Calling Gemini instead keeps the dense side to an
 * HTTP request with the key that is already in `.env` for chat, at the cost of
 * needing the network while indexing.
 *
 * Two things this client has to get right, because both are silent failures:
 *
 *   task type    A retrieval embedding model puts documents and queries in
 *                deliberately different places in the space. Embedding a query
 *                as RETRIEVAL_DOCUMENT still returns a perfectly valid vector,
 *                and search just gets quietly worse. Index and query calls are
 *                therefore separate methods, not one method with a flag the
 *                caller may forget.
 *   normalisation gemini-embedding-001 returns unit-length vectors ONLY at its
 *                native 3072 dimensions. At the truncated sizes (768, 1536)
 *                the output is not normalised, and cosine on unnormalised
 *                vectors of varying magnitude ranks by length as much as by
 *                meaning. Every vector is L2-normalised here, whatever the
 *                dimensionality, so the stored and query sides always agree.
 */
class Embeddings(private val cfg: Config, private val metrics: Metrics? = null) {
    private val log = LoggerFactory.getLogger("Embeddings")
    private val http: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(20))
        .build()

    /** Chunks per request. The API caps a batch at 100 inputs; 32 keeps each
     *  request small enough to retry cheaply when one fails. */
    private val batchSize = 32

    /** Input character cap. gemini-embedding-001 takes 2048 tokens; the chunker
     *  already caps a chunk well below that, so this only catches a pathological
     *  paragraph and truncates it rather than failing the whole document. */
    private val maxChars = 6_000

    val dimensions: Int get() = cfg.embedDims

    /**
     * Vectors for text being INDEXED. Order matches the input.
     *
     * The batches go out concurrently, up to EMBED_CONCURRENCY at a time. They
     * used to go one after another, which made indexing wall-clock a straight
     * multiple of the number of batches — a 10,000-chunk reindex is 313 of
     * them, and every one was a full round trip to Google spent waiting. Each
     * batch is independent, so the only thing serialising them was the loop.
     *
     * The cap is a real limit, not a formality: the embedding API rate-limits
     * per key, and firing 313 requests at once earns 429s that [withRetry] then
     * backs off from, which is slower than not having sent them.
     */
    fun documents(texts: List<String>): List<FloatArray> {
        val batches = texts.chunked(batchSize)
        if (batches.size <= 1) return batches.flatMap { embed(it, "RETRIEVAL_DOCUMENT") }

        val gate = Semaphore(cfg.embedConcurrency.coerceAtLeast(1))
        // One virtual thread per batch, all parked on the semaphore: the
        // threads are free, the permits are what does the limiting.
        return Executors.newVirtualThreadPerTaskExecutor().use { pool ->
            val futures = batches.map { batch ->
                pool.submit<List<FloatArray>> {
                    gate.acquire()
                    try {
                        embed(batch, "RETRIEVAL_DOCUMENT")
                    } finally {
                        gate.release()
                    }
                }
            }
            // Indexed in submission order, so the result is in input order
            // whatever order the responses actually arrived in — which is the
            // property Library depends on to pair vectors with chunk ids.
            futures.flatMap { future ->
                try {
                    future.get()
                } catch (e: ExecutionException) {
                    throw e.cause ?: e
                }
            }
        }
    }

    /** The vector for text being SEARCHED WITH. */
    fun query(text: String): FloatArray = embed(listOf(text), "RETRIEVAL_QUERY").first()

    private fun embed(texts: List<String>, taskType: String): List<FloatArray> {
        require(cfg.geminiApiKey.isNotBlank()) {
            "GEMINI_API_KEY is not set — the service cannot embed text without it"
        }
        val requests = JSONArray()
        for (t in texts) {
            requests.put(
                JSONObject()
                    .put("model", "models/${cfg.embedModel}")
                    .put("content", JSONObject().put(
                        "parts", JSONArray().put(JSONObject().put("text", t.take(maxChars)))))
                    .put("taskType", taskType)
                    .put("outputDimensionality", cfg.embedDims),
            )
        }
        val body = JSONObject().put("requests", requests).toString()
        val url = "${cfg.geminiBaseUrl}/v1beta/models/${cfg.embedModel}:batchEmbedContents"

        val response = withRetry("embed ${texts.size} text(s)") {
            val req = HttpRequest.newBuilder(URI(url))
                .header("Content-Type", "application/json")
                .header("x-goog-api-key", cfg.geminiApiKey)
                .timeout(Duration.ofSeconds(120))
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build()
            val res = http.send(req, HttpResponse.BodyHandlers.ofString())
            if (res.statusCode() !in 200..299) {
                throw EmbedFailure(res.statusCode(),
                    "embedding API HTTP ${res.statusCode()}: ${res.body().take(400)}")
            }
            res.body()
        }

        val arr = JSONObject(response).optJSONArray("embeddings")
            ?: throw IllegalStateException("embedding API returned no 'embeddings' array")
        check(arr.length() == texts.size) {
            "embedding API returned ${arr.length()} vectors for ${texts.size} inputs"
        }
        return (0 until arr.length()).map { i ->
            val values = arr.getJSONObject(i).getJSONArray("values")
            normalise(FloatArray(values.length()) { values.getDouble(it).toFloat() })
        }
    }

    /** Retry the transient half of the failure space (429 rate limits, 5xx,
     *  dropped connections) and nothing else — a 400 is a bad request and will
     *  be exactly as bad on the fourth attempt. */
    private fun <T> withRetry(what: String, call: () -> T): T {
        var delayMs = 2_000L
        var last: Exception? = null
        repeat(5) { attempt ->
            try {
                val out = call()
                metrics?.embedCall("ok")
                return out
            } catch (e: EmbedFailure) {
                // Counted separately from other failures because it is the one
                // that is a TUNING signal rather than a fault: a sustained rate
                // of 429s means EMBED_CONCURRENCY is above what the key allows,
                // and the backoff below is then making indexing slower than
                // sending fewer requests would have been.
                metrics?.embedCall(if (e.status == 429) "rate_limited" else "error")
                if (e.status != 429 && e.status !in 500..599) throw e
                last = e
            } catch (e: java.io.IOException) {
                metrics?.embedCall("error")
                last = e
            }
            log.warn("{} failed (attempt {}), retrying in {}ms: {}",
                what, attempt + 1, delayMs, last.message?.take(160))
            Thread.sleep(delayMs)
            delayMs = (delayMs * 2).coerceAtMost(30_000)
        }
        throw IllegalStateException("$what failed after 5 attempts", last)
    }

    private class EmbedFailure(val status: Int, message: String) : RuntimeException(message)

    companion object {
        /** L2-normalise in place and return — see the class comment for why this
         *  is not optional. A zero vector (empty input) is left alone rather
         *  than producing NaNs. */
        fun normalise(v: FloatArray): FloatArray {
            var sum = 0.0
            for (x in v) sum += x.toDouble() * x
            val norm = sqrt(sum)
            if (norm > 0) for (i in v.indices) v[i] = (v[i] / norm).toFloat()
            return v
        }
    }
}
