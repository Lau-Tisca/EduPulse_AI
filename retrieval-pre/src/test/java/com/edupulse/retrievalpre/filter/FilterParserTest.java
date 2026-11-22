package com.edupulse.retrievalpre.filter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

class FilterParserTest {

    private final FilterParser parser = new FilterParser();

    // Builds a Map keeping the order, e.g. params("source", "x", "page", "3").
    private static Map<String, String> params(String... keyValues) {
        Map<String, String> map = new LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            map.put(keyValues[i], keyValues[i + 1]);
        }
        return map;
    }

    // ---------- Success flows ----------

    @Test
    void singleValueBecomesEqCondition() {
        ValidatedFilter filter = parser.parse(params("source", "kubectl-quick-reference"));

        assertThat(filter.logic()).isEqualTo(FilterOperator.AND);
        assertThat(filter.conditions()).hasSize(1);
        ValidatedFilter.Condition condition = filter.conditions().get(0);
        assertThat(condition.field()).isEqualTo(FilterField.SOURCE);
        assertThat(condition.operator()).isEqualTo(FilterOperator.EQ);
        assertThat(condition.values()).containsExactly("kubectl-quick-reference");
    }

    @Test
    void commaSeparatedValuesBecomeInCondition() {
        ValidatedFilter filter = parser.parse(params("page", "3, 4"));

        ValidatedFilter.Condition condition = filter.conditions().get(0);
        assertThat(condition.field()).isEqualTo(FilterField.PAGE);
        assertThat(condition.operator()).isEqualTo(FilterOperator.IN);
        assertThat(condition.values()).containsExactly("3", "4");
    }

    @Test
    void logicOrIsApplied() {
        ValidatedFilter filter = parser.parse(params("source", "x", "page", "1", "logic", "or"));

        assertThat(filter.logic()).isEqualTo(FilterOperator.OR);
        assertThat(filter.conditions()).hasSize(2);
    }

    @Test
    void emptyParamsFallBackToLatestVersionDefault() {
        assertDefaultFilter(parser.parse(Map.of()));
    }

    @Test
    void nullParamsFallBackToLatestVersionDefault() {
        assertDefaultFilter(parser.parse(null));
    }

    private static void assertDefaultFilter(ValidatedFilter filter) {
        assertThat(filter.conditions()).hasSize(1);
        ValidatedFilter.Condition condition = filter.conditions().get(0);
        assertThat(condition.field()).isEqualTo(FilterField.IS_LATEST);
        assertThat(condition.values()).containsExactly("true");
    }

    @Test
    void onlyLogicParamFallsBackToDefault() {
        ValidatedFilter filter = parser.parse(params("logic", "OR"));

        assertThat(filter.conditions().get(0).field()).isEqualTo(FilterField.IS_LATEST);
    }

    // ---------- Failure flows ----------

    @Test
    void unknownFieldIsRejectedWithValidFieldsList() {
        assertThatThrownBy(() -> parser.parse(params("password", "x")))
                .isInstanceOf(InvalidFilterException.class)
                .hasMessageContaining("Unknown filter field")
                .satisfies(e -> {
                    InvalidFilterException ex = (InvalidFilterException) e;
                    assertThat(ex.validFields())
                            .containsExactly("doc_id", "version", "source", "page", "is_latest");
                    assertThat(ex.validOperators()).containsExactly("EQ", "IN", "AND", "OR");
                });
    }

    @Test
    void sqlInjectionAttemptIsRejectedAsUnknownField() {
        assertThatThrownBy(() -> parser.parse(params("source; DROP TABLE vector_store", "x")))
                .isInstanceOf(InvalidFilterException.class);
    }

    @Test
    void invalidLogicIsRejected() {
        assertThatThrownBy(() -> parser.parse(params("source", "x", "logic", "XOR")))
                .isInstanceOf(InvalidFilterException.class)
                .hasMessageContaining("Invalid logic");
    }

    @Test
    void nonNumericPageIsRejected() {
        assertThatThrownBy(() -> parser.parse(params("page", "abc")))
                .isInstanceOf(InvalidFilterException.class)
                .hasMessageContaining("whole number");
    }

    @Test
    void invalidIsLatestIsRejected() {
        assertThatThrownBy(() -> parser.parse(params("is_latest", "maybe")))
                .isInstanceOf(InvalidFilterException.class)
                .hasMessageContaining("true or false");
    }

    @Test
    void emptyValueIsRejected() {
        assertThatThrownBy(() -> parser.parse(params("source", "  ")))
                .isInstanceOf(InvalidFilterException.class)
                .hasMessageContaining("Empty value");
    }

    @Test
    void emptyItemInListIsRejected() {
        assertThatThrownBy(() -> parser.parse(params("page", "3,,4")))
                .isInstanceOf(InvalidFilterException.class)
                .hasMessageContaining("Empty value in list");
    }

    @Test
    void tooManyValuesAreRejected() {
        String twentyOne = String.join(",", java.util.Collections.nCopies(21, "1"));

        assertThatThrownBy(() -> parser.parse(params("page", twentyOne)))
                .isInstanceOf(InvalidFilterException.class)
                .hasMessageContaining("Too many values");
    }

    @Test
    void tooLongValueIsRejected() {
        String longValue = "a".repeat(201);

        assertThatThrownBy(() -> parser.parse(params("source", longValue)))
                .isInstanceOf(InvalidFilterException.class)
                .hasMessageContaining("too long");
    }
}
