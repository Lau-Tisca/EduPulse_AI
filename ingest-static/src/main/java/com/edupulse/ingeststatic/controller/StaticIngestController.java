package com.edupulse.ingeststatic.controller;

import com.edupulse.ingeststatic.dto.StaticIngestItemResult;
import com.edupulse.ingeststatic.dto.StaticIngestRequest;
import com.edupulse.ingeststatic.dto.StaticIngestResponse;
import com.edupulse.ingeststatic.exception.InvalidPdfException;
import com.edupulse.ingeststatic.service.StaticIngestService;
import java.io.IOException;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * REST endpoints for static PDF ingestion (Story 1 — Task 1).
 */
@RestController
@RequestMapping("/ingest/static")
public class StaticIngestController {

    private final StaticIngestService staticIngestService;

    public StaticIngestController(StaticIngestService staticIngestService) {
        this.staticIngestService = staticIngestService;
    }

    /**
     * Ingests a specific PDF by path (relative to app.static-resources-dir or absolute)
     * or all PDFs inside app.static-resources-dir when request body/filePath is omitted.
     */
    @PostMapping
    public ResponseEntity<StaticIngestResponse> ingestStatic(
            @RequestBody(required = false) StaticIngestRequest request
    ) {
        String filePath = request != null ? request.filePath() : null;
        String docId = request != null ? request.docId() : null;
        return ResponseEntity.ok(staticIngestService.ingest(filePath, docId));
    }

    /**
     * Ingests a directly uploaded PDF file via multipart/form-data.
     */
    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<StaticIngestItemResult> uploadPdf(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "docId", required = false) String docId
    ) {
        if (file == null || file.isEmpty()) {
            throw new InvalidPdfException("Uploaded PDF file must not be empty");
        }
        try {
            return ResponseEntity.ok(
                    staticIngestService.ingestBytes(file.getBytes(), file.getOriginalFilename(), docId)
            );
        } catch (IOException ex) {
            throw new InvalidPdfException("Failed to read uploaded PDF bytes", ex);
        }
    }
}
