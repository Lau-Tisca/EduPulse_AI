package com.edupulse.retrievalpre.web;

import java.util.List;
import java.util.Map;

import com.edupulse.retrievalpre.filter.FilterParser;
import com.edupulse.retrievalpre.filter.ValidatedFilter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Endpoint that checks metadata filters sent in the URL.
 * Through the gateway: GET /retrieval-pre/filters/validate?source=...&page=3,4&logic=OR
 */
@RestController
public class FilterController {

    private static final Logger log = LoggerFactory.getLogger(FilterController.class);

    private final FilterParser filterParser;

    public FilterController(FilterParser filterParser) {
        this.filterParser = filterParser;
    }

    @GetMapping("/filters/validate")
    public Map<String, Object> validate(@RequestParam Map<String, String> params) {
        // If the filter is invalid, the parser throws InvalidFilterException
        // and FilterExceptionHandler turns it into a 400 response.
        ValidatedFilter filter = filterParser.parse(params);

        log.info("Filter accepted: {} condition(s), logic={}", filter.conditions().size(), filter.logic());

        List<Map<String, Object>> conditions = filter.conditions().stream()
                .map(c -> Map.<String, Object>of(
                        "field", c.field().key(),
                        "operator", c.operator().name(),
                        "values", c.values()))
                .toList();

        return Map.of(
                "logic", filter.logic().name(),
                "conditions", conditions);
    }
}
