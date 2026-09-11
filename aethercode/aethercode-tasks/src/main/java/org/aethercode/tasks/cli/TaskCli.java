package org.aethercode.tasks.cli;

import org.aethercode.tasks.supervisor.SupervisorClient;
import org.aethercode.tasks.supervisor.SupervisorHome;
import org.aethercode.tasks.supervisor.SupervisorProcess;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

/**
 * prior round (T-380..T-385/§4.7 design.md + spec.md §4.7): the
 * {@code aethercode task ...} CLI surface. The CLI talks to
 * the supervisor over its lock-file-published TCP loopback
 * port. If the supervisor is not running, the CLI starts a
 * private one (in-process) and shuts it down on exit — the
 * canonical user experience is the long-lived one started by
 * the TUI or the desktop app, but for the headless CLI we
 * fall back to spawning one in the same JVM.
 *
 * <p>Subcommands:
 * <ul>
 *   <li>{@code task spawn <prompt>} — T-380</li>
 *   <li>{@code task list}           — T-381</li>
 *   <li>{@code task attach <id>}    — T-382</li>
 *   <li>{@code task kill <id>}      — T-383</li>
 *   <li>{@code task await <id>}     — T-384</li>
 *   <li>{@code task resume <id>}    — prior round bonus: surfacing
 *       the existing RPC for the headless user</li>
 *   <li>{@code task retry <id>}     — prior round bonus</li>
 *   <li>{@code task setLimits <id> k=v [...]}
 *                                   — prior round bonus: the T-353
 *       RPC exposed as a flag-driven subcommand</li>
 * </ul>
 *
 * <p>Wire-up: the {@link org.aethercode.cli.Main} CLI registers
 * this class as a subcommand via picocli. The TUI/desktop
 * manage their own supervisor and the CLI just connects.
 */
@Command(
        name = "task",
        mixinStandardHelpOptions = true,
        description = "Long-running background tasks (R321 / §4.7).",
        subcommands = {
                TaskCli.SpawnCommand.class,
                TaskCli.ListCommand.class,
                TaskCli.AttachCommand.class,
                TaskCli.KillCommand.class,
                TaskCli.AwaitCommand.class,
                TaskCli.ResumeCommand.class,
                TaskCli.RetryCommand.class,
                TaskCli.SetLimitsCommand.class,
        }
)
public final class TaskCli {

    @Command(name = "spawn", description = "Spawn a new background task (T-380).")
    public static class SpawnCommand implements Callable<Integer> {
        @Parameters(arity = "1..*", description = "Prompt to run in the background.")
        List<String> promptWords;

        @Option(names = {"--cwd"}, description = "Working directory. Default: current dir.")
        Path cwd = Path.of("").toAbsolutePath();

        @Option(names = {"--model"}, description = "Model name (e.g. 'MiniMax-M3').")
        String model;

        @Option(names = {"--wall-clock-ms"}, description = "Wall-clock cap.")
        Long wallClockMs;
        @Option(names = {"--tokens"}, description = "Token cap.")
        Long tokens;
        @Option(names = {"--calls"}, description = "LLM-call cap.")
        Long calls;
        @Option(names = {"--file-writes"}, description = "File-write cap.")
        Long fileWrites;
        @Option(names = {"--network"}, description = "Network-request cap.")
        Long network;

        @Override
        public Integer call() throws Exception {
            Map<String, Object> limits = new LinkedHashMap<>();
            if (wallClockMs != null) limits.put("wallClockMs", wallClockMs);
            if (tokens != null)      limits.put("tokens", tokens);
            if (calls != null)       limits.put("calls", calls);
            if (fileWrites != null)  limits.put("fileWrites", fileWrites);
            if (network != null)     limits.put("network", network);

            Map<String, Object> params = new LinkedHashMap<>();
            params.put("prompt", String.join(" ", promptWords));
            params.put("cwd", cwd.toString());
            if (model != null) params.put("model", model);
            if (!limits.isEmpty()) params.put("limits", limits);

            try (TaskSession s = TaskSession.connect()) {
                Map<String, Object> r = s.client.callMap("task/spawn", params);
                String id = (String) r.get("childId");
                System.out.println(id);
                return 0;
            }
        }
    }

    @Command(name = "list", description = "List background tasks (T-381).")
    public static class ListCommand implements Callable<Integer> {
        @Option(names = {"--status"}, description = "Filter by status: ${COMPLETION-CANDIDATES}.")
        String status;

        @Override
        public Integer call() throws Exception {
            Map<String, Object> params = new LinkedHashMap<>();
            if (status != null) params.put("status", status);
            try (TaskSession s = TaskSession.connect()) {
                List<Map<String, Object>> rows = s.client.callList("task/list", params);
                printListTable(rows);
                return 0;
            }
        }
    }

    @Command(name = "attach",
             description = "Attach to a background task and print its event log (T-382).")
    public static class AttachCommand implements Callable<Integer> {
        @Parameters(arity = "1", description = "Child id.")
        String childId;

        @Option(names = {"--since"}, description = "Event id cursor; 0 = all events.")
        long since = 0L;

        @Option(names = {"--limit"}, description = "Max events to print.")
        int limit = 1000;

        @Override
        public Integer call() throws Exception {
            Map<String, Object> params = new LinkedHashMap<>();
            params.put("childId", childId);
            params.put("since", since);
            params.put("limit", limit);
            try (TaskSession s = TaskSession.connect()) {
                Map<String, Object> r = s.client.callMap("task/attach", params);
                @SuppressWarnings("unchecked")
                Map<String, Object> child = (Map<String, Object>) r.get("child");
                System.out.println("child: " + child.get("id") + "  status=" + child.get("status"));
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> events = (List<Map<String, Object>>) r.get("events");
                for (Map<String, Object> e : events) {
                    System.out.println("  " + e.get("id") + "  " + e.get("type")
                            + "  " + e.get("payload"));
                }
                s.client.callMap("task/detach", Map.of("childId", childId));
                return 0;
            }
        }
    }

    @Command(name = "kill",
             description = "Kill a background task (T-383).")
    public static class KillCommand implements Callable<Integer> {
        @Parameters(arity = "1", description = "Child id.")
        String childId;

        @Option(names = {"--reason"}, description = "Optional kill reason.")
        String reason;

        @Override
        public Integer call() throws Exception {
            Map<String, Object> params = new LinkedHashMap<>();
            params.put("childId", childId);
            if (reason != null) params.put("reason", reason);
            try (TaskSession s = TaskSession.connect()) {
                Map<String, Object> r = s.client.callMap("task/kill", params);
                if (Boolean.TRUE.equals(r.get("ok"))) {
                    System.out.println("killed " + childId);
                    return 0;
                }
                System.err.println("kill failed: " + r);
                return 1;
            }
        }
    }

    @Command(name = "await",
             description = "Block until a background task reaches a terminal state (T-384).")
    public static class AwaitCommand implements Callable<Integer> {
        @Parameters(arity = "1", description = "Child id.")
        String childId;

        @Option(names = {"--timeout-ms"}, description = "Max wait in ms. Default 60_000.")
        long timeoutMs = 60_000L;

        @Override
        public Integer call() throws Exception {
            Map<String, Object> params = new LinkedHashMap<>();
            params.put("childId", childId);
            params.put("timeoutMs", timeoutMs);
            try (TaskSession s = TaskSession.connect()) {
                Map<String, Object> r = s.client.callMap("task/await", params);
                String status = (String) r.get("status");
                System.out.println(status);
                return switch (status) {
                    case "COMPLETED" -> 0;
                    case "FAILED", "KILLED" -> 2;
                    default -> 1;
                };
            }
        }
    }

    @Command(name = "resume", description = "Resume a paused background task.")
    public static class ResumeCommand implements Callable<Integer> {
        @Parameters(arity = "1", description = "Child id.")
        String childId;

        @Override
        public Integer call() throws Exception {
            try (TaskSession s = TaskSession.connect()) {
                Map<String, Object> r = s.client.callMap("task/resume",
                        Map.of("childId", childId));
                System.out.println(Boolean.TRUE.equals(r.get("ok")) ? "resumed" : "noop");
                return 0;
            }
        }
    }

    @Command(name = "retry",
             description = "Spawn a new task with the same prompt as an existing one.")
    public static class RetryCommand implements Callable<Integer> {
        @Parameters(arity = "1", description = "Original child id.")
        String childId;

        @Override
        public Integer call() throws Exception {
            try (TaskSession s = TaskSession.connect()) {
                Map<String, Object> r = s.client.callMap("task/retry",
                        Map.of("childId", childId));
                System.out.println(r.get("childId"));
                return 0;
            }
        }
    }

    @Command(name = "setLimits",
             description = "Raise or replace the limit caps for a background task.")
    public static class SetLimitsCommand implements Callable<Integer> {
        @Parameters(arity = "1..*",
                description = "<childId> <k=v> [...] (e.g. c-1234 tokens=8000 calls=50).")
        List<String> positional;

        @Override
        public Integer call() throws Exception {
            if (positional == null || positional.isEmpty()) {
                System.err.println("usage: aethercode task setLimits <childId> <k=v> [...]");
                return 2;
            }
            String childId = positional.get(0);
            Map<String, Object> limits = new LinkedHashMap<>();
            for (int i = 1; i < positional.size(); i++) {
                String kv = positional.get(i);
                int eq = kv.indexOf('=');
                if (eq < 0) {
                    System.err.println("expected k=v, got: " + kv);
                    return 2;
                }
                try {
                    limits.put(kv.substring(0, eq), Long.parseLong(kv.substring(eq + 1)));
                } catch (NumberFormatException nfe) {
                    System.err.println("bad value: " + kv);
                    return 2;
                }
            }
            try (TaskSession s = TaskSession.connect()) {
                Map<String, Object> r = s.client.callMap("task/setLimits", Map.of(
                        "childId", childId, "limits", limits));
                System.out.println("ok=" + r.get("ok") + "  limits=" + r.get("limits"));
                return 0;
            }
        }
    }

    private static void printListTable(List<Map<String, Object>> rows) {
        // Plain tab-separated; the TUI's column renderer is
        // out of scope here. The columns match the keys
        // `task/list` returns.
        System.out.println("CHILD_ID\tSTATUS\tPROMPT\tCWD");
        for (Map<String, Object> r : rows) {
            String id = String.valueOf(r.getOrDefault("id", ""));
            String status = String.valueOf(r.getOrDefault("status", ""));
            String prompt = String.valueOf(r.getOrDefault("prompt", ""));
            String cwd = String.valueOf(r.getOrDefault("cwd", ""));
            System.out.println(id + "\t" + status + "\t" + truncate(prompt, 60)
                    + "\t" + truncate(cwd, 40));
        }
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        if (s.length() <= max) return s;
        return s.substring(0, max - 1) + "…";
    }

    /**
     * Holds a {@link SupervisorClient} (and optionally a
     * private {@link SupervisorProcess} when the CLI started
     * its own supervisor because the user didn't have one).
     */
    private static final class TaskSession implements AutoCloseable {
        private static final Logger LOG = LoggerFactory.getLogger(TaskSession.class);
        final SupervisorClient client;
        private final SupervisorProcess ownedProcess;

        private TaskSession(SupervisorClient client, SupervisorProcess owned) {
            this.client = client;
            this.ownedProcess = owned;
        }

        static TaskSession connect() throws Exception {
            // the canonical user has a long-lived
            // supervisor (started by the TUI or the
            // desktop). If the lock file is present we
            // attach to it; if not we boot a private
            // supervisor in-process and tear it down on
            // exit.
            Path lock = SupervisorHome.dir().resolve("supervisor.sock");
            if (java.nio.file.Files.exists(lock)) {
                SupervisorClient c = new SupervisorClient(lock);
                c.connect();
                return new TaskSession(c, null);
            }
            LOG.info("supervisor lock file not found at {} — starting a private one",
                    lock);
            Path db = SupervisorHome.dir().resolve("sessions.db");
            SupervisorProcess p = new SupervisorProcess(db);
            p.start();
            SupervisorClient c = new SupervisorClient(lock);
            c.connect();
            return new TaskSession(c, p);
        }

        @Override
        public void close() {
            try { client.close(); } catch (Exception ignored) {}
            if (ownedProcess != null) {
                try { ownedProcess.stop(); } catch (Exception ignored) {}
            }
        }
    }
}
