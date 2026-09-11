package org.aethercode.talon;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.io.IOException;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Runtime configuration for a single Talon assistant process.
 *
 * <p>Java-native port of {@code deepagents_talon.config.TalonConfig}. The
 * record captures the (assistant id, per-assistant home, model, env) tuple
 * every other component reads at startup. Use {@link #fromEnv(Map, Path)}
 * to build one from environment variables.</p>
 *
 * <p><b>Note:</b> Talon is an experimental runtime and is subject to change
 * or removal at any time.</p>
 */
public record TalonConfig(
        String assistantId,
        Path home,
        String model,
        Map<String, String> env) {

    private static final Pattern ASSISTANT_ID_PATTERN = Pattern.compile("[A-Za-z0-9_.-]{1,128}");

    private static final String ENV_PREFIX = "DEEPAGENTS_TALON_";
    private static final String[] RUNTIME_ENV_PREFIXES = {
            "DEEPAGENTS_TALON_", "AGENT_", "LANGSMITH_", "OPENAI_", "SPEECH_", "TELEGRAM_"
    };
    private static final Set<String> RUNTIME_ENV_KEYS = Set.of(
            "BUILTIN_MCP_URL", "HOST_LANGCHAIN_API_URL", "TELEGRAM_BOT_TOKEN");

    public TalonConfig {
        if (assistantId == null) {
            throw new TalonConfigError("assistant id is required");
        }
        validateAssistantId(assistantId);
        env = env == null ? Map.of() : Map.copyOf(env);
    }

    /**
     * Build runtime configuration from environment variables.
     *
     * @param env environment mapping to read. Defaults to {@code System.getenv()}.
     * @param baseHome optional base directory for assistant state. Tests and
     *                 embedding hosts can supply this to avoid the user home
     *                 directory.
     * @return runtime configuration with a validated assistant id and
     *         namespaced home.
     * @throws TalonConfigError if the assistant id is empty or unsafe.
     */
    public static TalonConfig fromEnv(Map<String, String> env, Path baseHome) {
        Map<String, String> values = env != null ? env : System.getenv();
        String assistantId = firstPresent(values,
                new String[]{"DEEPAGENTS_TALON_ASSISTANT_ID", "AGENT_ASSISTANT_ID"},
                "default");
        validateAssistantId(assistantId);

        Path root;
        if (baseHome == null) {
            String configuredHome = values.get("DEEPAGENTS_TALON_HOME");
            root = (configuredHome == null || configuredHome.isEmpty())
                    ? Paths.get(System.getProperty("user.home")).resolve(".deepagents")
                    : Paths.get(configuredHome);
        } else {
            root = baseHome;
        }

        String model = firstPresent(values,
                new String[]{"DEEPAGENTS_TALON_MODEL", "AGENT_MODEL"},
                null);
        Map<String, String> runtimeEnv = new HashMap<>();
        for (Map.Entry<String, String> entry : values.entrySet()) {
            if (isRuntimeEnv(entry.getKey())) {
                runtimeEnv.put(entry.getKey(), entry.getValue());
            }
        }
        return new TalonConfig(assistantId, root, model, runtimeEnv);
    }

    /** Convenience overload that uses the OS environment. */
    public static TalonConfig fromEnv() {
        return fromEnv(null, null);
    }

    /**
     * Create the per-assistant home directory with restrictive permissions
     * (POSIX 0o700).
     *
     * @return the created per-assistant home directory.
     */
    public Path ensureHome() {
        try {
            java.util.Set<PosixFilePermission> homePerms = PosixFilePermissions.fromString("rwx------");
            try {
                Files.createDirectories(home,
                        PosixFilePermissions.asFileAttribute(homePerms));
            } catch (UnsupportedOperationException | SecurityException ex) {
                Files.createDirectories(home);
                setPosixPermissionsIfPossible(home, homePerms);
            }
            for (Path child : new Path[]{
                    manifestDir(), agentsDir(), cronDir(), channelDir(), inboundMediaDir()}) {
                java.util.Set<PosixFilePermission> childPerms =
                        PosixFilePermissions.fromString("rwx------");
                try {
                    Files.createDirectories(child, PosixFilePermissions.asFileAttribute(childPerms));
                } catch (UnsupportedOperationException | SecurityException ex) {
                    Files.createDirectories(child);
                    setPosixPermissionsIfPossible(child, childPerms);
                }
            }
        } catch (IOException e) {
            throw new RuntimeException("Failed to create Talon home " + home, e);
        }
        return home;
    }

    /** Directory where agent manifest files are materialized. */
    public Path manifestDir() {
        return home;
    }

    /** Directory reserved for custom subagent definitions. */
    public Path agentsDir() {
        return home.resolve("agents");
    }

    /** Directory reserved for scheduler state. */
    public Path cronDir() {
        return home.resolve("cron");
    }

    /** Directory reserved for channel session state. */
    public Path channelDir() {
        return home.resolve("channels");
    }

    /** Directory reserved for downloaded inbound channel media. */
    public Path inboundMediaDir() {
        return home.resolve("media").resolve("inbound");
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private static String firstPresent(Map<String, String> env,
                                       String[] keys,
                                       String defaultValue) {
        for (String key : keys) {
            if (env.containsKey(key)) {
                return env.get(key);
            }
        }
        return defaultValue;
    }

    private static void validateAssistantId(String assistantId) {
        if (assistantId == null
                || assistantId.isEmpty()
                || assistantId.equals(".")
                || assistantId.equals("..")
                || !ASSISTANT_ID_PATTERN.matcher(assistantId).matches()) {
            throw new TalonConfigError(
                    "assistant id must be 1-128 characters and contain only letters, "
                            + "numbers, underscore, hyphen, or dot");
        }
    }

    private static boolean isRuntimeEnv(String key) {
        if (RUNTIME_ENV_KEYS.contains(key)) {
            return true;
        }
        for (String prefix : RUNTIME_ENV_PREFIXES) {
            if (key.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    private static void setPosixPermissionsIfPossible(Path path,
                                                     java.util.Set<PosixFilePermission> perms) {
        try {
            Files.setPosixFilePermissions(path, perms);
        } catch (UnsupportedOperationException | IOException | SecurityException ignored) {
            // best-effort: not all filesystems / platforms support POSIX bits
        }
    }
}
