package com.edupulse.ingestdynamic.exception;

import com.google.genai.errors.ClientException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Translates dynamic ingestion and security exceptions into consistent JSON error responses.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(SsrfBlockedException.class)
    public ResponseEntity<Map<String, Object>> handleSsrfBlocked(SsrfBlockedException ex) {
        log.warn("SSRF guard blocked request: {}", sanitizeForLog(ex.getMessage()));
        return buildError(HttpStatus.FORBIDDEN, ex.getMessage());
    }

    @ExceptionHandler(InvalidWebhookSignatureException.class)
    public ResponseEntity<Map<String, Object>> handleInvalidWebhookSignature(InvalidWebhookSignatureException ex) {
        log.warn("Webhook signature verification failed: {}", sanitizeForLog(ex.getMessage()));
        return buildError(HttpStatus.UNAUTHORIZED, ex.getMessage());
    }

    @ExceptionHandler({InvalidUrlException.class, IllegalArgumentException.class, HttpMessageNotReadableException.class})
    public ResponseEntity<Map<String, Object>> handleBadRequest(RuntimeException ex) {
        String message = ex instanceof HttpMessageNotReadableException
                ? "Malformed JSON request payload"
                : ex.getMessage();
        log.warn("Dynamic ingestion validation error: {}", sanitizeForLog(message));
        return buildError(HttpStatus.BAD_REQUEST, message);
    }

    @ExceptionHandler(ContentParseException.class)
    public ResponseEntity<Map<String, Object>> handleContentParse(ContentParseException ex) {
        log.warn("Dynamic ingestion content parse error: {}", sanitizeForLog(ex.getMessage()));
        return buildError(HttpStatus.valueOf(422), ex.getMessage());
    }

    @ExceptionHandler(RemoteFetchException.class)
    public ResponseEntity<Map<String, Object>> handleRemoteFetch(RemoteFetchException ex) {
        log.warn("Remote fetch failed (HTTP {}): {}", ex.status().value(), sanitizeForLog(ex.getMessage()));
        return buildError(ex.status(), ex.getMessage());
    }

    @ExceptionHandler(ClientException.class)
    public ResponseEntity<Map<String, Object>> handleGenAiClientError(ClientException ex) {
        HttpStatus status = ex.code() == 429 ? HttpStatus.TOO_MANY_REQUESTS : HttpStatus.BAD_GATEWAY;
        log.error("Google GenAI API error (HTTP {}): {}", ex.code(), sanitizeForLog(ex.getMessage()));
        return buildError(status, "Embedding API error: " + ex.getMessage());
    }

    private ResponseEntity<Map<String, Object>> buildError(HttpStatus status, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("timestamp", Instant.now().toString());
        body.put("status", status.value());
        body.put("error", status.getReasonPhrase());
        body.put("message", message);
        return ResponseEntity.status(status).body(body);
    }

    private static String sanitizeForLog(String value) {
        return value == null ? "" : value.replace('\r', ' ').replace('\n', ' ');
    }
}
