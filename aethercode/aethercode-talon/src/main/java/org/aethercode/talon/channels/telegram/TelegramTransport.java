package org.aethercode.talon.channels.telegram;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Small JSON HTTP client for the Telegram Bot API.
 *
 * <p>Java-native port of the urllib-based transport in
 * {@code deepagents_talon.channels.telegram}. Uses the JDK
 * {@link HttpClient} and Jackson for JSON encoding. The transport is
 * a thin layer: callers pass a method name and a parameter map, and
 * receive the decoded {@code result} field on success or a
 * {@link TelegramChannelConfig.TransportException} on failure.</p>
 */
public class TelegramTransport {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final String apiBase;
    private final String token;
    private final Duration timeout;

    public TelegramTransport(String apiBase, String token, double timeoutSeconds) {
        this.apiBase = apiBase.replaceAll("/+$", "");
        this.token = token;
        this.timeout = Duration.ofMillis((long) (timeoutSeconds * 1000.0));
    }

    /**
     * Call a Bot API method and return the decoded response.
     */
    public Object call(String method, Map<String, Object> params) {
        String url = apiBase + "/bot" + token + "/" + method;
        try {
            byte[] body = MAPPER.writeValueAsBytes(params == null ? Map.of() : params);
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(timeout)
                    .header("content-type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                    .build();
            HttpResponse<String> response = HttpClient.newHttpClient()
                    .send(request, HttpResponse.BodyHandlers.ofString());
            return validateResponse(MAPPER.readValue(response.body(),
                    new TypeReference<Map<String, Object>>() {}));
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new TelegramChannelConfig.TransportException(
                    "Telegram Bot API request failed: " + method, e);
        }
    }

    /**
     * Call a Bot API method with one local file as multipart form data.
     */
    public Object upload(String method, String fileField, Path filePath,
                         Map<String, Object> params) {
        String boundary = "deepagents-talon-" + UUID.randomUUID().toString().replace("-", "");
        String url = apiBase + "/bot" + token + "/" + method;
        try {
            byte[] body = encodeMultipartForm(params == null ? Map.of() : params,
                    fileField, filePath, boundary);
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(timeout)
                    .header("content-type", "multipart/form-data; boundary=" + boundary)
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                    .build();
            HttpResponse<String> response = HttpClient.newHttpClient()
                    .send(request, HttpResponse.BodyHandlers.ofString());
            return validateResponse(MAPPER.readValue(response.body(),
                    new TypeReference<Map<String, Object>>() {}));
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new TelegramChannelConfig.TransportException(
                    "Telegram Bot API upload failed: " + method, e);
        }
    }

    @SuppressWarnings("unchecked")
    static Object validateResponse(Map<String, Object> payload) {
        Object ok = payload.getOrDefault("ok", Boolean.TRUE);
        if (Boolean.FALSE.equals(ok)) {
            Object description = payload.getOrDefault("description", "unknown error");
            Object retry = payload.get("retry_after");
            Double retryAfter = null;
            if (retry instanceof Number n) {
                retryAfter = n.doubleValue();
            }
            throw new TelegramChannelConfig.TransportException(
                    "Telegram Bot API error: " + description, retryAfter);
        }
        return payload.get("result");
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> extractResult(Object result) {
        if (!(result instanceof Map<?, ?> rawMap)) {
            throw new TelegramChannelConfig.TransportException(
                    "Telegram Bot API response missing result");
        }
        return (Map<String, Object>) rawMap;
    }

    @SuppressWarnings("unchecked")
    private static byte[] encodeMultipartForm(Map<String, Object> params,
                                              String fileField,
                                              Path filePath,
                                              String boundary) throws IOException {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        for (Map.Entry<String, Object> entry : params.entrySet()) {
            if (entry.getValue() == null) {
                continue;
            }
            out.write(("--" + boundary + "\r\n").getBytes(StandardCharsets.UTF_8));
            out.write(("Content-Disposition: form-data; name=\""
                    + escapeFormHeader(entry.getKey()) + "\"\r\n\r\n")
                    .getBytes(StandardCharsets.UTF_8));
            out.write(formFieldValue(entry.getValue()).getBytes(StandardCharsets.UTF_8));
            out.write("\r\n".getBytes(StandardCharsets.UTF_8));
        }
        String mime = probeContentType(filePath);
        out.write(("--" + boundary + "\r\n").getBytes(StandardCharsets.UTF_8));
        out.write(("Content-Disposition: form-data; name=\"" + escapeFormHeader(fileField)
                + "\"; filename=\"" + escapeFormHeader(filePath.getFileName().toString())
                + "\"\r\n").getBytes(StandardCharsets.UTF_8));
        out.write(("Content-Type: " + mime + "\r\n\r\n")
                .getBytes(StandardCharsets.UTF_8));
        out.write(Files.readAllBytes(filePath));
        out.write("\r\n".getBytes(StandardCharsets.UTF_8));
        out.write(("--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        return out.toByteArray();
    }

    private static String escapeFormHeader(String value) {
        return value.replaceAll("[\\x00-\\x1F\\x7F]", "")
                .replace("\\", "\\\\")
                .replace("\"", "\\\"");
    }

    private static String formFieldValue(Object value) {
        if (value instanceof Boolean b) {
            return b ? "true" : "false";
        }
        if (value instanceof Map<?, ?> || value instanceof Iterable<?>) {
            try {
                return MAPPER.writeValueAsString(value);
            } catch (IOException e) {
                return value.toString();
            }
        }
        return value.toString();
    }

    private static String probeContentType(Path path) {
        try {
            String probed = Files.probeContentType(path);
            if (probed != null) {
                return probed;
            }
        } catch (IOException ignored) {
            // best-effort
        }
        return "application/octet-stream";
    }

    // -----------------------------------------------------------------------
    // Test hooks
    // -----------------------------------------------------------------------

    /** Reusable (and overridable) HTTP client. */
    java.net.http.HttpClient newClient() {
        return java.net.http.HttpClient.newHttpClient();
    }

    /** Public so callers can share a single instance. */
    public String apiBase() {
        return apiBase;
    }
}
