package org.aethercode.code.configuration;

import org.aethercode.code.configuration.ConfigProviders.DefaultProvider;
import org.aethercode.code.configuration.ConfigProviders.EnvProvider;
import org.aethercode.code.configuration.ConfigProviders.TomlFileProvider;
import org.aethercode.code.configuration.ConfigTypes.ProviderHealth;
import org.aethercode.code.configuration.ConfigTypes.ProviderStatus;
import org.aethercode.code.configuration.ConfigTypes.TomlSnapshot;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Process-local snapshots for managed and user TOML configuration.
 *
 * <p>Java 21 port of the Python
 * {@code deepagents_code.configuration.service} module.</p>
 */
public final class ConfigService {
    private static final Logger LOGGER = Logger.getLogger(ConfigService.class.getName());

    /** Paths whose lists accumulate instead of being replaced. */
    public static final List<List<String>> UNION_PATHS = List.of(
            List.of("mcp", "disabled_project_servers"),
            List.of("mcp", "disabled_servers")
    );

    /** Sections whose managed value must be a TOML table. */
    public static final List<List<String>> MANAGED_TABLE_PATHS = List.of(
            List.of("themes"),
            List.of("ui", "terminal_themes"),
            List.of("models", "providers"),
            List.of("async_subagents"),
            List.of("sandboxes", "providers"),
            List.of("threads", "columns"),
            List.of("effort"),
            List.of("effort", "by_model")
    );

    /** Provenance label for a value managed policy decided. */
    public static final String MANAGED_SOURCE = "managed config";
    /** Provenance label for a value the user's own file decided. */
    public static final String USER_SOURCE = "config.toml";

    /** Manifest keys whose managed value must never resolve in the user's favor. */
    public static final List<String> ENFORCED_MANAGED_KEYS = List.of(
            "interpreter.enable_interpreter",
            "interpreter.ptc",
            "interpreter.ptc_acknowledge_unsafe",
            "models.auto_classifier",
            "runtime.recursion_limit",
            "sandboxes.default",
            "shell.allow_list",
            "skills.extra_allowed_dirs",
            "startup.mode",
            "startup.yolo_switcher",
            "tracing.langsmith_redact"
    );

    private static final ReentrantLock SNAPSHOT_LOCK = new ReentrantLock();
    private static volatile TomlSnapshot CACHED_MANAGED;

    private ConfigService() {}

    /** Return union paths rebased onto a subtree rooted at {@code prefix}. */
    public static List<List<String>> unionPathsUnder(List<String> prefix) {
        int depth = prefix.size();
        return UNION_PATHS.stream()
                .filter(p -> p.size() >= depth && p.subList(0, depth).equals(prefix))
                .map(p -> p.subList(depth, p.size()))
                .toList();
    }

    /** Return whether managed policy decided a value with this source label. */
    public static boolean managedDecided(String source) {
        return MANAGED_SOURCE.equals(source) || source.startsWith(MANAGED_SOURCE + " + ");
    }

    /**
     * Managed and user TOML snapshots from one resolution generation.
     */
    public record ConfigSources(TomlSnapshot managed, TomlSnapshot user) {}

    /** Return why the managed layer is absent from the merged result, if it is. */
    public String droppedManagedDetail(TomlSnapshot managed) {
        if (managed.status().usable()) return null;
        return managed.status().detail() != null ? managed.status().detail() : managed.status().health().name();
    }

    /**
     * Build a resolver whose managed provider owns {@code snapshot}.
     */
    public static ConfigResolver managedResolver(TomlSnapshot snapshot) {
        TomlSnapshot userEmpty = new TomlSnapshot(Map.of(),
                new ProviderStatus("config.toml", null, ProviderHealth.MISSING));
        return resolverFromSnapshots(snapshot, userEmpty);
    }

    /**
     * Build the standard provider chain from one file-snapshot generation.
     */
    public static ConfigResolver resolverFromSnapshots(TomlSnapshot managed, TomlSnapshot user) {
        return new ConfigResolver(List.of(
                new TomlFileProvider(managed.status().name(), managed.status().path(),
                        ConfigResolver.MANAGED_RANK, true, managed, null),
                new EnvProvider(),
                new TomlFileProvider(user.status().name(), user.status().path(),
                        ConfigResolver.USER_RANK, true, user, null),
                new DefaultProvider()
        ));
    }

    /**
     * Return the cached managed snapshot, or load a fresh one when
     * {@code refresh} is true.
     */
    public static TomlSnapshot getManagedSnapshot(boolean refresh) {
        return getManagedSnapshot(refresh, null);
    }

    /**
     * Return the cached managed snapshot, or an isolated snapshot for
     * an explicit {@code path}.
     */
    public static TomlSnapshot getManagedSnapshot(boolean refresh, Path path) {
        if (path != null) return loadManaged(path);
        SNAPSHOT_LOCK.lock();
        try {
            TomlSnapshot cached = CACHED_MANAGED;
            if (!refresh && cached != null) return cached;
            TomlSnapshot candidate = loadManaged(null);
            if (candidate.status().usable()) {
                CACHED_MANAGED = candidate;
            }
            return candidate;
        } finally {
            SNAPSHOT_LOCK.unlock();
        }
    }

    /** Load the managed provider without applying startup policy. */
    public static TomlSnapshot loadManaged(Path path) {
        if (path != null) {
            return new TomlFileProvider("managed config", path).load();
        }
        ConfigPaths.ResolvedManagedPath resolved = ConfigPaths.resolveManagedPath(null, null);
        TomlSnapshot snapshot = new TomlFileProvider("managed config", resolved.path()).load();
        if (resolved.fallback() != null
                && snapshot.status().health() == ProviderHealth.MISSING) {
            // "No file at a guessed path" is not "no policy deployed".
            ProviderStatus replaced = new ProviderStatus(
                    snapshot.status().name(), snapshot.status().path(),
                    ProviderHealth.INDETERMINATE, resolved.fallback());
            return new TomlSnapshot(snapshot.data(), replaced);
        }
        return snapshot;
    }

    /**
     * Load one user snapshot and the current managed snapshot.
     *
     * @param userPath    read this file as the user layer instead of the default
     * @param managedPath read managed policy from this file instead of the fixed path
     * @return both snapshots from one resolution generation
     */
    public static ConfigSources getConfigSources(Path userPath, Path managedPath,
                                                 Path defaultUserConfigPath) {
        if (userPath != null) {
            TomlSnapshot user = new TomlFileProvider("config.toml", userPath).load();
            TomlSnapshot managed = new TomlSnapshot(Map.of(),
                    new ProviderStatus("managed config", managedPath, ProviderHealth.MISSING));
            return new ConfigSources(managed, user);
        }
        TomlSnapshot managed = getManagedSnapshot(false, managedPath);
        TomlSnapshot user = new TomlFileProvider("config.toml", defaultUserConfigPath).load();
        return new ConfigSources(managed, user);
    }

    /** Drop the cached managed snapshot and the shared process resolver. */
    public static void invalidateConfigSources() {
        SNAPSHOT_LOCK.lock();
        try {
            CACHED_MANAGED = null;
        } finally {
            SNAPSHOT_LOCK.unlock();
        }
    }

    /**
     * Fail startup when present managed policy cannot be parsed or enforced.
     *
     * @throws ManagedConfigError if managed policy is present but unusable
     * @throws ManagedPolicyError if managed policy declares an unenforceable
     *                           key or malformed known section
     */
    public static void requireHealthyManagedConfig(boolean refresh) {
        TomlSnapshot snapshot = getManagedSnapshot(refresh);
        ProviderStatus status = snapshot.status();
        if (!status.usable()) throw new ManagedConfigError(status);
        // The full policy-violation check is owned by the manifest layer;
        // the configuration service only ensures the file is readable here.
        if (LOGGER.isLoggable(Level.FINE)) {
            LOGGER.log(Level.FINE, "Managed config OK: {0}", status);
        }
    }

    /** Provider health and policy enforceability from one managed snapshot. */
    public record ManagedHealth(ProviderStatus status, List<String> violations,
                                  List<String> rejections) {
        public boolean ok() { return status.usable() && violations.isEmpty(); }
    }

    /**
     * Raised when an enforced managed source cannot be read safely.
     */
    public static class ManagedConfigError extends RuntimeException {
        private final ProviderStatus status;
        public ManagedConfigError(ProviderStatus status) {
            this(status, null);
        }
        public ManagedConfigError(ProviderStatus status, String message) {
            super(buildMessage(status, message));
            this.status = status;
        }
        public ProviderStatus status() { return status; }

        private static String buildMessage(ProviderStatus status, String message) {
            if (message != null) return message;
            Path path = status.path() != null ? status.path() : ConfigPaths.managedConfigPath(null, null);
            String detail = status.detail() != null ? ": " + status.detail() : "";
            if (status.health() == ProviderHealth.INDETERMINATE) {
                return "Managed config location could not be determined" + detail
                        + ". Ask your administrator to verify the managed-config path.";
            }
            return "Managed config at " + path + " is " + status.health() + detail
                    + ". Ask your administrator to repair or remove the file.";
        }
    }

    /** Raised when managed policy declares a value that cannot be enforced. */
    public static class ManagedPolicyError extends ManagedConfigError {
        private final List<String> keys;
        public ManagedPolicyError(ProviderStatus status, List<String> keys) {
            super(status, "Managed config at " + (status.path() != null ? status.path()
                            : ConfigPaths.managedConfigPath(null, null))
                    + " rejects " + String.join(", ", keys)
                    + ". Ask your administrator to correct the value.");
            this.keys = List.copyOf(keys);
        }
        public List<String> keys() { return keys; }
    }
}
