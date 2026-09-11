package org.aethercode.code;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Health diagnostics for the TUI.
 *
 * <p>Java-native port of the Python {@code deepagents_code.doctor} module.
 * The doctor command inspects the local environment, config files, and
 * dependency state and emits a report. Java port keeps the report shape
 * the same as the Python source of truth so the doctor output stays
 * machine-parseable.</p>
 */
public final class Doctor {
    private Doctor() {}

    private static final Logger LOG = LoggerFactory.getLogger(Doctor.class);

    /** Severity of a single diagnostic. */
    public enum Severity { OK, INFO, WARNING, ERROR }

    /** One health-check result. */
    public record Diagnostic(String name, Severity severity, String message) {
        public static Diagnostic ok(String name) { return new Diagnostic(name, Severity.OK, ""); }
        public static Diagnostic info(String name, String msg) { return new Diagnostic(name, Severity.INFO, msg); }
        public static Diagnostic warning(String name, String msg) { return new Diagnostic(name, Severity.WARNING, msg); }
        public static Diagnostic error(String name, String msg) { return new Diagnostic(name, Severity.ERROR, msg); }
    }

    /** The full doctor report. */
    public record Report(List<Diagnostic> diagnostics) {
        public boolean hasErrors() {
            return diagnostics.stream().anyMatch(d -> d.severity() == Severity.ERROR);
        }
    }

    /** Run the full diagnostic suite. */
    public static Report run() {
        List<Diagnostic> out = new ArrayList<>();
        out.add(checkJavaVersion());
        out.add(checkConfigDir());
        out.add(checkStateDir());
        out.add(checkMcpTokens());
        out.add(checkLogbackConfig());
        out.add(checkNetworkConnectivity());
        return new Report(out);
    }

    static Diagnostic checkJavaVersion() {
        String version = System.getProperty("java.version", "");
        if (version.startsWith("21") || version.startsWith("22") || version.startsWith("23")) {
            return Diagnostic.ok("java.version");
        }
        return Diagnostic.warning("java.version",
                "Java 21+ recommended; got " + version);
    }

    static Diagnostic checkConfigDir() {
        return checkWritableDir("config.dir", Path.of(System.getProperty("user.home"),
                ".deepagents"));
    }

    static Diagnostic checkStateDir() {
        return checkWritableDir("state.dir", Path.of(System.getProperty("user.home"),
                ".deepagents", ".state"));
    }

    static Diagnostic checkMcpTokens() {
        Path p = AuthStore.defaultPath();
        if (!Files.exists(p)) {
            return Diagnostic.info("mcp.tokens", "no credentials stored");
        }
        return Diagnostic.ok("mcp.tokens");
    }

    static Diagnostic checkLogbackConfig() {
        // SLF4J/Logback presence is the meaningful signal for the Java port.
        try {
            Class.forName("ch.qos.logback.classic.Logger");
            return Diagnostic.ok("logback");
        } catch (ClassNotFoundException e) {
            return Diagnostic.warning("logback", "Logback not on the runtime classpath");
        }
    }

    static Diagnostic checkNetworkConnectivity() {
        // The Java port doesn't probe the network from the doctor; the
        // production version should perform a HEAD request to the SDK's
        // version endpoint. The check is informational until that lands.
        return Diagnostic.info("network", "skipped (offline check not implemented)");
    }

    private static Diagnostic checkWritableDir(String name, Path path) {
        if (!Files.exists(path)) {
            try {
                Files.createDirectories(path);
                return Diagnostic.info(name, "created " + path);
            } catch (IOException e) {
                return Diagnostic.error(name, "could not create " + path + ": " + e.getMessage());
            }
        }
        if (!Files.isDirectory(path)) {
            return Diagnostic.error(name, path + " is not a directory");
        }
        try {
            Path probe = Files.createTempFile(path, ".doctor-probe-", "");
            Files.deleteIfExists(probe);
            return Diagnostic.ok(name);
        } catch (IOException e) {
            return Diagnostic.error(name, "not writable: " + e.getMessage());
        }
    }
}
