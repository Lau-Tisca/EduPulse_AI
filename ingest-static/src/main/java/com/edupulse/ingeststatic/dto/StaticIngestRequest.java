package com.edupulse.ingeststatic.dto;

/**
 * Optional request payload for POST /ingest/static.
 * When filePath is omitted or blank, all PDF files in app.static-resources-dir are ingested.
 */
public record StaticIngestRequest(
        String filePath,
        String docId
) {
}
