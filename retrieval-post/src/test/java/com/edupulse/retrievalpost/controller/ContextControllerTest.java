package com.edupulse.retrievalpost.controller;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.edupulse.retrievalpost.exception.GlobalExceptionHandler;
import com.edupulse.retrievalpost.rerank.RankedChunk;
import com.edupulse.retrievalpost.rerank.RerankResult;
import com.edupulse.retrievalpost.rerank.RerankService;
import com.edupulse.retrievalpost.retrieval.CandidateRetriever;
import com.edupulse.retrievalpost.stitch.NeighborStitchingService;
import com.edupulse.retrievalpost.stitch.StitchedContext;
import com.edupulse.retrievalpost.stitch.StitchedPassage;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class ContextControllerTest {

    private CandidateRetriever candidateRetriever;
    private RerankService rerankService;
    private NeighborStitchingService stitchingService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        candidateRetriever = mock(CandidateRetriever.class);
        rerankService = mock(RerankService.class);
        stitchingService = mock(NeighborStitchingService.class);
        mockMvc = MockMvcBuilders
                .standaloneSetup(new ContextController(candidateRetriever, rerankService, stitchingService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void returnsStitchedPassagesWithBudgetInfo() throws Exception {
        List<Document> candidates = List.of(Document.builder().id("a").text("pod").score(0.8).build());
        List<RankedChunk> hits = List.of(new RankedChunk(1, "a", "k8s", 1, "k8s.pdf", 3, 5, "pod", 0.8, 0.9));
        when(candidateRetriever.retrieve("What is a Pod?")).thenReturn(candidates);
        when(rerankService.rerank("What is a Pod?", candidates))
                .thenReturn(new RerankResult(hits, true, null, "rerank-v3.5", 100));
        when(stitchingService.stitch(hits)).thenReturn(new StitchedContext(
                List.of(new StitchedPassage(1, "k8s", 1, "k8s.pdf", List.of(2, 3), List.of(4, 5, 6), List.of(5),
                        0.9, 0.8, 900, "stitched text")),
                900, 2500, 0, true));

        mockMvc.perform(post("/context")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"query\":\"What is a Pod?\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reranked").value(true))
                .andExpect(jsonPath("$.candidates").value(1))
                .andExpect(jsonPath("$.tokenBudget").value(2500))
                .andExpect(jsonPath("$.totalTokens").value(900))
                .andExpect(jsonPath("$.droppedHits").value(0))
                .andExpect(jsonPath("$.neighborsAvailable").value(true))
                .andExpect(jsonPath("$.passages[0].chunkIndexes.length()").value(3))
                .andExpect(jsonPath("$.passages[0].anchorChunkIndexes[0]").value(5))
                .andExpect(jsonPath("$.passages[0].pages[1]").value(3))
                .andExpect(jsonPath("$.passages[0].text").value("stitched text"));
    }

    @Test
    void rejectsBlankQueryWith400() throws Exception {
        mockMvc.perform(post("/context")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"query\":\"\"}"))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(candidateRetriever, rerankService, stitchingService);
    }
}
