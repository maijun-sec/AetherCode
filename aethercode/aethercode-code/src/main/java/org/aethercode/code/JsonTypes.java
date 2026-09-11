package org.aethercode.code;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;

/**
 * Shared recursive types for JSON-compatible data.
 *
 * <p>Java-native port of the Python {@code deepagents_code.json_types} module.
 * The Python module re-exports {@code pydantic.JsonValue} verbatim; here we
 * rely on Jackson's runtime type system and document the contracts via
 * convenience accessors and a single shared {@link ObjectMapper}.</p>
 */
public final class JsonTypes {
    private JsonTypes() {}

    /** Shared mapper used by all {@code deepagents-code} JSON helpers. */
    public static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * Convenience type reference for the recursive {@code JsonValue} union
     * ({@code str | int | float | bool | null | list | dict}).
     */
    public static final TypeReference<Object> JSON_VALUE = new TypeReference<>() {};

    /** Convenience type reference for a JSON object. */
    public static final TypeReference<Map<String, Object>> JSON_OBJECT = new TypeReference<>() {};

    /** Convenience type reference for a JSON array. */
    public static final TypeReference<List<Object>> JSON_ARRAY = new TypeReference<>() {};

    /**
     * Returns the value at the requested JSON-pointer-style path, or
     * {@code null} if the path is missing or the input is not a container.
     */
    @SuppressWarnings("unchecked")
    public static Object getAt(Object root, String key) {
        if (root instanceof Map<?, ?> map) {
            return map.get(key);
        }
        return null;
    }
}
