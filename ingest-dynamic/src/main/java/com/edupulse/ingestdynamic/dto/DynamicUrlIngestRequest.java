package com.edupulse.ingestdynamic.dto;

/**
 * Request body for {@code POST /ingest/dynamic/url}.
 *
 * @param url   HTTPS URL to fetch (HTML page, GitHub doc, or remote PDF)
 * @param docId optional logical document identifier; derived deterministically from URL when omitted
 */
public record DynamicUrlIngestRequest(
        String url,
        String docId
) {
}
