package com.edupulse.retrievalpre.filter;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

/**
 * Turns the URL parameters into a {@link ValidatedFilter}.
 *
 * Format:
 *   ?source=kubectl-quick-reference      -> source EQ one value
 *   ?page=3,4                            -> page IN two values
 *   ?logic=OR                            -> how conditions are combined (default AND)
 *
 * Anything that is not an allowed field (or "logic") is rejected.
 */
@Component
public class FilterParser {

    private static final String LOGIC_PARAM = "logic";
    private static final int MAX_VALUES = 20;
    private static final int MAX_VALUE_LENGTH = 200;

    // What we tell the client when something is wrong.
    private static final List<String> VALID_OPERATORS = List.of("EQ", "IN", "AND", "OR");

    public ValidatedFilter parse(Map<String, String> params) {
        // No filter at all -> default (only latest versions).
        if (params == null || params.isEmpty()) {
            return ValidatedFilter.defaultFilter();
        }

        FilterOperator logic = FilterOperator.AND;
        List<ValidatedFilter.Condition> conditions = new ArrayList<>();

        for (Map.Entry<String, String> entry : params.entrySet()) {
            String name = entry.getKey();
            String rawValue = entry.getValue();

            if (LOGIC_PARAM.equals(name)) {
                logic = parseLogic(rawValue);
                continue; // go to the next parameter
            }

            FilterField field = FilterField.fromKey(name);
            if (field == null) {
                throw error("Unknown filter field: '" + name + "'");
            }
            conditions.add(parseCondition(field, rawValue));
        }

        // Only "logic" was sent, no real condition -> default.
        if (conditions.isEmpty()) {
            return ValidatedFilter.defaultFilter();
        }
        return new ValidatedFilter(conditions, logic);
    }

    private FilterOperator parseLogic(String raw) {
        if ("AND".equalsIgnoreCase(raw)) {
            return FilterOperator.AND;
        }
        if ("OR".equalsIgnoreCase(raw)) {
            return FilterOperator.OR;
        }
        throw error("Invalid logic: '" + raw + "' (use AND or OR)");
    }

    private ValidatedFilter.Condition parseCondition(FilterField field, String rawValue) {
        if (rawValue == null || rawValue.isBlank()) {
            throw error("Empty value for field '" + field.key() + "'");
        }

        // A comma means several values -> IN; otherwise a single value -> EQ.
        // The -1 keeps empty items at the end, so "3,4," is rejected like "3,,4".
        List<String> values = new ArrayList<>();
        for (String part : rawValue.split(",", -1)) {
            String value = part.trim();
            if (value.isEmpty()) {
                throw error("Empty value in list for field '" + field.key() + "'");
            }
            if (value.length() > MAX_VALUE_LENGTH) {
                throw error("Value too long for field '" + field.key() + "'");
            }
            checkType(field, value);
            values.add(value);
        }
        if (values.size() > MAX_VALUES) {
            throw error("Too many values for field '" + field.key() + "' (max " + MAX_VALUES + ")");
        }

        FilterOperator operator = values.size() == 1 ? FilterOperator.EQ : FilterOperator.IN;
        return new ValidatedFilter.Condition(field, operator, values);
    }

    // page and version must be whole numbers, is_latest must be true/false.
    private void checkType(FilterField field, String value) {
        switch (field) {
            case PAGE, VERSION -> {
                try {
                    Integer.parseInt(value);
                } catch (NumberFormatException e) {
                    throw error("Field '" + field.key() + "' must be a whole number, got: '" + value + "'");
                }
            }
            case IS_LATEST -> {
                if (!"true".equalsIgnoreCase(value) && !"false".equalsIgnoreCase(value)) {
                    throw error("Field 'is_latest' must be true or false, got: '" + value + "'");
                }
            }
            default -> { /* doc_id and source accept any text */ }
        }
    }

    private InvalidFilterException error(String message) {
        return new InvalidFilterException(message, FilterField.validKeys(), VALID_OPERATORS);
    }
}
