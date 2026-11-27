package com.edupulse.ingestdynamic.service;

import com.edupulse.ingestdynamic.chunking.ChecksumService;
import com.edupulse.ingestdynamic.chunking.StructureAwareChunker;
import com.edupulse.ingestdynamic.chunking.VectorStoreBatchWriter;
import com.edupulse.ingestdynamic.config.IngestDynamicProperties;
import com.edupulse.ingestdynamic.dto.DocumentVersionInfo;
import com.edupulse.ingestdynamic.dto.DynamicIngestResult;
import com.edupulse.ingestdynamic.exception.ContentParseException;
import com.edupulse.ingestdynamic.exception.IngestionConflictException;
import com.edupulse.ingestdynamic.exception.RemoteFetchException;
import com.edupulse.ingestdynamic.fetch.FetchedContent;
import com.edupulse.ingestdynamic.fetch.RemoteContentFetcher;
import com.edupulse.ingestdynamic.parser.ContentParser;
import com.edupulse.ingestdynamic.versioning.DocumentVersionRepository;
import com.edupulse.ingestdynamic.versioning.DocumentVersionRepository.VersionSnapshot;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * Orchestrates dynamic ingestion from remote HTTPS URLs (Task 1) and watched local files (Task 3)
 * with SHA-256 idempotency and atomic {@code is_latest} versioning (Task 2).
 */
@Service
public class DynamicIngestService {

    private static final Logger log = LoggerFactory.getLogger(DynamicIngestService.class);
    private static final int MAX_DERIVED_DOC_ID_LEN = 180;
    private static final Pattern VALID_DOC_ID_PATTERN = Pattern.compile("^[a-zA-Z0-9._-]{1,180}$");
    private static final int LOCK_STRIPES = 256;

    private final IngestDynamicProperties properties;
    private final RemoteContentFetcher remoteContentFetcher;
    private final ContentParser contentParser;
    private final ChecksumService checksumService;
    private final StructureAwareChunker chunker;
    private final VectorStoreBatchWriter batchWriter;
    private final DocumentVersionRepository versionRepository;
    private final Object[] docLockStripes;

    @Autowired
    public DynamicIngestService(
            IngestDynamicProperties properties,
            RemoteContentFetcher remoteContentFetcher,
            ContentParser contentParser,
            ChecksumService checksumService,
            StructureAwareChunker chunker,
            VectorStoreBatchWriter batchWriter,
            DocumentVersionRepository versionRepository
    ) {
        this.properties = properties;
        this.remoteContentFetcher = remoteContentFetcher;
        this.contentParser = contentParser;
        this.checksumService = checksumService;
        this.chunker = chunker;
        this.batchWriter = batchWriter;
        this.versionRepository = versionRepository;
        this.docLockStripes = new Object[LOCK_STRIPES];
        for (int i = 0; i < LOCK_STRIPES; i++) {
            this.docLockStripes[i] = new Object();
        }
    }

    public DynamicIngestService(
            RemoteContentFetcher remoteContentFetcher,
            ContentParser contentParser,
            ChecksumService checksumService,
            StructureAwareChunker chunker,
            VectorStoreBatchWriter batchWriter,
            DocumentVersionRepository versionRepository
    ) {
        this(
                new IngestDynamicProperties(
                        IngestDynamicProperties.DEFAULT_CHUNK_SIZE,
                        IngestDynamicProperties.DEFAULT_CHUNK_OVERLAP,
                        IngestDynamicProperties.Watcher.defaults(),
                        IngestDynamicProperties.Ingest.defaults(),
                        new IngestDynamicProperties.Webhook("")
                ),
                remoteContentFetcher,
                contentParser,
                checksumService,
                chunker,
                batchWriter,
                versionRepository
        );
    }

    /**
     * Fetches a remote HTTPS URL (HTML, GitHub Markdown, or PDF) through SSRF guardrails,
     * parses and chunks it, and versions it into {@code vector_store}.
     */
    public DynamicIngestResult ingestUrl(String rawUrl, String customDocId) {
        if (customDocId != null && !customDocId.isBlank()) {
            validateDocId(customDocId.trim());
        }
        FetchedContent fetched = remoteContentFetcher.fetch(rawUrl);
        String source = fetched.finalUrl();
        String docId = deriveUrlDocId(customDocId, source);
        return ingestPayload(docId, source, fetched.contentType(), fetched.bytes());
    }

    /**
     * Ingests a local file (e.g. detected by {@code ResourceDirectoryWatcher}) enforcing strict
     * containment inside {@code APP_WATCHER_RESOURCES_DIR}, symlink rejection, and {@code APP_INGEST_MAX_BYTES}.
     */
    public DynamicIngestResult ingestLocalFile(Path filePath) {
        if (filePath == null) {
            throw new IllegalArgumentException("filePath must not be null");
        }
        if (Files.isSymbolicLink(filePath)) {
            throw new IllegalArgumentException("Symbolic links are not permitted in watched resources directory");
        }
        Path baseDir = Path.of(properties.watcher().resourcesDir()).toAbsolutePath().normalize();
        Path fileReal;
        try {
            Path baseReal = baseDir.toRealPath();
            fileReal = filePath.toRealPath();
            if (!fileReal.startsWith(baseReal)) {
                throw new IllegalArgumentException("Watched file must stay inside the configured resources directory");
            }
            if (!Files.isRegularFile(fileReal)) {
                throw new ContentParseException("Watched path is not a regular file: " + filePath.getFileName());
            }
            long maxBytes = properties.ingest().maxBytes();
            long fileSize = Files.size(fileReal);
            if (fileSize > maxBytes) {
                throw new RemoteFetchException(HttpStatus.CONTENT_TOO_LARGE,
                        "Watched file exceeds maximum allowed size of " + maxBytes + " bytes: "
                                + fileReal.getFileName());
            }
        } catch (IOException ex) {
            throw new ContentParseException("Failed to validate watched file: " + filePath.getFileName(), ex);
        }

        String filename = fileReal.getFileName().toString();
        String docId = deriveFileDocId(filename);
        byte[] bytes = readLocalFileBounded(fileReal, properties.ingest().maxBytes(), filename);
        return ingestPayload(docId, filename, "", bytes);
    }

    private static byte[] readLocalFileBounded(Path fileReal, long maxBytes, String filename) {
        try (InputStream in = Files.newInputStream(fileReal);
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            long total = 0;
            int read;
            while ((read = in.read(buffer)) != -1) {
                total += read;
                if (total > maxBytes) {
                    throw new RemoteFetchException(HttpStatus.CONTENT_TOO_LARGE,
                            "Watched file exceeds maximum allowed size of " + maxBytes + " bytes: " + filename);
                }
                out.write(buffer, 0, read);
            }
            return out.toByteArray();
        } catch (RemoteFetchException ex) {
            throw ex;
        } catch (IOException ex) {
            throw new ContentParseException("Failed to read watched file: " + filename, ex);
        }
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
        validateDocId(docId);
        String checksum = checksumService.sha256(bytes);
        Object lock = lockFor(docId);

        synchronized (lock) {
            Optional<VersionSnapshot> existingOpt = versionRepository.findVersionSnapshot(docId);
            if (existingOpt.isPresent()) {
                VersionSnapshot existing = existingOpt.get();
                // Skip only when an active (is_latest=true) version matches the checksum.
                if (existing.latestVersion() != null && checksum.equals(existing.latestChecksum())) {
                    log.info("Skipping dynamic ingestion (unchanged): docId={}, source={}, version={}, checksum={}",
                            sanitize(docId), sanitize(source), existing.latestVersion(), checksum);
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

            Optional<String> claimTokenOpt = versionRepository.tryClaim(docId, targetVersion, checksum);
            if (claimTokenOpt.isEmpty()) {
                log.warn("Dynamic ingestion claim conflict: docId={}, targetVersion={}, checksum={}",
                        sanitize(docId), targetVersion, checksum);
                throw new IngestionConflictException(
                        "Ingestion for document '" + docId + "' is already in progress");
            }
            String claimToken = claimTokenOpt.get();

            log.info("Starting dynamic ingestion: docId={}, source={}, targetVersion={}, previousVersion={}, sizeBytes={}",
                    sanitize(docId), sanitize(source), targetVersion, previousVersion, bytes.length);

            try {
                List<Document> parsedPages = contentParser.parse(bytes, contentType, source);
                // Write chunks with is_latest=false first so concurrent readers never see half-indexed docs.
                List<Document> chunks = chunker.chunk(parsedPages, docId, source, checksum, targetVersion, false);
                if (chunks.isEmpty()) {
                    throw new ContentParseException("Document produced zero chunks after splitting: " + source);
                }
                batchWriter.addAll(chunks, docId);
                versionRepository.activateVersion(docId, targetVersion, checksum, claimToken);

                String status = previousVersion == null
                        ? DynamicIngestResult.STATUS_INGESTED
                        : DynamicIngestResult.STATUS_UPDATED;
                String message = previousVersion == null
                        ? "Document ingested as version 1."
                        : "Document updated from version " + previousVersion + " to version " + targetVersion
                                + " (prior chunks marked is_latest=false).";

                log.info("Completed dynamic ingestion: docId={}, source={}, status={}, version={}, previousVersion={}, pages={}, chunks={}",
                        sanitize(docId), sanitize(source), status, targetVersion, previousVersion,
                        parsedPages.size(), chunks.size());

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
            } catch (Throwable ex) {
                try {
                    versionRepository.rollbackFailedVersion(docId, targetVersion, checksum, claimToken);
                } catch (Exception rollbackEx) {
                    log.error("Failed to roll back partial dynamic ingestion version {}: docId={}, checksum={}",
                            targetVersion, sanitize(docId), checksum, rollbackEx);
                }
                log.error("Dynamic ingestion failed; rolled back partial version {}: docId={}, source={}, checksum={}",
                        targetVersion, sanitize(docId), sanitize(source), checksum, ex);
                if (ex instanceof RuntimeException runtimeEx) {
                    throw runtimeEx;
                }
                if (ex instanceof Error err) {
                    throw err;
                }
                throw new IllegalStateException("Dynamic ingestion failed unexpectedly", ex);
            }
        }
    }

    String deriveUrlDocId(String customDocId, String finalUrl) {
        if (customDocId != null && !customDocId.isBlank()) {
            String trimmed = customDocId.trim();
            validateDocId(trimmed);
            return trimmed;
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
     * Strips {@code .pdf} to match {@code ingest-static}'s {@code deriveDocId} convention for PDFs,
     * while preserving non-PDF extensions ({@code .md}, {@code .html}, {@code .txt}) so same-stem
     * files like {@code guide.pdf} and {@code guide.md} do not collide and loop in the 30s watcher.
     */
    public static String deriveFileDocId(String filename) {
        String baseName = Path.of(filename).getFileName().toString();
        String lower = baseName.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".pdf") && baseName.length() > ".pdf".length()) {
            baseName = baseName.substring(0, baseName.length() - ".pdf".length());
        }
        String sanitized = baseName.replaceAll("[^a-zA-Z0-9._-]", "_");
        if (sanitized.length() > MAX_DERIVED_DOC_ID_LEN) {
            sanitized = sanitized.substring(0, MAX_DERIVED_DOC_ID_LEN);
        }
        return sanitized;
    }

    private Object lockFor(String docId) {
        return docLockStripes[Math.floorMod(docId.hashCode(), LOCK_STRIPES)];
    }

    private static void validateDocId(String docId) {
        if (docId == null || !VALID_DOC_ID_PATTERN.matcher(docId).matches()) {
            throw new IllegalArgumentException(
                    "docId must be 1-180 characters matching [a-zA-Z0-9._-]");
        }
    }

    private static String sanitize(String value) {
        return value == null ? "" : value.replace('\r', ' ').replace('\n', ' ');
    }
}
