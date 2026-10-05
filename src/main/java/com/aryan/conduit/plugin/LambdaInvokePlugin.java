package com.aryan.conduit.plugin;

import com.aryan.conduit.plugin.sdk.PluginConfigurationException;
import com.aryan.conduit.plugin.sdk.PluginContext;
import com.aryan.conduit.plugin.sdk.PluginMetadata;
import com.aryan.conduit.plugin.sdk.PluginResult;
import com.aryan.conduit.plugin.sdk.WorkflowPlugin;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.services.lambda.LambdaClient;
import software.amazon.awssdk.services.lambda.model.InvocationType;
import software.amazon.awssdk.services.lambda.model.InvokeRequest;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

@Component
public class LambdaInvokePlugin implements WorkflowPlugin {
    private static final int MAX_PAYLOAD = 900 * 1024;
    private final LambdaClient lambda;
    private final com.fasterxml.jackson.databind.ObjectMapper mapper;

    public LambdaInvokePlugin(LambdaClient lambda, com.fasterxml.jackson.databind.ObjectMapper mapper) {
        this.lambda = lambda; this.mapper = mapper;
    }

    @Override public PluginMetadata metadata() {
        return new PluginMetadata("AWS_LAMBDA_INVOKE", "AWS Lambda Invoke", "Invokes an AWS Lambda function synchronously or asynchronously.", "1.0.0",
                Map.of("type", "object", "required", List.of("functionName", "payload"), "properties", Map.of(
                        "functionName", Map.of("type", "string"), "payload", Map.of("type", "object"),
                        "invocationType", Map.of("type", "string", "enum", List.of("REQUEST_RESPONSE", "EVENT")))));
    }

    @Override public void validateConfiguration(Map<String, Object> c) {
        String name = SnsPublishPlugin.required(c, "functionName");
        if (name.length() > 256 || !(name.matches("[A-Za-z0-9_-]{1,64}(:[A-Za-z0-9_-]{1,128})?")
                || name.matches("arn:(aws|aws-us-gov|aws-cn):lambda:[a-z0-9-]+:[0-9]{12}:function:[A-Za-z0-9_-]{1,64}(:[A-Za-z0-9_-]{1,128})?")))
            throw new PluginConfigurationException("AWS_LAMBDA_INVOKE functionName is invalid");
        if (!c.containsKey("payload")) throw new PluginConfigurationException("AWS_LAMBDA_INVOKE payload is required");
        String type = String.valueOf(c.getOrDefault("invocationType", "REQUEST_RESPONSE")).toUpperCase();
        if (!List.of("REQUEST_RESPONSE", "EVENT").contains(type)) throw new PluginConfigurationException("invocationType must be REQUEST_RESPONSE or EVENT");
        try {
            if (mapper.writeValueAsBytes(c.get("payload")).length > MAX_PAYLOAD)
                throw new PluginConfigurationException("Lambda payload exceeds 900 KiB");
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) { throw new PluginConfigurationException("Lambda payload is not JSON serializable", e); }
    }

    @Override public PluginResult execute(PluginContext context) {
        Map<String, Object> c = context.configuration(); validateConfiguration(c);
        try {
            String payload = mapper.writeValueAsString(c.get("payload"));
            var response = lambda.invoke(InvokeRequest.builder().functionName(String.valueOf(c.get("functionName")))
                    .invocationType(InvocationType.fromValue(String.valueOf(c.getOrDefault("invocationType", "REQUEST_RESPONSE")).toUpperCase()))
                    .payload(SdkBytes.fromString(payload, StandardCharsets.UTF_8)).build());
            String result = response.payload() == null ? "" : response.payload().asUtf8String();
            if (result.length() > 256 * 1024) result = result.substring(0, 256 * 1024);
            boolean success = response.functionError() == null;
            return PluginResult.builder().success(success).output(result)
                    .metadata(Map.of("statusCode", response.statusCode(), "executedVersion", response.executedVersion() == null ? "" : response.executedVersion(),
                            "functionError", response.functionError() == null ? "" : response.functionError())).build();
        } catch (Exception e) { return SnsPublishPlugin.failure(e); }
    }
}
