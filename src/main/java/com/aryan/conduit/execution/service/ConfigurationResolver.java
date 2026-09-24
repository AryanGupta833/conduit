package com.aryan.conduit.execution.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
public class ConfigurationResolver {

    private static final Pattern PLACEHOLDER =
            Pattern.compile("\\$\\{([^}]+)}");

    private final ExpressionContextService expressionContextService;

    public String resolve(
            Long workflowExecutionId,
            String configurationJson
    ) {

        if (configurationJson == null || configurationJson.isBlank()) {
            return configurationJson;
        }

        Map<String, Object> context =
                expressionContextService.buildContext(
                        workflowExecutionId
                );

        Matcher matcher = PLACEHOLDER.matcher(configurationJson);

        StringBuffer result = new StringBuffer();

        while (matcher.find()) {

            String expression = matcher.group(1).trim();

            Object value = resolveExpression(
                    expression,
                    context
            );

            if (value == null) {
                throw new IllegalArgumentException(
                        "Unable to resolve configuration variable: ${"
                                + expression
                                + "}"
                );
            }

            matcher.appendReplacement(
                    result,
                    Matcher.quoteReplacement(
                            String.valueOf(value)
                    )
            );
        }

        matcher.appendTail(result);

        return result.toString();
    }

    private Object resolveExpression(
            String expression,
            Map<String, Object> context
    ) {

        if (!expression.contains(".")) {
            return context.get(expression);
        }

        String[] parts = expression.split("\\.");

        Object current = context;

        for (String part : parts) {

            if (!(current instanceof Map<?, ?> map)) {
                return null;
            }

            current = map.get(part);

            if (current == null) {
                return null;
            }
        }

        return current;
    }
}