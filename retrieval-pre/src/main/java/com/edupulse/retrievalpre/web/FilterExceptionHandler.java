package com.edupulse.retrievalpre.web;

import java.util.LinkedHashMap;
import java.util.Map;

import com.edupulse.retrievalpre.filter.InvalidFilterException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Turns an invalid filter into a clear HTTP 400 JSON response that lists
 * the valid fields and operators.
 */
@RestControllerAdvice
public class FilterExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(FilterExceptionHandler.class);

    @ExceptionHandler(InvalidFilterException.class)
    public ResponseEntity<Map<String, Object>> handleInvalidFilter(InvalidFilterException e) {
        log.warn("Invalid filter rejected: {}", forLog(e.getMessage()));

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", 400);
        body.put("error", "Bad Request");
        body.put("message", e.getMessage());
        body.put("validFields", e.validFields());
        body.put("validOperators", e.validOperators());
        return ResponseEntity.badRequest().body(body);
    }

    // The message can contain text from the URL: remove new lines so nobody can fake log lines.
    private static String forLog(String text) {
        return text == null ? "" : text.replace('\n', ' ').replace('\r', ' ');
    }
}
