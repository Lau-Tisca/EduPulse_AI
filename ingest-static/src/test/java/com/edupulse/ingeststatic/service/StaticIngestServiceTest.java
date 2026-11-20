package com.edupulse.ingeststatic.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.edupulse.ingeststatic.config.IngestStaticProperties;
import com.edupulse.ingeststatic.dto.StaticIngestItemResult;
import com.edupulse.ingeststatic.dto.StaticIngestResponse;
import com.edupulse.ingeststatic.exception.InvalidPdfException;
import com.edupulse.ingeststatic.exception.PdfNotFoundException;
import com.edupulse.ingeststatic.repository.VectorStoreChecksumRepository;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;

class StaticIngestServiceTest {

    @TempDir
    Path tempDir;

    private VectorStoreChecksumRepository checksumRepository;
    private VectorStore vectorStore;
    private StaticIngestService staticIngestService;

    @BeforeEach
    void setUp() {
        IngestStaticProperties properties = new IngestStaticProperties(512, 64, tempDir.toString());
        ChecksumService checksumService = new ChecksumService();
        PdfDocumentParser pdfDocumentParser = new PdfDocumentParser();
        StructureAwareChunker chunker = new StructureAwareChunker(properties);
        checksumRepository = mock(VectorStoreChecksumRepository.class);
        vectorStore = mock(VectorStore.class);
        VectorStoreBatchWriter batchWriter = new VectorStoreBatchWriter(vectorStore, 20, 2, 0L);

        staticIngestService = new StaticIngestService(
                properties,
                checksumService,
                pdfDocumentParser,
                chunker,
                checksumRepository,
                batchWriter
        );
    }

    @Test
    @SuppressWarnings("unchecked")
    void ingestsMultiColumnAndTablePdfWithAllMandatoryMetadata() throws IOException {
        Path pdfPath = tempDir.resolve("k8s-architecture-guide.pdf");
        Files.write(pdfPath, createMultiColumnAndTablePdf());
        when(checksumRepository.tryClaim(anyString(), anyString())).thenReturn(true);

        StaticIngestResponse response = staticIngestService.ingest("k8s-architecture-guide.pdf", null);

        assertThat(response.totalFiles()).isEqualTo(1);
        assertThat(response.ingestedFiles()).isEqualTo(1);
        assertThat(response.skippedFiles()).isZero();
        assertThat(response.totalChunksCreated()).isGreaterThanOrEqualTo(2);

        StaticIngestItemResult item = response.results().getFirst();
        assertThat(item.docId()).isEqualTo("k8s-architecture-guide");
        assertThat(item.source()).isEqualTo("k8s-architecture-guide.pdf");
        assertThat(item.version()).isEqualTo(1);
        assertThat(item.pagesParsed()).isEqualTo(2);
        assertThat(item.status()).isEqualTo(StaticIngestItemResult.STATUS_INGESTED);
        assertThat(item.checksum()).hasSize(64);

        ArgumentCaptor<List<Document>> docsCaptor = ArgumentCaptor.forClass(List.class);
        verify(vectorStore, times(1)).add(docsCaptor.capture());

        List<Document> storedChunks = docsCaptor.getValue();
        assertThat(storedChunks).hasSize(item.chunksCreated());

        for (int i = 0; i < storedChunks.size(); i++) {
            Document chunk = storedChunks.get(i);
            assertThat(chunk.getMetadata())
                    .containsEntry("doc_id", "k8s-architecture-guide")
                    .containsEntry("version", 1)
                    .containsEntry("source", "k8s-architecture-guide.pdf")
                    .containsEntry("chunk_index", i)
                    .containsEntry("checksum", item.checksum())
                    .containsEntry("is_latest", true)
                    .containsKey("page");
            assertThat((Integer) chunk.getMetadata().get("page")).isGreaterThanOrEqualTo(1);
        }

        String combinedText = storedChunks.stream()
                .map(Document::getText)
                .reduce("", (a, b) -> a + "\n" + b);
        assertThat(combinedText)
                .contains("kube-apiserver")
                .contains("etcd")
                .contains("kubelet")
                .contains("HorizontalPodAutoscaler");
    }

    @Test
    void skipsReIngestionWhenDocIdAndChecksumAlreadyExist() throws IOException {
        byte[] pdfBytes = createMultiColumnAndTablePdf();
        when(checksumRepository.tryClaim(anyString(), anyString())).thenReturn(false);

        StaticIngestItemResult result = staticIngestService.ingestBytes(
                pdfBytes,
                "k8s-architecture-guide.pdf",
                "k8s-architecture-guide"
        );

        assertThat(result.status()).isEqualTo(StaticIngestItemResult.STATUS_SKIPPED);
        assertThat(result.chunksCreated()).isZero();
        assertThat(result.pagesParsed()).isZero();
        verify(vectorStore, never()).add(anyList());
    }

    @Test
    void ingestsAllPdfsInResourcesDirectory() throws IOException {
        Files.write(tempDir.resolve("doc-a.pdf"), createMultiColumnAndTablePdf());
        Files.write(tempDir.resolve("doc-b.pdf"), createMultiColumnAndTablePdf());
        Files.writeString(tempDir.resolve("ignore-me.txt"), "not a pdf");
        when(checksumRepository.tryClaim(anyString(), anyString())).thenReturn(true);

        StaticIngestResponse response = staticIngestService.ingest(null, null);

        assertThat(response.totalFiles()).isEqualTo(2);
        assertThat(response.ingestedFiles()).isEqualTo(2);
        assertThat(response.skippedFiles()).isZero();
        verify(vectorStore, times(2)).add(anyList());
    }

    @Test
    void throwsPdfNotFoundExceptionWhenFileDoesNotExist() {
        assertThatThrownBy(() -> staticIngestService.ingest("missing-file.pdf", null))
                .isInstanceOf(PdfNotFoundException.class)
                .hasMessageContaining("missing-file.pdf");
        verify(vectorStore, never()).add(anyList());
    }

    @Test
    void throwsInvalidPdfExceptionForCorruptedNonPdfContent() {
        byte[] invalidBytes = "This is plain text, not a PDF file.".getBytes(StandardCharsets.UTF_8);
        when(checksumRepository.tryClaim(anyString(), anyString())).thenReturn(true);

        assertThatThrownBy(() -> staticIngestService.ingestBytes(invalidBytes, "corrupted.pdf", null))
                .isInstanceOf(InvalidPdfException.class)
                .hasMessageContaining("%PDF-");
        verify(vectorStore, never()).add(anyList());
    }

    @Test
    void rejectsPathTraversalOutsideStaticResourcesDir() {
        assertThatThrownBy(() -> staticIngestService.ingest("../outside.pdf", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("static resources directory");
    }

    @Test
    void rejectsAbsoluteFilePathOutsideOrInsideStaticResourcesDir() {
        assertThatThrownBy(() -> staticIngestService.ingest(tempDir.resolve("guide.pdf").toString(), null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("relative");
    }

    @Test
    void removesPartialVectorsAndMarksClaimFailedWhenBatchWritingFails() throws IOException {
        byte[] pdfBytes = createMultiColumnAndTablePdf();
        when(checksumRepository.tryClaim(anyString(), anyString())).thenReturn(true);
        doThrow(new IllegalStateException("embedding failed"))
                .when(vectorStore).add(anyList());

        assertThatThrownBy(() -> staticIngestService.ingestBytes(
                pdfBytes,
                "k8s-architecture-guide.pdf",
                "k8s-architecture-guide"
        )).isInstanceOf(IllegalStateException.class)
                .hasMessage("embedding failed");

        verify(checksumRepository).deleteByDocIdAndChecksum(
                eq("k8s-architecture-guide"),
                argThat(checksum -> checksum != null && checksum.length() == 64)
        );
        verify(checksumRepository).markFailed(
                eq("k8s-architecture-guide"),
                argThat(checksum -> checksum != null && checksum.length() == 64)
        );
    }

    @Test
    void stripsNulBytesAndControlCharsFromExtractedText() {
        PdfDocumentParser parser = new PdfDocumentParser();
        String cleaned = parser.normalizeLayoutText("Kubernetes\u0000\u0007   Security\r\n\r\n\r\nWhitepaper\u0000");
        assertThat(cleaned).isEqualTo("Kubernetes Security\n\nWhitepaper");
        assertThat(cleaned).doesNotContain("\u0000");
    }

    private byte[] createMultiColumnAndTablePdf() throws IOException {
        try (PDDocument document = new PDDocument();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {

            PDType1Font font = new PDType1Font(Standard14Fonts.FontName.HELVETICA);

            // Page 1: Two-column layout
            PDPage page1 = new PDPage();
            document.addPage(page1);
            try (PDPageContentStream cs = new PDPageContentStream(document, page1)) {
                cs.beginText();
                cs.setFont(font, 11);
                cs.newLineAtOffset(50, 720);
                cs.showText("Control Plane Overview");
                cs.newLineAtOffset(0, -18);
                cs.showText("The kube-apiserver exposes the Kubernetes HTTP API.");
                cs.newLineAtOffset(0, -16);
                cs.showText("All cluster state is persisted in etcd with Raft consensus.");
                cs.endText();

                cs.beginText();
                cs.setFont(font, 11);
                cs.newLineAtOffset(330, 720);
                cs.showText("Worker Node Components");
                cs.newLineAtOffset(0, -18);
                cs.showText("The kubelet ensures containers are running in Pods.");
                cs.newLineAtOffset(0, -16);
                cs.showText("kube-proxy maintains network rules on each node.");
                cs.endText();
            }

            // Page 2: Structured table
            PDPage page2 = new PDPage();
            document.addPage(page2);
            try (PDPageContentStream cs = new PDPageContentStream(document, page2)) {
                cs.beginText();
                cs.setFont(font, 11);
                cs.newLineAtOffset(50, 720);
                cs.showText("Resource | Purpose | API Group");
                cs.newLineAtOffset(0, -18);
                cs.showText("Pod | Smallest deployable compute unit | core/v1");
                cs.newLineAtOffset(0, -18);
                cs.showText("HorizontalPodAutoscaler | Automatic replica scaling | autoscaling/v2");
                cs.newLineAtOffset(0, -18);
                cs.showText("PersistentVolumeClaim | Storage request by user | core/v1");
                cs.endText();
            }

            document.save(out);
            return out.toByteArray();
        }
    }
}
