package com.edupulse.retrievalpost.rerank;

import java.util.Map;
import org.springframework.ai.document.Document;

/**
 * A retrieved chunk after reranking, carrying both scores and the citation metadata
 * ({@code doc_id}, {@code version}, {@code source}, {@code page}, {@code chunk_index}).
 *
 * @param rank        1-based position in the final ranking
 * @param vectorScore cosine similarity from the vector search (may be {@code null})
 * @param rerankScore Cohere relevance score (0..1); {@code null} when reranking fell back
 */
public record RankedChunk(
        int rank,
        String id,
        String docId,
        Integer version,
        String source,
        Integer page,
        Integer chunkIndex,
        String content,
        Double vectorScore,
        Double rerankScore
) {

    static RankedChunk from(Document document, int rank, Double rerankScore) {
        Map<String, Object> metadata = document.getMetadata();
        return new RankedChunk(
                rank,
                document.getId(),
                asString(metadata.get("doc_id")),
                asInteger(metadata.get("version")),
                asString(metadata.get("source")),
                asInteger(metadata.get("page")),
                asInteger(metadata.get("chunk_index")),
                document.getText(),
                document.getScore(),
                rerankScore
        );
    }

    private static String asString(Object value) {
        return value == null ? null : value.toString();
    }

    private static Integer asInteger(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof String text && !text.isBlank()) {
            try {
                return (int) Double.parseDouble(text.trim());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }
}
