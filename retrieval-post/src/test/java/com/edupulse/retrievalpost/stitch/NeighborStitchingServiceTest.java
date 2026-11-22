package com.edupulse.retrievalpost.stitch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.edupulse.retrievalpost.rerank.RankedChunk;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;

class NeighborStitchingServiceTest {

    /** Deterministic test tokenizer: one token per whitespace-separated word. */
    private static int words(String text) {
        return text.isBlank() ? 0 : text.trim().split("\\s+").length;
    }

    private NeighborChunkRepository repository;

    @BeforeEach
    void setUp() {
        repository = mock(NeighborChunkRepository.class);
    }

    private NeighborStitchingService service(int budget) {
        return new NeighborStitchingService(repository, budget, NeighborStitchingServiceTest::words);
    }

    /**
     * Document "k8s" v1 with 10 chunks; chunk i = "overlap(i-1) body(i)" mimicking ingestion overlap, where
     * overlap(i) is the tail of chunk i that the next chunk repeats.
     */
    private static String chunkText(int i) {
        String body = "body" + i + " w w w w w w w w overlap" + i + " tail tail tail tail tail tail";
        return i == 0 ? body : "overlap" + (i - 1) + " tail tail tail tail tail tail " + body;
    }

    private static NeighborChunk neighbor(int i) {
        return new NeighborChunk("id-" + i, "k8s", 1, "k8s.pdf", 10 + i / 2, i, chunkText(i));
    }

    private static RankedChunk hit(int rank, int chunkIndex, double rerankScore) {
        return new RankedChunk(rank, "id-" + chunkIndex, "k8s", 1, "k8s.pdf", 10 + chunkIndex / 2, chunkIndex,
                chunkText(chunkIndex), 0.6, rerankScore);
    }

    /** Repository stub answering with whichever of chunks 0..9 were requested. */
    private void stubRepositoryWithDocument() {
        when(repository.findByChunkIndexes(eq("k8s"), eq(1), anyCollection())).thenAnswer(invocation -> {
            Collection<Integer> wanted = invocation.getArgument(2);
            return wanted.stream()
                    .filter(i -> i >= 0 && i < 10)
                    .collect(Collectors.toMap(Function.identity(), NeighborStitchingServiceTest::neighbor));
        });
    }

    @Test
    void stitchesPreviousAndNextChunkAroundHitWithoutRepeatingOverlap() {
        stubRepositoryWithDocument();

        StitchedContext context = service(2500).stitch(List.of(hit(1, 5, 0.9)));

        assertThat(context.neighborsAvailable()).isTrue();
        assertThat(context.droppedHits()).isZero();
        assertThat(context.passages()).hasSize(1);
        StitchedPassage passage = context.passages().getFirst();
        assertThat(passage.rank()).isEqualTo(1);
        assertThat(passage.docId()).isEqualTo("k8s");
        assertThat(passage.version()).isEqualTo(1);
        assertThat(passage.source()).isEqualTo("k8s.pdf");
        assertThat(passage.chunkIndexes()).containsExactly(4, 5, 6);
        assertThat(passage.anchorChunkIndexes()).containsExactly(5);
        assertThat(passage.pages()).containsExactly(12, 13);
        assertThat(passage.rerankScore()).isEqualTo(0.9);
        assertThat(passage.text()).startsWith(chunkText(4)).contains("body5").endsWith("tail tail tail tail tail tail");
        assertThat(occurrences(passage.text(), "overlap4 tail")).isEqualTo(1);
        assertThat(occurrences(passage.text(), "overlap5 tail")).isEqualTo(1);
        assertThat(context.totalTokens()).isEqualTo(passage.tokenCount()).isLessThanOrEqualTo(2500);
    }

    @Test
    void mergesAdjacentHitsIntoOnePassageWithoutDuplicateChunks() {
        stubRepositoryWithDocument();

        StitchedContext context = service(2500).stitch(List.of(hit(1, 6, 0.9), hit(2, 5, 0.8)));

        assertThat(context.passages()).hasSize(1);
        StitchedPassage passage = context.passages().getFirst();
        assertThat(passage.chunkIndexes()).containsExactly(4, 5, 6, 7);
        assertThat(passage.anchorChunkIndexes()).containsExactly(5, 6);
        assertThat(passage.rank()).isEqualTo(1);
        assertThat(passage.rerankScore()).isEqualTo(0.9);
        assertThat(occurrences(passage.text(), "body5")).isEqualTo(1);
        assertThat(occurrences(passage.text(), "body6")).isEqualTo(1);
    }

    @Test
    void keepsSeparatePassagesOrderedByRankForDistantHitsAndOtherDocuments() {
        stubRepositoryWithDocument();
        RankedChunk otherDoc = new RankedChunk(1, "x", "aws", 2, "aws.pdf", 3, 0, "aws security text", 0.7, 0.95);
        when(repository.findByChunkIndexes(eq("aws"), eq(2), anyCollection())).thenReturn(Map.of());

        StitchedContext context = service(2500).stitch(List.of(otherDoc, hit(2, 1, 0.8), hit(3, 8, 0.7)));

        assertThat(context.passages()).extracting(StitchedPassage::rank).containsExactly(1, 2, 3);
        assertThat(context.passages()).extracting(StitchedPassage::docId).containsExactly("aws", "k8s", "k8s");
        assertThat(context.passages().get(1).chunkIndexes()).containsExactly(0, 1, 2);
        assertThat(context.passages().get(2).chunkIndexes()).containsExactly(7, 8, 9);
    }

    @Test
    void doesNotRequestNegativeChunkIndex() {
        stubRepositoryWithDocument();

        service(2500).stitch(List.of(hit(1, 0, 0.9)));

        verify(repository).findByChunkIndexes(eq("k8s"), eq(1),
                argThat(wanted -> wanted.size() == 1 && wanted.contains(1)));
    }

    @Test
    void skipsNeighborsThatDoNotFitTheBudget() {
        stubRepositoryWithDocument();
        int hitCost = words(chunkText(5)) + 1;

        StitchedContext context = service(hitCost + 5).stitch(List.of(hit(1, 5, 0.9)));

        assertThat(context.passages()).hasSize(1);
        assertThat(context.passages().getFirst().chunkIndexes()).containsExactly(5);
        assertThat(context.totalTokens()).isLessThanOrEqualTo(context.tokenBudget());
    }

    @Test
    void dropsLowerRankedHitsWhenHitsAloneExceedTheBudget() {
        stubRepositoryWithDocument();
        int hitCost = words(chunkText(2)) + 1;

        StitchedContext context = service(hitCost * 2).stitch(List.of(hit(1, 2, 0.9), hit(2, 5, 0.8), hit(3, 8, 0.7)));

        assertThat(context.droppedHits()).isEqualTo(1);
        assertThat(context.passages()).flatExtracting(StitchedPassage::anchorChunkIndexes).containsExactly(2, 5);
        assertThat(context.totalTokens()).isLessThanOrEqualTo(context.tokenBudget());
    }

    @Test
    void fallsBackToHitsOnlyWhenNeighborLookupFails() {
        when(repository.findByChunkIndexes(anyString(), anyInt(), anyCollection()))
                .thenThrow(new DataAccessResourceFailureException("pgvector down"));

        StitchedContext context = service(2500).stitch(List.of(hit(1, 5, 0.9), hit(2, 8, 0.7)));

        assertThat(context.neighborsAvailable()).isFalse();
        assertThat(context.passages()).extracting(StitchedPassage::chunkIndexes)
                .containsExactly(List.of(5), List.of(8));
    }

    @Test
    void keepsHitsWithoutPositionMetadataAsStandalonePassages() {
        RankedChunk unpositioned = new RankedChunk(1, "web-1", null, null, "https://kubernetes.io", null, null,
                "web chunk", 0.7, 0.9);

        StitchedContext context = service(2500).stitch(List.of(unpositioned));

        assertThat(context.passages()).hasSize(1);
        assertThat(context.passages().getFirst().text()).isEqualTo("web chunk");
        verify(repository, never()).findByChunkIndexes(any(), anyInt(), anyCollection());
    }

    @Test
    void returnsEmptyContextForNoHits() {
        StitchedContext context = service(2500).stitch(List.of());

        assertThat(context.passages()).isEmpty();
        assertThat(context.totalTokens()).isZero();
        verify(repository, never()).findByChunkIndexes(any(), anyInt(), anyCollection());
    }

    @Test
    void realTokenizerKeepsTwentyLargeChunksWithinBudget() {
        String bigChunk = IntStream.range(0, 450).mapToObj(i -> "token" + i).collect(Collectors.joining(" "));
        when(repository.findByChunkIndexes(anyString(), anyInt(), anyCollection())).thenReturn(Map.of());
        NeighborStitchingService realService = new NeighborStitchingService(repository, 2500,
                new org.springframework.ai.tokenizer.JTokkitTokenCountEstimator()::estimate);
        List<RankedChunk> hits = IntStream.rangeClosed(1, 5)
                .mapToObj(rank -> new RankedChunk(rank, "id" + rank, "doc" + rank, 1, "doc.pdf", 1, 0,
                        bigChunk, 0.6, 0.5))
                .toList();

        StitchedContext context = realService.stitch(hits);

        assertThat(context.totalTokens()).isLessThanOrEqualTo(2500);
        assertThat(context.droppedHits()).isPositive();
    }

    private static int occurrences(String text, String needle) {
        return Arrays.stream(text.split(java.util.regex.Pattern.quote(needle), -1)).toList().size() - 1;
    }
}
