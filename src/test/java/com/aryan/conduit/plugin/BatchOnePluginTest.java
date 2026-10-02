package com.aryan.conduit.plugin;

import com.aryan.conduit.plugin.sdk.PluginContext;
import com.aryan.conduit.plugin.sdk.PluginResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class BatchOnePluginTest {

    private final ObjectMapper objectMapper =
            new ObjectMapper();

    @Test
    void setVariablesShouldMergeVariables() {

        SetVariablesPlugin plugin =
                new SetVariablesPlugin();

        PluginContext context =
                new PluginContext(
                        "test-task",
                        30,
                        Map.of(
                                "variables",
                                Map.of(
                                        "environment",
                                        "production",
                                        "region",
                                        "ap-south-1"
                                )
                        ),
                        Map.of(
                                "existing",
                                "value"
                        )
                );

        PluginResult result =
                plugin.execute(context);

        assertTrue(result.success());

        assertEquals(
                "value",
                result.variables().get("existing")
        );

        assertEquals(
                "production",
                result.variables().get("environment")
        );

        assertEquals(
                "ap-south-1",
                result.variables().get("region")
        );
    }

    @Test
    void setVariablesShouldRejectMissingVariables() {

        SetVariablesPlugin plugin =
                new SetVariablesPlugin();

        assertThrows(
                RuntimeException.class,
                () -> plugin.validateConfiguration(
                        Map.of()
                )
        );
    }

    @Test
    void jsonTransformShouldExtractNestedValue()
            throws Exception {

        JsonTransformPlugin plugin =
                new JsonTransformPlugin(new ObjectMapper());

        Map<String, Object> config =
                new LinkedHashMap<>();

        config.put(
                "input",
                """
                {
                  "user": {
                    "name": "Aryan",
                    "age": 20
                  }
                }
                """
        );

        config.put(
                "operation",
                "EXTRACT"
        );

        config.put(
                "path",
                "user.name"
        );

        PluginContext context =
                new PluginContext(
                        "json-task",
                        30,
                        config,
                        Map.of()
                );

        PluginResult result =
                plugin.execute(context);

        assertTrue(result.success());
        assertEquals(
                "\"Aryan\"",
                result.output()
        );
    }

    @Test
    void jsonTransformShouldParseJson()
            throws Exception {

        JsonTransformPlugin plugin =
                new JsonTransformPlugin(new ObjectMapper());

        PluginContext context =
                new PluginContext(
                        "json-task",
                        30,
                        Map.of(
                                "input",
                                "{\"name\":\"Aryan\"}",
                                "operation",
                                "PARSE"
                        ),
                        Map.of()
                );

        PluginResult result =
                plugin.execute(context);

        assertTrue(result.success());
        assertTrue(
                result.output().contains("\"name\":\"Aryan\"")
        );
    }

    @Test
    void jsonTransformShouldStringifyObject()
            throws Exception {

        JsonTransformPlugin plugin =
                new JsonTransformPlugin(new ObjectMapper());

        PluginContext context =
                new PluginContext(
                        "json-task",
                        30,
                        Map.of(
                                "input",
                                Map.of(
                                        "name",
                                        "Aryan",
                                        "age",
                                        20
                                ),
                                "operation",
                                "STRINGIFY"
                        ),
                        Map.of()
                );

        PluginResult result =
                plugin.execute(context);

        assertTrue(result.success());

        assertTrue(
                result.output().contains("\"name\":\"Aryan\"")
        );
    }

    @Test
    void jsonTransformShouldRejectInvalidOperation() {

        JsonTransformPlugin plugin =
                new JsonTransformPlugin(new ObjectMapper());

        assertThrows(
                RuntimeException.class,
                () -> plugin.validateConfiguration(
                        Map.of(
                                "input",
                                "{}",
                                "operation",
                                "INVALID"
                        )
                )
        );
    }
}