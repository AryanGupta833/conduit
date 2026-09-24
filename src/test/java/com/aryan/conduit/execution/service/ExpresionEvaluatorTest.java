package com.aryan.conduit.execution.service;


import com.aryan.conduit.execution.entity.ExpressionNode;
import org.junit.jupiter.api.Test;
import com.aryan.conduit.execution.service.ExpressionLexer;
import com.aryan.conduit.execution.service.ExpressionParser;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExpressionEvaluatorTest {

    private final ExpressionEvaluator evaluator =
            new ExpressionEvaluator();

    @Test
    void shouldResolveNestedTaskOutput() {

        Map<String, Object> context =
                Map.of(
                        "fetchUser",
                        Map.of(
                                "success", true,
                                "output",
                                Map.of(
                                        "userId", 42,
                                        "email",
                                        "user@example.com"
                                )
                        )
                );

        String expression =
                "fetchUser.output.userId";

        ExpressionLexer lexer =
                new ExpressionLexer(expression);

        ExpressionParser parser =
                new ExpressionParser(
                        lexer.tokenize()
                );

        ExpressionNode ast =
                parser.parse();

        Object result =
                evaluator.evaluate(
                        ast,
                        context
                );

        assertEquals(42, result);
    }

    @Test
    void shouldResolveNestedStringValue() {

        Map<String, Object> context =
                Map.of(
                        "fetchUser",
                        Map.of(
                                "output",
                                Map.of(
                                        "email",
                                        "user@example.com"
                                )
                        )
                );

        ExpressionLexer lexer =
                new ExpressionLexer(
                        "fetchUser.output.email"
                );

        ExpressionParser parser =
                new ExpressionParser(
                        lexer.tokenize()
                );

        ExpressionNode ast =
                parser.parse();

        Object result =
                evaluator.evaluate(
                        ast,
                        context
                );

        assertEquals(
                "user@example.com",
                result
        );
    }

    @Test
    void shouldEvaluateConditionUsingNestedOutput() {

        Map<String, Object> context =
                Map.of(
                        "fetchUser",
                        Map.of(
                                "output",
                                Map.of(
                                        "userId", 42
                                )
                        )
                );

        ExpressionLexer lexer =
                new ExpressionLexer(
                        "fetchUser.output.userId == 42"
                );

        ExpressionParser parser =
                new ExpressionParser(
                        lexer.tokenize()
                );

        ExpressionNode ast =
                parser.parse();

        Object result =
                evaluator.evaluate(
                        ast,
                        context
                );

        System.out.println("RESULT = " + result);
        System.out.println("RESULT TYPE = " + result.getClass());

        assertTrue((Boolean) result);
    }
}