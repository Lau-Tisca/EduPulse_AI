package com.edupulse.retrievalpost.stitch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

class NeighborChunkRepositoryTest {

    private JdbcTemplate jdbcTemplate;
    private NeighborChunkRepository repository;

    @BeforeEach
    void setUp() {
        jdbcTemplate = mock(JdbcTemplate.class);
        repository = new NeighborChunkRepository(jdbcTemplate);
    }

    @Test
    @SuppressWarnings("unchecked")
    void comparesMetadataAsBoundTextWithoutCasting() {
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), any(Object[].class))).thenReturn(List.of());

        repository.findByChunkIndexes("k8s", 1, List.of(4, 6));

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object[]> params = ArgumentCaptor.forClass(Object[].class);
        verify(jdbcTemplate).query(sql.capture(), any(RowMapper.class), params.capture());
        assertThat(sql.getValue()).doesNotContain("::numeric").doesNotContain("::int")
                .contains("metadata->>'chunk_index' IN (?, ?)");
        assertThat(params.getValue()).containsExactly("k8s", "1", "4", "6");
    }

    @Test
    @SuppressWarnings("unchecked")
    void mapsRowsLenientlyAndSkipsRowsWithUnparsableChunkIndex() throws SQLException {
        List<Map<String, String>> rows = List.of(
                Map.of("id", "a", "content", "text 4", "source", "k8s.pdf", "page", "iv", "chunk_index", "4"),
                Map.of("id", "b", "content", "text 6", "source", "k8s.pdf", "page", "7.0", "chunk_index", "6"),
                Map.of("id", "c", "content", "broken", "source", "k8s.pdf", "page", "1", "chunk_index", "x"));
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), any(Object[].class))).thenAnswer(invocation -> {
            RowMapper<NeighborChunk> mapper = invocation.getArgument(1);
            List<NeighborChunk> mapped = new ArrayList<>();
            for (int i = 0; i < rows.size(); i++) {
                mapped.add(mapper.mapRow(resultSet(rows.get(i)), i));
            }
            return mapped;
        });

        Map<Integer, NeighborChunk> result = repository.findByChunkIndexes("k8s", 1, List.of(4, 6));

        assertThat(result).containsOnlyKeys(4, 6);
        assertThat(result.get(4).page()).isNull();
        assertThat(result.get(6).page()).isEqualTo(7);
        assertThat(result.get(6).content()).isEqualTo("text 6");
    }

    @Test
    void skipsQueryForEmptyIndexes() {
        assertThat(repository.findByChunkIndexes("k8s", 1, List.of())).isEmpty();
        verifyNoInteractions(jdbcTemplate);
    }

    private static ResultSet resultSet(Map<String, String> row) throws SQLException {
        ResultSet rs = mock(ResultSet.class);
        for (Map.Entry<String, String> column : row.entrySet()) {
            when(rs.getString(column.getKey())).thenReturn(column.getValue());
        }
        return rs;
    }
}
