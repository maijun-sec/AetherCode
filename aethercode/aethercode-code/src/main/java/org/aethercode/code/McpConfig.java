package org.aethercode.code;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Validation and environment-variable expansion for MCP server config.
 *
 * <p>Resolves {@code ${VAR}} and {@code ${VAR:-default}} references in the
 * supported configuration fields ({@code command}, {@code url}, {@code args},
 * {@code env}, {@code headers}) and validates their types. A
 * {@code ${VAR:-default}} reference falls back to {@code default} when
 * {@code VAR} is unset <em>or</em> empty (POSIX {@code :-} semantics).
 * Java-native port of the Python {@code deepagents_code.mcp_config} module.</p>
 */
public final class McpConfig {
    private McpConfig() {}

    private static final Pattern ENV_REF_RE =
            Pattern.compile("\\$\\{([A-Za-z_][A-Za-z0-9_]*)(?::-([^{}]*))?\\}");

    private static final Pattern ENV_BRACE_RE = Pattern.compile("\\$\\{");

    /**
     * Expand {@code ${VAR}} / {@code ${VAR:-default}} references in one
     * config string. A bare {@code $VAR} (no braces) and a literal {@code $}
     * pass through untouched; only the braced forms expand.
     *
     * @param value raw configuration string
     * @param field fully qualified field path for error messages
     * @return the interpolated string
     * @throws IllegalStateException if a required env var is unset, or the
     *         string contains a malformed {@code ${...}} reference
     */
    public static String interpolateEnv(String value, String field) {
        if (value == null) {
            return null;
        }
        Map<String, String> env = System.getenv();
        // Reject any `${` that isn't the start of a well-formed reference. The
        // check is against the raw `value` (not the substituted result).
        List<int[]> refSpans = new ArrayList<>();
        Matcher m = ENV_REF_RE.matcher(value);
        while (m.find()) {
            refSpans.add(new int[]{m.start(), m.end()});
        }
        Matcher brace = ENV_BRACE_RE.matcher(value);
        while (brace.find()) {
            boolean found = false;
            for (int[] span : refSpans) {
                if (span[0] <= brace.start() && brace.start() < span[1]) {
                    found = true;
                    break;
                }
            }
            if (!found) {
                throw new IllegalStateException(field + " contains a malformed '${...}' reference. "
                        + "Use '${VAR}' or '${VAR:-default}'.");
            }
        }
        return ENV_REF_RE.matcher(value).replaceAll(match -> {
            String name = match.group(1);
            String def = match.group(2);
            String resolved = env.get(name);
            if (resolved != null && !resolved.isEmpty()) {
                return Matcher.quoteReplacement(resolved);
            }
            if (def != null) {
                return Matcher.quoteReplacement(def);
            }
            if (resolved != null) {
                return Matcher.quoteReplacement(resolved);
            }
            throw new IllegalStateException(field + " references unset env var " + name
                    + ". Set " + name + " in the environment or provide a default.");
        });
    }

    /**
     * Validate and interpolate one string field.
     */
    public static String resolveString(Object value, String field) {
        if (!(value instanceof String s)) {
            throw new IllegalStateException(field + " must be a string, got "
                    + (value == null ? "null" : value.getClass().getSimpleName()));
        }
        return interpolateEnv(s, field);
    }

    /**
     * Validate and interpolate string values in a mapping field.
     */
    public static Map<String, String> resolveMappingValues(Map<String, Object> values, String field) {
        Map<String, String> out = new LinkedHashMap<>();
        if (values == null) return out;
        for (Map.Entry<String, Object> e : values.entrySet()) {
            out.put(e.getKey(), resolveString(e.getValue(), field + "." + e.getKey()));
        }
        return out;
    }

    /**
     * Resolve {@code ${VAR}} references in one MCP server's supported fields.
     * Interpolates the {@code command}, {@code url}, {@code args}, {@code env},
     * and {@code headers} fields. Every other field is copied through verbatim.
     */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> resolveMcpServerEnv(String serverName, Map<String, Object> serverConfig) {
        if (serverConfig == null) {
            return new HashMap<>();
        }
        Map<String, Object> resolved = new LinkedHashMap<>(serverConfig);
        String prefix = "mcpServers." + serverName;
        for (String name : new String[]{"command", "url"}) {
            if (resolved.containsKey(name)) {
                resolved.put(name, resolveString(resolved.get(name), prefix + "." + name));
            }
        }
        if (resolved.containsKey("args")) {
            Object args = resolved.get("args");
            if (!(args instanceof List<?> list)) {
                throw new IllegalStateException(prefix + ".args must be a list, got "
                        + (args == null ? "null" : args.getClass().getSimpleName()));
            }
            List<String> out = new ArrayList<>();
            int i = 0;
            for (Object v : list) {
                out.add(resolveString(v, prefix + ".args[" + i + "]"));
                i++;
            }
            resolved.put("args", out);
        }
        for (String name : new String[]{"env", "headers"}) {
            if (!resolved.containsKey(name)) continue;
            Object values = resolved.get(name);
            if (!(values instanceof Map<?, ?> map)) {
                throw new IllegalStateException(prefix + "." + name + " must be a dictionary, got "
                        + (values == null ? "null" : values.getClass().getSimpleName()));
            }
            Map<String, Object> cast = (Map<String, Object>) map;
            resolved.put(name, resolveMappingValues(cast, prefix + "." + name));
        }
        return resolved;
    }
}
