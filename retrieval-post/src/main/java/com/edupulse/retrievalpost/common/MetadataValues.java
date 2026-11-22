package com.edupulse.retrievalpost.common;

/**
 * Lenient conversion of chunk metadata values. Ingestion services may store numbers as JSON numbers or strings
 * ({@code 4}, {@code "4"}, {@code "4.0"}); unparsable values (e.g. page {@code "iv"}) become {@code null}
 * instead of failing the request.
 */
public final class MetadataValues {

    private MetadataValues() {
    }

    public static String asString(Object value) {
        return value == null ? null : value.toString();
    }

    public static Integer asInteger(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof String text && !text.isBlank()) {
            try {
                return (int) Double.parseDouble(text.trim());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }
}
