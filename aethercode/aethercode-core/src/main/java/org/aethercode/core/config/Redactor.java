package org.aethercode.core.config;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * redact sensitive values in a settings map (or JSON-like
 * string). Used before printing the user's config to logs, the TUI,
 * or the {@code /doctor} command. The redactor replaces values for
 * keys whose name matches a sensitive pattern with {@code ***}.
 */
public final class Redactor {

    public static final String PLACEHOLDER = "***";

    /** default sensitive key patterns (case-insensitive substring). */
    public static final List<String> DEFAULT_SENSITIVE_KEYS = List.of(
            "password", "secret", "token", "api_key", "apikey", "credential",
            "private_key", "auth", "session_key", "passphrase"
    );

    private static final Pattern QUOTED_VALUE = Pattern.compile(
            "(\"[^\"]*\"\\s*:\\s*)\"([^\"]*)\"");

    private final Set<String> sensitiveKeys;

    public Redactor() {
        this(DEFAULT_SENSITIVE_KEYS);
    }

    public Redactor(List<String> sensitiveKeys) {
        this.sensitiveKeys = Set.copyOf(sensitiveKeys);
    }

    /** walk a map and redact sensitive values. */
    public Map<String, Object> redactMap(Map<String, Object> input) {
        if (input == null) return Map.of();
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : input.entrySet()) {
            String key = e.getKey();
            Object value = e.getValue();
            if (isSensitive(key)) {
                out.put(key, PLACEHOLDER);
            } else if (value instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> nested = (Map<String, Object>) value;
                out.put(key, redactMap(nested));
            } else if (value instanceof List) {
                out.put(key, redactList((List<?>) value));
            } else {
                out.put(key, value);
            }
        }
        return out;
    }

    public List<Object> redactList(List<?> input) {
        if (input == null) return List.of();
        java.util.ArrayList<Object> out = new java.util.ArrayList<>();
        for (Object item : input) {
            if (item instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> m = (Map<String, Object>) item;
                out.add(redactMap(m));
            } else if (item instanceof List) {
                out.add(redactList((List<?>) item));
            } else {
                out.add(item);
            }
        }
        return out;
    }

    /** redact sensitive values in a JSON-ish string. Best-effort — does not parse JSON. */
    public String redactString(String input) {
        if (input == null) return null;
        Matcher m = QUOTED_VALUE.matcher(input);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String fullMatch = m.group(0);
            String keyPart = m.group(1);
            String value = m.group(2);
            // Extract the key name (between the first pair of quotes in keyPart)
            String keyName = extractKeyName(keyPart);
            String replacement;
            if (isSensitive(keyName)) {
                replacement = keyPart + "\"" + PLACEHOLDER + "\"";
            } else {
                replacement = fullMatch;
            }
            m.appendReplacement(out, Matcher.quoteReplacement(replacement));
        }
        m.appendTail(out);
        return out.toString();
    }

    private static String extractKeyName(String keyPart) {
        int firstQuote = keyPart.indexOf('"');
        int lastQuote = keyPart.lastIndexOf('"');
        if (firstQuote < 0 || lastQuote <= firstQuote) return "";
        return keyPart.substring(firstQuote + 1, lastQuote);
    }

    public boolean isSensitive(String key) {
        if (key == null) return false;
        String lower = key.toLowerCase(Locale.ROOT);
        for (String pattern : sensitiveKeys) {
            if (lower.contains(pattern.toLowerCase(Locale.ROOT))) return true;
        }
        return false;
    }

    /** get a copy of the sensitive-key set. */
    public Set<String> sensitiveKeys() { return sensitiveKeys; }

    public static Redactor withCustomKeys(String... keys) {
        java.util.ArrayList<String> all = new java.util.ArrayList<>(DEFAULT_SENSITIVE_KEYS);
        for (String k : keys) all.add(k);
        return new Redactor(all);
    }
}
