package com.edupulse.retrievalpost.stitch;

import com.edupulse.retrievalpost.common.MetadataValues;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Loads sibling chunks by position directly from {@code vector_store} (no embedding call needed).
 * <p>
 * Metadata is compared as text ({@code metadata->>'version' = '1'}), never cast: a cast is evaluated on rows of
 * other documents too, so a single row with non-numeric metadata would make every lookup fail. All values are
 * bound as JDBC parameters; only the number of {@code ?} placeholders is built dynamically.
 */
@Repository
public class NeighborChunkRepository {

    static final String SELECT_BY_POSITION = """
            SELECT id::text AS id,
                   content,
                   metadata->>'source' AS source,
                   metadata->>'page' AS page,
                   metadata->>'chunk_index' AS chunk_index
            FROM vector_store
            WHERE metadata->>'doc_id' = ?
              AND metadata->>'version' = ?
              AND metadata->>'chunk_index' IN (%s)
            """;

    private final JdbcTemplate jdbcTemplate;

    public NeighborChunkRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * @return chunks of {@code docId}/{@code version} whose {@code chunk_index} is in {@code chunkIndexes},
     *         keyed by chunk index; rows with an unparsable {@code chunk_index} are skipped
     */
    public Map<Integer, NeighborChunk> findByChunkIndexes(String docId, int version, Collection<Integer> chunkIndexes) {
        if (docId == null || chunkIndexes == null || chunkIndexes.isEmpty()) {
            return Map.of();
        }
        List<Object> params = new ArrayList<>(chunkIndexes.size() + 2);
        params.add(docId);
        params.add(String.valueOf(version));
        chunkIndexes.forEach(index -> params.add(String.valueOf(index)));
        String placeholders = String.join(", ", Collections.nCopies(chunkIndexes.size(), "?"));

        List<NeighborChunk> rows = jdbcTemplate.query(
                SELECT_BY_POSITION.formatted(placeholders),
                (rs, rowNum) -> {
                    Integer chunkIndex = MetadataValues.asInteger(rs.getString("chunk_index"));
                    if (chunkIndex == null) {
                        return null;
                    }
                    return new NeighborChunk(
                            rs.getString("id"),
                            docId,
                            version,
                            rs.getString("source"),
                            MetadataValues.asInteger(rs.getString("page")),
                            chunkIndex,
                            rs.getString("content"));
                },
                params.toArray());

        Map<Integer, NeighborChunk> byIndex = new TreeMap<>();
        rows.stream().filter(Objects::nonNull).forEach(row -> byIndex.putIfAbsent(row.chunkIndex(), row));
        return byIndex;
    }
}
