package com.edupulse.retrievalpre.filter;

import java.util.List;

/**
 * Thrown when the filter sent by the client is not valid.
 * Carries the lists of valid fields and operators so the error response
 * (HTTP 400) can show them to the client.
 */
public class InvalidFilterException extends RuntimeException {

    private final List<String> validFields;
    private final List<String> validOperators;

    public InvalidFilterException(String message, List<String> validFields, List<String> validOperators) {
        super(message);
        this.validFields = List.copyOf(validFields);
        this.validOperators = List.copyOf(validOperators);
    }

    public List<String> validFields() {
        return validFields;
    }

    public List<String> validOperators() {
        return validOperators;
    }
}
