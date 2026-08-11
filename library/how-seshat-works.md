# How Seshat works

Seshat answers questions from a folder of text files and nothing else. This document describes what happens between dropping a file in that folder and reading a cited answer, and it is itself indexed, so the system can answer questions about its own behaviour.

## Ingestion

A background scanner walks the library folder on start-up and every five minutes thereafter. It considers only files with a text extension — txt, md, markdown, rst, log, csv, tsv, json, yaml, adoc and a few others — and it then opens each one and attempts a strict UTF-8 decode. A file that contains a NUL byte, or that fails to decode, is skipped and logged. This second test is the one that matters in practice: a PDF renamed to end in .txt passes the extension check and would otherwise be indexed as several kilobytes of nonsense that pollutes every subsequent search.

Each accepted file is hashed with SHA-256 and the hash is recorded against its path. On a later scan, a file whose hash is unchanged is skipped entirely — no re-chunking, no embedding calls, no database writes. This is what makes a five-minute poll cheap enough to be the whole ingestion mechanism rather than something that needs a queue behind it.

## Chunking

Text is split along paragraphs, meaning two or more consecutive line breaks. A single newline does not split anything, so hard-wrapped prose stays one paragraph.

That rule alone produces two kinds of bad chunk, and two corrections sit on top of it. A run shorter than ninety characters — a heading, a date line, a one-line list item — is glued onto the paragraph that follows it, because a heading on its own matches a query and then carries no answer. A paragraph longer than three thousand characters is cut on sentence boundaries, because a single vector cannot usefully represent a wall of text about eleven different things.

Each resulting paragraph becomes a chunk with a contiguous ordinal within its document. The ordinals being contiguous is what allows the load_chunk tool to fetch a window of neighbouring paragraphs by range.

## Indexing

Every chunk is written to Postgres, which holds the authoritative text, and indexed in Qdrant, which holds only vectors and the identifiers needed to get back to Postgres. A chunk's database primary key is also its Qdrant point identifier, so one integer names a paragraph in both stores. Because the text lives in exactly one place, the vector index can be dropped and rebuilt at any time without re-reading a single file.

Two vectors are stored per chunk. The dense vector is a Gemini embedding at 768 dimensions, L2-normalised and compared by cosine distance; it captures meaning, so a question phrased completely differently from the source still retrieves it. The sparse vector is BM25, which captures exact terms — names, codes, numbers, identifiers — that a dense vector reliably loses.

The BM25 side is split across the two ends of a query. The stored vector carries only term frequency with length normalisation; inverse document frequency is supplied by Qdrant itself at query time, from its own live corpus statistics. This is what keeps the index honest as documents arrive. Baking inverse document frequency into the stored vectors would mean every previously indexed chunk carried statistics for a corpus that no longer existed, and correcting that would mean re-encoding the whole collection every time a file was added.

## Retrieval

A search runs both retrievers and fuses the two ranked lists inside Qdrant using reciprocal rank fusion. Two other modes are available and the model chooses between them: dense-only, for a concept the corpus may word completely differently, and keyword-only, for an exact identifier where semantic similarity is noise.

There is no cross-encoder reranking stage. Reranking would improve result ordering measurably, and it needs a local model — which is the single dependency this build exists to avoid.

## Answering

The chat model is given two tools and told to search before answering anything, to search again with different words when the first results are thin, and to cite every claim by chunk identifier. The interface rewrites those citations into clickable references. A citation pointing at a chunk that was not among the retrieved results is rendered visibly dead rather than hidden, because a fabricated citation is precisely the thing a reader needs to be able to see.
