package org.aethercode.deepagents.middleware;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Structured-output response format for a subagent.
 *
 * <p>Java-native port of the union
 * {@code ResponseFormat[Any] | type | dict[str, Any]} from
 * {@code deepagents.middleware.subagents.SubAgent.response_format}.
 * The Java port stores the spec under a single {@link Kind} tag and
 * a payload object so callers can recover the original shape at
 * invocation time.</p>
 */
public record SubAgentResponseFormat(Kind kind, Object schema) {

    public enum Kind { TOOL_STRATEGY, PROVIDER_STRATEGY, AUTO_STRATEGY, JSON_SCHEMA, PYTHON_CLASS }

    public SubAgentResponseFormat {
        Objects.requireNonNull(kind, "kind");
    }

    public static SubAgentResponseFormat toolStrategy(Object schema) {
        return new SubAgentResponseFormat(Kind.TOOL_STRATEGY, schema);
    }
    public static SubAgentResponseFormat providerStrategy(Object schema) {
        return new SubAgentResponseFormat(Kind.PROVIDER_STRATEGY, schema);
    }
    public static SubAgentResponseFormat autoStrategy(Object schema) {
        return new SubAgentResponseFormat(Kind.AUTO_STRATEGY, schema);
    }
    public static SubAgentResponseFormat fromJsonSchema(Map<String, Object> schema) {
        return new SubAgentResponseFormat(Kind.JSON_SCHEMA,
                schema == null ? Map.of() : Map.copyOf(schema));
    }
    public static SubAgentResponseFormat fromClass(Class<?> schema) {
        return new SubAgentResponseFormat(Kind.PYTHON_CLASS, schema);
    }

    public Optional<Object> schemaOpt() {
        return schema == null ? Optional.empty() : Optional.of(schema);
    }
}
