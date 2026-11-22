package com.edupulse.retrievalpost.controller;

import com.edupulse.retrievalpost.dto.ContextResponse;
import com.edupulse.retrievalpost.dto.RerankRequest;
import com.edupulse.retrievalpost.rerank.RerankResult;
import com.edupulse.retrievalpost.rerank.RerankService;
import com.edupulse.retrievalpost.retrieval.CandidateRetriever;
import com.edupulse.retrievalpost.stitch.NeighborStitchingService;
import com.edupulse.retrievalpost.stitch.StitchedContext;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code POST /context} (via gateway: {@code POST /retrieval-post/context}): vector search, Cohere rerank and
 * neighbor stitching within {@code APP_TOKEN_BUDGET}. This is the context that answer generation will use.
 */
@RestController
public class ContextController {

    private static final Logger log = LoggerFactory.getLogger(ContextController.class);

    private final CandidateRetriever candidateRetriever;
    private final RerankService rerankService;
    private final NeighborStitchingService stitchingService;

    public ContextController(CandidateRetriever candidateRetriever,
                             RerankService rerankService,
                             NeighborStitchingService stitchingService) {
        this.candidateRetriever = candidateRetriever;
        this.rerankService = rerankService;
        this.stitchingService = stitchingService;
    }

    @PostMapping(path = "/context", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ContextResponse context(@RequestBody(required = false) RerankRequest request) {
        String query = QueryValidator.validate(request);
        log.info("Context request received: queryLength={}", query.length());

        List<Document> candidates = candidateRetriever.retrieve(query);
        RerankResult rerank = rerankService.rerank(query, candidates);
        StitchedContext context = stitchingService.stitch(rerank.results());
        return ContextResponse.of(query, candidates.size(), rerank, context);
    }
}
