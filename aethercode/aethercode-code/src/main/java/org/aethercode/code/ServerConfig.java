package org.aethercode.code;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Server config (stub).
 *
 * <p>Java-native port of the Python {@code deepagents_code._server_config}
 * module. The Java port exposes the surface used by the managed-runtime
 * configuration loader; the full implementation lands with the
 * deepagents-core configuration port.</p>
 */
public final class ServerConfig {
    private ServerConfig() {}

    /** A server config entry. */
    public record Entry(
            String key,
            String type,
            String defaultValue) {
    }

    /** Default server config entries. */
    public static final Map<String, Entry> ENTRIES = Map.of(
            "host", new Entry("host", "string", "127.0.0.1"),
            "port", new Entry("port", "int", "0"),
            "auth_token", new Entry("auth_token", "string", null));
}
