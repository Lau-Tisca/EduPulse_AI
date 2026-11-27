package com.edupulse.ingestdynamic.parser;

import com.edupulse.ingestdynamic.exception.ContentParseException;
import java.util.List;
import java.util.Locale;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Component;

/**
 * Dispatches raw bytes to the appropriate parser (PDF, HTML, or Markdown/plain text)
 * based on magic bytes, HTTP Content-Type, and source extension.
 */
@Component
public class ContentParser {

    private final PdfDocumentParser pdfDocumentParser;
    private final HtmlContentParser htmlContentParser;
    private final MarkdownContentParser markdownContentParser;

    public ContentParser(
            PdfDocumentParser pdfDocumentParser,
            HtmlContentParser htmlContentParser,
            MarkdownContentParser markdownContentParser
    ) {
        this.pdfDocumentParser = pdfDocumentParser;
        this.htmlContentParser = htmlContentParser;
        this.markdownContentParser = markdownContentParser;
    }

    public List<Document> parse(byte[] bytes, String contentType, String source) {
        if (bytes == null || bytes.length == 0) {
            throw new ContentParseException("Fetched content is empty: " + source);
        }

        String normalizedType = normalizeContentType(contentType);
        String lowerSource = source == null ? "" : stripQueryAndFragment(source).toLowerCase(Locale.ROOT);

        if (pdfDocumentParser.hasPdfMagic(bytes)
                || normalizedType.equals("application/pdf")
                || lowerSource.endsWith(".pdf")) {
            return pdfDocumentParser.parse(bytes, source);
        }

        if (normalizedType.equals("text/html")
                || normalizedType.equals("application/xhtml+xml")
                || lowerSource.endsWith(".html")
                || lowerSource.endsWith(".htm")) {
            return htmlContentParser.parse(bytes, source);
        }

        if (normalizedType.equals("text/markdown")
                || normalizedType.equals("text/x-markdown")
                || normalizedType.equals("text/plain")
                || lowerSource.endsWith(".md")
                || lowerSource.endsWith(".markdown")
                || lowerSource.endsWith(".txt")) {
            return markdownContentParser.parse(bytes, source);
        }

        // Fallback: sniff HTML tags, otherwise parse as text/markdown.
        String head = new String(bytes, 0, Math.min(bytes.length, 512), java.nio.charset.StandardCharsets.UTF_8)
                .trim()
                .toLowerCase(Locale.ROOT);
        if (head.startsWith("<!doctype html") || head.startsWith("<html")) {
            return htmlContentParser.parse(bytes, source);
        }
        return markdownContentParser.parse(bytes, source);
    }

    private static String normalizeContentType(String contentType) {
        if (contentType == null || contentType.isBlank()) {
            return "";
        }
        int semicolon = contentType.indexOf(';');
        String mediaType = semicolon >= 0 ? contentType.substring(0, semicolon) : contentType;
        return mediaType.trim().toLowerCase(Locale.ROOT);
    }

    private static String stripQueryAndFragment(String source) {
        int q = source.indexOf('?');
        String withoutQuery = q >= 0 ? source.substring(0, q) : source;
        int hash = withoutQuery.indexOf('#');
        return hash >= 0 ? withoutQuery.substring(0, hash) : withoutQuery;
    }
}
