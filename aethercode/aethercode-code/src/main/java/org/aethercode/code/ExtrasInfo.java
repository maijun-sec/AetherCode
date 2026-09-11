package org.aethercode.code;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Information about installed Python extras.
 *
 * <p>Java-native port of the Python {@code deepagents_code.extras_info}
 * module. The Java port provides a tiny "is this extra available?" check
 * used by the install/upgrade paths; for the Java runtime it just reads
 * a static set of known extras so callers don't depend on Python's
 * importlib.metadata.</p>
 */
public final class ExtrasInfo {
    private ExtrasInfo() {}

    /** One installed extra. */
    public record Extra(String name, String version) {}

    private static final Map<String, Extra> EXTRAS = new LinkedHashMap<>();

    /** Return the known installed extras. */
    public static Map<String, Extra> known() {
        return Map.copyOf(EXTRAS);
    }

    /** Register an extra (used by the install-time wiring). */
    public static void register(Extra extra) {
        if (extra == null) return;
        EXTRAS.put(extra.name(), extra);
    }

    /** Whether the named extra is available. */
    public static boolean isAvailable(String name) {
        return EXTRAS.containsKey(name);
    }

    /** The set of registered extra names. */
    public static Set<String> names() {
        return Set.copyOf(EXTRAS.keySet());
    }
}
