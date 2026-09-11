package org.aethercode.core.context;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * walk the working directory and capture enough context for the model to
 * feel grounded. Cheap, sync, never throws — if anything fails (no git, no
 * env), the corresponding field stays null and the prompt renders without it.
 */
public final class SessionContextCapturer {

    private static final Logger LOG = LoggerFactory.getLogger(SessionContextCapturer.class);

    /** The set of env vars copied into the prompt. Names are case-sensitive on Linux; we normalise. */
    public static final java.util.List<String> ENV_KEYS = java.util.List.of(
            "AETHERCODE_MODEL", "AETHERCODE_API_KEY", "USER", "USERNAME", "SHELL", "LANG");

    private final java.util.function.Function<Path, String[]> shellRunner;
    private final java.util.function.Supplier<String> hostnameSupplier;
    private final java.util.function.Supplier<String> osSupplier;
    private final java.util.function.Supplier<String> userSupplier;
    private final java.util.function.Supplier<Map<String, String>> envSupplier;

    public SessionContextCapturer() {
        this(SessionContextCapturer::runShell,
             () -> safeHost(),
             () -> System.getProperty("os.name"),
             () -> System.getProperty("user.name"),
             () -> System.getenv());
    }

    /** constructor for tests — pass in canned shell / env / etc. */
    public SessionContextCapturer(java.util.function.Function<Path, String[]> shellRunner,
                                  java.util.function.Supplier<String> hostnameSupplier,
                                  java.util.function.Supplier<String> osSupplier,
                                  java.util.function.Supplier<String> userSupplier,
                                  java.util.function.Supplier<Map<String, String>> envSupplier) {
        this.shellRunner = shellRunner;
        this.hostnameSupplier = hostnameSupplier;
        this.osSupplier = osSupplier;
        this.userSupplier = userSupplier;
        this.envSupplier = envSupplier;
    }

    public SessionContext capture(Path cwd) {
        SessionContext.Builder b = SessionContext.builder().cwd(cwd);
        b.os(osSupplier.get());
        b.hostname(hostnameSupplier.get());
        b.user(userSupplier.get());
        b.capturedAt(Instant.now());

        // git: only attempt when .git is present to avoid spurious errors
        if (java.nio.file.Files.isDirectory(cwd.resolve(".git"))) {
            try {
                String[] head = shellRunner.apply(cwd);
                if (head != null && head.length > 0 && !head[0].isBlank()) {
                    b.gitBranch(head[0].trim());
                }
                if (head != null && head.length > 1 && !head[1].isBlank()) {
                    b.gitHead(head[1].trim());
                }
                if (head != null && head.length > 2 && "1".equals(head[2].trim())) {
                    b.gitDirty(true);
                }
            } catch (Exception e) {
                LOG.debug("git capture failed: {}", e.getMessage());
            }
        }

        Map<String, String> envAll = envSupplier.get();
        if (envAll != null) {
            for (String k : ENV_KEYS) {
                String v = envAll.get(k);
                if (v != null) b.env(k, v);
            }
        }
        return b.build();
    }

    /** default shell runner — invokes {@code git branch --show-current / rev-parse --short HEAD / status --porcelain}. */
    private static String[] runShell(Path cwd) {
        try {
            String branch = runOnce(cwd, "git", "branch", "--show-current");
            String head = runOnce(cwd, "git", "rev-parse", "--short", "HEAD");
            String status = runOnce(cwd, "git", "status", "--porcelain");
            boolean dirty = status != null && !status.isBlank();
            return new String[]{branch == null ? "" : branch, head == null ? "" : head, dirty ? "1" : "0"};
        } catch (Exception e) {
            return new String[]{"", "", "0"};
        }
    }

    private static String runOnce(Path cwd, String... cmd) throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(cmd).directory(cwd.toFile()).redirectErrorStream(true);
        Process p = pb.start();
        byte[] out = p.getInputStream().readAllBytes();
        if (!p.waitFor(3, TimeUnit.SECONDS)) { p.destroyForcibly(); return null; }
        return new String(out, java.nio.charset.StandardCharsets.UTF_8).trim();
    }

    private static String safeHost() {
        try { return java.net.InetAddress.getLocalHost().getHostName(); }
        catch (Exception e) { return null; }
    }
}
