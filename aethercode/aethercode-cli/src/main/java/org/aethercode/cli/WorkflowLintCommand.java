package org.aethercode.cli;

import org.aethercode.workflows.ValidationError;
import org.aethercode.workflows.WorkflowPaths;
import org.aethercode.workflows.engine.StubWorkflowService;
import org.aethercode.workflows.engine.WorkflowService;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;

/**
 * Phase 2.1 / T-2-12 (design.md §3.6, spec.md §11.3): the
 * {@code ac workflow lint <name>} subcommand. Validates a
 * workflow's YAML without running it. The command is local —
 * no JSON-RPC round-trip; the lint is a pure file I/O check
 * plus a semantic validator call, both of which complete in
 * milliseconds.
 *
 * <p>Output shape:
 * <pre>
 *   $ ac workflow lint tdd-feature
 *   ok — tdd-feature (0 problems)
 *   $ ac workflow lint broken
 *   broken: prompts: at least one prompt is required
 *   broken: inputs.feature.type: unknown input type 'X' (known: [...])
 *   2 problems found.
 * </pre>
 * Exit code 0 on success, 1 on validation failures, 2 on
 * workflow-not-found.
 */
@Command(
        name = "lint",
        mixinStandardHelpOptions = true,
        description = "Validate a workflow YAML without running it (T-2-12).")
public class WorkflowLintCommand implements Callable<Integer> {

    @Parameters(arity = "1", description = "Workflow name (kebab-case, no extension).")
    String name;

    @Option(names = {"--cwd"},
            description = "Project cwd (default: $user.dir). Affects the project layer.")
    Path cwd = WorkflowPaths.defaultCwd();

    @Option(names = {"--user-home"},
            description = "User home (default: $user.home). Affects the user layer.")
    Path userHome = WorkflowPaths.defaultUserHome();

    @Option(names = {"--json"},
            description = "Emit validation errors as one JSON object per line (scriptable).")
    boolean json;

    @Override
    public Integer call() {
        // 1) Validate the name before touching the file system.
        try {
            WorkflowPaths.assertSafeName(name);
        } catch (IllegalArgumentException iae) {
            System.err.println("invalid workflow name: " + iae.getMessage());
            return 2;
        }

        // 2) Run the lint through the WorkflowService. The CLI
        //    uses the StubWorkflowService until Java-A's
        //    engine wires in a validator; once the engine is
        //    available, this can be replaced with a one-liner
        //    that delegates to the live service.
        WorkflowService service = new StubWorkflowService();
        List<ValidationError> errs;
        try {
            errs = service.lint(userHome, cwd, name);
        } catch (RuntimeException re) {
            System.err.println("lint failed: " + re.getMessage());
            return 2;
        }
        if (errs == null) errs = List.of();

        // 3) Render. JSON mode is a one-line-per-error format
        //    suitable for `jq` / `grep`; human mode is a
        //    conventional "path: message" table.
        if (json) {
            for (ValidationError e : errs) {
                System.out.println("{\"path\":\"" + jsonEscape(e.path()) + "\","
                        + "\"message\":\"" + jsonEscape(e.message()) + "\"}");
            }
        } else {
            if (errs.isEmpty()) {
                System.out.println("ok — " + name + " (0 problems)");
            } else {
                for (ValidationError e : errs) {
                    System.out.println(name + ": " + e);
                }
                System.out.println(errs.size() + " problem" + (errs.size() == 1 ? "" : "s")
                        + " found.");
            }
        }
        return errs.isEmpty() ? 0 : 1;
    }

    /** Minimal JSON-string escaper for the --json output path. We
     *  don't pull in Jackson for the CLI command's print logic
     *  because (a) the values are short fixed-vocabulary strings
     *  and (b) avoiding the dep keeps the CLI's per-subcommand
     *  startup lean. */
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
