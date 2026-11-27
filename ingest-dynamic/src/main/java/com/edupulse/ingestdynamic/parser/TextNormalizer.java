package com.edupulse.ingestdynamic.parser;

/**
 * Shared text normalization stripping PostgreSQL-incompatible control bytes (including NUL)
 * and collapsing excessive whitespace while keeping paragraph boundaries.
 */
final class TextNormalizer {

    private TextNormalizer() {
    }

    static String normalize(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        String withoutControlChars = raw.replaceAll("[\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F]", "");
        String normalizedLineEndings = withoutControlChars.replace("\r\n", "\n").replace("\r", "\n");
        String collapsedSpaces = normalizedLineEndings.replaceAll("[ \\t\\f]+", " ");
        String trimmedLines = collapsedSpaces.replaceAll(" *\\n *", "\n");
        return trimmedLines.replaceAll("\\n{3,}", "\n\n").trim();
    }
}
