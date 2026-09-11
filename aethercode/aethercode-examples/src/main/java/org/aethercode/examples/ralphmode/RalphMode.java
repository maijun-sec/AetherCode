package org.aethercode.examples.ralphmode;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.IntFunction;

/**
 * Ralph Mode &mdash; autonomous looping for Deep Agents.
 *
 * <p>Java port of
 * {@code deepagents-main/examples/ralph_mode/ralph_mode.py}. Each
 * iteration starts with a fresh context; the filesystem and git
 * serve as the agent's memory across iterations.</p>
 *
 * <p>The Python port delegates the per-iteration agent invocation to
 * {@code deepagents_cli.non_interactive.run_non_interactive}. The
 * Java port does not have an equivalent runtime yet, so this class
 * exposes a {@link PerIterationRunner} interface that callers can
 * implement against whatever agent runtime they want. The bundled
 * {@link #main(String[])} entry point uses a stub runner that just
 * logs the iteration prompt.</p>
 */
public final class RalphMode {
    private RalphMode() {}

    /** Exit code for a graceful user-initiated stop. */
    public static final int EXIT_INTERRUPTED = 130;

    /**
     * A single iteration prompt template. Mirrors the Python port's
     * f-string at the top of {@code ralph(...)}.
     */
    public static String buildPrompt(int iteration, int maxIterations, String task) {
        String display = maxIterations > 0
                ? iteration + "/" + maxIterations
                : Integer.toString(iteration);
        return "## Ralph Iteration " + display + "\n\n"
                + "Your previous work is in the filesystem. "
                + "Check what exists and keep building.\n\n"
                + "TASK:\n" + task + "\n\n"
                + "Make progress. You'll be called again.";
    }

    /**
     * Pluggable iteration runner. The default
     * {@link PerIterationRunner#stub()} returns {@code 0} after
     * logging the prompt; callers can replace it with a real
     * agent runtime.
     */
    @FunctionalInterface
    public interface PerIterationRunner {
        /** Run a single iteration. Returns the process exit code. */
        int runIteration(int iteration, int maxIterations, String task, RalphConfig config);

        /** A no-op runner that just logs the prompt and returns 0. */
        static PerIterationRunner stub() {
            return (iteration, max, task, cfg) -> {
                System.out.println(buildPrompt(iteration, max, task));
                return 0;
            };
        }
    }

    /** Configuration for one {@link #ralph} run. */
    public record RalphConfig(
            String modelName,
            Map<String, Object> modelParams,
            String sandboxType,
            String sandboxId,
            String sandboxSetup,
            boolean stream) {

        public RalphConfig {
            modelParams = modelParams == null ? Map.of() : Map.copyOf(modelParams);
        }

        public static Builder builder() { return new Builder(); }

        public static final class Builder {
            private String modelName;
            private Map<String, Object> modelParams = Map.of();
            private String sandboxType = "none";
            private String sandboxId;
            private String sandboxSetup;
            private boolean stream = true;

            public Builder modelName(String v) { this.modelName = v; return this; }
            public Builder modelParams(Map<String, Object> v) { this.modelParams = v; return this; }
            public Builder sandboxType(String v) { this.sandboxType = v; return this; }
            public Builder sandboxId(String v) { this.sandboxId = v; return this; }
            public Builder sandboxSetup(String v) { this.sandboxSetup = v; return this; }
            public Builder stream(boolean v) { this.stream = v; return this; }
            public RalphConfig build() {
                return new RalphConfig(modelName, modelParams, sandboxType,
                        sandboxId, sandboxSetup, stream);
            }
        }
    }

    /**
     * Run the Ralph loop.
     *
     * @param task declarative description of what to build.
     * @param maxIterations maximum iterations; 0 means unlimited.
     * @param config runner configuration.
     * @param workPath working directory the agent operates on.
     * @param runner per-iteration runner; the per-iteration agent
     *                invocation is delegated through this hook.
     */
    public static void ralph(String task,
                             int maxIterations,
                             RalphConfig config,
                             Path workPath,
                             PerIterationRunner runner) {
        System.out.println("Ralph Mode");
        System.out.println("Task: " + task);
        String itersLabel = maxIterations == 0
                ? "unlimited (Ctrl+C to stop)"
                : Integer.toString(maxIterations);
        System.out.println("Iterations: " + itersLabel);
        if (config.modelName() != null) {
            System.out.println("Model: " + config.modelName());
        }
        if (!"none".equals(config.sandboxType())) {
            String label = config.sandboxType()
                    + (config.sandboxId() == null ? "" : " (id: " + config.sandboxId() + ")");
            System.out.println("Sandbox: " + label);
        }
        System.out.println("Working directory: " + workPath);

        AtomicBoolean stopped = new AtomicBoolean(false);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            if (stopped.compareAndSet(false, true)) {
                System.out.println("\n[stopped via signal]");
            }
        }));

        int iteration = 1;
        while (maxIterations == 0 || iteration <= maxIterations) {
            if (stopped.get()) break;
            String separator = "=".repeat(60);
            System.out.println("\n" + separator);
            System.out.println("RALPH ITERATION " + iteration);
            System.out.println(separator + "\n");

            int exitCode;
            try {
                exitCode = runner.runIteration(iteration, maxIterations, task, config);
            } catch (RuntimeException exc) {
                System.out.println("Iteration " + iteration + " failed: " + exc.getMessage());
                break;
            }
            if (exitCode == EXIT_INTERRUPTED) break;
            if (exitCode != 0) {
                System.out.println("Iteration " + iteration + " exited with code " + exitCode);
            }
            System.out.println("\n...continuing to iteration " + (iteration + 1));
            iteration++;
        }
        System.out.println("\nFiles in " + workPath + ":");
        try (var stream = Files.walk(workPath)) {
            stream.filter(Files::isRegularFile)
                    .filter(p -> !p.toString().contains(".git"))
                    .sorted()
                    .forEach(p -> System.out.println("  " + workPath.relativize(p)));
        } catch (Exception exc) {
            // Best-effort listing.
        }
    }

    /**
     * Parse a simple {@code key=value} string list (one entry per
     * JSON object) into a typed map. Mirrors the {@code --model-params}
     * flag from the Python port.
     */
    public static Map<String, Object> parseModelParams(String json) {
        if (json == null || json.isBlank()) return Map.of();
        Map<String, Object> out = new LinkedHashMap<>();
        // Tiny parser: handles {"key": value, "key2": "string"} only.
        String body = json.strip();
        if (body.startsWith("{") && body.endsWith("}")) body = body.substring(1, body.length() - 1);
        for (String pair : body.split(",")) {
            String trimmed = pair.strip();
            if (trimmed.isEmpty()) continue;
            int colon = trimmed.indexOf(':');
            if (colon < 0) continue;
            String key = trimmed.substring(0, colon).strip().replace("\"", "");
            String value = trimmed.substring(colon + 1).strip();
            if (value.startsWith("\"") && value.endsWith("\"")) {
                out.put(key, value.substring(1, value.length() - 1));
            } else if ("true".equals(value) || "false".equals(value)) {
                out.put(key, Boolean.parseBoolean(value));
            } else {
                try { out.put(key, Long.parseLong(value)); }
                catch (NumberFormatException ex) {
                    try { out.put(key, Double.parseDouble(value)); }
                    catch (NumberFormatException ex2) { out.put(key, value); }
                }
            }
        }
        return out;
    }

    /** Convenience main entry point mirroring the Python port. */
    public static void main(String[] args) {
        List<String> argv = new ArrayList<>(List.of(args));
        if (argv.isEmpty()) {
            System.out.println("Usage: ralph <task> [--iterations N] [--model spec] [--work-dir path] "
                    + "[--sandbox kind] [--sandbox-id id] [--sandbox-setup script] "
                    + "[--model-params '{...}'] [--no-stream]");
            return;
        }
        String task = argv.remove(0);
        int iterations = 0;
        String model = null;
        Path workDir = Path.of(".").toAbsolutePath();
        String sandbox = "none";
        String sandboxId = null;
        String sandboxSetup = null;
        boolean stream = true;
        Map<String, Object> modelParams = Map.of();
        for (int i = 0; i < argv.size(); i++) {
            String a = argv.get(i);
            switch (a) {
                case "--iterations" -> iterations = Integer.parseInt(argv.get(++i));
                case "--model" -> model = argv.get(++i);
                case "--work-dir" -> workDir = Path.of(argv.get(++i)).toAbsolutePath();
                case "--sandbox" -> sandbox = argv.get(++i);
                case "--sandbox-id" -> sandboxId = argv.get(++i);
                case "--sandbox-setup" -> sandboxSetup = argv.get(++i);
                case "--model-params" -> modelParams = parseModelParams(argv.get(++i));
                case "--no-stream" -> stream = false;
                default -> System.out.println("[warn] unknown flag: " + a);
            }
        }
        RalphConfig cfg = RalphConfig.builder()
                .modelName(model)
                .modelParams(modelParams)
                .sandboxType(sandbox)
                .sandboxId(sandboxId)
                .sandboxSetup(sandboxSetup)
                .stream(stream)
                .build();
        final Path finalWorkDir = workDir;
        IntFunction<Path> work = i -> finalWorkDir;
        ralph(task, iterations, cfg, finalWorkDir, PerIterationRunner.stub());
    }
}
