package com.edupulse.retrievalpost.controller;

import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.edupulse.retrievalpost.exception.CandidateRetrievalException;
import com.edupulse.retrievalpost.exception.GlobalExceptionHandler;
import com.edupulse.retrievalpost.rerank.RankedChunk;
import com.edupulse.retrievalpost.rerank.RerankFallbackReason;
import com.edupulse.retrievalpost.rerank.RerankResult;
import com.edupulse.retrievalpost.rerank.RerankService;
import com.edupulse.retrievalpost.retrieval.CandidateRetriever;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class RerankControllerTest {

    private CandidateRetriever candidateRetriever;
    private RerankService rerankService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        candidateRetriever = mock(CandidateRetriever.class);
        rerankService = mock(RerankService.class);
        mockMvc = MockMvcBuilders
                .standaloneSetup(new RerankController(candidateRetriever, rerankService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void returnsRerankedChunksWithCitationMetadata() throws Exception {
        List<Document> candidates = List.of(
                Document.builder().id("a").text("pod text").score(0.80).build(),
                Document.builder().id("b").text("rbac text").score(0.78).build());
        when(candidateRetriever.retrieve("What is a Pod?")).thenReturn(candidates);
        when(rerankService.rerank(eq("What is a Pod?"), eq(candidates))).thenReturn(new RerankResult(
                List.of(new RankedChunk(1, "a", "k8s-arch", 1, "k8s-arch.pdf", 3, 12, "pod text", 0.80, 0.93)),
                true, null, "rerank-v3.5", 120));

        mockMvc.perform(post("/rerank")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"query\":\"  What is a Pod?  \"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.query").value("What is a Pod?"))
                .andExpect(jsonPath("$.reranked").value(true))
                .andExpect(jsonPath("$.fallbackReason").doesNotExist())
                .andExpect(jsonPath("$.model").value("rerank-v3.5"))
                .andExpect(jsonPath("$.candidates").value(2))
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
    void reportsFallbackReasonWhenRerankingIsUnavailable() throws Exception {
        when(candidateRetriever.retrieve(anyString())).thenReturn(List.of());
        when(rerankService.rerank(anyString(), anyList())).thenReturn(new RerankResult(
                List.of(new RankedChunk(1, "a", "d", 1, "d.pdf", 1, 0, "t", 0.7, null)),
                false, RerankFallbackReason.MISSING_API_KEY, "rerank-v3.5", 2));

        mockMvc.perform(post("/rerank")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"query\":\"What is a Pod?\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reranked").value(false))
                .andExpect(jsonPath("$.fallbackReason").value("MISSING_API_KEY"))
                .andExpect(jsonPath("$.results[0].rerankScore").doesNotExist());
    }

    @Test
    void rejectsBlankQueryWith400() throws Exception {
        mockMvc.perform(post("/rerank")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"query\":\"   \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("query must not be blank"));

        verifyNoInteractions(candidateRetriever, rerankService);
    }

    @Test
    void rejectsMissingQueryWith400() throws Exception {
        mockMvc.perform(post("/rerank")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(candidateRetriever, rerankService);
    }

    @Test
    void rejectsMalformedJsonWith400() throws Exception {
        mockMvc.perform(post("/rerank")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"query\":"))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(candidateRetriever, rerankService);
    }

    @Test
    void rejectsTooLongQueryWith400() throws Exception {
        String longQuery = "k".repeat(QueryValidator.MAX_QUERY_LENGTH + 1);

        mockMvc.perform(post("/rerank")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"query\":\"" + longQuery + "\"}"))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(candidateRetriever, rerankService);
    }

    @Test
    void returns503WhenVectorSearchFails() throws Exception {
        when(candidateRetriever.retrieve(anyString()))
                .thenThrow(new CandidateRetrievalException("Vector search failed", new IllegalStateException()));

        mockMvc.perform(post("/rerank")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"query\":\"What is a Pod?\"}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.detail").value("Vector search is temporarily unavailable"));

        verifyNoInteractions(rerankService);
    }
}
