package com.edupulse.ingestdynamic.watcher;

import com.edupulse.ingestdynamic.config.IngestDynamicProperties;
import com.edupulse.ingestdynamic.dto.DynamicIngestResult;
import com.edupulse.ingestdynamic.service.DynamicIngestService;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Scheduled file watcher (Story 2 — Task 3) polling {@code APP_WATCHER_RESOURCES_DIR} every
 * {@code APP_WATCHER_INTERVAL_SEC} seconds (default 30s) for new or modified documents.
 * <p>
 * Synchronizes against {@code vector_store} via {@link DynamicIngestService}: unchanged PDFs
 * previously indexed by {@code ingest-static} are skipped, modified files trigger version
 * {@code N + 1} with {@code is_latest} cutover, and new {@code .pdf/.md/.txt/.html} files are
 * automatically ingested.
 */
@Component
public class ResourceDirectoryWatcher {

    private static final Logger log = LoggerFactory.getLogger(ResourceDirectoryWatcher.class);
    private static final Set<String> SUPPORTED_EXTENSIONS = Set.of(
            ".pdf", ".md", ".markdown", ".txt", ".html", ".htm"
    );

    private final IngestDynamicProperties properties;
    private final DynamicIngestService dynamicIngestService;
    private final AtomicBoolean scanning = new AtomicBoolean(false);

    public ResourceDirectoryWatcher(
            IngestDynamicProperties properties,
            DynamicIngestService dynamicIngestService
    ) {
        this.properties = properties;
        this.dynamicIngestService = dynamicIngestService;
    }

    @Scheduled(
            fixedDelayString = "${app.watcher.interval-sec:30}000",
            initialDelayString = "${app.watcher.initial-delay-ms:15000}"
    )
    public void pollDirectory() {
        if (!scanning.compareAndSet(false, true)) {
            log.debug("File watcher scan skipped because previous scan is still running");
            return;
        }
        try {
            scanNow();
        } finally {
            scanning.set(false);
        }
    }

    public int scanNow() {
        Path resourcesDir = Path.of(properties.watcher().resourcesDir()).toAbsolutePath().normalize();
        if (!Files.exists(resourcesDir) || !Files.isDirectory(resourcesDir)) {
            log.debug("Watcher resources directory does not exist yet: {}", resourcesDir);
            return 0;
        }

        List<Path> candidates;
        try (Stream<Path> stream = Files.list(resourcesDir)) {
            candidates = stream
                    .filter(Files::isRegularFile)
                    .filter(ResourceDirectoryWatcher::isSupportedFile)
                    .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                    .toList();
        } catch (IOException ex) {
            log.warn("File watcher failed to list directory {}: {}", resourcesDir, ex.getMessage());
            return 0;
        }

        int processed = 0;
        int changed = 0;
        for (Path file : candidates) {
            try {
                DynamicIngestResult result = dynamicIngestService.ingestLocalFile(file);
                processed++;
                if (!DynamicIngestResult.STATUS_SKIPPED.equals(result.status())) {
                    changed++;
                    log.info("File watcher indexed file: file={}, docId={}, status={}, version={}, chunks={}",
                            file.getFileName(), result.docId(), result.status(), result.version(), result.chunksCreated());
                }
            } catch (RuntimeException ex) {
                log.error("File watcher failed to ingest file {}: {}", file.getFileName(), ex.getMessage(), ex);
            }
        }

        if (changed > 0) {
            log.info("File watcher scan complete: dir={}, scanned={}, changed={}", resourcesDir, processed, changed);
        } else {
            log.debug("File watcher scan complete (no changes): dir={}, scanned={}", resourcesDir, processed);
        }
        return changed;
    }

    static boolean isSupportedFile(Path path) {
        String name = path.getFileName().toString();
        if (name.startsWith(".")) {
            return false;
        }
        String lower = name.toLowerCase(Locale.ROOT);
        for (String ext : SUPPORTED_EXTENSIONS) {
            if (lower.endsWith(ext)) {
                return true;
            }
        }
        return false;
    }
}
