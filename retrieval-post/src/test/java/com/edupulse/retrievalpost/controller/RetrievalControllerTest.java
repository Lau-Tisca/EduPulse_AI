package com.edupulse.retrievalpost.controller;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.edupulse.retrievalpost.exception.CandidateRetrievalException;
import com.edupulse.retrievalpost.exception.GlobalExceptionHandler;
import com.edupulse.retrievalpost.pipeline.PipelineResult;
import com.edupulse.retrievalpost.pipeline.RetrievalPipeline;
import com.edupulse.retrievalpost.rerank.RankedChunk;
import com.edupulse.retrievalpost.rerank.RerankFallbackReason;
import com.edupulse.retrievalpost.rerank.RerankResult;
import com.edupulse.retrievalpost.stitch.StitchedContext;
import com.edupulse.retrievalpost.stitch.StitchedPassage;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class RetrievalControllerTest {

    private static final RankedChunk HIT =
            new RankedChunk(1, "a", "k8s-arch", 1, "k8s-arch.pdf", 3, 12, "pod text", 0.80, 0.93);

    private RetrievalPipeline pipeline;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        pipeline = mock(RetrievalPipeline.class);
        mockMvc = MockMvcBuilders
                .standaloneSetup(new RetrievalController(pipeline))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void rerankReturnsChunksWithCitationMetadataAndScores() throws Exception {
        when(pipeline.rerank("What is a Pod?")).thenReturn(new PipelineResult("What is a Pod?", 20,
                new RerankResult(List.of(HIT), true, null, "rerank-v3.5", 120), null));

        mockMvc.perform(post("/rerank")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"query\":\"  What is a Pod?  \"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.query").value("What is a Pod?"))
                .andExpect(jsonPath("$.reranked").value(true))
                .andExpect(jsonPath("$.fallbackReason").doesNotExist())
                .andExpect(jsonPath("$.model").value("rerank-v3.5"))
                .andExpect(jsonPath("$.candidates").value(20))
                .andExpect(jsonPath("$.results.length()").value(1))
                .andExpect(jsonPath("$.results[0].rank").value(1))
                .andExpect(jsonPath("$.results[0].docId").value("k8s-arch"))
                .andExpect(jsonPath("$.results[0].source").value("k8s-arch.pdf"))
                .andExpect(jsonPath("$.results[0].page").value(3))
                .andExpect(jsonPath("$.results[0].chunkIndex").value(12))
                .andExpect(jsonPath("$.results[0].vectorScore").value(0.80))
                .andExpect(jsonPath("$.results[0].rerankScore").value(0.93));
    }

    @Test
    void rerankReportsFallbackReason() throws Exception {
        RankedChunk fallbackHit = new RankedChunk(1, "a", "d", 1, "d.pdf", 1, 0, "t", 0.7, null);
        when(pipeline.rerank(anyString())).thenReturn(new PipelineResult("q", 20,
                new RerankResult(List.of(fallbackHit), false, RerankFallbackReason.MISSING_API_KEY, "rerank-v3.5", 2),
                null));

        mockMvc.perform(post("/rerank")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"query\":\"q\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reranked").value(false))
                .andExpect(jsonPath("$.fallbackReason").value("MISSING_API_KEY"))
                .andExpect(jsonPath("$.results[0].rerankScore").doesNotExist());
    }

    @Test
    void contextReturnsStitchedPassagesWithBudgetInfo() throws Exception {
        StitchedContext context = new StitchedContext(
                List.of(new StitchedPassage(1, "k8s", 1, "k8s.pdf", List.of(2, 3), List.of(4, 5, 6), List.of(5),
                        0.9, 0.8, 900, "stitched text")),
                900, 2500, 0, true);
        when(pipeline.context("What is a Pod?")).thenReturn(new PipelineResult("What is a Pod?", 20,
                new RerankResult(List.of(HIT), true, null, "rerank-v3.5", 100), context));

        mockMvc.perform(post("/context")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"query\":\"What is a Pod?\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reranked").value(true))
                .andExpect(jsonPath("$.candidates").value(20))
                .andExpect(jsonPath("$.tokenBudget").value(2500))
                .andExpect(jsonPath("$.totalTokens").value(900))
                .andExpect(jsonPath("$.droppedHits").value(0))
                .andExpect(jsonPath("$.neighborsAvailable").value(true))
                .andExpect(jsonPath("$.passages[0].chunkIndexes.length()").value(3))
                .andExpect(jsonPath("$.passages[0].anchorChunkIndexes[0]").value(5))
                .andExpect(jsonPath("$.passages[0].pages[1]").value(3))
                .andExpect(jsonPath("$.passages[0].text").value("stitched text"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"/rerank", "/context"})
    void rejectsBlankQueryWith400(String path) throws Exception {
        mockMvc.perform(post(path)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"query\":\"   \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("query must not be blank"));

        verifyNoInteractions(pipeline);
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"query\":", "not json"})
    void rejectsMissingQueryOrMalformedJsonWith400(String body) throws Exception {
        mockMvc.perform(post("/rerank")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(pipeline);
    }

    @Test
    void rejectsTooLongQueryWith400() throws Exception {
        String longQuery = "k".repeat(QueryValidator.MAX_QUERY_LENGTH + 1);

        mockMvc.perform(post("/context")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"query\":\"" + longQuery + "\"}"))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(pipeline);
    }

    @Test
    void returns503WhenVectorSearchFails() throws Exception {
        when(pipeline.context(anyString()))
                .thenThrow(new CandidateRetrievalException("Vector search failed", new IllegalStateException()));

        mockMvc.perform(post("/context")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"query\":\"What is a Pod?\"}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.detail").value("Vector search is temporarily unavailable"));
    }
}
