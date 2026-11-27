package com.edupulse.ingestdynamic.parser;

import com.edupulse.ingestdynamic.exception.ContentParseException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Component;

/**
 * Parses Markdown and plain text documents (such as GitHub raw docs or local .md/.txt files),
 * stripping Hugo/Jekyll YAML frontmatter and HTML comments.
 */
@Component
public class MarkdownContentParser {

    // Bounded non-backtracking frontmatter pattern matching a leading --- ... --- block.
    private static final Pattern YAML_FRONTMATTER = Pattern.compile("\\A---[ \\t]*\\r?\\n[\\s\\S]*?\\r?\\n---[ \\t]*(?:\\r?\\n|\\z)");
    private static final Pattern HTML_COMMENTS = Pattern.compile("<!--[^-]*+(?:-(?!->)[^-]*+)*+-->");

    public List<Document> parse(byte[] bytes, String sourceName) {
        if (bytes == null || bytes.length == 0) {
            throw new ContentParseException("Text/Markdown payload is empty: " + sourceName);
        }
        String text = new String(bytes, StandardCharsets.UTF_8);
        text = YAML_FRONTMATTER.matcher(text).replaceFirst("");
        text = HTML_COMMENTS.matcher(text).replaceAll("");
        String normalized = TextNormalizer.normalize(text);

        if (normalized.isBlank()) {
            throw new ContentParseException("No extractable text found in Markdown/text content: " + sourceName);
        }

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("page", 1);
        return List.of(new Document(normalized, metadata));
    }
}
