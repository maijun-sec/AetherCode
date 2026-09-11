package org.aethercode.talon;

import org.slf4j.Logger;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Observability helpers for Talon runtime processes.
 *
 * <p>Java-native port of {@code deepagents_talon.observability}.</p>
 */
public final class Observability {

    private Observability() {}

    /** Environment values that activate tracing. */
    public static final Set<String> TRUTHY_ENV_VALUES = Set.of("1", "true", "yes", "on");
    /** Default project name when none is configured. */
    public static final String DEFAULT_LANGSMITH_PROJECT = "deepagents-talon";
    /** Replacement text for redacted log values. */
    public static final String REDACTED_LOG_VALUE = "[redacted]";

    private static final List<String> SECRET_KEY_MARKERS = List.of(
            "api_key", "apikey", "authorization", "bearer", "credential",
            "cookie", "oauth", "password", "secret", "session", "token");
    private static final Set<String> PII_KEYS = Set.of(
            "conversation_id", "message_id", "sender_id");
    private static final Pattern BEARER_RE = Pattern.compile(
            "(?i)\\bbearer\\s+[A-Za-z0-9._~+/-]+=*");
    private static final Pattern SECRET_ASSIGNMENT_RE = Pattern.compile(
            "(?i)\\b(api[_-]?key|authorization|password|secret|token)=([^&\\s]+)");

    /**
     * Return whether LangSmith tracing is configured for this process.
     */
    public static boolean langsmithTracingEnabled(Map<String, String> env) {
        String tracing = env.getOrDefault("LANGSMITH_TRACING", "");
        return TRUTHY_ENV_VALUES.contains(tracing.toLowerCase())
                && env.get("LANGSMITH_API_KEY") != null
                && !env.get("LANGSMITH_API_KEY").isEmpty();
    }

    /**
     * Emit one structured JSON event through the standard logger.
     */
    public static void logEvent(Logger logger, String event,
                                Map<String, Object> fields) {
        java.util.Map<String, Object> payload = new java.util.LinkedHashMap<>();
        payload.put("event", event);
        if (fields != null) {
            payload.putAll(redactMapping(fields));
        }
        String json = JsonUtils.toJson(payload);
        logger.info("talon_event {}", json);
    }

    /**
     * Return a log-safe copy of {@code value}.
     *
     * @return a JSON-compatible value with obvious secrets and URL query
     *         data removed.
     */
    public static Object redactForLogging(Object value) {
        if (value instanceof Map<?, ?> m) {
            java.util.Map<String, Object> out = new java.util.LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : m.entrySet()) {
                String key = String.valueOf(entry.getKey());
                if (isSecretKey(key)) {
                    out.put(key, REDACTED_LOG_VALUE);
                } else {
                    out.put(key, redactForLogging(entry.getValue()));
                }
            }
            return out;
        }
        if (value instanceof List<?> list) {
            java.util.List<Object> out = new ArrayList<>(list.size());
            for (Object item : list) {
                out.add(redactForLogging(item));
            }
            return out;
        }
        if (value instanceof String s) {
            return redactString(s);
        }
        return value;
    }

    /**
     * Return a stable non-secret reference for a sensitive identifier.
     */
    public static String stableLogRef(String value) {
        if (value == null) {
            return "";
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash).substring(0, 12);
        } catch (NoSuchAlgorithmException e) {
            return Integer.toHexString(value.hashCode());
        }
    }

    // -----------------------------------------------------------------------
    // Internals
    // -----------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private static java.util.Map<String, Object> redactMapping(
            java.util.Map<String, Object> value) {
        return (java.util.Map<String, Object>) redactForLogging(value);
    }

    private static boolean isSecretKey(String key) {
        String normalized = key.toLowerCase().replace('-', '_');
        if (PII_KEYS.contains(normalized)) {
            return true;
        }
        for (String marker : SECRET_KEY_MARKERS) {
            if (normalized.contains(marker)) {
                return true;
            }
        }
        return false;
    }

    private static String redactString(String value) {
        String text = sanitizeUrl(value);
        text = BEARER_RE.matcher(text).replaceAll("Bearer [redacted]");
        text = SECRET_ASSIGNMENT_RE.matcher(text).replaceAll("$1=[redacted]");
        return text;
    }

    private static String sanitizeUrl(String value) {
        URI uri;
        try {
            uri = URI.create(value);
        } catch (IllegalArgumentException ex) {
            return value;
        }
        String scheme = uri.getScheme();
        if (scheme == null
                || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https")
                || scheme.equalsIgnoreCase("ws") || scheme.equalsIgnoreCase("wss"))
                || uri.getHost() == null) {
            return value;
        }
        int port = uri.getPort();
        String hostPort = port < 0 ? uri.getHost() : uri.getHost() + ":" + port;
        return scheme + "://" + hostPort + (uri.getPath() == null ? "" : uri.getPath());
    }
}
