package com.edupulse.ingeststatic.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcVectorStoreChecksumRepository implements VectorStoreChecksumRepository {

    private static final String EXISTS_SQL = """
            SELECT COUNT(*)
            FROM vector_store
            WHERE metadata->>'doc_id' = ?
              AND metadata->>'checksum' = ?
            """;

    private final JdbcTemplate jdbcTemplate;

    public JdbcVectorStoreChecksumRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public boolean existsByDocIdAndChecksum(String docId, String checksum) {
        Integer count = jdbcTemplate.queryForObject(EXISTS_SQL, Integer.class, docId, checksum);
        return count != null && count > 0;
    }
}
