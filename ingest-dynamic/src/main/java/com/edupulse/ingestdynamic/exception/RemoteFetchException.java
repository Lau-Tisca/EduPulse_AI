package com.edupulse.ingestdynamic.exception;

import org.springframework.http.HttpStatus;

/**
 * Thrown when fetching remote content fails (HTTP error, timeout, redirect limit, or payload ceiling exceeded).
 */
public class RemoteFetchException extends RuntimeException {

    private final HttpStatus status;

    public RemoteFetchException(HttpStatus status, String message) {
        super(message);
        this.status = status;
    }

    public RemoteFetchException(HttpStatus status, String message, Throwable cause) {
        super(message, cause);
        this.status = status;
    }

    public HttpStatus status() {
        return status;
    }
}
