package com.aryan.conduit.plugin;

import com.aryan.conduit.plugin.sdk.PluginConfigurationException;
import com.aryan.conduit.plugin.sdk.PluginContext;
import com.aryan.conduit.plugin.sdk.PluginMetadata;
import com.aryan.conduit.plugin.sdk.PluginResult;
import com.aryan.conduit.plugin.sdk.WorkflowPlugin;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

@Component
public class SqsSendPlugin implements WorkflowPlugin {
    private final SqsClient sqs;
    public SqsSendPlugin(SqsClient sqs) { this.sqs = sqs; }

    @Override public PluginMetadata metadata() {
        return new PluginMetadata("AWS_SQS_SEND", "AWS SQS Send", "Sends a message to an AWS SQS queue, including FIFO deduplication support.", "1.0.0",
                Map.of("type", "object", "required", List.of("queueUrl", "messageBody"), "properties", Map.of(
                        "queueUrl", Map.of("type", "string"), "messageBody", Map.of("type", "string"), "messageGroupId", Map.of("type", "string"), "messageDeduplicationId", Map.of("type", "string"))));
    }

    @Override public void validateConfiguration(Map<String, Object> c) {
        String queueUrl = SnsPublishPlugin.required(c, "queueUrl");
        try {
            URI uri = URI.create(queueUrl);
            String host = uri.getHost();
            if (!"https".equalsIgnoreCase(uri.getScheme()) || host == null || uri.getUserInfo() != null
                    || !(host.matches("sqs\\.[a-z0-9-]+\\.amazonaws\\.com") || host.matches("sqs\\.[a-z0-9-]+\\.amazonaws\\.com\\.cn")))
                throw new IllegalArgumentException();
        } catch (IllegalArgumentException e) { throw new PluginConfigurationException("AWS_SQS_SEND queueUrl must be an HTTPS AWS SQS queue URL"); }
        String body = SnsPublishPlugin.required(c, "messageBody");
        if (body.getBytes(StandardCharsets.UTF_8).length > 1024 * 1024)
            throw new PluginConfigurationException("AWS_SQS_SEND messageBody exceeds 1 MiB");
        optionalLength(c, "messageGroupId", 128); optionalLength(c, "messageDeduplicationId",  dedupLimit(c));
    }

    private int dedupLimit(Map<String, Object> c) { return 128; }
    private void optionalLength(Map<String, Object> c, String key, int max) {
        if (c.get(key) != null && (String.valueOf(c.get(key)).isBlank() || String.valueOf(c.get(key)).length() > max))
            throw new PluginConfigurationException("AWS_SQS_SEND '" + key + "' length is invalid");
    }

    @Override public PluginResult execute(PluginContext context) {
        Map<String, Object> c = context.configuration(); validateConfiguration(c);
        try {
            var builder = SendMessageRequest.builder().queueUrl(String.valueOf(c.get("queueUrl"))).messageBody(String.valueOf(c.get("messageBody")));
            if (c.get("messageGroupId") != null) builder.messageGroupId(String.valueOf(c.get("messageGroupId")));
            if (c.get("messageDeduplicationId") != null) builder.messageDeduplicationId(String.valueOf(c.get("messageDeduplicationId")));
            else if (context.idempotencyKey() != null && String.valueOf(c.get("queueUrl")).endsWith(".fifo"))
                builder.messageDeduplicationId(context.idempotencyKey().replace(':', '-'));
            var response = sqs.sendMessage(builder.build());
            return PluginResult.builder().success(true).output("SQS message sent")
                    .metadata(Map.of("messageId", response.messageId(), "sequenceNumber", response.sequenceNumber() == null ? "" : response.sequenceNumber())).build();
        } catch (Exception e) { return SnsPublishPlugin.failure(e); }
    }
}
