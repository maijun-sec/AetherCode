package org.aethercode.examples.asyncsubagent;

import org.aethercode.examples.support.MiniJson;

import java.util.List;
import java.util.Map;

/**
 * Lightweight JSON helpers used by the async subagent server. Wraps
 * the shared {@link org.aethercode.examples.support.MiniJson} so the
 * server's payload handling is decoupled from the rest of the
 * examples.
 */
final class JsonHelpers {
    private JsonHelpers() {}

    /** Parse a JSON value. */
    static Object parse(String text) {
        if (text == null || text.isBlank()) return Map.of();
        return MiniJson.parse(text);
    }

    /** Serialize a value to a JSON string. */
    static String dump(Object value) {
        return MiniJson.toJson(value);
    }

    /** Serialize a value to JSON bytes. */
    static byte[] dumpBytes(Object value) {
        return dump(value).getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }
}
