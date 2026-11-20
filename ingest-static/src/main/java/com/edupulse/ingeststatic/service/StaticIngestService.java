package com.edupulse.ingeststatic.service;

import com.edupulse.ingeststatic.config.IngestStaticProperties;
import com.edupulse.ingeststatic.dto.StaticIngestItemResult;
import com.edupulse.ingeststatic.dto.StaticIngestResponse;
import com.edupulse.ingeststatic.exception.InvalidPdfException;
import com.edupulse.ingeststatic.exception.PdfNotFoundException;
import com.edupulse.ingeststatic.repository.VectorStoreChecksumRepository;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Service;

/**
 * Orchestrates static PDF ingestion (Story 1 — Task 1):
 * reading PDFs, SHA-256 idempotency check, parsing, chunking, and pgvector storage.
 */
@Service
public class StaticIngestService {

    private static final Logger log = LoggerFactory.getLogger(StaticIngestService.class);
    private static final int INITIAL_VERSION = 1;

    private final IngestStaticProperties properties;
    private final ChecksumService checksumService;
    private final PdfDocumentParser pdfDocumentParser;
    private final StructureAwareChunker chunker;
    private final VectorStoreChecksumRepository checksumRepository;
    private final VectorStoreBatchWriter batchWriter;

    public StaticIngestService(
            IngestStaticProperties properties,
            ChecksumService checksumService,
            PdfDocumentParser pdfDocumentParser,
            StructureAwareChunker chunker,
            VectorStoreChecksumRepository checksumRepository,
            VectorStoreBatchWriter batchWriter
    ) {
        this.properties = properties;
        this.checksumService = checksumService;
        this.pdfDocumentParser = pdfDocumentParser;
        this.chunker = chunker;
        this.checksumRepository = checksumRepository;
        this.batchWriter = batchWriter;
    }

    public StaticIngestResponse ingest(String requestedFilePath, String customDocId) {
        if (requestedFilePath != null && !requestedFilePath.isBlank()) {
            Path resolved = resolveFilePath(requestedFilePath);
            StaticIngestItemResult item = ingestFile(resolved, customDocId);
            return buildAggregateResponse(List.of(item));
        }
        return ingestDirectory();
    }

    public StaticIngestResponse ingestDirectory() {
        Path resourcesDir = Path.of(properties.staticResourcesDir()).toAbsolutePath().normalize();
        if (!Files.exists(resourcesDir) || !Files.isDirectory(resourcesDir)) {
            throw new PdfNotFoundException("Static resources directory does not exist: " + resourcesDir);
        }

        List<Path> pdfFiles;
        try (Stream<Path> stream = Files.list(resourcesDir)) {
            pdfFiles = stream
                    .filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".pdf"))
                    .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                    .toList();
        } catch (IOException ex) {
            throw new InvalidPdfException("Failed to list static resources directory: " + resourcesDir, ex);
        }

        if (pdfFiles.isEmpty()) {
            log.info("No PDF files found in static resources directory: {}", resourcesDir);
            return new StaticIngestResponse(0, 0, 0, 0, List.of());
        }

        List<StaticIngestItemResult> results = new ArrayList<>();
        for (Path pdfFile : pdfFiles) {
            results.add(ingestFile(pdfFile, null));
        }
        return buildAggregateResponse(results);
    }

    public StaticIngestItemResult ingestFile(Path filePath, String customDocId) {
        if (!Files.exists(filePath) || !Files.isRegularFile(filePath)) {
            throw new PdfNotFoundException("PDF file not found: " + filePath);
        }
        String filename = filePath.getFileName().toString();
        if (!filename.toLowerCase(Locale.ROOT).endsWith(".pdf")) {
            throw new InvalidPdfException("Only .pdf files are supported for static ingestion: " + filename);
        }
        try {
            byte[] bytes = Files.readAllBytes(filePath);
            return ingestBytes(bytes, filename, customDocId);
        } catch (IOException ex) {
            throw new InvalidPdfException("Failed to read PDF file: " + filePath, ex);
        }
    }

    public StaticIngestItemResult ingestBytes(byte[] pdfBytes, String filename, String customDocId) {
        String source = (filename == null || filename.isBlank()) ? "uploaded.pdf" : filename.trim();
        String docId = deriveDocId(customDocId, source);
        String checksum = checksumService.sha256(pdfBytes);

        if (checksumRepository.existsByDocIdAndChecksum(docId, checksum)) {
            log.info("Skipping static PDF ingestion (unchanged): docId={}, source={}, checksum={}",
                    docId, source, checksum);
            return new StaticIngestItemResult(
                    docId,
                    source,
                    checksum,
                    INITIAL_VERSION,
                    0,
                    0,
                    StaticIngestItemResult.STATUS_SKIPPED,
                    "Document already indexed with identical checksum; skipped re-embedding."
            );
        }

        log.info("Starting static PDF ingestion: docId={}, source={}, sizeBytes={}",
                docId, source, pdfBytes.length);

        List<Document> pages = pdfDocumentParser.parse(pdfBytes, source);
        List<Document> chunks = chunker.chunk(pages, docId, source, checksum, INITIAL_VERSION);

        batchWriter.addAll(chunks, docId);

        log.info("Completed static PDF ingestion: docId={}, source={}, pages={}, chunks={}, checksum={}",
                docId, source, pages.size(), chunks.size(), checksum);

        return new StaticIngestItemResult(
                docId,
                source,
                checksum,
                INITIAL_VERSION,
                pages.size(),
                chunks.size(),
                StaticIngestItemResult.STATUS_INGESTED,
                "Document ingested and indexed successfully."
        );
    }

    private Path resolveFilePath(String requestedFilePath) {
        Path rawPath = Path.of(requestedFilePath);
        if (rawPath.isAbsolute()) {
            return rawPath.normalize();
        }
        Path baseDir = Path.of(properties.staticResourcesDir()).toAbsolutePath().normalize();
        Path resolved = baseDir.resolve(rawPath).normalize();
        if (!resolved.startsWith(baseDir)) {
            throw new IllegalArgumentException("Relative filePath must stay inside static resources directory");
        }
        return resolved;
    }

    private String deriveDocId(String customDocId, String source) {
        if (customDocId != null && !customDocId.isBlank()) {
            return customDocId.trim();
        }
        String baseName = Path.of(source).getFileName().toString();
        if (baseName.toLowerCase(Locale.ROOT).endsWith(".pdf")) {
            baseName = baseName.substring(0, baseName.length() - 4);
        }
        return baseName.replaceAll("[^a-zA-Z0-9._-]", "_");
    }

    private StaticIngestResponse buildAggregateResponse(List<StaticIngestItemResult> results) {
        int ingested = (int) results.stream()
                .filter(r -> StaticIngestItemResult.STATUS_INGESTED.equals(r.status()))
                .count();
        int skipped = (int) results.stream()
                .filter(r -> StaticIngestItemResult.STATUS_SKIPPED.equals(r.status()))
                .count();
        int totalChunks = results.stream()
                .mapToInt(StaticIngestItemResult::chunksCreated)
                .sum();
        return new StaticIngestResponse(results.size(), ingested, skipped, totalChunks, results);
    }
}
