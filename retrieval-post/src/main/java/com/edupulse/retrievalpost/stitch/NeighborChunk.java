package com.edupulse.retrievalpost.stitch;

/**
 * A chunk loaded from {@code vector_store} by position ({@code doc_id}, {@code version}, {@code chunk_index}).
 */
public record NeighborChunk(
        String id,
        String docId,
        int version,
        String source,
        Integer page,
        int chunkIndex,
        String content
) {
}
