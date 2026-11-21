package com.edupulse.retrievalpre.filter;

import java.util.Arrays;
import java.util.List;

/**
 * Metadata fields that clients are allowed to filter on.
 * Keys match the metadata stored in pgvector by ingest-static.
 */
public enum FilterField {
    DOC_ID("doc_id"),
    VERSION("version"),
    SOURCE("source"),
    PAGE("page"),
    IS_LATEST("is_latest");

    private final String key;

    FilterField(String key) {
        this.key = key;
    }

    public String key() {
        return key;
    }

    /** Finds the field with this key (e.g. "doc_id"), or returns null if there is none. */
    public static FilterField fromKey(String key) {
        for (FilterField field : values()) {
            if (field.key.equals(key)) {
                return field;
            }
        }
        return null;
    }

    /** The list of all allowed keys, used in error messages. */
    public static List<String> validKeys() {
        return Arrays.stream(values()).map(FilterField::key).toList();
    }
}
