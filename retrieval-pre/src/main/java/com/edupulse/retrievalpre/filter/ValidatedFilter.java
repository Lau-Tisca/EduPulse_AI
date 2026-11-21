package com.edupulse.retrievalpre.filter;

import java.util.List;

/**
 * A metadata filter that has already been checked against the allowed fields
 * and operators. The rest of the code only works with this class, never with
 * raw text coming from the URL.
 */
public class ValidatedFilter {

    // The conditions of the filter, e.g. "source = X" and "page = 3".
    private final List<Condition> conditions;

    // How the conditions are combined: AND (all must match) or OR (any can match).
    private final FilterOperator logic;

    public ValidatedFilter(List<Condition> conditions, FilterOperator logic) {
        // A filter must have at least one condition.
        if (conditions == null || conditions.isEmpty()) {
            throw new IllegalArgumentException("A filter needs at least one condition");
        }
        // Only AND / OR are allowed to combine conditions.
        if (logic != FilterOperator.AND && logic != FilterOperator.OR) {
            throw new IllegalArgumentException("Logic must be AND or OR, got: " + logic);
        }
        // Keep a copy of the list so nobody can change it from outside later.
        this.conditions = List.copyOf(conditions);
        this.logic = logic;
    }

    public List<Condition> conditions() {
        return conditions;
    }

    public FilterOperator logic() {
        return logic;
    }

    /** Used when the client sends no filter at all: only the latest document versions. */
    public static ValidatedFilter defaultFilter() {
        Condition onlyLatest = new Condition(
                FilterField.IS_LATEST, FilterOperator.EQ, List.of("true"));
        return new ValidatedFilter(List.of(onlyLatest), FilterOperator.AND);
    }

    /**
     * One condition, e.g. "page IN 3,4" means field = PAGE, operator = IN,
     * values = ["3", "4"].
     */
    public static class Condition {

        private final FilterField field;
        private final FilterOperator operator;
        private final List<String> values;

        public Condition(FilterField field, FilterOperator operator, List<String> values) {
            if (field == null) {
                throw new IllegalArgumentException("Field must not be null");
            }
            // Only EQ (equals) and IN (one of several values) are allowed here.
            if (operator != FilterOperator.EQ && operator != FilterOperator.IN) {
                throw new IllegalArgumentException("Operator must be EQ or IN, got: " + operator);
            }
            if (values == null || values.isEmpty()) {
                throw new IllegalArgumentException("A condition needs at least one value");
            }
            // EQ compares with a single value; IN can have several.
            if (operator == FilterOperator.EQ && values.size() != 1) {
                throw new IllegalArgumentException("EQ takes exactly one value");
            }
            this.field = field;
            this.operator = operator;
            this.values = List.copyOf(values);
        }

        public FilterField field() {
            return field;
        }

        public FilterOperator operator() {
            return operator;
        }

        public List<String> values() {
            return values;
        }
    }
}
