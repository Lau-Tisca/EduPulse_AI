package com.edupulse.ingeststatic.service;

import com.edupulse.ingeststatic.exception.InvalidPdfException;
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
import org.springframework.stereotype.Service;

/**
 * Parses PDFs (including multi-column layouts and tables) using Spring AI's
 * PagePdfDocumentReader with automatic fallback to TikaDocumentReader.
 */
@Service
public class PdfDocumentParser {

    private static final Logger log = LoggerFactory.getLogger(PdfDocumentParser.class);
    private static final byte[] PDF_MAGIC = "%PDF-".getBytes(StandardCharsets.US_ASCII);

    public List<Document> parse(byte[] pdfBytes, String filename) {
        validatePdfBytes(pdfBytes, filename);
        Resource resource = toNamedResource(pdfBytes, filename);

        List<Document> pageDocuments = parseWithPagePdfReader(resource, filename);
        if (pageDocuments.isEmpty()) {
            log.info("PagePdfDocumentReader yielded no text for '{}'; falling back to TikaDocumentReader", filename);
            pageDocuments = parseWithTikaReader(resource, filename);
        }

        if (pageDocuments.isEmpty()) {
            throw new InvalidPdfException("No extractable text found in PDF: " + filename);
        }
        return pageDocuments;
    }

    private void validatePdfBytes(byte[] pdfBytes, String filename) {
        if (pdfBytes == null || pdfBytes.length < PDF_MAGIC.length) {
            throw new InvalidPdfException("File is empty or too small to be a valid PDF: " + filename);
        }
        for (int i = 0; i < PDF_MAGIC.length; i++) {
            if (pdfBytes[i] != PDF_MAGIC[i]) {
                throw new InvalidPdfException("File does not have a valid PDF header (%PDF-): " + filename);
            }
        }
    }

    private List<Document> parseWithPagePdfReader(Resource resource, String filename) {
        try {
            PdfDocumentReaderConfig config = PdfDocumentReaderConfig.builder()
                    .withPagesPerDocument(1)
                    .build();
            PagePdfDocumentReader reader = new PagePdfDocumentReader(resource, config);
            List<Document> rawPages = reader.get();
            List<Document> cleaned = new ArrayList<>();

            int fallbackPage = 1;
            for (Document doc : rawPages) {
                String text = normalizeLayoutText(doc.getText());
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
            log.warn("PagePdfDocumentReader failed for '{}': {}", filename, ex.getMessage());
            return List.of();
        }
    }

    private List<Document> parseWithTikaReader(Resource resource, String filename) {
        try {
            TikaDocumentReader reader = new TikaDocumentReader(resource);
            List<Document> rawDocs = reader.get();
            List<Document> cleaned = new ArrayList<>();
            int page = 1;
            for (Document doc : rawDocs) {
                String text = normalizeLayoutText(doc.getText());
                if (!text.isBlank()) {
                    Map<String, Object> metadata = new LinkedHashMap<>(doc.getMetadata());
                    metadata.put("page", page);
                    cleaned.add(new Document(text, metadata));
                    page++;
                }
            }
            return cleaned;
        } catch (Exception ex) {
            throw new InvalidPdfException("Failed to parse PDF '" + filename + "' with PDFBox and Tika", ex);
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

    String normalizeLayoutText(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        // PostgreSQL UTF8 text columns reject NUL (\u0000) bytes sometimes present in PDF streams.
        String withoutControlChars = raw.replaceAll("[\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F]", "");
        String normalizedLineEndings = withoutControlChars.replace("\r\n", "\n").replace("\r", "\n");
        String collapsedSpaces = normalizedLineEndings.replaceAll("[ \\t\\f]+", " ");
        String trimmedLines = collapsedSpaces.replaceAll(" *\\n *", "\n");
        return trimmedLines.replaceAll("\\n{3,}", "\n\n").trim();
    }

    private Resource toNamedResource(byte[] bytes, String filename) {
        String safeName = (filename == null || filename.isBlank()) ? "document.pdf" : filename;
        return new ByteArrayResource(bytes) {
            @Override
            public String getFilename() {
                return safeName;
            }
        };
    }
}
