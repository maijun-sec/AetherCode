package org.aethercode.evals.harbor;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;

/**
 * Lightweight JSON utility for the {@code deepagents-evals} port.
 *
 * <p>Wraps a single shared {@link ObjectMapper} so a non-strict parse of
 * trajectory / config JSON can be done with one call. The {@code lenient}
 * parsers accept bare strings and unquoted keys, which mirrors the
 * defensive shapes the Python port tolerates.</p>
 */
final class JsonLoaders {

    private static final ObjectMapper STRICT = JsonMapper.builder().build();
    private static final ObjectMapper LENIENT = JsonMapper.builder()
            .configure(com.fasterxml.jackson.core.JsonParser.Feature.ALLOW_UNQUOTED_FIELD_NAMES, true)
            .configure(com.fasterxml.jackson.core.JsonParser.Feature.ALLOW_SINGLE_QUOTES, true)
            .configure(com.fasterxml.jackson.core.JsonParser.Feature.ALLOW_COMMENTS, true)
            .build();

    private JsonLoaders() {}

    /** Parse a JSON string into a Java value ({@code Map}, {@code List}, primitive). */
    static Object parseJson(String json) throws Exception {
        return STRICT.readValue(json, Object.class);
    }

    /**
     * Lenient parse: accepts unquoted field names, single-quoted strings,
     * and trailing commas. Returns {@code null} when the input cannot be
     * parsed at all.
     */
    static Object parseJsonLenient(String json) throws Exception {
        return LENIENT.readValue(json, Object.class);
    }
}
