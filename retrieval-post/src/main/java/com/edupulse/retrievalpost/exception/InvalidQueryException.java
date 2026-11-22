package com.edupulse.retrievalpost.exception;

/**
 * The client query is missing, blank or too long; mapped to {@code 400 Bad Request}.
 */
public class InvalidQueryException extends RuntimeException {

    public InvalidQueryException(String message) {
        super(message);
    }
}
