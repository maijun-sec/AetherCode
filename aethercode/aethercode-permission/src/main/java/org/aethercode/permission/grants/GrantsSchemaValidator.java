package org.aethercode.permission.grants;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * T-203 / design.md §3.1.1: structural validation of a
 * {@code grants.json} payload against the schema declared there.
 *
 * <p>The TS spec uses zod at the boundary. In the Java build we use
 * Jackson for the parse + a hand-written validator for the
 * cross-field rules Jackson can't express. The point of having a
 * dedicated validator (rather than letting Jackson surface a raw
 * {@code JsonMappingException}) is that callers can show users a
 * <em>useful</em> list of problems — "missing field X", "scope
 * must be one of session|project|user" — instead of a deep path
 * like {@code $.grants[3].scope}.
 *
 * <p>The validator is permissive about extra fields (forward
 * compatibility) and strict about required ones. {@code expiresAt}
 * is optional; when present it must be a positive number.
 */
public final class GrantsSchemaValidator {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final Set<String> ALLOWED_SCOPES =
            Set.of("session", "project", "user");
    private static final Set<String> ALLOWED_DECISIONS =
            Set.of("allow", "deny");

    private GrantsSchemaValidator() {}

    /** Parse JSON text + validate against the schema. Returns the
     *  structured result so callers can branch on success vs
     *  failure. */
    public static ValidationResult validate(String json) {
        List<String> errors = new ArrayList<>();
        if (json == null || json.isBlank()) {
            errors.add("payload is empty");
            return new ValidationResult(false, List.of(), errors);
        }
        Object root;
        try {
            root = MAPPER.readValue(json, Object.class);
        } catch (Exception e) {
            errors.add("not valid JSON: " + e.getMessage());
            return new ValidationResult(false, List.of(), errors);
        }
        if (!(root instanceof Map<?, ?> map)) {
            errors.add("root must be a JSON object");
            return new ValidationResult(false, List.of(), errors);
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> obj = (Map<String, Object>) map;
        int schemaVersion = readInt(obj, "schemaVersion", errors);
        if (schemaVersion != GrantsFile.CURRENT_SCHEMA_VERSION) {
            errors.add("schemaVersion must be " + GrantsFile.CURRENT_SCHEMA_VERSION
                    + " (got " + schemaVersion + ")");
        }
        Object grantsRaw = obj.get("grants");
        if (grantsRaw == null) {
            errors.add("missing required field: grants");
            return new ValidationResult(false, List.of(), errors);
        }
        if (!(grantsRaw instanceof List<?> list)) {
            errors.add("grants must be an array");
            return new ValidationResult(false, List.of(), errors);
        }
        List<Grant> grants = new ArrayList<>(list.size());
        for (int i = 0; i < list.size(); i++) {
            Object item = list.get(i);
            if (!(item instanceof Map<?, ?> gmap)) {
                errors.add("grants[" + i + "] must be an object");
                continue;
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> g = (Map<String, Object>) gmap;
            Grant parsed = parseGrant(i, g, errors);
            if (parsed != null) grants.add(parsed);
        }
        boolean ok = errors.isEmpty();
        return new ValidationResult(ok, grants, List.copyOf(errors));
    }

    private static Grant parseGrant(int idx, Map<String, Object> g, List<String> errors) {
        String id = readString(g, "id", errors, "grants[" + idx + "].id");
        GrantScope scope = readEnum(g, "scope", ALLOWED_SCOPES, errors,
                "grants[" + idx + "].scope", GrantScope::fromWire);
        String scopeId = readString(g, "scopeId", errors, "grants[" + idx + "].scopeId");
        String category = readString(g, "category", errors, "grants[" + idx + "].category");
        GrantDecision decision = readEnum(g, "decision", ALLOWED_DECISIONS, errors,
                "grants[" + idx + "].decision", GrantDecision::fromWire);
        String reason = readString(g, "reason", errors, "grants[" + idx + "].reason");
        long createdAt = readLong(g, "createdAt", errors, "grants[" + idx + "].createdAt", false);
        Long expiresAt = readLongBoxed(g, "expiresAt", errors, "grants[" + idx + "].expiresAt");
        if (!errors.isEmpty()) return null;
        return new Grant(id, scope, scopeId, category, decision, reason, createdAt, expiresAt);
    }

    private static int readInt(Map<String, Object> obj, String key, List<String> errors) {
        Object v = obj.get(key);
        if (v == null) {
            errors.add("missing required field: " + key);
            return 0;
        }
        if (v instanceof Number n) return n.intValue();
        errors.add(key + " must be a number");
        return 0;
    }

    private static long readLong(Map<String, Object> obj, String key, List<String> errors, String label, boolean optional) {
        Object v = obj.get(key);
        if (v == null) {
            if (!optional) errors.add("missing required field: " + label);
            return 0L;
        }
        if (v instanceof Number n) return n.longValue();
        errors.add(label + " must be a number");
        return 0L;
    }

    private static Long readLongBoxed(Map<String, Object> obj, String key, List<String> errors, String label) {
        Object v = obj.get(key);
        if (v == null) return null;
        if (v instanceof Number n) return n.longValue();
        errors.add(label + " must be a number");
        return null;
    }

    private static String readString(Map<String, Object> obj, String key, List<String> errors, String label) {
        return readString(obj, key, errors, label, false);
    }

    private static String readString(Map<String, Object> obj, String key, List<String> errors, String label, boolean optional) {
        Object v = obj.get(key);
        if (v == null) {
            if (!optional) errors.add("missing required field: " + label);
            return null;
        }
        if (v instanceof String s) {
            if (s.isBlank() && !optional) {
                errors.add(label + " must not be blank");
            }
            return s;
        }
        errors.add(label + " must be a string");
        return null;
    }

    @FunctionalInterface
    private interface EnumParser<E extends Enum<E>> {
        E parse(String wire);
    }

    private static <E extends Enum<E>> E readEnum(
            Map<String, Object> obj, String key, Set<String> allowed, List<String> errors,
            String label, EnumParser<E> parser) {
        Object v = obj.get(key);
        if (v == null) {
            errors.add("missing required field: " + label);
            return null;
        }
        if (!(v instanceof String s)) {
            errors.add(label + " must be a string");
            return null;
        }
        if (!allowed.contains(s)) {
            errors.add(label + " must be one of " + allowed + " (got '" + s + "')");
            return null;
        }
        try {
            return parser.parse(s);
        } catch (IllegalArgumentException iae) {
            errors.add(label + ": " + iae.getMessage());
            return null;
        }
    }

    /** Outcome of a {@link #validate(String)} call. {@code grants}
     *  is populated even when {@code ok} is false (best-effort
     *  partial parse) so callers can recover a known-good subset
     *  and surface only the truly broken entries. */
    public record ValidationResult(
            boolean ok,
            List<Grant> grants,
            List<String> errors
    ) {
        public ValidationResult {
            grants = List.copyOf(grants);
            errors = List.copyOf(errors);
        }
    }
}
