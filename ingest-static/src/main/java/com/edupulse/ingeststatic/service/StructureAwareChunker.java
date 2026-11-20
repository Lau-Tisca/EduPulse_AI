package com.edupulse.ingeststatic.service;

import com.edupulse.ingeststatic.config.IngestStaticProperties;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.ai.document.Document;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.stereotype.Service;

/**
 * Splits parsed PDF pages using Spring AI's TokenTextSplitter (APP_CHUNK_SIZE, APP_CHUNK_OVERLAP)
 * and attaches the mandatory EduPulse RAG metadata to every chunk.
 */
@Service
public class StructureAwareChunker {

    private final IngestStaticProperties properties;

    public StructureAwareChunker(IngestStaticProperties properties) {
        this.properties = properties;
    }

    public List<Document> chunk(
            List<Document> pageDocuments,
            String docId,
            String source,
            String checksum,
            int version
    ) {
        TokenTextSplitter splitter = TokenTextSplitter.builder()
                .withChunkSize(properties.chunkSize())
                .withMinChunkSizeChars(1)
                .withMinChunkLengthToEmbed(1)
                .withKeepSeparator(true)
                .build();

        List<Document> chunks = new ArrayList<>();
        int chunkIndex = 0;
        String previousChunkText = null;

        for (Document pageDoc : pageDocuments) {
            int pageNumber = resolvePageNumber(pageDoc.getMetadata());
            List<Document> splitDocs = splitter.apply(List.of(pageDoc));

            for (Document splitDoc : splitDocs) {
                String chunkText = splitDoc.getText();
                if (chunkText == null || chunkText.isBlank()) {
                    continue;
                }

                String finalText = applyOverlap(previousChunkText, chunkText, properties.chunkOverlap());
                previousChunkText = chunkText;

                Map<String, Object> metadata = new LinkedHashMap<>();
                metadata.put("doc_id", docId);
                metadata.put("version", version);
                metadata.put("source", source);
                metadata.put("page", pageNumber);
                metadata.put("chunk_index", chunkIndex);
                metadata.put("checksum", checksum);
                metadata.put("is_latest", true);

                chunks.add(new Document(finalText, metadata));
                chunkIndex++;
            }
        }
        return chunks;
    }

    private String applyOverlap(String previousText, String currentText, int overlapTokens) {
        if (overlapTokens <= 0 || previousText == null || previousText.isBlank()) {
            return currentText.trim();
        }
        String[] words = previousText.trim().split("\\s+");
        if (words.length == 0) {
            return currentText.trim();
        }
        int start = Math.max(0, words.length - overlapTokens);
        String overlapPrefix = String.join(" ", Arrays.copyOfRange(words, start, words.length));
        if (currentText.startsWith(overlapPrefix)) {
            return currentText.trim();
        }
        return overlapPrefix + "\n" + currentText.trim();
    }

    private int resolvePageNumber(Map<String, Object> metadata) {
        Object pageObj = metadata.get("page");
        if (pageObj instanceof Number num) {
            return Math.max(1, num.intValue());
        }
        return 1;
    }
}
