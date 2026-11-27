package com.edupulse.ingestdynamic.dto;

import java.util.List;

/**
 * Response returned immediately ({@code 202 Accepted}) by {@code POST /ingest/dynamic/webhook}.
 */
public record WebhookAckResponse(
        boolean accepted,
        int queuedCount,
        List<Long> jobIds,
        String message
) {
}
