package com.edupulse.retrievalpost.pipeline;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.edupulse.retrievalpost.exception.CandidateRetrievalException;
import com.edupulse.retrievalpost.rerank.RankedChunk;
import com.edupulse.retrievalpost.rerank.RerankResult;
import com.edupulse.retrievalpost.rerank.RerankService;
import com.edupulse.retrievalpost.retrieval.CandidateRetriever;
import com.edupulse.retrievalpost.stitch.NeighborStitchingService;
import com.edupulse.retrievalpost.stitch.StitchedContext;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

class RetrievalPipelineTest {

    private CandidateRetriever retriever;
    private RerankService rerankService;
    private NeighborStitchingService stitchingService;
    private RetrievalPipeline pipeline;

    private final List<Document> candidates = List.of(
            Document.builder().id("a").text("pod").score(0.8).build(),
            Document.builder().id("b").text("node").score(0.7).build());
    private final List<RankedChunk> hits = List.of(
            new RankedChunk(1, "a", "k8s", 1, "k8s.pdf", 3, 5, "pod", 0.8, 0.9));
    private final RerankResult rerankResult = new RerankResult(hits, true, null, "rerank-v3.5", 50);

    @BeforeEach
    void setUp() {
        retriever = mock(CandidateRetriever.class);
        rerankService = mock(RerankService.class);
        stitchingService = mock(NeighborStitchingService.class);
        pipeline = new RetrievalPipeline(retriever, rerankService, stitchingService);
    }

    @Test
    void rerankRunsVectorSearchThenRerankWithoutStitching() {
        when(retriever.retrieve("q")).thenReturn(candidates);
        when(rerankService.rerank("q", candidates)).thenReturn(rerankResult);

        PipelineResult result = pipeline.rerank("q");

        assertThat(result.query()).isEqualTo("q");
        assertThat(result.candidates()).isEqualTo(2);
        assertThat(result.rerank()).isSameAs(rerankResult);
        assertThat(result.context()).isNull();
        verifyNoInteractions(stitchingService);
    }

    @Test
    void contextStitchesTheRerankedHits() {
        StitchedContext stitched = new StitchedContext(List.of(), 0, 2500, 0, true);
        when(retriever.retrieve("q")).thenReturn(candidates);
        when(rerankService.rerank("q", candidates)).thenReturn(rerankResult);
        when(stitchingService.stitch(hits)).thenReturn(stitched);

        PipelineResult result = pipeline.context("q");

        assertThat(result.candidates()).isEqualTo(2);
        assertThat(result.rerank()).isSameAs(rerankResult);
        assertThat(result.context()).isSameAs(stitched);
    }

    @Test
    void propagatesVectorSearchFailureWithoutCallingRerank() {
        when(retriever.retrieve("q")).thenThrow(new CandidateRetrievalException("down", new IllegalStateException()));

        assertThatThrownBy(() -> pipeline.context("q")).isInstanceOf(CandidateRetrievalException.class);
        verifyNoInteractions(rerankService, stitchingService);
    }
}
