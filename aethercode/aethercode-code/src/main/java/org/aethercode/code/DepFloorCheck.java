package org.aethercode.code;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Dependency-floor check (stub).
 *
 * <p>Java-native port of the Python {@code deepagents_code._dep_floor_check}
 * module. The Java port exposes the surface used by the startup-time
 * dependency-floors guard; the full port checks {@code deepagents-core},
 * the partner SDKs, etc. against a minimum version.</p>
 */
public final class DepFloorCheck {
    private DepFloorCheck() {}

    /** A single dependency floor. */
    public record Floor(
            String packageName,
            String minVersion) {
    }

    /** Default dependency floors. */
    public static final Map<String, Floor> FLOORS = Map.of(
            "deepagents-core", new Floor("deepagents-core", "0.1.0"),
            "deepagents-acp", new Floor("deepagents-acp", "0.1.0"),
            "deepagents-cli", new Floor("deepagents-cli", "0.1.0"),
            "deepagents-talon", new Floor("deepagents-talon", "0.1.0"));
}
