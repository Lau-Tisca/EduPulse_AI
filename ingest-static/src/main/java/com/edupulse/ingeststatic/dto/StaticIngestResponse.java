package com.edupulse.ingeststatic.dto;

import java.util.List;

/**
 * Aggregate response returned by POST /ingest/static.
 */
public record StaticIngestResponse(
        int totalFiles,
        int ingestedFiles,
        int skippedFiles,
        int totalChunksCreated,
        List<StaticIngestItemResult> results
) {
}
