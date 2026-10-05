package com.aryan.conduit.plugin;

import com.aryan.conduit.plugin.sdk.PluginConfigurationException;
import com.aryan.conduit.plugin.sdk.PluginContext;
import com.aryan.conduit.plugin.sdk.PluginMetadata;
import com.aryan.conduit.plugin.sdk.PluginResult;
import com.aryan.conduit.plugin.sdk.WorkflowPlugin;
import com.aryan.conduit.plugin.security.SsrfAddressPolicy;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.sshd.client.SshClient;
import org.apache.sshd.client.session.ClientSession;
import org.apache.sshd.sftp.client.SftpClient;
import org.apache.sshd.sftp.client.SftpClientFactory;
import org.springframework.stereotype.Component;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Bounded SFTP directory listing and workspace file transfer with pinned SSH host keys. */
@Component
public class SftpPlugin implements WorkflowPlugin {
    private static final long DEFAULT_MAX_BYTES = 25L * 1024 * 1024;
    private static final long HARD_MAX_BYTES = 100L * 1024 * 1024;
    private static final int MAX_LIST_ENTRIES = 500;
    private final ObjectMapper objectMapper;

    public SftpPlugin(ObjectMapper objectMapper) { this.objectMapper = objectMapper; }

    @Override public PluginMetadata metadata() {
        return new PluginMetadata("SFTP", "SFTP File Transfer",
                "List a remote directory, download a remote file to the managed workspace, or upload a workspace file over verified SFTP.",
                "1.0.0", Map.of("type", "object", "required", List.of("operation", "host", "username", "password", "hostKeyFingerprint"),
                "properties", Map.ofEntries(
                        Map.entry("operation", Map.of("type", "string", "enum", List.of("LIST", "DOWNLOAD", "UPLOAD"))),
                        Map.entry("host", Map.of("type", "string", "maxLength", 253)),
                        Map.entry("port", Map.of("type", "integer", "default", 22, "minimum", 1, "maximum", 65535)),
                        Map.entry("username", Map.of("type", "string", "maxLength", 128)),
                        Map.entry("password", Map.of("type", "string", "description", "Use a workflow credential reference, for example {\"$credential\":\"sftpPassword\"}")),
                        Map.entry("hostKeyFingerprint", Map.of("type", "string", "description", "Required OpenSSH SHA256 fingerprint, e.g. SHA256:base64digest")),
                        Map.entry("remotePath", Map.of("type", "string", "maxLength", 4096)),
                        Map.entry("localPath", Map.of("type", "string", "description", "Managed workspace-relative file path")),
                        Map.entry("maxBytes", Map.of("type", "integer", "default", DEFAULT_MAX_BYTES, "maximum", HARD_MAX_BYTES)),
                        Map.entry("maxEntries", Map.of("type", "integer", "default", 100, "maximum", MAX_LIST_ENTRIES)),
                        Map.entry("timeoutSeconds", Map.of("type", "integer", "default", 15, "minimum", 1, "maximum", 60)),
                        Map.entry("overwrite", Map.of("type", "boolean", "default", false)),
                        Map.entry("allowPrivateNetwork", Map.of("type", "boolean", "default", false,
                                "description", "Explicit opt-in for approved private SFTP servers"))
                )));
    }

    @Override public void validateConfiguration(Map<String, Object> c) {
        if (c == null) throw new PluginConfigurationException("SFTP configuration is required");
        String operation = required(c, "operation").toUpperCase(Locale.ROOT);
        if (!List.of("LIST", "DOWNLOAD", "UPLOAD").contains(operation))
            throw new PluginConfigurationException("SFTP operation must be LIST, DOWNLOAD, or UPLOAD");
        String host = required(c, "host");
        if (host.length() > 253 || host.contains("/") || host.contains("\\") || host.contains("@"))
            throw new PluginConfigurationException("SFTP host is invalid");
        required(c, "username");
        required(c, "password"); // resolved by the workflow credential service immediately before execution
        validateFingerprint(required(c, "hostKeyFingerprint"));
        int port = integer(c, "port", 22);
        if (port < 1 || port > 65535) throw new PluginConfigurationException("SFTP port must be between 1 and 65535");
        int timeout = integer(c, "timeoutSeconds", 15);
        if (timeout < 1 || timeout > 60) throw new PluginConfigurationException("SFTP timeoutSeconds must be between 1 and 60");
        long maxBytes = longValue(c, "maxBytes", DEFAULT_MAX_BYTES);
        if (maxBytes < 1 || maxBytes > HARD_MAX_BYTES) throw new PluginConfigurationException("SFTP maxBytes must be between 1 and " + HARD_MAX_BYTES);
        int maxEntries = integer(c, "maxEntries", 100);
        if (maxEntries < 1 || maxEntries > MAX_LIST_ENTRIES) throw new PluginConfigurationException("SFTP maxEntries must be between 1 and " + MAX_LIST_ENTRIES);
        if (c.get("allowPrivateNetwork") != null && !(c.get("allowPrivateNetwork") instanceof Boolean))
            throw new PluginConfigurationException("SFTP allowPrivateNetwork must be boolean");
        if (c.get("overwrite") != null && !(c.get("overwrite") instanceof Boolean))
            throw new PluginConfigurationException("SFTP overwrite must be boolean");
        if (operation.equals("LIST")) validateRemotePath(required(c, "remotePath"));
        if (operation.equals("DOWNLOAD") || operation.equals("UPLOAD")) {
            validateRemotePath(required(c, "remotePath"));
            required(c, "localPath");
        }
        try { SsrfAddressPolicy.validateHost(host, Boolean.TRUE.equals(c.get("allowPrivateNetwork"))); }
        catch (PluginConfigurationException e) { throw new PluginConfigurationException("SFTP host rejected by network policy: " + e.getMessage(), e); }
    }

    @Override public PluginResult execute(PluginContext context) throws Exception {
        Map<String, Object> c = context.configuration();
        validateConfiguration(c);
        if (context.workspace() == null) throw new PluginConfigurationException("SFTP requires the managed execution workspace");
        String operation = String.valueOf(c.get("operation")).toUpperCase(Locale.ROOT);
        String host = String.valueOf(c.get("host"));
        int timeoutMs = integer(c, "timeoutSeconds", 15) * 1000;
        try (SshClient ssh = SshClient.setUpDefaultClient()) {
            ssh.setServerKeyVerifier((session, address, key) -> fingerprintMatches(key, String.valueOf(c.get("hostKeyFingerprint"))));
            ssh.start();
            try (ClientSession session = ssh.connect(String.valueOf(c.get("username")), host, integer(c, "port", 22))
                    .verify(timeoutMs).getSession()) {
                session.addPasswordIdentity(String.valueOf(c.get("password")));
                session.auth().verify(timeoutMs);
                try (SftpClient sftp = SftpClientFactory.instance().createSftpClient(session)) {
                    return switch (operation) {
                        case "LIST" -> list(sftp, c, host);
                        case "DOWNLOAD" -> download(sftp, context, c, host);
                        case "UPLOAD" -> upload(sftp, context, c, host);
                        default -> throw new PluginConfigurationException("Unsupported SFTP operation");
                    };
                }
            }
        } catch (PluginConfigurationException e) { throw e; }
        catch (Exception e) {
            String message = e.getMessage() == null ? "SFTP operation failed" : e.getMessage();
            if (message.toLowerCase(Locale.ROOT).contains("server key"))
                throw new IllegalStateException("SFTP server host key did not match the configured SHA256 fingerprint", e);
            throw new IllegalStateException("SFTP " + operation + " failed for " + host + ": " + e.getClass().getSimpleName(), e);
        }
    }

    private PluginResult list(SftpClient sftp, Map<String, Object> c, String host) throws IOException {
        String remotePath = String.valueOf(c.get("remotePath"));
        int limit = integer(c, "maxEntries", 100);
        List<Map<String, Object>> entries = new ArrayList<>();
        boolean truncated = false;
        for (SftpClient.DirEntry entry : sftp.readDir(remotePath)) {
            if (".".equals(entry.getFilename()) || "..".equals(entry.getFilename())) continue;
            if (entries.size() == limit) { truncated = true; break; }
            SftpClient.Attributes attributes = entry.getAttributes();
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("name", entry.getFilename());
            item.put("directory", attributes.isDirectory());
            item.put("sizeBytes", attributes.getSize());
            if (attributes.getModifyTime() != null) item.put("modifiedTime", attributes.getModifyTime().toString());
            entries.add(item);
        }
        return PluginResult.builder().success(true).output(objectMapper.writeValueAsString(entries))
                .metadata(Map.of("operation", "LIST", "host", host, "remotePath", remotePath, "entryCount", entries.size(), "truncated", truncated)).build();
    }

    private PluginResult download(SftpClient sftp, PluginContext context, Map<String, Object> c, String host) throws IOException {
        String remotePath = String.valueOf(c.get("remotePath"));
        long maxBytes = longValue(c, "maxBytes", DEFAULT_MAX_BYTES);
        SftpClient.Attributes attributes = sftp.stat(remotePath);
        if (attributes.getSize() > maxBytes) throw new PluginConfigurationException("SFTP remote file exceeds configured maxBytes");
        try (InputStream input = new LimitedInputStream(sftp.read(remotePath), maxBytes)) {
            long bytes = context.workspace().writeAtomic(String.valueOf(c.get("localPath")), input,
                    Boolean.TRUE.equals(c.get("overwrite")), true);
            return PluginResult.builder().success(true).output("Downloaded " + bytes + " bytes to the managed workspace")
                    .metadata(Map.of("operation", "DOWNLOAD", "host", host, "remotePath", remotePath,
                            "localPath", c.get("localPath"), "sizeBytes", bytes)).build();
        }
    }

    private PluginResult upload(SftpClient sftp, PluginContext context, Map<String, Object> c, String host) throws IOException {
        String remotePath = String.valueOf(c.get("remotePath"));
        long maxBytes = longValue(c, "maxBytes", DEFAULT_MAX_BYTES);
        if (!Boolean.TRUE.equals(c.get("overwrite"))) {
            try { sftp.stat(remotePath); throw new PluginConfigurationException("SFTP remote file already exists; set overwrite=true to replace it"); }
            catch (org.apache.sshd.sftp.common.SftpException e) {
                if (e.getStatus() != org.apache.sshd.sftp.common.SftpConstants.SSH_FX_NO_SUCH_FILE) throw e;
            }
        }
        String tempPath = remotePath + ".conduit-" + shortId(context.idempotencyKey()) + ".part";
        long bytes = 0;
        boolean renamed = false;
        try (InputStream input = context.workspace().openRead(String.valueOf(c.get("localPath")));
             java.io.OutputStream output = sftp.write(tempPath)) {
            byte[] buffer = new byte[32 * 1024];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                if (read == 0) continue;
                bytes += read;
                if (bytes > maxBytes) throw new PluginConfigurationException("SFTP local file exceeds configured maxBytes");
                output.write(buffer, 0, read);
            }
        }
        try {
            if (Boolean.TRUE.equals(c.get("overwrite"))) {
                try { sftp.remove(remotePath); } catch (org.apache.sshd.sftp.common.SftpException e) {
                    if (e.getStatus() != org.apache.sshd.sftp.common.SftpConstants.SSH_FX_NO_SUCH_FILE) throw e;
                }
            }
            sftp.rename(tempPath, remotePath);
            renamed = true;
        } finally {
            if (!renamed) try { sftp.remove(tempPath); } catch (IOException ignored) { }
        }
        return PluginResult.builder().success(true).output("Uploaded " + bytes + " bytes")
                .metadata(Map.of("operation", "UPLOAD", "host", host, "remotePath", remotePath,
                        "localPath", c.get("localPath"), "sizeBytes", bytes)).build();
    }

    private static boolean fingerprintMatches(PublicKey key, String expected) {
        try {
            String actual = "SHA256:" + Base64.getEncoder().withoutPadding()
                    .encodeToString(MessageDigest.getInstance("SHA-256").digest(key.getEncoded()));
            return MessageDigest.isEqual(actual.getBytes(StandardCharsets.US_ASCII), expected.getBytes(StandardCharsets.US_ASCII));
        } catch (Exception e) { return false; }
    }

    private static void validateFingerprint(String fingerprint) {
        if (!fingerprint.matches("SHA256:[A-Za-z0-9+/]{43}"))
            throw new PluginConfigurationException("SFTP hostKeyFingerprint must be an OpenSSH SHA256 fingerprint");
    }
    private static void validateRemotePath(String path) {
        if (path == null || path.isBlank() || path.length() > 4096 || path.indexOf('\0') >= 0
                || path.codePoints().anyMatch(Character::isISOControl))
            throw new PluginConfigurationException("SFTP remotePath must be a valid path up to 4096 characters");
    }
    private static String required(Map<String, Object> c, String name) {
        Object value = c.get(name);
        if (!(value instanceof String text) || text.isBlank()) throw new PluginConfigurationException("SFTP requires a non-empty '" + name + "'");
        return text.trim();
    }
    private static int integer(Map<String, Object> c, String name, int fallback) {
        Object value = c.get(name);
        if (value == null) return fallback;
        if (!(value instanceof Number number)) throw new PluginConfigurationException("SFTP '" + name + "' must be an integer");
        return number.intValue();
    }
    private static long longValue(Map<String, Object> c, String name, long fallback) {
        Object value = c.get(name);
        if (value == null) return fallback;
        if (!(value instanceof Number number)) throw new PluginConfigurationException("SFTP '" + name + "' must be an integer");
        return number.longValue();
    }
    private static String shortId(String idempotencyKey) {
        try { return (idempotencyKey == null ? java.util.UUID.randomUUID().toString() : idempotencyKey).replaceAll("[^A-Za-z0-9_-]", "").substring(0, 16); }
        catch (RuntimeException e) { return java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 16); }
    }

    private static final class LimitedInputStream extends FilterInputStream {
        private final long limit;
        private long count;
        private LimitedInputStream(InputStream input, long limit) { super(input); this.limit = limit; }
        @Override public int read() throws IOException {
            int value = super.read();
            if (value >= 0 && ++count > limit) throw new IOException("SFTP download exceeded configured maxBytes");
            return value;
        }
        @Override public int read(byte[] bytes, int offset, int length) throws IOException {
            int read = super.read(bytes, offset, (int) Math.min(length, Math.max(1, limit - count + 1)));
            if (read > 0 && (count += read) > limit) throw new IOException("SFTP download exceeded configured maxBytes");
            return read;
        }
    }
}
