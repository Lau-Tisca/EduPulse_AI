package com.edupulse.ingestdynamic.dto;

/**
 * Summary of a stored document version in {@code vector_store}.
 */
public record DocumentVersionInfo(
        int version,
        String checksum,
        boolean isLatest,
        int chunkCount
) {
}
