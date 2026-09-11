package org.aethercode.code.configuration;

import org.aethercode.code.configuration.ConfigTypes.Found;
import org.aethercode.code.configuration.ConfigTypes.Invalid;
import org.aethercode.code.configuration.ConfigTypes.ProviderHealth;
import org.aethercode.code.configuration.ConfigTypes.ProviderResult;
import org.aethercode.code.configuration.ConfigTypes.ProviderStatus;
import org.aethercode.code.configuration.ConfigTypes.TomlSnapshot;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import java.util.logging.Logger;

/**
 * Synchronous providers and provider-domain option coercion.
 *
 * <p>Java 21 port of the Python
 * {@code deepagents_code.configuration.providers} module.</p>
 */
public final class ConfigProviders {
    private static final Logger LOGGER = Logger.getLogger(ConfigProviders.class.getName());

    /** Diagnostic suffix matched by other modules to deduplicate warnings. */
    public static final String SHADOWED_TABLE_SUFFIX =
            "— every option under it falls back to its next source";
    public static final String UNUSABLE_SOURCE_SUFFIX =
            "— using defaults for every option it would have set";
    public static final String RETAINED_SOURCE_SUFFIX =
            "— still applying the last readable version of it";

    private ConfigProviders() {}

    // ---- Coercion helpers ----------------------------------------------------

    /**
     * Coerce one present environment value within the env provider domain.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public static ProviderResult<Object> coerceEnvironmentValue(
            Provider.ManifestOption option, String raw, String name) {
        if (raw == null) return new Invalid("Ignoring " + name + " (null)");
        String kind = option.kindName() == null ? "" : option.kindName();
        // Coercion based on the manifest kind. Only the most common kinds
        // are handled here; the full manifest validation lives outside
        // the configuration layer.
        return switch (kind) {
            case "BOOL", "BOOL_MODE_DEFAULT" -> {
                Boolean b = classifyEnvBool(raw);
                yield b == null ? new Invalid("Ignoring " + name + "=" + raw + " (expected bool)")
                                : new Found<>(b);
            }
            case "BOOL_PRESENCE" -> new Found<>(!raw.isEmpty());
            case "STR" -> new Found<>(raw);
            case "NON_EMPTY_STR" -> {
                String trimmed = raw.strip();
                yield trimmed.isEmpty()
                        ? new Invalid("Ignoring " + name + "=" + raw + " (expected non-empty string)")
                        : new Found<>(trimmed);
            }
            case "INT" -> {
                try { yield new Found<>(Long.parseLong(raw.strip())); }
                catch (NumberFormatException e) {
                    yield new Invalid("Ignoring " + name + "=" + raw + " (expected int)");
                }
            }
            case "NON_NEGATIVE_INT" -> {
                try {
                    long v = Long.parseLong(raw.strip());
                    yield v >= 0 ? new Found<>(v)
                                 : new Invalid("Ignoring " + name + "=" + raw + " (expected int >= 0)");
                } catch (NumberFormatException e) {
                    yield new Invalid("Ignoring " + name + "=" + raw + " (expected int >= 0)");
                }
            }
            case "FLOAT" -> {
                try { yield new Found<>(Double.parseDouble(raw.strip())); }
                catch (NumberFormatException e) {
                    yield new Invalid("Ignoring " + name + "=" + raw + " (expected number)");
                }
            }
            case "THEME_DELEGATE" -> new Found<>(raw);
            case "CURSOR_STYLE_DELEGATE" -> {
                if ("block".equals(raw) || "underline".equals(raw)) {
                    yield new Found<>(raw);
                }
                yield new Invalid("Ignoring " + name + "=" + raw + " (expected 'block' or 'underline')");
            }
            case "STARTUP_MODE_DELEGATE" -> {
                if ("manual".equals(raw) || "auto".equals(raw) || "yolo".equals(raw)) {
                    yield new Found<>(raw);
                }
                yield new Invalid("Ignoring " + name + "=" + raw + " (expected 'manual', 'auto', or 'yolo')");
            }
            case "LOG_LEVEL_DELEGATE" -> {
                String level = raw.strip().toUpperCase(Locale.ROOT);
                if (List.of("DEBUG", "INFO", "WARNING", "ERROR", "CRITICAL").contains(level)) {
                    yield new Found<>(level);
                }
                yield new Invalid("Ignoring " + name + "=" + raw + " (expected log level)");
            }
            default -> new Invalid(option.key() + " is not env-backed; ignoring " + name + "=" + raw);
        };
    }

    /**
     * Coerce one present TOML value within the file-provider domain.
     */
    @SuppressWarnings("unchecked")
    public static ProviderResult<Object> coerceTomlValue(
            Provider.ManifestOption option, Object raw, String source) {
        String kind = option.kindName() == null ? "" : option.kindName();
        String label = option.tomlKeys() == null || option.tomlKeys().isEmpty()
                ? option.key()
                : String.join(".", option.tomlKeys());
        if (List.of("BOOL", "BOOL_MODE_DEFAULT", "BOOL_PRESENCE").contains(kind)) {
            if (raw instanceof Boolean b) {
                Object value = option.invertTomlBool() ? !b : b;
                return new Found<>(value);
            }
            return new Invalid("Ignoring " + label + "=" + raw + " in " + source + " (expected bool)");
        }
        if ("INT".equals(kind)) {
            if (raw instanceof Long l) return new Found<>(l);
            if (raw instanceof Integer i) return new Found<>(i.longValue());
            return new Invalid("Ignoring " + label + "=" + raw + " in " + source + " (expected int)");
        }
        if ("NON_NEGATIVE_INT".equals(kind)) {
            long value;
            if (raw instanceof Long l) value = l;
            else if (raw instanceof Integer i) value = i.longValue();
            else return new Invalid("Ignoring " + label + "=" + raw + " in " + source + " (expected int >= 0)");
            return value >= 0 ? new Found<>(value)
                              : new Invalid("Ignoring " + label + "=" + raw + " in " + source + " (expected int >= 0)");
        }
        if ("FLOAT".equals(kind)) {
            if (raw instanceof Number n) return new Found<>(n.doubleValue());
            return new Invalid("Ignoring " + label + "=" + raw + " in " + source + " (expected number)");
        }
        if ("STR".equals(kind)) {
            if (raw instanceof String s) return new Found<>(s);
            return new Invalid("Ignoring " + label + "=" + raw + " in " + source + " (expected string)");
        }
        if ("NON_EMPTY_STR".equals(kind)) {
            if (raw instanceof String s) {
                String trimmed = s.strip();
                if (!trimmed.isEmpty()) return new Found<>(trimmed);
            }
            return new Invalid("Ignoring " + label + "=" + raw + " in " + source + " (expected non-empty string)");
        }
        if ("STRUCTURED".equals(kind)) {
            return new Found<>(raw);
        }
        if ("CURSOR_STYLE_DELEGATE".equals(kind)) {
            if (raw instanceof String s && ("block".equals(s) || "underline".equals(s))) {
                return new Found<>(s);
            }
            return new Invalid("Ignoring " + label + "=" + raw + " in " + source
                    + " (expected 'block' or 'underline')");
        }
        if ("STARTUP_MODE_DELEGATE".equals(kind)) {
            if (raw instanceof String s && List.of("manual", "auto", "yolo").contains(s)) {
                return new Found<>(s);
            }
            return new Invalid("Ignoring " + label + "=" + raw + " in " + source
                    + " (expected 'manual', 'auto', or 'yolo')");
        }
        if ("SHELL_LIST_DELEGATE".equals(kind)) {
            if (raw instanceof List<?> list) {
                List<String> items = new ArrayList<>();
                for (Object o : list) {
                    if (o instanceof String s) items.add(s);
                }
                return new Found<>(items);
            }
            if (raw instanceof String s) {
                List<String> items = new ArrayList<>();
                for (String part : s.split(",")) {
                    String t = part.strip();
                    if (!t.isEmpty()) items.add(t);
                }
                return new Found<>(items);
            }
            return new Invalid("Ignoring " + label + "=" + raw + " in " + source
                    + " (expected string or list)");
        }
        return new Invalid("Ignoring " + label + "=" + raw + " in " + source
                + " (unsupported kind " + kind + ")");
    }

    /**
     * Classify an env-style boolean string. Returns {@code null} when
     * the input is not a recognized boolean.
     */
    public static Boolean classifyEnvBool(String raw) {
        if (raw == null) return null;
        String v = raw.strip().toLowerCase(Locale.ROOT);
        return switch (v) {
            case "1", "true", "yes", "on", "t" -> Boolean.TRUE;
            case "0", "false", "no", "off", "f" -> Boolean.FALSE;
            default -> null;
        };
    }

    // ---- Ranked helpers ------------------------------------------------------

    /**
     * Read and coerce one option from a parsed TOML provider.
     */
    public static RankedProviderValue rankedTomlValue(
            Provider.ManifestOption option, Map<String, Object> data,
            int rank, boolean durable, ProviderStatus status) {
        ProviderResult<Object> result;
        if (!status.usable() || option.tomlKeys() == null || option.tomlKeys().isEmpty()) {
            result = ConfigTypes.Unset.INSTANCE;
        } else {
            Object node = data;
            result = ConfigTypes.Unset.INSTANCE;
            for (int i = 0; i < option.tomlKeys().size(); i++) {
                String key = option.tomlKeys().get(i);
                if (!(node instanceof Map<?, ?> m)) {
                    List<String> path = option.tomlKeys().subList(0, i);
                    result = new Invalid("Ignoring " + status.name() + " ["
                            + String.join(".", path) + "]; expected a table, got "
                            + (node == null ? "null" : node.getClass().getSimpleName())
                            + " " + SHADOWED_TABLE_SUFFIX);
                    break;
                }
                if (!m.containsKey(key)) {
                    result = ConfigTypes.Unset.INSTANCE;
                    break;
                }
                node = m.get(key);
                if (i == option.tomlKeys().size() - 1) {
                    result = coerceTomlValue(option, node, status.name());
                }
            }
        }
        return new RankedProviderValue(rank, durable, status, result, List.of());
    }

    /**
     * Read and coerce one option from the process-environment domain.
     */
    public static RankedProviderValue rankedEnvironmentValue(
            Provider.ManifestOption option, Map<String, String> environ, int rank) {
        List<String> names = new ArrayList<>();
        if (option.envVar() != null && !option.envVar().isEmpty()) {
            String canonical = option.envVar();
            String prefixed = canonical.startsWith("DEEPAGENTS_CODE_")
                    ? canonical
                    : "DEEPAGENTS_CODE_" + canonical;
            names.add(environ.containsKey(prefixed) ? prefixed : canonical);
        }
        if (option.fallbackEnvVars() != null) names.addAll(option.fallbackEnvVars());

        ProviderStatus status = new ProviderStatus("environment", null, ProviderHealth.OK);
        Invalid lastInvalid = null;
        List<String> diagnostics = new ArrayList<>();
        for (String name : names) {
            String raw = environ.get(name);
            if (raw == null) continue;
            status = new ProviderStatus("env (" + name + ")", null, ProviderHealth.OK);
            if (raw.strip().isEmpty()) {
                if (option.emptyEnvIsFalse()) {
                    return new RankedProviderValue(rank, false, status,
                            new Found<>(false), List.of());
                }
                if (!raw.isEmpty()) {
                    Invalid inv = new Invalid("Ignoring " + name + "=" + raw
                            + " (whitespace-only; treated as unset)");
                    lastInvalid = inv;
                    diagnostics.add(inv.reason());
                }
                continue;
            }
            ProviderResult<Object> result = coerceEnvironmentValue(option, raw, name);
            if (result instanceof Found<?> f) {
                @SuppressWarnings("unchecked")
                ProviderResult<Object> typed = (ProviderResult<Object>) f;
                return new RankedProviderValue(rank, false, status, typed, List.copyOf(diagnostics));
            }
            if (result instanceof Invalid inv) {
                lastInvalid = inv;
                diagnostics.add(inv.reason());
            }
        }
        return new RankedProviderValue(rank, false, status,
                lastInvalid != null ? lastInvalid : ConfigTypes.Unset.INSTANCE,
                List.copyOf(diagnostics));
    }

    /**
     * Resolve a file provider's terminal-aware theme preference.
     */
    public static RankedProviderValue rankedThemeTomlValue(
            Map<String, Object> data, int rank, boolean durable, ProviderStatus status,
            ThemeResolution.ThemeRegistry registry) {
        if (!status.usable()) {
            return new RankedProviderValue(rank, durable, status,
                    ConfigTypes.Unset.INSTANCE, List.of());
        }
        Object ui = data.get("ui");
        if (ui == null) {
            return new RankedProviderValue(rank, durable, status,
                    ConfigTypes.Unset.INSTANCE, List.of());
        }
        if (!(ui instanceof Map<?, ?>)) {
            Invalid invalid = new Invalid("[ui] in " + status.name() + " should be a table; got "
                    + ui.getClass().getSimpleName() + " while resolving theme");
            return new RankedProviderValue(rank, durable, status, invalid, List.of());
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> uiMap = (Map<String, Object>) ui;
        String resolved = ThemeResolution.resolveTerminalMapping(uiMap, registry);
        if (resolved != null) {
            String termProgram = System.getenv("TERM_PROGRAM");
            if (termProgram == null) termProgram = "";
            termProgram = termProgram.strip();
            ProviderStatus selected = new ProviderStatus(
                    status.name() + " [ui.terminal_themes." + termProgram + "]",
                    status.path(), status.health(), status.detail());
            return new RankedProviderValue(rank, durable, selected, new Found<>(resolved), List.of());
        }
        Object saved = uiMap.get("theme");
        resolved = ThemeResolution.resolveThemeName(saved, registry);
        if (resolved != null) {
            ProviderStatus selected = new ProviderStatus(
                    status.name() + " [ui.theme]", status.path(), status.health(), status.detail());
            return new RankedProviderValue(rank, durable, selected, new Found<>(resolved), List.of());
        }
        if (saved instanceof String s) {
            Invalid invalid = new Invalid("Unknown theme '" + s + "' in " + status.name() + "; ignoring it");
            return new RankedProviderValue(rank, durable, status, invalid, List.of());
        }
        return new RankedProviderValue(rank, durable, status,
                ConfigTypes.Unset.INSTANCE, List.of());
    }

    /**
     * Resolve the theme environment provider.
     */
    public static RankedProviderValue rankedThemeEnvironmentValue(
            Map<String, String> environ, int rank) {
        // The Python module uses a single "DEEPAGENTS_CODE_THEME" variable.
        String themeVar = "DEEPAGENTS_CODE_THEME";
        ProviderStatus status = new ProviderStatus("env (" + themeVar + ")", null, ProviderHealth.OK);
        String raw = environ.get(themeVar);
        if (raw == null) {
            return new RankedProviderValue(rank, false, status,
                    ConfigTypes.Unset.INSTANCE, List.of());
        }
        // Resolved without a registry here; the resolver applies the
        // actual mapping when assembling the final result.
        return new RankedProviderValue(rank, false, status, new Found<>(raw), List.of());
    }

    /**
     * Produce an option's typed or mode-dependent default.
     */
    public static RankedProviderValue rankedDefaultValue(Provider.ManifestOption option, int rank) {
        ProviderStatus status = new ProviderStatus("default", null, ProviderHealth.OK);
        String kind = option.kindName() == null ? "" : option.kindName();
        if ("STRUCTURED".equals(kind)) {
            return new RankedProviderValue(rank, true, status,
                    ConfigTypes.Unset.INSTANCE, List.of());
        }
        return new RankedProviderValue(rank, true, status,
                new Found<>(option.defaultValue()), List.of());
    }

    // ---- Concrete providers --------------------------------------------------

    /**
     * Ranked provider backed by one local TOML file snapshot.
     */
    public static final class TomlFileProvider implements Provider {
        private final String name;
        private final Path path;
        private final int rank;
        private final boolean durable;
        private final Supplier<TomlSnapshot> loader;
        private final AtomicReference<TomlSnapshot> snapshot = new AtomicReference<>();
        private final AtomicReference<ProviderStatus> failure = new AtomicReference<>();

        public TomlFileProvider(String name, Path path, int rank, boolean durable,
                                 TomlSnapshot initial, Supplier<TomlSnapshot> loader) {
            this.name = name;
            this.path = path;
            this.rank = rank;
            this.durable = durable;
            this.loader = loader;
            this.snapshot.set(initial);
        }

        /** Convenience: lazy-load on first read. */
        public TomlFileProvider(String name, Path path, int rank, boolean durable) {
            this(name, path, rank, durable, null, null);
        }

        /** Convenience: default USER_RANK with durable=true. */
        public TomlFileProvider(String name, Path path) {
            this(name, path, ConfigResolver.USER_RANK, true);
        }

        @Override public String name() { return name; }
        @Override public int rank() { return rank; }
        @Override public boolean durable() { return durable; }

        /** Parse the file and classify missing, unreadable, or corrupt states. */
        public TomlSnapshot load() {
            if (path == null) {
                return new TomlSnapshot(Map.of(),
                        new ProviderStatus(name, null, ProviderHealth.INDETERMINATE,
                                "no path is known for this source, so it cannot be re-read"));
            }
            Map<String, Object> data;
            try {
                byte[] bytes = Files.readAllBytes(path);
                String text = new String(bytes, StandardCharsets.UTF_8);
                data = TomlParser.parse(text);
            } catch (java.nio.file.NoSuchFileException e) {
                return new TomlSnapshot(Map.of(),
                        new ProviderStatus(name, path, ProviderHealth.MISSING));
            } catch (IOException e) {
                return new TomlSnapshot(Map.of(),
                        new ProviderStatus(name, path, ProviderHealth.UNREADABLE,
                                e.getClass().getSimpleName()));
            } catch (TomlParser.TomlException e) {
                String detail = e.getMessage() != null && e.getMessage().contains("UTF")
                        ? "not UTF-8 encoded"
                        : e.getMessage();
                return new TomlSnapshot(Map.of(),
                        new ProviderStatus(name, path, ProviderHealth.CORRUPT, detail));
            }
            if (!(data instanceof Map<?, ?>)) {
                return new TomlSnapshot(Map.of(),
                        new ProviderStatus(name, path, ProviderHealth.CORRUPT,
                                "top-level TOML value is not a table"));
            }
            return new TomlSnapshot(castMap(data),
                    new ProviderStatus(name, path, ProviderHealth.OK));
        }

        @Override
        public ProviderResult<Object> get(ManifestOption option) {
            TomlSnapshot snap = currentSnapshot();
            RankedProviderValue ranked = rankedTomlValue(option, snap.data(), rank, durable, snap.status());
            ProviderStatus failureStatus = failure.get();
            return _withRejectionDiagnostic(ranked, failureStatus != null ? failureStatus : snap.status(),
                    snap.status().usable());
        }

        private static ProviderResult<Object> _withRejectionDiagnostic(
                RankedProviderValue ranked, ProviderStatus status, boolean retained) {
            if (status.usable()) return ranked.result();
            String location = status.path() != null ? " (" + status.path() + ")" : "";
            String detail = status.detail() != null ? ": " + status.detail() : "";
            String suffix = retained ? RETAINED_SOURCE_SUFFIX : UNUSABLE_SOURCE_SUFFIX;
            String reason = "Ignoring " + status.name() + location + " — it is " + status.health()
                    + detail + " " + suffix;
            if (ranked.result() instanceof Invalid inv) {
                return new Invalid(reason + " | " + inv.reason());
            }
            return new Invalid(reason);
        }

        @Override
        public ProviderStatus status() {
            ProviderStatus f = failure.get();
            if (f != null) return f;
            TomlSnapshot snap = snapshot.get();
            if (snap == null) {
                reload();
                snap = snapshot.get();
            }
            return snap != null ? snap.status() : new ProviderStatus(name, path, ProviderHealth.INDETERMINATE);
        }

        @Override
        public void reload() {
            TomlSnapshot candidate = loader != null ? loader.get() : load();
            if (candidate.status().usable()) {
                snapshot.set(candidate);
                failure.set(null);
            } else {
                if (snapshot.get() == null) snapshot.set(candidate);
                failure.set(candidate.status());
            }
        }

        private TomlSnapshot currentSnapshot() {
            if (snapshot.get() == null) reload();
            TomlSnapshot snap = snapshot.get();
            if (snap == null) {
                throw new IllegalStateException(name + " reload produced no snapshot");
            }
            return snap;
        }

        @SuppressWarnings("unchecked")
        private static Map<String, Object> castMap(Map<String, Object> m) {
            // Sanity cast; the parser already returns LinkedHashMap<String,Object>.
            return (Map<String, Object>) m;
        }
    }

    /**
     * Live process-environment configuration provider.
     */
    public static final class EnvProvider implements Provider {
        private final String name;
        private final int rank;
        private final Map<String, String> environ;

        public EnvProvider() { this("environment", ConfigResolver.ENVIRONMENT_RANK, System.getenv()); }
        public EnvProvider(String name, int rank, Map<String, String> environ) {
            this.name = name;
            this.rank = rank;
            this.environ = environ;
        }

        @Override public String name() { return name; }
        @Override public int rank() { return rank; }
        @Override public boolean durable() { return false; }

        @Override
        public ProviderResult<Object> get(ManifestOption option) {
            if ("THEME_DELEGATE".equals(option.kindName())) {
                RankedProviderValue ranked = rankedThemeEnvironmentValue(environ, rank);
                return ranked.result();
            }
            RankedProviderValue ranked = rankedEnvironmentValue(option, environ, rank);
            return ranked.result();
        }

        @Override
        public ProviderStatus status() {
            return new ProviderStatus(name, null, ProviderHealth.OK);
        }

        @Override
        public void reload() { /* live; nothing to do */ }
    }

    /**
     * Typed manifest-default configuration provider.
     */
    public static final class DefaultProvider implements Provider {
        private final String name;
        private final int rank;

        public DefaultProvider() { this("default", ConfigResolver.DEFAULT_RANK); }
        public DefaultProvider(String name, int rank) {
            this.name = name;
            this.rank = rank;
        }

        @Override public String name() { return name; }
        @Override public int rank() { return rank; }
        @Override public boolean durable() { return true; }

        @Override
        public ProviderResult<Object> get(ManifestOption option) {
            RankedProviderValue ranked = rankedDefaultValue(option, rank);
            if (ranked.result() instanceof ConfigTypes.Unset) {
                return new Found<>(option.defaultValue());
            }
            return ranked.result();
        }

        @Override
        public ProviderStatus status() {
            return new ProviderStatus(name, null, ProviderHealth.OK);
        }

        @Override
        public void reload() { /* immutable */ }
    }

    /**
     * Internal helper for provider result wrapper.
     */
    public record RankedProviderValue(int rank, boolean durable, ProviderStatus status,
                                       ProviderResult<Object> result, List<String> diagnostics) {
        public RankedProviderValue {
            Objects.requireNonNull(status, "status");
            Objects.requireNonNull(result, "result");
            diagnostics = diagnostics == null ? List.of() : List.copyOf(diagnostics);
        }
    }
}
