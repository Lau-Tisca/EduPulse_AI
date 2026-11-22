package com.edupulse.retrievalpost.stitch;

import java.util.List;

/**
 * A contiguous run of chunks from one document version: reranked hit(s) plus their stitched neighbors.
 *
 * @param rank               best (lowest) rerank position among the hits contained in this passage
 * @param chunkIndexes       every chunk in the passage, ascending
 * @param anchorChunkIndexes the chunks that were actual rerank hits (the rest are neighbors)
 * @param pages              distinct pages covered, ascending (for citations)
 * @param rerankScore        best Cohere score among the anchors ({@code null} on rerank fallback)
 * @param vectorScore        best vector similarity among the anchors
 * @param tokenCount         tokens of {@code text} after overlap removal
 */
public record StitchedPassage(
        int rank,
        String docId,
        Integer version,
        String source,
        List<Integer> pages,
        List<Integer> chunkIndexes,
        List<Integer> anchorChunkIndexes,
        Double rerankScore,
        Double vectorScore,
        int tokenCount,
        String text
) {
}
