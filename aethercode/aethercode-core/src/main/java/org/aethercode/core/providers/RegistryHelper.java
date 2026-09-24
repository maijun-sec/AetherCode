package org.aethercode.core.providers;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/**
 * R341 — cross-scope environment variable reader.
 *
 * <p>On Windows, env vars live in three scopes:
 * <ul>
 *   <li><b>Process</b> — the current process's env. Inherited from
 *       the parent process (the AetherCode desktop). Java sees this
 *       via {@link System#getenv(String)}.</li>
 *   <li><b>User</b> — {@code HKCU\Environment}. Persisted by
 *       {@code setx VAR value}. Visible to every process the user
 *       launches AFTER the next explorer.exe refresh.</li>
 *   <li><b>Machine</b> — {@code HKLM\SYSTEM\CurrentControlSet\Control\
 *       Session Manager\Environment}. Persisted by {@code setx /m}.
 *       Visible to every process on the machine, but only if the
 *       parent process actually merged HKLM into its env at logon
 *       (which is the path that breaks for Windows-service-launched
 *       hosts — the original R341 motivation for this class).</li>
 * </ul>
 *
 * <p>{@link #readEnv(String)} walks all three in order, returning
 * the first non-blank hit. {@link ProviderSpec#apiKey()} builds on
 * this for the canonical 4-step chain.
 *
 * <p>On non-Windows platforms the registry lookups are skipped;
 * Process scope is the canonical view (no HKCU/HKLM equivalent
 * that the daemon would need to consult).
 *
 * <p><b>Implementation note</b>: we shell out to {@code reg.exe}
 * rather than link JNI/JNA, because the daemon's CLI module ships
 * stdlib-only and adding a native dep just to read HKCU would
 * regress the deploy story. {@code reg.exe} is bundled with every
 * Windows install since NT 4.0, so there's no portability tax.
 */
public final class RegistryHelper {

    private static final Logger LOG = LoggerFactory.getLogger(RegistryHelper.class);

    /** User-scope env registry path. */
    static final String USER_ENV_KEY = "HKCU\\Environment";

    /** Machine-scope env registry path. */
    static final String MACHINE_ENV_KEY =
            "HKLM\\SYSTEM\\CurrentControlSet\\Control\\Session Manager\\Environment";

    /** Maximum time we'll wait for {@code reg.exe} to return.
     *  Cold registry cache can spike to ~3s on heavily-loaded
     *  workstations; 5s leaves slack. */
    static final int REG_QUERY_TIMEOUT_SECONDS = 5;

    /** Depth cap for {@code REG_EXPAND_SZ} expansion. Bounds
     *  pathological %{FOO}% cycles from spinning startup. */
    static final int EXPAND_DEPTH_CAP = 10;

    private RegistryHelper() {}

    /** Read the first non-blank value of {@code name} across
     *  Process → User → Machine scopes. Returns {@code null}
     *  when nothing is set anywhere. Safe to call concurrently
     * — no mutable state. */
    public static String readEnv(String name) {
        if (name == null || name.isBlank()) return null;
        // 1. Process scope (inherited from desktop)
        String v = System.getenv(name);
        if (v != null && !v.isBlank()) return v;
        if (!isWindows()) return null;
        // 2. User scope (HKCU\Environment)
        v = readWindowsEnv(USER_ENV_KEY, name);
        if (v != null && !v.isBlank()) return v;
        // 3. Machine scope (HKLM\...)
        v = readWindowsEnv(MACHINE_ENV_KEY, name);
        return v;  // null ok
    }

    /** True if any of the three scopes has a non-blank value
     *  for {@code name}. The renderer's Settings panel uses
     *  this for a coarse "is the key configured anywhere"
     *  hint before the per-scope lookup. */
    public static boolean isDefined(String name) {
        return readEnv(name) != null;
    }

    /** Read a single Windows env var from the registry at the
     *  given key path. Returns {@code null} when the var is
     *  not set, the key is absent, the platform is not
     *  Windows, or {@code reg.exe} fails. The {@code reg.exe}
     *  call is bounded by {@link #REG_QUERY_TIMEOUT_SECONDS}
     *  so a frozen registry can't hang daemon startup. */
    static String readWindowsEnv(String key, String name) {
        try {
            // ProcessBuilder passes each arg as a separate token
            // to CreateProcess, so the registry path doesn't need
            // shell-style quoting (the legacy escape dance with
            // "\\\" was correct for cmd /c but unnecessary here).
            Process p = new ProcessBuilder(
                            "reg.exe", "query",
                            key, "/v", name)
                    .redirectErrorStream(true)
                    .start();
            boolean exited = p.waitFor(REG_QUERY_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (!exited) {
                p.destroyForcibly();
                LOG.warn("reg.exe timed out reading {} from {}", name, key);
                return null;
            }
            if (p.exitValue() != 0) {
                // exit 1 = "unable to find the specified registry value or key"
                // — this is the common case when the var is simply not set.
                // Don't log at warn level to avoid spamming startup when
                // many providers probe many keys.
                return null;
            }
            // reg.exe writes stdout as UTF-8 on modern Windows
            // (verified empirically in R341 — UTF-16LE is what
            // some older docs claim but the live binary emits
            // ASCII / UTF-8 with the locale codepage fallback).
            // UTF-8 covers both safely (CP-1252 / GBK outputs are
            // valid UTF-8 when restricted to ASCII characters,
            // which is everything reg.exe prints).
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    // Output line shape:
                    //   "    VAR_NAME    REG_SZ    value"
                    // or "    VAR_NAME    REG_EXPAND_SZ    %PATH%;..."
                    String trimmed = line.trim();
                    String[] parts = trimmed.split("\\s+", 3);
                    if (parts.length >= 3 && parts[0].equalsIgnoreCase(name)) {
                        String raw = parts[2];
                        if (raw.indexOf('%') >= 0) {
                            return expandEnvVars(raw);
                        }
                        return raw;
                    }
                }
            }
            return null;
        } catch (Exception e) {
            LOG.debug("reg.exe query failed for {} from {}: {}", name, key, e.getMessage());
            return null;
        }
    }

    /** Expand {@code %FOO%} references in a Windows
     *  {@code REG_EXPAND_SZ} value. The expansion itself
     *  uses {@link System#getenv(String)} — the expanded
     *  form doesn't need cross-scope lookup; the registry
     *  stored whatever the user meant when the value was
     *  last touched. Depth-bounded to {@link #EXPAND_DEPTH_CAP}
     *  so a pathological {@code %FOO% = %FOO%} loop can't
     *  hang startup. */
    private static String expandEnvVars(String raw) {
        if (raw == null || raw.isEmpty()) return raw;
        String out = raw;
        for (int i = 0; i < EXPAND_DEPTH_CAP; i++) {
            int s = out.indexOf('%');
            if (s < 0) break;
            int e = out.indexOf('%', s + 1);
            if (e < 0) break;
            String key = out.substring(s + 1, e);
            String v = System.getenv(key);
            if (v == null) break;
            out = out.substring(0, s) + v + out.substring(e + 1);
        }
        return out;
    }

    /** True on any Windows-style OS (Windows, Windows Server).
     *  Used to skip the {@code reg.exe} call on macOS / Linux
     *  where Process scope IS the canonical view (no HKCU/HKLM
     *  equivalent that the daemon would need to consult). */
    private static boolean isWindows() {
        String os = System.getProperty("os.name");
        return os != null && os.toLowerCase().startsWith("windows");
    }
}