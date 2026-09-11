package org.aethercode.tasks.limits;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * R240 (O-5): per-user default {@link Limits} loaded from
 * {@code ~/.aethercode/task-defaults.yaml}. The supervisor reads
 * this file on startup and uses the parsed {@link Limits} as the
 * fallback when a {@code task/spawn} RPC does not pass an explicit
 * {@code limits} blob.
 *
 * <h2>File format</h2>
 *
 * <p>YAML is intentionally avoided as a hard dependency: aethercode-tasks
 * does not currently pull SnakeYAML. Instead the format is a tiny,
 * line-oriented subset that handles the common case without a parser:
 *
 * <pre>{@code
 * # ~/.aethercode/task-defaults.yaml
 * wallClockMs: 600000    # 10 minutes
 * tokens:      50000
 * calls:       200
 * fileWrites:  50
 * network:     100
 * }</pre>
 *
 * Each line is {@code key: value}. Keys are the same as the
 * {@link Limits} record fields ({@code wallClockMs}, {@code tokens},
 * {@code calls}, {@code fileWrites}, {@code network}). Comments
 * ({@code #}) and blank lines are ignored. Unknown keys produce a
 * WARN log and are otherwise ignored. Values that fail to parse
 * produce a WARN and are dropped.
 *
 * <p>To get the same shape with a real YAML library later, the
 * single-call {@link #loadFrom(Path)} reads the file as text and
 * passes through {@link Limits#fromMap(Map)}; switching the format
 * to a richer one is a one-method change.
 *
 * <h2>Resolution</h2>
 *
 * <ol>
 *   <li>If {@code AETHERCODE_TASK_DEFAULTS} is set, treat its value
 *       as an explicit file path and use it (even if missing — the
 *       caller's expectation of "I configured it" wins).</li>
 *   <li>Otherwise default to {@code <user.home>/.aethercode/task-defaults.yaml}.</li>
 *   <li>If the resolved file does not exist, return
 *       {@link Limits#unlimited()} (and log INFO once).</li>
 *   <li>If the file exists but is malformed, log WARN and return
 *       {@link Limits#unlimited()} — never throw, because the
 *       defaults are a convenience not a guard.</li>
 * </ol>
 */
public final class DefaultLimitsConfig {

    private static final Logger LOG = LoggerFactory.getLogger(DefaultLimitsConfig.class);

    /** Env var override for the defaults file path. */
    public static final String ENV_PATH = "AETHERCODE_TASK_DEFAULTS";

    /** Default path under {@code user.home}. */
    public static final Path DEFAULT_PATH_SUFFIX =
            Path.of(".aethercode", "task-defaults.yaml");

    /** All known keys, in the order they appear in the rendered YAML. */
    static final String[] KNOWN_KEYS = {
            "wallClockMs", "tokens", "calls", "fileWrites", "network"
    };

    private DefaultLimitsConfig() {}

    /**
     * Load defaults using the standard resolution rules (env var →
     * {@code ~/.aethercode/task-defaults.yaml} → unlimited). Never
     * throws.
     */
    public static Limits load() {
        return loadFrom(resolvePath().orElse(null));
    }

    /**
     * Load defaults from a specific path. {@code null} or a
     * non-existent path returns {@link Limits#unlimited()}. Malformed
     * content is logged at WARN and treated as empty (i.e. unlimited
     * for the missing keys, explicit for the parseable ones).
     */
    public static Limits loadFrom(Path path) {
        if (path == null) {
            LOG.info("no default-limits file resolved; using Limits.unlimited()");
            return Limits.unlimited();
        }
        if (!Files.exists(path)) {
            LOG.info("default-limits file not found at {}; using Limits.unlimited()", path);
            return Limits.unlimited();
        }
        Map<String, Long> parsed;
        try {
            parsed = parse(path);
        } catch (IOException ioe) {
            LOG.warn("could not read default-limits file {}: {}",
                    path, ioe.getMessage());
            return Limits.unlimited();
        }
        if (parsed.isEmpty()) {
            return Limits.unlimited();
        }
        Limits.Builder b = Limits.builder();
        putIfPresent(parsed, "wallClockMs", b::wallClockMs);
        putIfPresent(parsed, "tokens",      b::tokens);
        putIfPresent(parsed, "calls",       b::calls);
        putIfPresent(parsed, "fileWrites",  b::fileWrites);
        putIfPresent(parsed, "network",     b::network);
        Limits limits = b.build();
        LOG.info("loaded default limits from {}: {}", path, limits.toMap());
        return limits;
    }

    /**
     * Render a {@link Limits} to the file format consumed by
     * {@link #loadFrom(Path)}. Useful for {@code aethercode task
     * defaults-init} style commands. The order matches
     * {@link #KNOWN_KEYS}; null fields are skipped.
     */
    public static String toYaml(Limits limits) {
        Objects.requireNonNull(limits, "limits");
        StringBuilder sb = new StringBuilder("# aethercode task defaults — R240 (O-5)\n");
        for (String key : KNOWN_KEYS) {
            Long v = field(limits, key);
            if (v == null) continue;
            sb.append(key).append(": ").append(v).append('\n');
        }
        return sb.toString();
    }

    /** Write a {@link Limits} to a path. Creates parent directories. */
    public static void writeTo(Path path, Limits limits) throws IOException {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(limits, "limits");
        if (path.getParent() != null) Files.createDirectories(path.getParent());
        Files.writeString(path, toYaml(limits), StandardCharsets.UTF_8);
    }

    /** Internal: env var → user.home fallback. */
    static Optional<Path> resolvePath() {
        String env = System.getenv(ENV_PATH);
        if (env != null && !env.isBlank()) {
            return Optional.of(Path.of(env.trim()));
        }
        String home = System.getProperty("user.home");
        if (home == null || home.isBlank()) return Optional.empty();
        return Optional.of(Path.of(home).resolve(DEFAULT_PATH_SUFFIX));
    }

    /**
     * Parse a {@code key: value} file. Tolerant: bad lines are
     * dropped with a WARN, never throws on a single line. Returns
     * an empty map for an empty / missing file.
     */
    static Map<String, Long> parse(Path path) throws IOException {
        Map<String, Long> out = new LinkedHashMap<>();
        for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
            int hash = line.indexOf('#');
            String stripped = hash >= 0 ? line.substring(0, hash) : line;
            String trimmed = stripped.trim();
            if (trimmed.isEmpty()) continue;
            int colon = trimmed.indexOf(':');
            if (colon <= 0) {
                LOG.warn("default-limits file {}: ignoring line without colon: {}",
                        path, trimmed);
                continue;
            }
            String key = trimmed.substring(0, colon).trim();
            String val = trimmed.substring(colon + 1).trim();
            if (!isKnownKey(key)) {
                LOG.warn("default-limits file {}: unknown key '{}' (known: {})",
                        path, key, String.join(",", KNOWN_KEYS));
                continue;
            }
            try {
                long n = Long.parseLong(val);
                out.put(key, n);
            } catch (NumberFormatException nfe) {
                LOG.warn("default-limits file {}: bad value for '{}' ({}); ignoring",
                        path, key, val);
            }
        }
        return out;
    }

    static boolean isKnownKey(String key) {
        for (String k : KNOWN_KEYS) if (k.equals(key)) return true;
        return false;
    }

    @FunctionalInterface
    private interface LongSetter { void set(long v); }

    private static void putIfPresent(Map<String, Long> m, String key, LongSetter setter) {
        Long v = m.get(key);
        if (v != null) setter.set(v);
    }

    private static Long field(Limits l, String key) {
        return switch (key) {
            case "wallClockMs" -> l.wallClockMs();
            case "tokens"      -> l.tokens();
            case "calls"       -> l.calls();
            case "fileWrites"  -> l.fileWrites();
            case "network"     -> l.network();
            default -> {
                LOG.warn("DefaultLimitsConfig.field: unknown key '{}' (programmer error)",
                        key);
                yield null;
            }
        };
    }

    /** Visible for the test suite: render the example default-set. */
    public static Limits example() {
        return Limits.builder()
                .wallClockMs(600_000L)
                .tokens(50_000L)
                .calls(200L)
                .fileWrites(50L)
                .network(100L)
                .build();
    }

    /** Default-resolved path, or {@code null} if env disables it. */
    public static Path resolvedPathOrNull() {
        return resolvePath().orElse(null);
    }

    /** Lower-case the env value for stable matching. */
    static String normalizeEnvValue(String v) {
        return v == null ? "" : v.trim().toLowerCase(Locale.ROOT);
    }
}
