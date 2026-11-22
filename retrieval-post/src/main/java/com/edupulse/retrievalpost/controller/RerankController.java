package com.edupulse.retrievalpost.controller;

import com.edupulse.retrievalpost.dto.RerankRequest;
import com.edupulse.retrievalpost.dto.RerankResponse;
import com.edupulse.retrievalpost.rerank.RerankResult;
import com.edupulse.retrievalpost.rerank.RerankService;
import com.edupulse.retrievalpost.retrieval.CandidateRetriever;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code POST /rerank} (via gateway: {@code POST /retrieval-post/rerank}): vector search for the top
 * {@code APP_RERANK_TOP_N} candidates, then Cohere reranking down to {@code APP_RERANK_TOP_K}.
 */
@RestController
public class RerankController {

    private static final Logger log = LoggerFactory.getLogger(RerankController.class);

    private final CandidateRetriever candidateRetriever;
    private final RerankService rerankService;

    public RerankController(CandidateRetriever candidateRetriever, RerankService rerankService) {
        this.candidateRetriever = candidateRetriever;
        this.rerankService = rerankService;
    }

    @PostMapping(path = "/rerank", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public RerankResponse rerank(@RequestBody(required = false) RerankRequest request) {
        String query = QueryValidator.validate(request);
        log.info("Rerank request received: queryLength={}", query.length());

        List<Document> candidates = candidateRetriever.retrieve(query);
        RerankResult result = rerankService.rerank(query, candidates);
        return RerankResponse.of(query, candidates.size(), result);
    }
}
