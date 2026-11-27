package com.edupulse.ingestdynamic.controller;

import com.edupulse.ingestdynamic.dto.DocumentVersionInfo;
import com.edupulse.ingestdynamic.dto.DynamicIngestResult;
import com.edupulse.ingestdynamic.dto.DynamicUrlIngestRequest;
import com.edupulse.ingestdynamic.exception.InvalidUrlException;
import com.edupulse.ingestdynamic.service.DynamicIngestService;
import java.util.List;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST endpoints for dynamic URL ingestion (Story 2 — Task 1) and version inspection (Task 2).
 * Reachable via API Gateway at {@code /ingest-dynamic/ingest/dynamic/**}.
 */
@RestController
@RequestMapping("/ingest/dynamic")
public class DynamicIngestController {

    private final DynamicIngestService dynamicIngestService;

    public DynamicIngestController(DynamicIngestService dynamicIngestService) {
        this.dynamicIngestService = dynamicIngestService;
    }

    @PostMapping(path = "/url", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<DynamicIngestResult> ingestUrl(@RequestBody(required = false) DynamicUrlIngestRequest request) {
        if (request == null || request.url() == null || request.url().isBlank()) {
            throw new InvalidUrlException("Request body must include a non-blank 'url' field");
        }
        return ResponseEntity.ok(dynamicIngestService.ingestUrl(request.url(), request.docId()));
    }

    @GetMapping(path = "/versions/{docId}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<List<DocumentVersionInfo>> getVersions(@PathVariable("docId") String docId) {
        return ResponseEntity.ok(dynamicIngestService.getVersionHistory(docId));
    }
}
