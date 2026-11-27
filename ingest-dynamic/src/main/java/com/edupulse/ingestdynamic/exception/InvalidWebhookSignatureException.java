package com.edupulse.ingestdynamic.exception;

/**
 * Thrown when a webhook request is missing or has an invalid {@code X-Hub-Signature-256} HMAC header.
 */
public class InvalidWebhookSignatureException extends RuntimeException {

    public InvalidWebhookSignatureException(String message) {
        super(message);
    }
}
