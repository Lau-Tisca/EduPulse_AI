package com.edupulse.ingestdynamic.controller;

import com.edupulse.ingestdynamic.dto.WebhookAckResponse;
import com.edupulse.ingestdynamic.exception.RemoteFetchException;
import com.edupulse.ingestdynamic.security.HmacSignatureVerifier;
import com.edupulse.ingestdynamic.webhook.WebhookPayloadParser;
import com.edupulse.ingestdynamic.webhook.WebhookProcessingService;
import com.edupulse.ingestdynamic.webhook.WebhookTarget;
import jakarta.servlet.http.HttpServletRequest;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * HMAC-SHA256 authenticated webhook endpoint (Story 2 — Task 3).
 * Reachable via API Gateway at {@code POST /ingest-dynamic/ingest/dynamic/webhook}.
 */
@RestController
@RequestMapping("/ingest/dynamic")
public class WebhookController {

    private static final Logger log = LoggerFactory.getLogger(WebhookController.class);
    static final int MAX_WEBHOOK_PAYLOAD_BYTES = 1_048_576; // 1 MB
    private static final int READ_BUFFER_SIZE = 8192;

    private final HmacSignatureVerifier signatureVerifier;
    private final WebhookPayloadParser payloadParser;
    private final WebhookProcessingService processingService;

    public WebhookController(
            HmacSignatureVerifier signatureVerifier,
            WebhookPayloadParser payloadParser,
            WebhookProcessingService processingService
    ) {
        this.signatureVerifier = signatureVerifier;
        this.payloadParser = payloadParser;
        this.processingService = processingService;
    }

    @PostMapping(path = "/webhook", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<WebhookAckResponse> handleWebhook(
            @RequestHeader(value = HmacSignatureVerifier.SIGNATURE_HEADER, required = false) String signature,
            HttpServletRequest request
    ) throws IOException {
        byte[] rawPayload = readBoundedBody(request);

        // 1. Verify HMAC-SHA256 before parsing any payload (401 Unauthorized on failure).
        signatureVerifier.verify(rawPayload, signature);

        // 2. Extract target URLs (custom JSON or GitHub push commits).
        List<WebhookTarget> targets = payloadParser.parse(rawPayload);
        if (targets.isEmpty()) {
            log.info("Verified webhook received with no supported documentation URLs to ingest");
            return ResponseEntity.status(HttpStatus.ACCEPTED).body(
                    new WebhookAckResponse(true, 0, List.of(), "Webhook verified; no supported documentation targets found.")
            );
        }

        // 3. Persist jobs to webhook_retry_queue and dispatch asynchronously.
        List<Long> jobIds = processingService.enqueueAndDispatch(targets);
        log.info("Verified webhook accepted: queuedCount={}, jobIds={}", jobIds.size(), jobIds);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(
                new WebhookAckResponse(
                        true,
                        jobIds.size(),
                        jobIds,
                        "Webhook verified; queued " + jobIds.size() + " target(s) for asynchronous ingestion."
                )
        );
    }

    private static byte[] readBoundedBody(HttpServletRequest request) throws IOException {
        long declaredLength = request.getContentLengthLong();
        if (declaredLength > MAX_WEBHOOK_PAYLOAD_BYTES) {
            throw new RemoteFetchException(HttpStatus.CONTENT_TOO_LARGE,
                    "Webhook payload exceeds maximum allowed size of " + MAX_WEBHOOK_PAYLOAD_BYTES + " bytes");
        }
        try (InputStream in = request.getInputStream(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[READ_BUFFER_SIZE];
            int total = 0;
            int read;
            while ((read = in.read(buffer)) != -1) {
                total += read;
                if (total > MAX_WEBHOOK_PAYLOAD_BYTES) {
                    throw new RemoteFetchException(HttpStatus.CONTENT_TOO_LARGE,
                            "Webhook payload exceeds maximum allowed size of " + MAX_WEBHOOK_PAYLOAD_BYTES + " bytes");
                }
                out.write(buffer, 0, read);
            }
            return out.toByteArray();
        }
    }
}
