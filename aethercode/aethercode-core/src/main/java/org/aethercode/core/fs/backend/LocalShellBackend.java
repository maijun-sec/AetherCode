package org.aethercode.core.fs.backend;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * {@link FilesystemBackend} extended with a local shell {@code execute()}.
 *
 * <p>Java-native port of deepagents <code>LocalShellBackend</code>. Commands
 * run as host subprocesses through the system shell with the backend's
 * {@code root_dir} as the working directory. <strong>No sandboxing or
 * isolation</strong> &mdash; the agent can read or modify any file the user
 * can. The virtual-mode filesystem guard still applies to file ops but does
 * not restrict shell commands (by design).</p>
 */
public class LocalShellBackend extends FilesystemBackend implements SandboxBackendProtocol {

    /** Default per-command timeout in seconds. */
    public static final int DEFAULT_EXECUTE_TIMEOUT = 120;
    /** Default cap on combined stdout/stderr bytes captured. */
    public static final int DEFAULT_MAX_OUTPUT_BYTES = 100_000;
    /** Exit code returned by the wrapper on a real timeout. */
    public static final int TIMEOUT_EXIT_CODE = 124;

    private final int defaultTimeout;
    private final int maxOutputBytes;
    private final Map<String, String> env;
    private final String sandboxId;

    public LocalShellBackend() {
        this((Path) null, true, DEFAULT_EXECUTE_TIMEOUT, DEFAULT_MAX_OUTPUT_BYTES, null, false);
    }

    public LocalShellBackend(String rootDir, boolean virtualMode) {
        this(rootDir == null ? null : Paths.get(rootDir),
                virtualMode, DEFAULT_EXECUTE_TIMEOUT, DEFAULT_MAX_OUTPUT_BYTES, null, false);
    }

    public LocalShellBackend(String rootDir, boolean virtualMode, int timeout,
                             int maxOutputBytes, Map<String, String> env, boolean inheritEnv) {
        this(rootDir == null ? null : Paths.get(rootDir),
                virtualMode, timeout, maxOutputBytes, env, inheritEnv);
    }

    public LocalShellBackend(Path rootDir, boolean virtualMode, int timeout,
                             int maxOutputBytes, Map<String, String> env, boolean inheritEnv) {
        super(rootDir, virtualMode, 10);
        if (timeout <= 0) {
            throw new IllegalArgumentException("timeout must be positive, got " + timeout);
        }
        this.defaultTimeout = timeout;
        this.maxOutputBytes = maxOutputBytes;
        if (inheritEnv) {
            Map<String, String> base = new HashMap<>(System.getenv());
            if (env != null) base.putAll(env);
            this.env = base;
        } else {
            this.env = env == null ? new HashMap<>() : new HashMap<>(env);
        }
        this.sandboxId = "local-" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    }

    /** Convenience constructor matching the Python port's keyword signature. */
    public LocalShellBackend(String rootDir, boolean virtualMode, int timeout) {
        this(rootDir, virtualMode, timeout, DEFAULT_MAX_OUTPUT_BYTES, null, false);
    }

    public LocalShellBackend(String rootDir, boolean virtualMode, int timeout,
                             int maxOutputBytes) {
        this(rootDir, virtualMode, timeout, maxOutputBytes, null, false);
    }

    @Override
    public String id() {
        return sandboxId;
    }

    public int defaultTimeout() { return defaultTimeout; }
    public int maxOutputBytes() { return maxOutputBytes; }
    public Map<String, String> env() { return env; }

    // -----------------------------------------------------------------
    //  execute
    // -----------------------------------------------------------------

    @Override
    public ExecuteResponse execute(String command, Integer timeout) {
        if (command == null || command.isEmpty()) {
            return ExecuteResponse.of(
                    "Error: Command must be a non-empty string.",
                    1, false);
        }
        int effective = timeout != null ? timeout : defaultTimeout;
        if (effective <= 0) {
            throw new IllegalArgumentException("timeout must be positive, got " + effective);
        }
        try {
            ProcessBuilder pb = new ProcessBuilder();
            // On Windows we use cmd.exe; on Unix we use sh. The Python port uses
            // `shell=True` which maps to /bin/sh on POSIX and cmd on Windows.
            boolean isWindows = System.getProperty("os.name").toLowerCase().contains("win");
            if (isWindows) {
                pb.command("cmd.exe", "/c", command);
            } else {
                pb.command("/bin/sh", "-c", command);
            }
            pb.directory(cwd().toFile());
            pb.environment().clear();
            pb.environment().putAll(env);
            pb.redirectInput(ProcessBuilder.Redirect.from(new java.io.File(
                    isWindows ? "NUL" : "/dev/null")));
            Process proc = pb.start();
            // Drain stdout and stderr concurrently to avoid blocking the child.
            StringBuilder stdout = new StringBuilder();
            StringBuilder stderr = new StringBuilder();
            Thread outThread = drainAsync(proc.getInputStream(), stdout);
            Thread errThread = drainAsync(proc.getErrorStream(), stderr);
            boolean finished;
            try {
                finished = proc.waitFor(effective, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                proc.destroyForcibly();
                Thread.currentThread().interrupt();
                return ExecuteResponse.of(
                        "Error: command interrupted", 1, false);
            }
            if (!finished) {
                proc.destroyForcibly();
                try { outThread.join(2000); } catch (InterruptedException ignored) {}
                try { errThread.join(2000); } catch (InterruptedException ignored) {}
                String msg = timeout != null
                        ? "Error: Command timed out after " + effective
                                + " seconds (custom timeout). The command may be stuck or require more time."
                        : "Error: Command timed out after " + effective
                                + " seconds. For long-running commands, re-run using the timeout parameter.";
                return ExecuteResponse.of(msg, TIMEOUT_EXIT_CODE, false);
            }
            try { outThread.join(2000); } catch (InterruptedException ignored) {}
            try { errThread.join(2000); } catch (InterruptedException ignored) {}
            return buildResponse(proc.exitValue(), stdout.toString(), stderr.toString());
        } catch (IOException | RuntimeException e) {
            return ExecuteResponse.of(
                    "Error executing command (" + e.getClass().getSimpleName() + "): " + e.getMessage(),
                    1, false);
        }
    }

    @Override
    public CompletableFuture<ExecuteResponse> aexecute(String command, Integer timeout) {
        return CompletableFuture.supplyAsync(() -> execute(command, timeout));
    }

    private static Thread drainAsync(InputStream in, StringBuilder sink) {
        Thread t = new Thread(() -> {
            try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                char[] buf = new char[4096];
                int n;
                while ((n = r.read(buf)) != -1) {
                    sink.append(buf, 0, n);
                }
            } catch (IOException ignored) {
            }
        }, "shell-output");
        t.setDaemon(true);
        t.start();
        return t;
    }

    private ExecuteResponse buildResponse(int exitCode, String stdout, String stderr) {
        // Combine stdout and stderr into a single output, with each stderr line
        // prefixed by `[stderr]`. Matches the Python port exactly.
        StringBuilder combined = new StringBuilder();
        if (!stdout.isEmpty()) {
            combined.append(stdout);
        }
        if (!stderr.isEmpty()) {
            // Ensure separator between stdout and the first stderr line.
            if (combined.length() > 0 && !combined.toString().endsWith("\n")) {
                combined.append('\n');
            }
            String[] lines = stderr.strip().split("\n", -1);
            for (int i = 0; i < lines.length; i++) {
                String line = lines[i];
                if (line.isEmpty() && i == lines.length - 1) continue;
                combined.append("[stderr] ").append(line);
                if (i < lines.length - 1) combined.append('\n');
            }
        }
        String output = combined.length() == 0 ? "<no output>" : combined.toString();
        boolean truncated = false;
        if (output.length() > maxOutputBytes) {
            output = output.substring(0, maxOutputBytes)
                    + "\n\n... Output truncated at " + maxOutputBytes + " bytes.";
            truncated = true;
        }
        if (exitCode != 0) {
            // Append exit-code info on a fresh pair of lines.
            if (!output.endsWith("\n")) output = output + "\n";
            output = output + "\nExit code: " + exitCode;
        }
        return ExecuteResponse.of(output, exitCode, truncated);
    }
}
