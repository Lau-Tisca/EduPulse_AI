package com.edupulse.ingestdynamic.parser;

import com.edupulse.ingestdynamic.exception.ContentParseException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.reader.pdf.PagePdfDocumentReader;
import org.springframework.ai.reader.pdf.config.PdfDocumentReaderConfig;
import org.springframework.ai.reader.tika.TikaDocumentReader;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

/**
 * Parses PDFs (remote or local) via Spring AI's PagePdfDocumentReader with automatic fallback to
 * TikaDocumentReader, preserving real page numbers and sanitizing PostgreSQL-incompatible control bytes.
 */
@Component
public class PdfDocumentParser {

    private static final Logger log = LoggerFactory.getLogger(PdfDocumentParser.class);
    public static final byte[] PDF_MAGIC = "%PDF-".getBytes(StandardCharsets.US_ASCII);

    public boolean hasPdfMagic(byte[] bytes) {
        if (bytes == null || bytes.length < PDF_MAGIC.length) {
            return false;
        }
        for (int i = 0; i < PDF_MAGIC.length; i++) {
            if (bytes[i] != PDF_MAGIC[i]) {
                return false;
            }
        }
        return true;
    }

    public List<Document> parse(byte[] pdfBytes, String sourceName) {
        if (!hasPdfMagic(pdfBytes)) {
            throw new ContentParseException("Content does not have a valid PDF header (%PDF-): " + sourceName);
        }
        Resource resource = toNamedResource(pdfBytes, sourceName);

        List<Document> pageDocuments = parseWithPagePdfReader(resource, sourceName);
        if (pageDocuments.isEmpty()) {
            log.info("PagePdfDocumentReader yielded no text for '{}'; falling back to TikaDocumentReader", sourceName);
            pageDocuments = parseWithTikaReader(resource, sourceName);
        }
        if (pageDocuments.isEmpty()) {
            throw new ContentParseException("No extractable text found in PDF: " + sourceName);
        }
        return pageDocuments;
    }

    private List<Document> parseWithPagePdfReader(Resource resource, String sourceName) {
        try {
            PdfDocumentReaderConfig config = PdfDocumentReaderConfig.builder()
                    .withPagesPerDocument(1)
                    .build();
            PagePdfDocumentReader reader = new PagePdfDocumentReader(resource, config);
            List<Document> rawPages = reader.get();
            List<Document> cleaned = new ArrayList<>();

            int fallbackPage = 1;
            for (Document doc : rawPages) {
                String text = TextNormalizer.normalize(doc.getText());
                if (!text.isBlank()) {
                    int pageNumber = extractPageNumber(doc.getMetadata(), fallbackPage);
                    Map<String, Object> metadata = new LinkedHashMap<>(doc.getMetadata());
                    metadata.put("page", pageNumber);
                    cleaned.add(new Document(text, metadata));
                }
                fallbackPage++;
            }
            return cleaned;
        } catch (Exception ex) {
            log.warn("PagePdfDocumentReader failed for '{}': {}", sourceName, ex.getMessage());
            return List.of();
        }
    }

    private List<Document> parseWithTikaReader(Resource resource, String sourceName) {
        try {
            TikaDocumentReader reader = new TikaDocumentReader(resource);
            List<Document> rawDocs = reader.get();
            List<Document> cleaned = new ArrayList<>();
            int page = 1;
            for (Document doc : rawDocs) {
                String text = TextNormalizer.normalize(doc.getText());
                if (!text.isBlank()) {
                    Map<String, Object> metadata = new LinkedHashMap<>(doc.getMetadata());
                    metadata.put("page", page);
                    cleaned.add(new Document(text, metadata));
                    page++;
                }
            }
            return cleaned;
        } catch (Exception ex) {
            throw new ContentParseException("Failed to parse PDF '" + sourceName + "' with PDFBox and Tika", ex);
        }
    }

    private int extractPageNumber(Map<String, Object> metadata, int fallbackPage) {
        Object startPage = metadata.get(PagePdfDocumentReader.METADATA_START_PAGE_NUMBER);
        if (startPage instanceof Number num) {
            return Math.max(1, num.intValue());
        }
        if (startPage instanceof String str) {
            try {
                return Math.max(1, Integer.parseInt(str.trim()));
            } catch (NumberFormatException ignored) {
                return fallbackPage;
            }
        }
        return fallbackPage;
    }

    private Resource toNamedResource(byte[] bytes, String sourceName) {
        String safeName = (sourceName == null || sourceName.isBlank()) ? "document.pdf" : sourceName;
        return new ByteArrayResource(bytes) {
            @Override
            public String getFilename() {
                return safeName;
            }
        };
    }
}
