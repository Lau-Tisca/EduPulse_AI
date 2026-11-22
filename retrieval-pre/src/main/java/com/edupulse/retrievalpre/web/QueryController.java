package com.edupulse.retrievalpre.web;

import java.util.Map;

import com.edupulse.retrievalpre.normalize.QueryNormalizer;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Endpoint to try the query normalization.
 * Through the gateway: GET /retrieval-pre/query/normalize?q=Hi, can you explain HPA?
 */
@RestController
public class QueryController {

    private final QueryNormalizer queryNormalizer;

    public QueryController(QueryNormalizer queryNormalizer) {
        this.queryNormalizer = queryNormalizer;
    }

    // "q" is required: if it is missing, Spring answers 400 Bad Request by itself.
    @GetMapping("/query/normalize")
    public Map<String, String> normalize(@RequestParam("q") String q) {
        return Map.of(
                "raw", q,
                "normalized", queryNormalizer.normalize(q));
    }
}
