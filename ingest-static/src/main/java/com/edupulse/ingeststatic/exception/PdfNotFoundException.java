package com.edupulse.ingeststatic.exception;

/**
 * Raised when a requested PDF file or directory does not exist.
 */
public class PdfNotFoundException extends RuntimeException {
    public PdfNotFoundException(String message) {
        super(message);
    }
}
