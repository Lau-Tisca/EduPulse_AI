package com.edupulse.ingestdynamic.exception;

/**
 * Thrown when fetched HTML, Markdown, or PDF content cannot be parsed or yields no extractable text.
 */
public class ContentParseException extends RuntimeException {

    public ContentParseException(String message) {
        super(message);
    }

    public ContentParseException(String message, Throwable cause) {
        super(message, cause);
    }
}
