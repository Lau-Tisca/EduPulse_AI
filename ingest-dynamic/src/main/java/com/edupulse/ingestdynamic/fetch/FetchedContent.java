package com.edupulse.ingestdynamic.fetch;

/**
 * Raw payload fetched from a validated remote HTTPS URL.
 */
public record FetchedContent(
        String finalUrl,
        String contentType,
        byte[] bytes
) {
}
