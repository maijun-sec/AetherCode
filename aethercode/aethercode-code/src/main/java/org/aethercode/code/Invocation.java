package org.aethercode.code;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Resolution of the command name this process was launched with.
 *
 * <p>Java-native port of the Python {@code deepagents_code._invocation} module.
 * {@code dcode} is only one of the names that reach this code; the package
 * ships both {@code deepagents-code} and {@code dcode} console scripts, and
 * per-project shims (a renamed symlink pointing at the worktree) are a
 * common way to run several checkouts side by side. Hardcoding {@code dcode}
 * would tell those users to run a command that may not exist.</p>
 */
public final class Invocation {
    private Invocation() {}

    private static final Logger LOG = LoggerFactory.getLogger(Invocation.class);

    /** Command name assumed when the launch name cannot be determined. */
    public static final String DEFAULT_INVOKED_NAME = "dcode";

    /** Console scripts shipped in the project ({@code [project.scripts]}). */
    public static final Set<String> STANDARD_INVOKED_NAMES = Set.of("dcode", "deepagents-code");

    /** Env-var consulted for an explicit invoke-name override. */
    public static final String INVOKED_AS = "DEEPAGENTS_CODE_INVOKED_AS";

    private static final int MAX_NAME_LENGTH = 64;
    private static final Pattern SAFE_NAME_RE = Pattern.compile("\\A[A-Za-z0-9][A-Za-z0-9._+\\-]*\\Z");
    private static final String WINDOWS_EXECUTABLE_SUFFIX = ".exe";

    /**
     * Return {@code raw} as a command name, or {@code null} when it is not
     * plausible.
     */
    public static String sanitize(String raw) {
        if (raw == null) return null;
        String name = raw.strip();
        if (name.toLowerCase(Locale.ROOT).endsWith(WINDOWS_EXECUTABLE_SUFFIX)) {
            name = name.substring(0, name.length() - WINDOWS_EXECUTABLE_SUFFIX.length());
        }
        if (name.isEmpty() || name.length() > MAX_NAME_LENGTH) {
            return null;
        }
        if (name.endsWith(".py") || name.toLowerCase(Locale.ROOT).startsWith("python")) {
            return null;
        }
        if (!SAFE_NAME_RE.matcher(name).matches()) {
            return null;
        }
        return name;
    }

    /**
     * Return the command name this process was launched with. Cached because
     * the launch environment is fixed for the life of the JVM.
     */
    public static String invokedName() {
        return invokedName(System.getenv(), firstNonNull(System.getProperty("argv0"), ""));
    }

    /** Variant that accepts an explicit env map and an explicit {@code argv[0]}. */
    public static String invokedName(Map<String, String> env, String argv0) {
        String override = env == null ? null : env.get(INVOKED_AS);
        if (override != null) {
            String name = sanitize(override);
            if (name != null) {
                return name;
            }
            LOG.debug("Ignoring implausible {} value", INVOKED_AS);
        }
        if (argv0 != null && !argv0.isEmpty()) {
            String base = Path.of(argv0).getFileName().toString();
            String name = sanitize(base);
            if (name != null) {
                return name;
            }
        }
        return DEFAULT_INVOKED_NAME;
    }

    /**
     * Note a non-standard launch name in the debug console, once per process.
     */
    public static void logNonstandardInvokedName() {
        String name = invokedName();
        if (STANDARD_INVOKED_NAMES.contains(name)) {
            return;
        }
        LOG.info("Invoked as non-standard command '{}'; resume hints will use this name", name);
    }

    private static String firstNonNull(String a, String b) {
        return a == null ? b : a;
    }
}
