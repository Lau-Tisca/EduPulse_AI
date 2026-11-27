package com.edupulse.ingestdynamic.exception;

/**
 * Thrown when a client submits a missing or syntactically invalid URL.
 */
public class InvalidUrlException extends RuntimeException {

    public InvalidUrlException(String message) {
        super(message);
    }

    public InvalidUrlException(String message, Throwable cause) {
        super(message, cause);
    }
}
