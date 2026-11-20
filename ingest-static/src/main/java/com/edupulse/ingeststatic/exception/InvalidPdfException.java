package com.edupulse.ingeststatic.exception;

/**
 * Raised when an input file is not a valid PDF, is empty, or fails PDF extraction.
 */
public class InvalidPdfException extends RuntimeException {
    public InvalidPdfException(String message) {
        super(message);
    }

    public InvalidPdfException(String message, Throwable cause) {
        super(message, cause);
    }
}
