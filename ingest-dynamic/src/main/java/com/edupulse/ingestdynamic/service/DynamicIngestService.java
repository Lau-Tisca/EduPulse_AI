package com.edupulse.ingestdynamic.service;

import com.edupulse.ingestdynamic.chunking.ChecksumService;
import com.edupulse.ingestdynamic.chunking.StructureAwareChunker;
import com.edupulse.ingestdynamic.chunking.VectorStoreBatchWriter;
import com.edupulse.ingestdynamic.dto.DocumentVersionInfo;
import com.edupulse.ingestdynamic.dto.DynamicIngestResult;
import com.edupulse.ingestdynamic.exception.ContentParseException;
import com.edupulse.ingestdynamic.fetch.FetchedContent;
import com.edupulse.ingestdynamic.fetch.RemoteContentFetcher;
import com.edupulse.ingestdynamic.parser.ContentParser;
import com.edupulse.ingestdynamic.versioning.DocumentVersionRepository;
import com.edupulse.ingestdynamic.versioning.DocumentVersionRepository.VersionSnapshot;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Service;

/**
 * Orchestrates dynamic ingestion from remote HTTPS URLs (Task 1) and watched local files (Task 3)
 * with SHA-256 idempotency and atomic {@code is_latest} versioning (Task 2).
 */
@Service
public class DynamicIngestService {

    private static final Logger log = LoggerFactory.getLogger(DynamicIngestService.class);
    private static final int MAX_DERIVED_DOC_ID_LEN = 180;

    private final RemoteContentFetcher remoteContentFetcher;
    private final ContentParser contentParser;
    private final ChecksumService checksumService;
    private final StructureAwareChunker chunker;
    private final VectorStoreBatchWriter batchWriter;
    private final DocumentVersionRepository versionRepository;
    private final ConcurrentHashMap<String, Object> docLocks = new ConcurrentHashMap<>();

    public DynamicIngestService(
            RemoteContentFetcher remoteContentFetcher,
            ContentParser contentParser,
            ChecksumService checksumService,
            StructureAwareChunker chunker,
            VectorStoreBatchWriter batchWriter,
            DocumentVersionRepository versionRepository
    ) {
        this.remoteContentFetcher = remoteContentFetcher;
        this.contentParser = contentParser;
        this.checksumService = checksumService;
        this.chunker = chunker;
        this.batchWriter = batchWriter;
        this.versionRepository = versionRepository;
    }

    /**
     * Fetches a remote HTTPS URL (HTML, GitHub Markdown, or PDF) through SSRF guardrails,
     * parses and chunks it, and versions it into {@code vector_store}.
     */
    public DynamicIngestResult ingestUrl(String rawUrl, String customDocId) {
        FetchedContent fetched = remoteContentFetcher.fetch(rawUrl);
        String source = fetched.finalUrl();
        String docId = deriveUrlDocId(customDocId, source);
        return ingestPayload(docId, source, fetched.contentType(), fetched.bytes());
    }

    /**
     * Ingests a local file (e.g. detected by {@code ResourceDirectoryWatcher}) using the same
     * {@code doc_id} derivation convention as {@code ingest-static} so unchanged static PDFs are skipped.
     */
    public DynamicIngestResult ingestLocalFile(Path filePath) {
        String filename = filePath.getFileName().toString();
        String docId = deriveFileDocId(filename);
        byte[] bytes;
        try {
            bytes = Files.readAllBytes(filePath);
        } catch (IOException ex) {
            throw new ContentParseException("Failed to read watched file: " + filename, ex);
        }
        return ingestPayload(docId, filename, "", bytes);
    }

    /**
     * Returns version history for {@code docId}.
     */
    public List<DocumentVersionInfo> getVersionHistory(String docId) {
        if (docId == null || docId.isBlank()) {
            throw new IllegalArgumentException("docId must not be blank");
        }
        return versionRepository.findVersionHistory(docId.trim());
    }

    public DynamicIngestResult ingestPayload(String docId, String source, String contentType, byte[] bytes) {
        String checksum = checksumService.sha256(bytes);
        Object lock = docLocks.computeIfAbsent(docId, ignored -> new Object());

        synchronized (lock) {
            Optional<VersionSnapshot> existingOpt = versionRepository.findVersionSnapshot(docId);
            if (existingOpt.isPresent()) {
                VersionSnapshot existing = existingOpt.get();
                if (checksum.equals(existing.latestChecksum())) {
                    log.info("Skipping dynamic ingestion (unchanged): docId={}, source={}, version={}, checksum={}",
                            docId, source, existing.latestVersion(), checksum);
                    return new DynamicIngestResult(
                            docId,
                            source,
                            checksum,
                            existing.latestVersion(),
                            existing.latestVersion(),
                            0,
                            0,
                            DynamicIngestResult.STATUS_SKIPPED,
                            "Document already indexed at latest version with identical checksum; skipped re-embedding."
                    );
                }
            }

            Integer previousVersion = existingOpt.map(VersionSnapshot::latestVersion).orElse(null);
            int targetVersion = existingOpt.map(s -> s.maxVersion() + 1).orElse(1);

            if (!versionRepository.tryClaim(docId, targetVersion, checksum)) {
                log.info("Skipping dynamic ingestion (claim held): docId={}, targetVersion={}, checksum={}",
                        docId, targetVersion, checksum);
                return new DynamicIngestResult(
                        docId,
                        source,
                        checksum,
                        previousVersion != null ? previousVersion : targetVersion,
                        previousVersion,
                        0,
                        0,
                        DynamicIngestResult.STATUS_SKIPPED,
                        "Ingestion for this document version is already in progress or completed."
                );
            }

            log.info("Starting dynamic ingestion: docId={}, source={}, targetVersion={}, previousVersion={}, sizeBytes={}",
                    docId, source, targetVersion, previousVersion, bytes.length);

            try {
                List<Document> parsedPages = contentParser.parse(bytes, contentType, source);
                // Write chunks with is_latest=false first so concurrent readers never see half-indexed docs.
                List<Document> chunks = chunker.chunk(parsedPages, docId, source, checksum, targetVersion, false);
                if (chunks.isEmpty()) {
                    throw new ContentParseException("Document produced zero chunks after splitting: " + source);
                }
                batchWriter.addAll(chunks, docId);
                versionRepository.activateVersion(docId, targetVersion, checksum);

                String status = previousVersion == null
                        ? DynamicIngestResult.STATUS_INGESTED
                        : DynamicIngestResult.STATUS_UPDATED;
                String message = previousVersion == null
                        ? "Document ingested as version 1."
                        : "Document updated from version " + previousVersion + " to version " + targetVersion
                                + " (prior chunks marked is_latest=false).";

                log.info("Completed dynamic ingestion: docId={}, source={}, status={}, version={}, previousVersion={}, pages={}, chunks={}",
                        docId, source, status, targetVersion, previousVersion, parsedPages.size(), chunks.size());

                return new DynamicIngestResult(
                        docId,
                        source,
                        checksum,
                        targetVersion,
                        previousVersion,
                        parsedPages.size(),
                        chunks.size(),
                        status,
                        message
                );
            } catch (RuntimeException ex) {
                versionRepository.rollbackFailedVersion(docId, targetVersion, checksum);
                log.error("Dynamic ingestion failed; rolled back partial version {}: docId={}, source={}, checksum={}",
                        targetVersion, docId, source, checksum, ex);
                throw ex;
            }
        }
    }

    String deriveUrlDocId(String customDocId, String finalUrl) {
        if (customDocId != null && !customDocId.isBlank()) {
            return customDocId.trim();
        }
        URI uri = URI.create(finalUrl);
        String host = uri.getHost() == null ? "remote" : uri.getHost().toLowerCase(Locale.ROOT);
        String path = uri.getPath() == null ? "" : uri.getPath();
        while (path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }
        String combined = path.isEmpty() ? host : host + path;
        String sanitized = combined.replaceAll("[^a-zA-Z0-9._-]", "_")
                .replaceAll("_+", "_");
        if (sanitized.endsWith("_")) {
            sanitized = sanitized.substring(0, sanitized.length() - 1);
        }
        if (sanitized.length() <= MAX_DERIVED_DOC_ID_LEN) {
            return sanitized;
        }
        String suffix = "_" + checksumService.sha256(finalUrl.getBytes(StandardCharsets.UTF_8)).substring(0, 12);
        return sanitized.substring(0, MAX_DERIVED_DOC_ID_LEN - suffix.length()) + suffix;
    }

    /**
     * Matches {@code ingest-static}'s {@code deriveDocId} convention for {@code .pdf} files and
     * extends it cleanly to {@code .md}, {@code .markdown}, {@code .txt}, {@code .html}, {@code .htm}.
     */
    public static String deriveFileDocId(String filename) {
        String baseName = Path.of(filename).getFileName().toString();
        String lower = baseName.toLowerCase(Locale.ROOT);
        for (String ext : List.of(".pdf", ".markdown", ".md", ".html", ".htm", ".txt")) {
            if (lower.endsWith(ext) && baseName.length() > ext.length()) {
                baseName = baseName.substring(0, baseName.length() - ext.length());
                break;
            }
        }
        return baseName.replaceAll("[^a-zA-Z0-9._-]", "_");
    }
}
