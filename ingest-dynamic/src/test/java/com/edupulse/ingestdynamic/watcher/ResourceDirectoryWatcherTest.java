package com.edupulse.ingestdynamic.watcher;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.edupulse.ingestdynamic.config.IngestDynamicProperties;
import com.edupulse.ingestdynamic.dto.DynamicIngestResult;
import com.edupulse.ingestdynamic.service.DynamicIngestService;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ResourceDirectoryWatcherTest {

    @TempDir
    Path tempDir;

    @Test
    void scansSupportedFilesSkipsHiddenAndIsolatesPerFileErrors() throws Exception {
        Path unchanged = Files.writeString(tempDir.resolve("a-existing.pdf"), "%PDF-1.4 dummy");
        Path broken = Files.writeString(tempDir.resolve("b-broken.md"), "broken");
        Path updated = Files.writeString(tempDir.resolve("c-updated.md"), "# New Kubernetes doc");
        Files.writeString(tempDir.resolve(".gitkeep"), "");
        Files.writeString(tempDir.resolve("ignored.png"), "binary");

        IngestDynamicProperties props = new IngestDynamicProperties(
                512,
                64,
                new IngestDynamicProperties.Watcher(30, tempDir.toString()),
                IngestDynamicProperties.Ingest.defaults(),
                new IngestDynamicProperties.Webhook("secret")
        );
        DynamicIngestService ingestService = mock(DynamicIngestService.class);

        when(ingestService.ingestLocalFile(unchanged)).thenReturn(new DynamicIngestResult(
                "a-existing", "a-existing.pdf", "abc", 1, 1, 0, 0, DynamicIngestResult.STATUS_SKIPPED, "skipped"));
        when(ingestService.ingestLocalFile(broken)).thenThrow(new RuntimeException("parse error"));
        when(ingestService.ingestLocalFile(updated)).thenReturn(new DynamicIngestResult(
                "c-updated", "c-updated.md", "def", 2, 1, 1, 3, DynamicIngestResult.STATUS_UPDATED, "updated"));

        ResourceDirectoryWatcher watcher = new ResourceDirectoryWatcher(props, ingestService);
        int changed = watcher.scanNow();

        assertThat(changed).isEqualTo(1);
        verify(ingestService).ingestLocalFile(unchanged);
        verify(ingestService).ingestLocalFile(broken);
        verify(ingestService).ingestLocalFile(updated);
        verify(ingestService, never()).ingestLocalFile(tempDir.resolve(".gitkeep"));
        verify(ingestService, never()).ingestLocalFile(tempDir.resolve("ignored.png"));
    }

    @Test
    void returnsZeroQuietlyWhenDirectoryDoesNotExist() {
        IngestDynamicProperties props = new IngestDynamicProperties(
                512,
                64,
                new IngestDynamicProperties.Watcher(30, tempDir.resolve("missing-dir").toString()),
                IngestDynamicProperties.Ingest.defaults(),
                new IngestDynamicProperties.Webhook("secret")
        );
        DynamicIngestService ingestService = mock(DynamicIngestService.class);
        ResourceDirectoryWatcher watcher = new ResourceDirectoryWatcher(props, ingestService);

        assertThat(watcher.scanNow()).isZero();
        verify(ingestService, never()).ingestLocalFile(any());
    }
}
