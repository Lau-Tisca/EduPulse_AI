package com.edupulse.retrievalpre.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import com.edupulse.retrievalpre.filter.FilterField;
import com.edupulse.retrievalpre.filter.FilterParser;
import com.edupulse.retrievalpre.filter.InvalidFilterException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

class FilterExceptionHandlerTest {

    @Test
    void invalidFilterIsMappedTo400WithValidFieldsAndOperators() {
        FilterExceptionHandler handler = new FilterExceptionHandler();
        InvalidFilterException exception =
                new InvalidFilterException("bad filter", FilterField.validKeys(), List.of("EQ", "IN", "AND", "OR"));

        ResponseEntity<Map<String, Object>> response = handler.handleInvalidFilter(exception);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody())
                .containsEntry("status", 400)
                .containsEntry("message", "bad filter")
                .containsEntry("validFields", List.of("doc_id", "version", "source", "page", "is_latest"))
                .containsEntry("validOperators", List.of("EQ", "IN", "AND", "OR"));
    }

    @Test
    void controllerReturnsParsedFilterForValidInput() {
        FilterController controller = new FilterController(new FilterParser());

        Map<String, Object> result = controller.validate(Map.of("source", "kubectl-quick-reference"));

        assertThat(result).containsEntry("logic", "AND");
        assertThat((List<?>) result.get("conditions")).hasSize(1);
    }
}
