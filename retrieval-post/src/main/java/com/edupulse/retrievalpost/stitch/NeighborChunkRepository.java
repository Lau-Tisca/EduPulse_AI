package com.edupulse.retrievalpost.stitch;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Loads sibling chunks by position directly from {@code vector_store} (no embedding call needed).
 * All values are bound as JDBC parameters; only the number of {@code ?} placeholders is built dynamically.
 */
@Repository
public class NeighborChunkRepository {

    private static final String SELECT_BY_POSITION = """
            SELECT id::text AS id,
                   content,
                   metadata->>'source' AS source,
                   metadata->>'page' AS page,
                   metadata->>'chunk_index' AS chunk_index
            FROM vector_store
            WHERE metadata->>'doc_id' = ?
              AND (metadata->>'version')::numeric = ?
              AND (metadata->>'chunk_index')::numeric IN (%s)
            """;

    private final JdbcTemplate jdbcTemplate;

    public NeighborChunkRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * @return chunks of {@code docId}/{@code version} whose {@code chunk_index} is in {@code chunkIndexes},
     *         keyed by chunk index
     */
    public Map<Integer, NeighborChunk> findByChunkIndexes(String docId, int version, Collection<Integer> chunkIndexes) {
        if (docId == null || chunkIndexes == null || chunkIndexes.isEmpty()) {
            return Map.of();
        }
        List<Object> params = new ArrayList<>(chunkIndexes.size() + 2);
        params.add(docId);
        params.add(version);
        params.addAll(chunkIndexes);
        String placeholders = String.join(", ", Collections.nCopies(chunkIndexes.size(), "?"));

        List<NeighborChunk> rows = jdbcTemplate.query(
                SELECT_BY_POSITION.formatted(placeholders),
                (rs, rowNum) -> new NeighborChunk(
                        rs.getString("id"),
                        docId,
                        version,
                        rs.getString("source"),
                        parseInteger(rs.getString("page")),
                        parseInteger(rs.getString("chunk_index")),
                        rs.getString("content")),
                params.toArray());

        Map<Integer, NeighborChunk> byIndex = new TreeMap<>();
        for (NeighborChunk row : rows) {
            byIndex.putIfAbsent(row.chunkIndex(), row);
        }
        return byIndex;
    }

    private static Integer parseInteger(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return (int) Double.parseDouble(value.trim());
    }
}
