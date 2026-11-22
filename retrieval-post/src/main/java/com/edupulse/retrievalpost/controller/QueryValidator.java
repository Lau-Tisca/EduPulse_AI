package com.edupulse.retrievalpost.controller;

import com.edupulse.retrievalpost.dto.RerankRequest;
import com.edupulse.retrievalpost.exception.InvalidQueryException;

/**
 * Shared request validation for retrieval-post endpoints.
 */
final class QueryValidator {

    static final int MAX_QUERY_LENGTH = 1000;

    private QueryValidator() {
    }

    /**
     * @return the trimmed query
     * @throws InvalidQueryException when the query is missing, blank or longer than {@link #MAX_QUERY_LENGTH}
     */
    static String validate(RerankRequest request) {
        if (request == null || request.query() == null || request.query().isBlank()) {
            throw new InvalidQueryException("query must not be blank");
        }
        String query = request.query().trim();
        if (query.length() > MAX_QUERY_LENGTH) {
            throw new InvalidQueryException("query must be at most " + MAX_QUERY_LENGTH + " characters");
        }
        return query;
    }
}
