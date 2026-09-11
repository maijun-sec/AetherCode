package org.aethercode.talon;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * JSON helpers shared by the Talon runtime host.
 *
 * <p>Thin wrapper around Jackson that picks a deterministic, sorted-key
 * configuration so the same input always produces the same output. Used
 * for structured log lines and for the small on-disk records (cron jobs,
 * Telegram offset, ...) that the host persists.</p>
 */
public final class JsonUtils {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);

    private JsonUtils() {}

    /** Serialize {@code value} to a compact JSON string. */
    public static String toJson(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            return String.valueOf(value);
        }
    }

    /** Serialize {@code value} as a pretty-printed JSON string. */
    public static String toPrettyJson(Object value) {
        try {
            return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(value);
        } catch (JsonProcessingException e) {
            return String.valueOf(value);
        }
    }

    /** Parse JSON text into a generic {@code Map}. */
    public static Map<String, Object> parseMap(String json) {
        if (json == null || json.isEmpty()) {
            return Map.of();
        }
        try {
            return MAPPER.readValue(json, new TypeReference<Map<String, Object>>() {});
        } catch (IOException e) {
            throw new RuntimeException("could not parse JSON map: " + e.getMessage(), e);
        }
    }

    /** Parse JSON text into a {@code List<Map>}. */
    public static List<Map<String, Object>> parseListOfMaps(String json) {
        if (json == null || json.isEmpty()) {
            return List.of();
        }
        try {
            return MAPPER.readValue(json, new TypeReference<List<Map<String, Object>>>() {});
        } catch (IOException e) {
            throw new RuntimeException("could not parse JSON list: " + e.getMessage(), e);
        }
    }

    /** Parse JSON text into the given Java type. */
    public static <T> T parse(String json, Class<T> type) {
        if (json == null || json.isEmpty()) {
            return null;
        }
        try {
            return MAPPER.readValue(json, type);
        } catch (IOException e) {
            throw new RuntimeException("could not parse JSON: " + e.getMessage(), e);
        }
    }

    /** Return the shared, deterministic {@link ObjectMapper}. */
    public static ObjectMapper mapper() {
        return MAPPER;
    }
}
