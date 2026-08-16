import io.qdrant.client.ConditionFactory.match
import io.qdrant.client.PointIdFactory.id
import io.qdrant.client.QdrantClient
import io.qdrant.client.QdrantGrpcClient
import io.qdrant.client.QueryFactory.fusion
import io.qdrant.client.QueryFactory.nearest
import io.qdrant.client.ValueFactory.value
import io.qdrant.client.VectorFactory.vector
import io.qdrant.client.VectorsFactory.namedVectors
import io.qdrant.client.grpc.Collections.CreateCollection
import io.qdrant.client.grpc.Collections.Distance
import io.qdrant.client.grpc.Collections.Modifier
import io.qdrant.client.grpc.Collections.SparseVectorConfig
import io.qdrant.client.grpc.Collections.SparseVectorParams
import io.qdrant.client.grpc.Collections.VectorParams
import io.qdrant.client.grpc.Collections.VectorParamsMap
import io.qdrant.client.grpc.Collections.VectorsConfig
import io.qdrant.client.grpc.Points.Filter
import io.qdrant.client.grpc.Points.Fusion
import io.qdrant.client.grpc.Points.PointStruct
import io.qdrant.client.grpc.Points.PrefetchQuery
import io.qdrant.client.grpc.Points.QueryPoints
import org.slf4j.LoggerFactory

/** Which vectors a search runs on. */
enum class Mode {
    HYBRID,   // dense + BM25, fused server-side with reciprocal rank fusion
    DENSE,    // meaning only
    KEYWORD;  // exact terms only

    companion object {
        fun from(s: String?): Mode = when (s?.trim()?.lowercase()) {
            null, "", "hybrid" -> HYBRID
            "dense", "vector", "semantic" -> DENSE
            "keyword", "bm25", "sparse" -> KEYWORD
            else -> throw IllegalArgumentException("bad mode '$s' (expected hybrid|dense|keyword)")
        }
    }
}

/**
 * Qdrant: the vector index, and only that.
 *
 * One collection with two named vectors per point — `dense` (Gemini embedding,
 * cosine) and `bm25` (sparse, IDF modifier). A point id IS the Postgres
 * `chunk.id`, and the payload carries only what a filter or a result header
 * needs; the chunk text itself is never stored here. That split is deliberate:
 * the vector store can be dropped and rebuilt from Postgres at any time
 * (`POST /reindex`), and no answer ever depends on two copies of the same text
 * agreeing with each other.
 *
 * Hybrid search is one round trip: two prefetches (dense and sparse) fused with
 * RRF inside Qdrant. There is no cross-encoder rerank stage — that needs a
 * local model, which is exactly the dependency this build is avoiding, so RRF
 * over the two retrievers is the ranking.
 */
class Store(private val cfg: Config) : AutoCloseable {
    private val log = LoggerFactory.getLogger("Store")

    private val client: QdrantClient =
        QdrantClient(QdrantGrpcClient.newBuilder(cfg.qdrantHost, cfg.qdrantPort, false).build())

    /** Create the collection if it isn't there, waiting for Qdrant to come up. */
    fun ensureCollection() {
        var attempt = 0
        while (true) {
            try {
                if (client.collectionExistsAsync(cfg.collection).await()) {
                    checkDimensions()
                    log.info("collection '{}' ready", cfg.collection)
                    return
                }
                client.createCollectionAsync(
                    CreateCollection.newBuilder()
                        .setCollectionName(cfg.collection)
                        .setVectorsConfig(
                            VectorsConfig.newBuilder().setParamsMap(
                                VectorParamsMap.newBuilder().putMap(
                                    DENSE,
                                    VectorParams.newBuilder()
                                        .setSize(cfg.embedDims.toLong())
                                        .setDistance(Distance.Cosine)
                                        .build(),
                                ),
                            ),
                        )
                        // Modifier.Idf is what lets the stored sparse vectors
                        // carry term frequency only — Qdrant supplies inverse
                        // document frequency from live corpus statistics at
                        // query time. See Bm25's class comment.
                        .setSparseVectorsConfig(
                            SparseVectorConfig.newBuilder().putMap(
                                SPARSE,
                                SparseVectorParams.newBuilder().setModifier(Modifier.Idf).build(),
                            ),
                        )
                        .build(),
                ).await()
                log.info("created collection '{}' (dense {}d cosine + bm25 sparse)",
                    cfg.collection, cfg.embedDims)
                return
            } catch (e: Dimensions) {
                throw e          // a configuration error, not something to wait out
            } catch (e: Exception) {
                attempt++
                if (attempt % 6 == 1) log.warn("waiting for Qdrant: {}", e.message)
                if (attempt > 120) throw e
                Thread.sleep(2_000)
            }
        }
    }

    /** EMBED_DIMS no longer matching the collection that is actually there. */
    class Dimensions(message: String) : RuntimeException(message)

    /**
     * The existing collection's dense width against the configured one.
     *
     * A collection is created once and then only ever opened, so changing
     * EMBED_DIMS against an existing Qdrant volume used to be accepted in
     * silence and then fail on every single upsert, for ever, with a gRPC
     * dimension error a long way from its cause. The width is a property of the
     * stored vectors and cannot be changed in place: the collection has to be
     * dropped and the corpus re-embedded, so this says exactly that and stops.
     */
    private fun checkDimensions() {
        val actual = client.getCollectionInfoAsync(cfg.collection).await()
            .config.params.vectorsConfig.paramsMap.mapMap[DENSE]?.size?.toInt()
            ?: return   // no named dense vector: an older or hand-made collection, leave it be
        if (actual == cfg.embedDims) return
        throw Dimensions(
            "collection '${cfg.collection}' stores ${actual}d dense vectors but EMBED_DIMS is " +
                "${cfg.embedDims}. A collection's width cannot be changed in place — either set " +
                "EMBED_DIMS back to $actual, or delete the collection and re-index " +
                "(docker compose down -v qdrant, then POST /reindex).",
        )
    }

    data class Point(
        val chunkId: Long,
        val documentId: Long,
        val ordinal: Int,
        val path: String,
        val title: String,
        val dense: FloatArray,
        val sparse: Bm25.Sparse,
    )

    fun upsert(points: List<Point>) {
        if (points.isEmpty()) return
        // Batched: one upsert of ten thousand points is a single gRPC message
        // large enough to be refused, and a failure loses the whole document.
        for (batch in points.chunked(256)) {
            client.upsertAsync(cfg.collection, batch.map { p ->
                val vectors = buildMap {
                    put(DENSE, vector(p.dense.toList()))
                    // A chunk of pure punctuation tokenizes to nothing. Omitting
                    // the sparse vector leaves it dense-searchable rather than
                    // making Qdrant reject an empty sparse vector.
                    if (!p.sparse.isEmpty) put(SPARSE, vector(p.sparse.values, p.sparse.indices))
                }
                PointStruct.newBuilder()
                    .setId(id(p.chunkId))
                    .setVectors(namedVectors(vectors))
                    .putAllPayload(mapOf(
                        "document_id" to value(p.documentId),
                        "ordinal" to value(p.ordinal.toLong()),
                        "path" to value(p.path),
                        "title" to value(p.title),
                    ))
                    .build()
            }).await(WRITE_TIMEOUT_SECONDS)
        }
    }

    /** Drop every point belonging to one document — the first half of a
     *  re-index, and all of a deletion. */
    fun deleteDocument(documentId: Long) {
        client.deleteAsync(
            cfg.collection,
            Filter.newBuilder().addMust(match("document_id", documentId)).build(),
        ).await(WRITE_TIMEOUT_SECONDS)
    }

    data class Hit(val chunkId: Long, val score: Float)

    /**
     * Retrieve candidate chunk ids, best first.
     *
     * [dense] and [sparse] are supplied by the caller rather than computed here
     * so that a KEYWORD search costs no embedding API call at all — the caller
     * knows which vectors the mode actually needs.
     */
    fun search(mode: Mode, dense: FloatArray?, sparse: Bm25.Sparse?, limit: Int): List<Hit> {
        val q = QueryPoints.newBuilder()
            .setCollectionName(cfg.collection)
            .setLimit(limit.toLong())

        when (mode) {
            Mode.HYBRID -> {
                requireNotNull(dense) { "hybrid search needs a dense vector" }
                requireNotNull(sparse) { "hybrid search needs a sparse vector" }
                q.addPrefetch(
                    PrefetchQuery.newBuilder()
                        .setQuery(nearest(dense.toList())).setUsing(DENSE)
                        .setLimit(limit.toLong()).build(),
                )
                // A query of nothing but stopwords has no sparse side; run it
                // as a dense-only prefetch rather than sending an empty vector.
                if (!sparse.isEmpty) {
                    q.addPrefetch(
                        PrefetchQuery.newBuilder()
                            .setQuery(nearest(sparse.values, sparse.indices)).setUsing(SPARSE)
                            .setLimit(limit.toLong()).build(),
                    )
                }
                q.setQuery(fusion(Fusion.RRF))
            }
            Mode.DENSE -> {
                requireNotNull(dense) { "dense search needs a dense vector" }
                q.setQuery(nearest(dense.toList())).setUsing(DENSE)
            }
            Mode.KEYWORD -> {
                requireNotNull(sparse) { "keyword search needs a sparse vector" }
                if (sparse.isEmpty) return emptyList()
                q.setQuery(nearest(sparse.values, sparse.indices)).setUsing(SPARSE)
            }
        }

        return client.queryAsync(q.build()).await().map { Hit(it.id.num, it.score) }
    }

    /** A real round trip, for the readiness probe. */
    fun ping() {
        client.collectionExistsAsync(cfg.collection).await()
    }

    override fun close() {
        runCatching { client.close() }
    }

    companion object {
        const val DENSE = "dense"
        const val SPARSE = "bm25"

        /** A search happens with someone waiting on it; a batch upsert of 256
         *  points does not. Both are bounded, because the alternative to a
         *  bounded wait is a request thread parked for ever on a Qdrant that
         *  stopped answering without closing the connection. */
        private const val READ_TIMEOUT_SECONDS = 30L
        private const val WRITE_TIMEOUT_SECONDS = 120L

        /**
         * `get()` with a deadline, and without the wrapper.
         *
         * A future that fails reports an ExecutionException whose message is
         * the class name of the real cause, which is what turned a Qdrant error
         * into "java.util.concurrent.ExecutionException" in the logs. Unwrapping
         * here means every caller's error message is Qdrant's own.
         */
        private fun <T> java.util.concurrent.Future<T>.await(
            seconds: Long = READ_TIMEOUT_SECONDS,
        ): T = try {
            get(seconds, java.util.concurrent.TimeUnit.SECONDS)
        } catch (e: java.util.concurrent.ExecutionException) {
            throw e.cause ?: e
        } catch (e: java.util.concurrent.TimeoutException) {
            cancel(true)
            throw java.util.concurrent.TimeoutException("Qdrant did not answer within ${seconds}s")
        }
    }
}
