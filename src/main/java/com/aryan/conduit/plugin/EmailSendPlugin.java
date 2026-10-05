package com.aryan.conduit.plugin;

import com.aryan.conduit.plugin.sdk.PluginConfigurationException;
import com.aryan.conduit.plugin.sdk.PluginContext;
import com.aryan.conduit.plugin.sdk.PluginMetadata;
import com.aryan.conduit.plugin.sdk.PluginResult;
import com.aryan.conduit.plugin.sdk.WorkflowPlugin;
import jakarta.mail.internet.MimeMessage;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Properties;

/** SMTP email sender. Credentials should be supplied using Conduit's $credential reference. */
@Component
public class EmailSendPlugin implements WorkflowPlugin {
    @Override public PluginMetadata metadata() {
        return new PluginMetadata("EMAIL_SEND", "Email Send", "Send an email through a configured SMTP server.", "1.0.0",
                Map.of("type", "object", "required", List.of("smtpHost", "from", "to", "subject", "body"), "properties", Map.of(
                        "smtpHost", Map.of("type", "string"), "smtpPort", Map.of("type", "integer", "default", 587),
                        "username", Map.of("type", "string"), "password", Map.of("type", "string", "writeOnly", true),
                        "from", Map.of("type", "string"), "to", Map.of("type", "array", "items", Map.of("type", "string")),
                        "subject", Map.of("type", "string"), "body", Map.of("type", "string"),
                        "html", Map.of("type", "boolean", "default", false),
                        "attachmentPaths", Map.of("type", "array", "items", Map.of("type", "string")))));
    }
    @Override public void validateConfiguration(Map<String, Object> c) {
        for (String key : List.of("smtpHost", "from", "subject", "body"))
            if (!(c.get(key) instanceof String s) || s.isBlank()) throw new PluginConfigurationException("EMAIL_SEND requires " + key);
        Object port = c.getOrDefault("smtpPort", 587);
        if (!(port instanceof Number n) || n.intValue() < 1 || n.intValue() > 65535) throw new PluginConfigurationException("EMAIL_SEND smtpPort must be between 1 and 65535");
        if (!(c.get("to") instanceof List<?> recipients) || recipients.isEmpty() || recipients.size() > 50 || recipients.stream().anyMatch(x -> !(x instanceof String s) || s.isBlank()))
            throw new PluginConfigurationException("EMAIL_SEND requires 1 to 50 recipient addresses");
        if (String.valueOf(c.get("subject")).length() > 998 || String.valueOf(c.get("body")).length() > 1_000_000)
            throw new PluginConfigurationException("EMAIL_SEND subject or body exceeds the allowed size");
        if (c.get("attachmentPaths") != null && (!(c.get("attachmentPaths") instanceof List<?> list) || list.size() > 10))
            throw new PluginConfigurationException("EMAIL_SEND attachmentPaths must contain at most 10 workspace paths");
    }
    @Override public PluginResult execute(PluginContext context) {
        Map<String, Object> c = context.configuration(); validateConfiguration(c);
        JavaMailSenderImpl sender = new JavaMailSenderImpl();
        sender.setHost(String.valueOf(c.get("smtpHost")));
        sender.setPort(((Number)c.getOrDefault("smtpPort", 587)).intValue());
        sender.setUsername(c.get("username") == null ? "" : String.valueOf(c.get("username")));
        sender.setPassword(c.get("password") == null ? "" : String.valueOf(c.get("password")));
        sender.setDefaultEncoding("UTF-8");
        Properties props = sender.getJavaMailProperties();
        props.put("mail.smtp.auth", !sender.getUsername().isBlank());
        props.put("mail.smtp.starttls.enable", "true");
        props.put("mail.smtp.starttls.required", "true");
        props.put("mail.smtp.ssl.checkserveridentity", "true");
        props.put("mail.smtp.connectiontimeout", "10000"); props.put("mail.smtp.timeout", "15000"); props.put("mail.smtp.writetimeout", "15000");
        try {
            MimeMessage message = sender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");
            helper.setFrom(String.valueOf(c.get("from")));
            helper.setTo(((List<?>)c.get("to")).stream().map(String::valueOf).toArray(String[]::new));
            helper.setSubject(String.valueOf(c.get("subject")));
            helper.setText(String.valueOf(c.get("body")), Boolean.TRUE.equals(c.get("html")));
            if (c.get("attachmentPaths") instanceof List<?> paths && !paths.isEmpty()) {
                if (context.workspace() == null) throw new PluginConfigurationException("EMAIL_SEND attachments require the managed workspace");
                for (Object value : paths) {
                    String path = String.valueOf(value);
                    if (path.isBlank() || path.startsWith("/") || path.contains("\\") || path.contains("..")) throw new PluginConfigurationException("EMAIL_SEND attachment path must be workspace-relative");
                    helper.addAttachment(path.substring(path.lastIndexOf('/') + 1), () -> context.workspace().openRead(path));
                }
            }
            sender.send(message);
            return PluginResult.builder().success(true).output("Email sent").metadata(Map.of("operation", "SEND", "recipientCount", ((List<?>)c.get("to")).size())).build();
        } catch (PluginConfigurationException e) { throw e; }
        catch (Exception e) { return PluginResult.builder().success(false).output("Email delivery failed: " + e.getClass().getSimpleName()).metadata(Map.of("operation", "SEND")).build(); }
    }
}
