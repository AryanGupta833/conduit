package com.aryan.conduit.execution.service;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ExpressionEvaluatorSafetyTest {
    private final ExpressionEvaluator evaluator = new ExpressionEvaluator();

    @Test
    void resolvesNestedValuesAndWrappedBooleanBranchExpressions() {
        Map<String, Object> context = Map.of("variables", Map.of("customerId", "C123"),
                "tasks", Map.of("fetchUser", Map.of("output", Map.of("status", "SUCCESS", "id", 123))));
        assertEquals(true, evaluator.evaluate("${tasks.fetchUser.output.status == \"SUCCESS\" && variables.customerId == \"C123\"}", context));
        assertEquals(true, evaluator.evaluate("tasks.fetchUser.output.id == 123", context));
        assertEquals(true, evaluator.evaluate("!false && tasks.fetchUser.output.id >= 123", context));
    }

    @Test
    void missingAndNullLookupsAreNullSafeForEquality() {
        Map<String, Object> context = Map.of("variables", java.util.Collections.singletonMap("value", null));
        assertEquals(true, evaluator.evaluate("variables.missing == null", context));
        assertEquals(true, evaluator.evaluate("variables.value == null", context));
        assertThrows(IllegalArgumentException.class, () -> evaluator.evaluate("variables.missing > 1", context));
    }

    @Test
    void rejectsMalformedOversizedAndExecutableExpressions() {
        assertThrows(RuntimeException.class, () -> evaluator.evaluate("variables.x == ", Map.of()));
        assertThrows(IllegalArgumentException.class, () -> evaluator.evaluate("x".repeat(2049), Map.of()));
        assertThrows(RuntimeException.class, () -> evaluator.evaluate("T(java.lang.Runtime).getRuntime()", Map.of()));
        assertThrows(RuntimeException.class, () -> evaluator.evaluate("variables.x == 1 trailing", Map.of()));
    }

    @Test
    void handlesEscapedQuotesAndShortCircuits() {
        assertEquals(true, evaluator.evaluate("\"a\\\"b\" == \"a\\\"b\"", Map.of()));
        assertEquals(false, evaluator.evaluate("false && variables.missing > 1", Map.of()));
        assertEquals(true, evaluator.evaluate("true || variables.missing > 1", Map.of()));
    }
}
