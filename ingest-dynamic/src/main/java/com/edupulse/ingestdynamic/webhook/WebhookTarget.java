package com.edupulse.ingestdynamic.webhook;

/**
 * Target URL (and optional explicit {@code docId}) extracted from a verified webhook payload.
 */
public record WebhookTarget(
        String url,
        String docId
) {
}
