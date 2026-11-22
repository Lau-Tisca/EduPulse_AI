package com.edupulse.retrievalpost.dto;

/**
 * Body of {@code POST /rerank} and {@code POST /context}.
 */
public record QueryRequest(String query) {
}
