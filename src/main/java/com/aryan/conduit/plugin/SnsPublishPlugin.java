package com.aryan.conduit.plugin;

import com.aryan.conduit.plugin.sdk.PluginConfigurationException;
import com.aryan.conduit.plugin.sdk.PluginContext;
import com.aryan.conduit.plugin.sdk.PluginMetadata;
import com.aryan.conduit.plugin.sdk.PluginResult;
import com.aryan.conduit.plugin.sdk.WorkflowPlugin;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.sns.SnsClient;
import software.amazon.awssdk.services.sns.model.PublishRequest;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

@Component
public class SnsPublishPlugin implements WorkflowPlugin {
    private final SnsClient sns;
    public SnsPublishPlugin(SnsClient sns) { this.sns = sns; }

    @Override public PluginMetadata metadata() {
        return new PluginMetadata("AWS_SNS_PUBLISH", "AWS SNS Publish", "Publishes a bounded message to an SNS topic using the AWS credential chain.", "1.0.0",
                Map.of("type", "object", "required", List.of("topicArn", "message"), "properties", Map.of(
                        "topicArn", Map.of("type", "string"), "message", Map.of("type", "string"), "subject", Map.of("type", "string"))));
    }

    @Override public void validateConfiguration(Map<String, Object> c) {
        String arn = required(c, "topicArn");
        if (!arn.matches("arn:(aws|aws-us-gov|aws-cn):sns:[a-z0-9-]+:[0-9]{12}:[A-Za-z0-9_.-]{1,256}"))
            throw new PluginConfigurationException("AWS_SNS_PUBLISH topicArn must be a valid SNS topic ARN");
        String message = required(c, "message");
        if (message.getBytes(StandardCharsets.UTF_8).length > 256 * 1024)
            throw new PluginConfigurationException("AWS_SNS_PUBLISH message exceeds 256 KiB");
        if (c.get("subject") != null && String.valueOf(c.get("subject")).length() > 100)
            throw new PluginConfigurationException("AWS_SNS_PUBLISH subject exceeds 100 characters");
    }

    @Override public PluginResult execute(PluginContext context) {
        Map<String, Object> c = context.configuration();
        validateConfiguration(c);
        try {
            var builder = PublishRequest.builder().topicArn(String.valueOf(c.get("topicArn"))).message(String.valueOf(c.get("message")));
            if (c.get("subject") != null) builder.subject(String.valueOf(c.get("subject")));
            var response = sns.publish(builder.build());
            return PluginResult.builder().success(true).output("SNS message published")
                    .metadata(Map.of("messageId", response.messageId(), "sequenceNumber", response.sequenceNumber() == null ? "" : response.sequenceNumber())).build();
        } catch (Exception e) { return failure(e); }
    }

    static String required(Map<String, Object> c, String key) {
        Object value = c == null ? null : c.get(key);
        if (!(value instanceof String text) || text.isBlank()) throw new PluginConfigurationException("AWS configuration requires '" + key + "'");
        return text.trim();
    }
    static PluginResult failure(Exception e) {
        String code = e instanceof software.amazon.awssdk.awscore.exception.AwsServiceException service && service.awsErrorDetails() != null
                ? service.awsErrorDetails().errorCode() : e.getClass().getSimpleName();
        return PluginResult.builder().success(false).output("AWS request failed (" + code + ")").build();
    }
}
