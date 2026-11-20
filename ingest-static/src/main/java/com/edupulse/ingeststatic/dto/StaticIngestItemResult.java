package com.edupulse.ingeststatic.dto;

/**
 * Per-file outcome returned from static PDF ingestion.
 */
public record StaticIngestItemResult(
        String docId,
        String source,
        String checksum,
        int version,
        int pagesParsed,
        int chunksCreated,
        String status,
        String message
) {
    public static final String STATUS_INGESTED = "INGESTED";
    public static final String STATUS_SKIPPED = "SKIPPED";
}
