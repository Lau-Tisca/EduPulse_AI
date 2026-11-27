package com.edupulse.ingestdynamic.dto;

/**
 * Result of ingesting a single dynamic resource (URL or watched local file).
 */
public record DynamicIngestResult(
        String docId,
        String source,
        String checksum,
        int version,
        Integer previousVersion,
        int pagesParsed,
        int chunksCreated,
        String status,
        String message
) {
    public static final String STATUS_INGESTED = "INGESTED";
    public static final String STATUS_UPDATED = "UPDATED";
    public static final String STATUS_SKIPPED = "SKIPPED";
}
