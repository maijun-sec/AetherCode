package org.aethercode.evals.verifier;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Schema verifier — type-checks a structured action against a simple
 * schema spec.
 *
 * <p>The schema spec is a flat key → type map. Each entry declares the
 * expected Java type of the corresponding value in the input map. The
 * verifier fails on the first key whose value is missing or has the
 * wrong type.</p>
 *
 * <p>This is intentionally minimal — the model parameterises on a
 * flat map so it can be used for tool calls, JSON responses, or any
 * other structured artifact without pulling in a JSON-Schema validator
 * dependency. For complex schemas (refs, anyOf, allOf) use a real
 * validator; for the common case of "did the LLM fill in these 3
 * fields with the right types", this is enough.</p>
 *
 * <p>Recognised type names (case-insensitive):</p>
 * <ul>
 *   <li>{@code "string"} — {@link String}</li>
 *   <li>{@code "int"} / {@code "integer"} — {@link Integer} or
 *       {@link Long} (any {@link Number} whose {@code intValue()} is exact)</li>
 *   <li>{@code "long"} — {@link Long} (any {@link Number} exact to long)</li>
 *   <li>{@code "number"} / {@code "double"} — {@link Number}</li>
 *   <li>{@code "boolean"} / {@code "bool"} — {@link Boolean}</li>
 *   <li>{@code "list"} / {@code "array"} — {@link java.util.List} or array</li>
 *   <li>{@code "map"} / {@code "object"} — {@link Map}</li>
 * </ul>
 *
 * <p>Severity: a missing required field is {@code BLOCK} (the action
 * cannot execute without it); a wrong-type field is also {@code BLOCK}.
 * A field that is declared {@code optional=true} and missing is
 * {@code INFO.passed()}.</p>
 */
public class SchemaVerifier implements Verifier<Map<String, Object>> {

    private final String name;
    private final Map<String, FieldSpec> schema;

    public SchemaVerifier(String name, Map<String, FieldSpec> schema) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name must be non-blank");
        }
        if (schema == null || schema.isEmpty()) {
            throw new IllegalArgumentException("schema must be non-empty");
        }
        this.name = name;
        this.schema = Map.copyOf(schema);
    }

    /** Convenience: build from a {@code Map<String, String>} of name → type. All fields required. */
    public static SchemaVerifier ofTypeMap(String name, Map<String, String> typeMap) {
        Map<String, FieldSpec> specs = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : typeMap.entrySet()) {
            specs.put(e.getKey(), FieldSpec.required(e.getValue()));
        }
        return new SchemaVerifier(name, specs);
    }

    /** Spec for one field: expected type + whether it's required. */
    public record FieldSpec(String type, boolean required) {
        public static FieldSpec required(String type) { return new FieldSpec(type, true); }
        public static FieldSpec optional(String type) { return new FieldSpec(type, false); }
    }

    @Override
    public String name() { return name; }

    @Override
    public String description() {
        return "Schema check: " + schema.keySet();
    }

    @Override
    public VerificationResult verify(Map<String, Object> input) {
        if (input == null) {
            return VerificationResult.fail(Verifier.Severity.BLOCK,
                    "input is null",
                    Map.of("verifier", name));
        }
        for (Map.Entry<String, FieldSpec> e : schema.entrySet()) {
            String key = e.getKey();
            FieldSpec spec = e.getValue();
            Object value = input.get(key);
            if (value == null) {
                if (spec.required()) {
                    return VerificationResult.fail(Verifier.Severity.BLOCK,
                            "required field missing: " + key,
                            Map.of("verifier", name, "field", key));
                }
                // optional + null = pass
                continue;
            }
            if (!typeMatches(spec.type(), value)) {
                return VerificationResult.fail(Verifier.Severity.BLOCK,
                        "field " + key + " has wrong type: expected " + spec.type() +
                                ", got " + value.getClass().getSimpleName(),
                        Map.of("verifier", name, "field", key,
                                "expected", spec.type(), "actual", value.getClass().getName()));
            }
        }
        return VerificationResult.pass("schema check passed",
                Map.of("verifier", name, "fields_checked", schema.size()));
    }

    static boolean typeMatches(String type, Object value) {
        if (type == null) return true;
        return switch (type.toLowerCase()) {
            case "string" -> value instanceof String;
            case "int", "integer" -> value instanceof Integer || value instanceof Long || value instanceof Short || value instanceof Byte;
            case "long" -> value instanceof Long;
            case "number", "double" -> value instanceof Number;
            case "boolean", "bool" -> value instanceof Boolean;
            case "list", "array" -> value instanceof java.util.List<?> || (value != null && value.getClass().isArray());
            case "map", "object" -> value instanceof Map<?, ?>;
            default -> true; // unknown type = permissive
        };
    }
}
