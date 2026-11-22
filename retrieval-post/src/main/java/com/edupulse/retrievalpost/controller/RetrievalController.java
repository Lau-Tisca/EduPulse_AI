package com.edupulse.retrievalpost.controller;

import com.edupulse.retrievalpost.dto.ContextResponse;
import com.edupulse.retrievalpost.dto.QueryRequest;
import com.edupulse.retrievalpost.dto.RerankResponse;
import com.edupulse.retrievalpost.pipeline.RetrievalPipeline;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Post-retrieval endpoints (via gateway prefix {@code /retrieval-post}):
 * <ul>
 *   <li>{@code POST /rerank} — vector search for the top {@code APP_RERANK_TOP_N} candidates, Cohere rerank to
 *       {@code APP_RERANK_TOP_K}.</li>
 *   <li>{@code POST /context} — the above plus neighbor stitching within {@code APP_TOKEN_BUDGET}.</li>
 * </ul>
 */
@RestController
public class RetrievalController {

    private static final Logger log = LoggerFactory.getLogger(RetrievalController.class);

    private final RetrievalPipeline pipeline;

    public RetrievalController(RetrievalPipeline pipeline) {
        this.pipeline = pipeline;
    }

    @PostMapping(path = "/rerank", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public RerankResponse rerank(@RequestBody(required = false) QueryRequest request) {
        String query = QueryValidator.validate(request);
        log.info("Rerank request received: queryLength={}", query.length());
        return RerankResponse.of(pipeline.rerank(query));
    }

    @PostMapping(path = "/context", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ContextResponse context(@RequestBody(required = false) QueryRequest request) {
        String query = QueryValidator.validate(request);
        log.info("Context request received: queryLength={}", query.length());
        return ContextResponse.of(pipeline.context(query));
    }
}
