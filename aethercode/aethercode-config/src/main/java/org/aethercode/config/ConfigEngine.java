package org.aethercode.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * Loader for {@code .aethercode/config.json}. Falls back to
 * {@link AetherCodeConfig#defaults()} when:
 * <ul>
 *   <li>the file does not exist (new project / no config yet)</li>
 *   <li>the file is not parseable JSON</li>
 *   <li>the file is missing the {@code permissionMatrix} field</li>
 * </ul>
 * Never throws. A broken config is logged at WARN and the safe default is used.
 */
public final class ConfigEngine {

    private static final Logger log = LoggerFactory.getLogger(ConfigEngine.class);

    private ConfigEngine() {}

    /**
     * Load the config from a {@code .aethercode/config.json} candidate path.
     * If the file is missing or invalid, returns {@link AetherCodeConfig#defaults()}.
     */
    public static AetherCodeConfig loadFrom(Path configFile) {
        if (configFile == null || !Files.exists(configFile)) {
            return AetherCodeConfig.defaults();
        }
        try {
            String raw = Files.readString(configFile);
            @SuppressWarnings("unchecked")
            Map<String, Object> map = new ObjectMapper().readValue(raw, Map.class);
            return fromMap(map);
        } catch (IOException | RuntimeException e) {
            log.warn("R98: failed to parse config {}; falling back to defaults: {}",
                    configFile, e.toString());
            return AetherCodeConfig.defaults();
        }
    }

    /**
     * Load from a project root. Resolves {@code <root>/.aethercode/config.json}.
     * If {@code root} is null, returns defaults.
     */
    public static AetherCodeConfig loadFromProjectRoot(Path root) {
        if (root == null) return AetherCodeConfig.defaults();
        return loadFrom(root.resolve(".aethercode").resolve("config.json"));
    }

    /**
     * Parse a config from an already-decoded map. Visible for tests and for
     * callers (e.g. {@code aethercode-cli/Main}) that already have the map
     * in hand and want to avoid the round-trip through JSON.
     */
    @SuppressWarnings("unchecked")
    public static AetherCodeConfig fromMap(Map<String, Object> map) {
        if (map == null || map.isEmpty()) return AetherCodeConfig.defaults();
        int version = numberOr(map.get("version"), 1);
        boolean skip = boolOr(map.get("skipConfirmation"), false);
        int rounds = (int) numberOr(map.get("skipConfirmationRounds"), 0);
        String workflow = stringOr(map.get("workflow"), "design-first");
        Object phases = map.get("phases");
        // waterline read from config (default 5).
        int waterline = (int) numberOr(map.get("skipLowWaterline"), 5);
        // explicit permission mode the user wants as the boot
        // default. When set, the engine's effective mode starts with
        // this value (overriding the suggester's recommendation).
        // Null = no override, use suggester.
        String defaultMode = stringOr(map.get("defaultPermissionMode"), null);

        Object pmRaw = map.get("permissionMatrix");
        PermissionMatrix pm;
        if (pmRaw instanceof Map) {
            pm = parseMatrix((Map<String, Object>) pmRaw);
        } else {
            // No matrix declared: start from defaults so adding a config.json
            // that only sets workflow/skip still has the safe matrix.
            pm = org.aethercode.config.defaults.DefaultMatrix.build();
        }
        AetherCodeConfig cfg = new AetherCodeConfig(version, pm, skip, rounds, workflow, phases, waterline, new AetherCodeConfig.MemoryConfig());
        cfg.defaultPermissionMode = defaultMode;
        return cfg;
    }

    @SuppressWarnings("unchecked")
    private static PermissionMatrix parseMatrix(Map<String, Object> raw) {
        java.util.Map<String, java.util.Map<String, java.util.Map<String, String>>> out =
                new java.util.LinkedHashMap<>();
        for (Map.Entry<String, Object> toolE : raw.entrySet()) {
            if (!(toolE.getValue() instanceof Map)) continue;
            java.util.Map<String, java.util.Map<String, String>> toolBucket = new java.util.LinkedHashMap<>();
            for (Map.Entry<String, Object> pathE : ((Map<String, Object>) toolE.getValue()).entrySet()) {
                if (!(pathE.getValue() instanceof Map)) continue;
                java.util.Map<String, String> opBucket = new java.util.LinkedHashMap<>();
                for (Map.Entry<String, Object> opE : ((Map<String, Object>) pathE.getValue()).entrySet()) {
                    String op = opE.getKey();
                    Object val = opE.getValue();
                    if (val == null) continue;
                    opBucket.put(op.trim().toUpperCase(), val.toString().trim().toUpperCase());
                }
                toolBucket.put(pathE.getKey(), opBucket);
            }
            out.put(toolE.getKey(), toolBucket);
        }
        return new PermissionMatrix(out);
    }

    private static int numberOr(Object o, int fallback) {
        if (o instanceof Number) return ((Number) o).intValue();
        if (o instanceof String) {
            try { return Integer.parseInt((String) o); } catch (NumberFormatException ignore) {}
        }
        return fallback;
    }

    private static long numberOrLong(Object o, long fallback) {
        if (o instanceof Number) return ((Number) o).longValue();
        return fallback;
    }

    private static boolean boolOr(Object o, boolean fallback) {
        if (o instanceof Boolean) return (Boolean) o;
        if (o instanceof String) return Boolean.parseBoolean((String) o);
        return fallback;
    }

    private static String stringOr(Object o, String fallback) {
        if (o == null) return fallback;
        String s = o.toString();
        return s.isBlank() ? fallback : s;
    }
}
