package org.aethercode.cli;

import org.aethercode.workflows.SessionRef;
import org.aethercode.workflows.WorkflowPaths;
import org.aethercode.workflows.engine.StubWorkflowService;
import org.aethercode.workflows.engine.WorkflowService;
import org.aethercode.workflows.engine.WorkflowServiceException;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Callable;

/**
 * Phase 2.1 / T-2-10 (design.md §3.6, spec.md §11.3): the
 * {@code ac workflow run <name> --input.k=v ...} subcommand.
 * Spawns a session for the named workflow and prints the
 * resulting {@code sessionId}. Workflow inputs are passed as
 * {@code --input.<name>=<value>} repeated flags; the
 * conventional kebab-case key is converted to its camelCase
 * / snake_case form by the YAML engine, so the CLI just
 * passes through whatever the user typed.
 *
 * <p>Output (default): just the session id, one line, so the
 * user can pipe it into {@code ac session show <id>}.
 * <p>Output (--json): the full SessionRef shape.
 *
 * <p>Exit codes:
 * <ul>
 *   <li>0 — session spawned (or, in the stub path, NOT_FOUND
 *       surfaced cleanly).</li>
 *   <li>1 — validation failed (printed to stderr).</li>
 *   <li>2 — workflow not found / bad name.</li>
 * </ul>
 *
 * <p>Implementation note: the CLI uses the
 * {@link StubWorkflowService} until the real engine is
 * wired into the supervisor. The stub satisfies the contract
 * for the {@code run} RPC with a clean error message so the
 * user sees something useful even on a partially-built box.
 */
@Command(
        name = "run",
        mixinStandardHelpOptions = true,
        description = "Spawn a session for a named workflow (T-2-10).")
public class WorkflowRunCommand implements Callable<Integer> {

    @Parameters(arity = "1", description = "Workflow name (kebab-case, no extension).")
    String name;

    @Option(names = {"--cwd"},
            description = "Project cwd (default: $user.dir).")
    Path cwd = WorkflowPaths.defaultCwd();

    @Option(names = {"--user-home"},
            description = "User home (default: $user.home).")
    Path userHome = WorkflowPaths.defaultUserHome();

    @Option(names = {"--input"},
            description = "Workflow input in the form k=v. Repeat for multiple inputs.")
    String[] inputs = new String[0];

    @Option(names = {"--json"},
            description = "Emit the SessionRef as a JSON object instead of just the id.")
    boolean json;

    @Override
    public Integer call() {
        try {
            WorkflowPaths.assertSafeName(name);
        } catch (IllegalArgumentException iae) {
            System.err.println("invalid workflow name: " + iae.getMessage());
            return 2;
        }
        Map<String, Object> inputMap = parseInputs(inputs);
        WorkflowService service = new StubWorkflowService();
        try {
            SessionRef ref = service.run(userHome, cwd, name, inputMap);
            if (json) {
                System.out.println("{"
                        + "\"id\":\"" + jsonEscape(ref.id()) + "\","
                        + "\"cwd\":\"" + jsonEscape(ref.cwd()) + "\","
                        + "\"model\":\"" + jsonEscape(ref.model()) + "\","
                        + "\"workflowName\":\"" + jsonEscape(ref.workflowName()) + "\""
                        + "}");
            } else {
                System.out.println(ref.id());
            }
            return 0;
        } catch (WorkflowServiceException wse) {
            // Surface the validation / not-found envelope.
            if (wse.code() == WorkflowServiceException.Code.NOT_FOUND) {
                System.err.println(wse.getMessage());
                return 2;
            }
            if (wse.code() == WorkflowServiceException.Code.VALIDATION_FAILED) {
                System.err.println("validation failed:");
                if (wse.validationErrors() != null) {
                    for (var e : wse.validationErrors()) {
                        System.err.println("  - " + e);
                    }
                }
                return 1;
            }
            System.err.println("run failed: " + wse.getMessage());
            return 1;
        } catch (RuntimeException re) {
            System.err.println("run failed: " + re.getMessage());
            return 1;
        }
    }

    /** Parse the repeated {@code --input k=v} flags into a
     *  map. The values are kept as strings; the workflow
     *  engine coerces them to the declared type. Empty values
     *  (e.g. {@code --input.feature=}) are stored as the empty
     *  string — not omitted — so the engine sees "user passed
     *  the flag" rather than "user didn't pass it". */
    private static Map<String, Object> parseInputs(String[] flags) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (flags == null) return out;
        for (String f : flags) {
            if (f == null) continue;
            int eq = f.indexOf('=');
            if (eq < 0) {
                System.err.println("ignoring malformed --input (expected k=v): " + f);
                continue;
            }
            String k = f.substring(0, eq).trim();
            String v = f.substring(eq + 1);
            if (k.isEmpty()) {
                System.err.println("ignoring malformed --input (empty key): " + f);
                continue;
            }
            out.put(k, v);
        }
        return out;
    }

    private static String jsonEscape(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
                }
            }
        }
        return sb.toString();
    }
}
