package com.edupulse.ingeststatic.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.google.genai.errors.ClientException;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;

class VectorStoreBatchWriterTest {

    @Test
    void retriesOn429RateLimitAndSucceeds() {
        VectorStore vectorStore = mock(VectorStore.class);
        ClientException rateLimitEx = new ClientException(429, "Too Many Requests", "Quota exceeded");
        doThrow(rateLimitEx)
                .doNothing()
                .when(vectorStore).add(anyList());

        VectorStoreBatchWriter writer = new VectorStoreBatchWriter(vectorStore, 2, 2, 0L);
        List<Document> docs = List.of(
                new Document("chunk 1"),
                new Document("chunk 2"),
                new Document("chunk 3")
        );

        writer.addAll(docs, "test-doc");

        // Batch 1 (2 docs): fails once + succeeds on retry (2 calls); Batch 2 (1 doc): succeeds (1 call)
        verify(vectorStore, times(3)).add(anyList());
    }

    @Test
    void rethrowsAfterMaxRetriesExhausted() {
        VectorStore vectorStore = mock(VectorStore.class);
        ClientException rateLimitEx = new ClientException(429, "Too Many Requests", "Quota exceeded");
        doThrow(rateLimitEx).when(vectorStore).add(anyList());

        VectorStoreBatchWriter writer = new VectorStoreBatchWriter(vectorStore, 2, 2, 0L);

        assertThatThrownBy(() -> writer.addAll(List.of(new Document("chunk 1")), "test-doc"))
                .isSameAs(rateLimitEx);

        verify(vectorStore, times(3)).add(anyList());
    }
}
