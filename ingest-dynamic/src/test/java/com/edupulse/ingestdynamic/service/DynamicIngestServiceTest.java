package com.edupulse.ingestdynamic.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.edupulse.ingestdynamic.chunking.ChecksumService;
import com.edupulse.ingestdynamic.chunking.StructureAwareChunker;
import com.edupulse.ingestdynamic.chunking.VectorStoreBatchWriter;
import com.edupulse.ingestdynamic.config.IngestDynamicProperties;
import com.edupulse.ingestdynamic.dto.DynamicIngestResult;
import com.edupulse.ingestdynamic.exception.IngestionConflictException;
import com.edupulse.ingestdynamic.exception.RemoteFetchException;
import com.edupulse.ingestdynamic.fetch.FetchedContent;
import com.edupulse.ingestdynamic.fetch.RemoteContentFetcher;
import com.edupulse.ingestdynamic.parser.ContentParser;
import com.edupulse.ingestdynamic.parser.HtmlContentParser;
import com.edupulse.ingestdynamic.parser.MarkdownContentParser;
import com.edupulse.ingestdynamic.parser.PdfDocumentParser;
import com.edupulse.ingestdynamic.versioning.DocumentVersionRepository;
import com.edupulse.ingestdynamic.versioning.DocumentVersionRepository.VersionSnapshot;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.document.Document;
import org.springframework.http.HttpStatus;

class DynamicIngestServiceTest {

    private RemoteContentFetcher remoteContentFetcher;
    private VectorStoreBatchWriter batchWriter;
    private DocumentVersionRepository versionRepository;
    private ChecksumService checksumService;
    private DynamicIngestService service;

    @BeforeEach
    void setUp() {
        IngestDynamicProperties props = new IngestDynamicProperties(
                512,
                64,
                IngestDynamicProperties.Watcher.defaults(),
                IngestDynamicProperties.Ingest.defaults(),
                new IngestDynamicProperties.Webhook("secret")
        );
        remoteContentFetcher = mock(RemoteContentFetcher.class);
        batchWriter = mock(VectorStoreBatchWriter.class);
        versionRepository = mock(DocumentVersionRepository.class);
        checksumService = new ChecksumService();
        ContentParser contentParser = new ContentParser(
                new PdfDocumentParser(),
                new HtmlContentParser(),
                new MarkdownContentParser()
        );
        service = new DynamicIngestService(
                remoteContentFetcher,
                contentParser,
                checksumService,
                new StructureAwareChunker(props),
                batchWriter,
                versionRepository
        );
    }

    @Test
    @SuppressWarnings("unchecked")
    void ingestsNewDocumentAsVersion1WithAllSevenMetadataFields() {
        byte[] bytes = "# Kubernetes Pods\nPods are the smallest deployable units.".getBytes(StandardCharsets.UTF_8);
        String url = "https://raw.githubusercontent.com/kubernetes/website/main/pods.md";
        when(remoteContentFetcher.fetch(url)).thenReturn(new FetchedContent(url, "text/markdown", bytes));
        when(versionRepository.findVersionSnapshot("k8s-pods")).thenReturn(Optional.empty());
        when(versionRepository.tryClaim(eq("k8s-pods"), eq(1), anyString())).thenReturn(true);

        DynamicIngestResult result = service.ingestUrl(url, "k8s-pods");

        assertThat(result.status()).isEqualTo(DynamicIngestResult.STATUS_INGESTED);
        assertThat(result.version()).isEqualTo(1);
        assertThat(result.previousVersion()).isNull();
        assertThat(result.chunksCreated()).isGreaterThanOrEqualTo(1);

        ArgumentCaptor<List<Document>> chunksCaptor = ArgumentCaptor.forClass(List.class);
        verify(batchWriter).addAll(chunksCaptor.capture(), eq("k8s-pods"));
        Document firstChunk = chunksCaptor.getValue().getFirst();
        assertThat(firstChunk.getMetadata())
                .containsEntry("doc_id", "k8s-pods")
                .containsEntry("version", 1)
                .containsEntry("source", url)
                .containsEntry("page", 1)
                .containsEntry("chunk_index", 0)
                .containsEntry("checksum", checksumService.sha256(bytes))
                .containsEntry("is_latest", false); // written invisible before atomic cutover

        verify(versionRepository).activateVersion("k8s-pods", 1, checksumService.sha256(bytes));
    }

    @Test
    void skipsUnchangedDocumentWhenLatestChecksumMatches() {
        byte[] bytes = "Unchanged Kubernetes doc".getBytes(StandardCharsets.UTF_8);
        String checksum = checksumService.sha256(bytes);
        when(versionRepository.findVersionSnapshot("k8s-doc"))
                .thenReturn(Optional.of(new VersionSnapshot(1, 1, checksum)));

        DynamicIngestResult result = service.ingestPayload("k8s-doc", "k8s-doc.md", "text/markdown", bytes);

        assertThat(result.status()).isEqualTo(DynamicIngestResult.STATUS_SKIPPED);
        assertThat(result.version()).isEqualTo(1);
        assertThat(result.chunksCreated()).isZero();
        verify(batchWriter, never()).addAll(any(), anyString());
        verify(versionRepository, never()).activateVersion(anyString(), anyInt(), anyString());
    }

    @Test
    void incrementsVersionAndFlipsIsLatestWhenChecksumChanges() {
        byte[] updatedBytes = "# Updated RBAC Guide v2\nNew cluster role binding rules.".getBytes(StandardCharsets.UTF_8);
        String newChecksum = checksumService.sha256(updatedBytes);
        when(versionRepository.findVersionSnapshot("k8s-rbac"))
                .thenReturn(Optional.of(new VersionSnapshot(1, 1, "old-checksum-v1")));
        when(versionRepository.tryClaim("k8s-rbac", 2, newChecksum)).thenReturn(true);

        DynamicIngestResult result = service.ingestPayload("k8s-rbac", "https://kubernetes.io/rbac", "text/markdown", updatedBytes);

        assertThat(result.status()).isEqualTo(DynamicIngestResult.STATUS_UPDATED);
        assertThat(result.version()).isEqualTo(2);
        assertThat(result.previousVersion()).isEqualTo(1);
        verify(versionRepository).activateVersion("k8s-rbac", 2, newChecksum);
    }

    @Test
    void rollsBackOnlyFailedVersionWithoutTouchingPriorLatestVersion() {
        byte[] updatedBytes = "# RBAC v2 content".getBytes(StandardCharsets.UTF_8);
        String newChecksum = checksumService.sha256(updatedBytes);
        when(versionRepository.findVersionSnapshot("k8s-rbac"))
                .thenReturn(Optional.of(new VersionSnapshot(1, 1, "old-checksum-v1")));
        when(versionRepository.tryClaim("k8s-rbac", 2, newChecksum)).thenReturn(true);
        doThrow(new RuntimeException("Embedding API error")).when(batchWriter).addAll(any(), eq("k8s-rbac"));

        assertThatThrownBy(() -> service.ingestPayload("k8s-rbac", "https://kubernetes.io/rbac", "text/markdown", updatedBytes))
                .isInstanceOf(RuntimeException.class);

        verify(versionRepository).rollbackFailedVersion("k8s-rbac", 2, newChecksum);
        verify(versionRepository, never()).activateVersion(anyString(), anyInt(), anyString());
    }

    @Test
    void derivesLocalFileDocIdMatchingIngestStaticForPdfAndAvoidingCrossExtensionCollisions() {
        assertThat(DynamicIngestService.deriveFileDocId("kubectl-quick-reference.pdf"))
                .isEqualTo("kubectl-quick-reference");
        assertThat(DynamicIngestService.deriveFileDocId("k8s notes (v2).md"))
                .isEqualTo("k8s_notes__v2_.md");
        assertThat(DynamicIngestService.deriveFileDocId("guide.pdf"))
                .isNotEqualTo(DynamicIngestService.deriveFileDocId("guide.md"));
    }

    @Test
    void doesNotSkipWhenOnlyUnactivatedPartialVersionExistsAfterCrash() {
        byte[] bytes = "# Recovered Kubernetes Doc".getBytes(StandardCharsets.UTF_8);
        String checksum = checksumService.sha256(bytes);
        // Simulate partial crashed v1 rows (is_latest=false only -> latestVersion=null, latestChecksum=null).
        when(versionRepository.findVersionSnapshot("crashed-doc"))
                .thenReturn(Optional.of(new VersionSnapshot(null, 1, null)));
        when(versionRepository.tryClaim("crashed-doc", 2, checksum)).thenReturn(true);

        DynamicIngestResult result = service.ingestPayload("crashed-doc", "crashed-doc.md", "text/markdown", bytes);

        assertThat(result.status()).isEqualTo(DynamicIngestResult.STATUS_INGESTED);
        assertThat(result.version()).isEqualTo(2);
        assertThat(result.previousVersion()).isNull();
        verify(versionRepository).activateVersion("crashed-doc", 2, checksum);
    }

    @Test
    void throwsIngestionConflictWhenClaimIsAlreadyHeldByAnotherWorker() {
        byte[] bytes = "# Concurrent Update".getBytes(StandardCharsets.UTF_8);
        String checksum = checksumService.sha256(bytes);
        when(versionRepository.findVersionSnapshot("k8s-rbac")).thenReturn(Optional.empty());
        when(versionRepository.tryClaim("k8s-rbac", 1, checksum)).thenReturn(false);

        assertThatThrownBy(() -> service.ingestPayload("k8s-rbac", "https://kubernetes.io/rbac", "text/markdown", bytes))
                .isInstanceOf(IngestionConflictException.class);
        verify(batchWriter, never()).addAll(any(), anyString());
    }

    @Test
    void preservesOriginalExceptionEvenIfRollbackFails() {
        byte[] bytes = "# RBAC v2".getBytes(StandardCharsets.UTF_8);
        String checksum = checksumService.sha256(bytes);
        when(versionRepository.findVersionSnapshot("k8s-rbac")).thenReturn(Optional.empty());
        when(versionRepository.tryClaim("k8s-rbac", 1, checksum)).thenReturn(true);
        doThrow(new IllegalStateException("root embedding error")).when(batchWriter).addAll(any(), eq("k8s-rbac"));
        doThrow(new RuntimeException("db rollback failed"))
                .when(versionRepository).rollbackFailedVersion("k8s-rbac", 1, checksum);

        assertThatThrownBy(() -> service.ingestPayload("k8s-rbac", "https://kubernetes.io/rbac", "text/markdown", bytes))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("root embedding error");
    }

    @Test
    void rejectsInvalidOrOverlongCustomDocId() {
        assertThatThrownBy(() -> service.ingestUrl("https://kubernetes.io/docs/", "../escape"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.ingestUrl("https://kubernetes.io/docs/", "a".repeat(200)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.ingestUrl("https://kubernetes.io/docs/", "bad\nid"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void ingestLocalFileBlocksPathEscapeAndOversizedFiles(@TempDir Path tempDir) throws Exception {
        Path watchedDir = Files.createDirectories(tempDir.resolve("resources"));
        Path outsideFile = Files.writeString(tempDir.resolve("secret.txt"), "top-secret");
        Path oversizedFile = Files.write(watchedDir.resolve("huge.md"), new byte[2048]);

        IngestDynamicProperties strictProps = new IngestDynamicProperties(
                512,
                64,
                new IngestDynamicProperties.Watcher(30, watchedDir.toString()),
                new IngestDynamicProperties.Ingest(1024, 5000, true, IngestDynamicProperties.Ingest.DEFAULT_ALLOWED_DOMAINS),
                new IngestDynamicProperties.Webhook("secret")
        );
        DynamicIngestService localService = new DynamicIngestService(
                strictProps,
                remoteContentFetcher,
                new ContentParser(new PdfDocumentParser(), new HtmlContentParser(), new MarkdownContentParser()),
                checksumService,
                new StructureAwareChunker(strictProps),
                batchWriter,
                versionRepository
        );

        assertThatThrownBy(() -> localService.ingestLocalFile(outsideFile))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must stay inside");

        assertThatThrownBy(() -> localService.ingestLocalFile(oversizedFile))
                .isInstanceOf(RemoteFetchException.class)
                .extracting(ex -> ((RemoteFetchException) ex).status())
                .isEqualTo(HttpStatus.CONTENT_TOO_LARGE);
    }
}
