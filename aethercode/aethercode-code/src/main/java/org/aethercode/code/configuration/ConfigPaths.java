package org.aethercode.code.configuration;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Logger;

/**
 * Fixed operating-system paths for managed configuration.
 *
 * <p>Java 21 port of the Python
 * {@code deepagents_code.configuration.paths} module.</p>
 */
public final class ConfigPaths {
    private static final Logger LOGGER = Logger.getLogger(ConfigPaths.class.getName());

    /** Hardcoded fallback for Windows ProgramData lookup. */
    public static final String PROGRAM_DATA_DEFAULT = "C:/ProgramData";

    private ConfigPaths() {}

    /**
     * Where managed policy is read from, and whether that location is
     * certain. The fallback holds why the path is a guess, or
     * {@code null} when the path is authoritative.
     */
    public record ResolvedManagedPath(Path path, String fallback) {
        public boolean isAuthoritative() {
            return fallback == null;
        }
    }

    /**
     * Read ProgramData from the Windows registry if available.
     *
     * @return registry-reported path and {@code null} on success, or
     *     {@code null} and the reason the lookup failed. Off-Windows
     *     both entries are {@code null}.
     */
    public static RegistryLookup programDataFromRegistry() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (!os.contains("win")) {
            return new RegistryLookup(null, null);
        }
        try {
            Process proc = new ProcessBuilder("reg", "query",
                    "HKLM\\SOFTWARE\\Microsoft\\Windows\\CurrentVersion\\Explorer\\Shell Folders",
                    "/v", "Common AppData")
                    .redirectErrorStream(true)
                    .start();
            String output;
            try (var in = proc.getInputStream()) {
                output = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            }
            int code = proc.waitFor();
            if (code != 0) {
                LOGGER.warning("Could not read ProgramData from registry (exit=" + code
                        + "); falling back to " + PROGRAM_DATA_DEFAULT);
                return new RegistryLookup(null,
                        "ProgramData could not be read from the registry (exit=" + code
                                + "); looked under " + PROGRAM_DATA_DEFAULT);
            }
            for (String line : output.split("\\R")) {
                if (line.contains("Common AppData") || line.contains("CommonAppData")) {
                    int idx = line.lastIndexOf("REG_SZ");
                    if (idx < 0) {
                        idx = line.lastIndexOf("REG_EXPAND_SZ");
                    }
                    if (idx >= 0) {
                        String value = line.substring(idx).replaceFirst("^\\S+\\s+", "").trim();
                        if (!value.isEmpty()) {
                            return new RegistryLookup(value, null);
                        }
                    }
                }
            }
            LOGGER.warning("Registry ProgramData value is unusable; falling back to "
                    + PROGRAM_DATA_DEFAULT);
            return new RegistryLookup(null,
                    "registry ProgramData value is unusable; looked under " + PROGRAM_DATA_DEFAULT);
        } catch (IOException | InterruptedException exc) {
            if (exc instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            LOGGER.warning("Could not read ProgramData from the registry ("
                    + exc.getClass().getSimpleName() + "); falling back to " + PROGRAM_DATA_DEFAULT);
            return new RegistryLookup(null,
                    "ProgramData could not be read from the registry ("
                            + exc.getClass().getSimpleName() + "); looked under " + PROGRAM_DATA_DEFAULT);
        }
    }

    /** Result of a ProgramData registry lookup. */
    public record RegistryLookup(String value, String fallback) {}

    /**
     * Resolve the real ProgramData directory, ignoring process env vars.
     *
     * <p>{@code %ProgramData%} can be redefined by any unprivileged user
     * in their own shell, which would redirect the managed-config lookup
     * to a user-controlled path and silently drop (or replace)
     * administrator policy. Read the value from the registry and fall
     * back to the hardcoded default only if the registry query fails.</p>
     */
    public static ProgramDataResult windowsProgramData(Map<String, String> environ) {
        if (environ != null) {
            String value = environ.get("ProgramData");
            if (value == null || value.isEmpty()) {
                value = environ.get("PROGRAMDATA");
            }
            if (value == null || value.isEmpty()) {
                value = PROGRAM_DATA_DEFAULT;
            }
            return new ProgramDataResult(value, null);
        }
        RegistryLookup lookup = programDataFromRegistry();
        return new ProgramDataResult(
                lookup.value() != null ? lookup.value() : PROGRAM_DATA_DEFAULT,
                lookup.fallback());
    }

    /** Result of resolving the ProgramData directory. */
    public record ProgramDataResult(String root, String fallback) {}

    /** Map one platform to its fixed managed-config path. */
    public static ResolvedManagedPath resolve(String platform, Map<String, String> environ) {
        String active = platform != null ? platform : systemPlatform();
        if ("darwin".equals(active)) {
            return new ResolvedManagedPath(
                    Path.of("/Library/Application Support/dcode/managed_config.toml"),
                    null);
        }
        if ("win32".equals(active)) {
            ProgramDataResult result = windowsProgramData(environ);
            return new ResolvedManagedPath(
                    Path.of(result.root(), "dcode", "managed_config.toml"),
                    result.fallback());
        }
        return new ResolvedManagedPath(Path.of("/etc/dcode/managed_config.toml"), null);
    }

    /**
     * Return the fixed managed-config path and whether it is authoritative.
     *
     * <p>What the snapshot loader reads. Callers that report health need
     * this rather than {@link #managedConfigPath}, so a guessed path is
     * never mistaken for an authoritative one.</p>
     */
    public static ResolvedManagedPath resolveManagedPath(String platform,
                                                        Map<String, String> environ) {
        return resolve(platform, environ);
    }

    /**
     * Return the fixed managed-config path for the current OS. For
     * display and error messages.
     */
    public static Path managedConfigPath(String platform, Map<String, String> environ) {
        return resolve(platform, environ).path();
    }

    private static String systemPlatform() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("mac") || os.contains("darwin")) return "darwin";
        if (os.contains("win")) return "win32";
        return "linux";
    }
}
