package org.aethercode.cli;

import org.aethercode.workflows.WorkflowPaths;
import org.aethercode.workflows.engine.StubWorkflowService;
import org.aethercode.workflows.engine.WorkflowService;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;

/**
 * Phase 2.1 / T-2-08 (design.md §3.6, spec.md §11.3): the
 * {@code ac workflow list} subcommand. Prints every workflow
 * visible from {@code --cwd}, one per line, with a
 * {@code project>} or {@code user>} marker so the user can
 * see which layer owns each entry.
 *
 * <p>Output shape (human mode, default):
 * <pre>
 *   project&gt; tdd-feature     Write a failing test, implement to green, refactor.
 *   user&gt;     explain-code    Quick code-walkthrough for a function or class.
 * </pre>
 *
 * <p>Output shape (--json mode):
 * <pre>
 *   {"name":"tdd-feature","source":"project","description":"..."}
 *   {"name":"explain-code","source":"user","description":"..."}
 * </pre>
 *
 * <p>Implementation note: the command uses the {@link StubWorkflowService}
 * for now. Wiring in the real engine from {@code aethercode-workflows}
 * is a one-liner — replace the constructor with
 * {@code DefaultWorkflowService.with(spawner)} — but the stub
 * has the same {@code list(...)} contract so the CLI shape is
 * stable across the swap.
 */
@Command(
        name = "list",
        mixinStandardHelpOptions = true,
        description = "List every workflow visible from the current project (T-2-08).")
public class WorkflowListCommand implements Callable<Integer> {

    @Option(names = {"--cwd"},
            description = "Project cwd (default: $user.dir).")
    Path cwd = WorkflowPaths.defaultCwd();

    @Option(names = {"--user-home"},
            description = "User home (default: $user.home).")
    Path userHome = WorkflowPaths.defaultUserHome();

    @Option(names = {"--json"},
            description = "Emit one JSON object per line instead of a human table.")
    boolean json;

    @Override
    public Integer call() {
        WorkflowService service = new StubWorkflowService();
        List<WorkflowService.WorkflowSummary> rows = service.list(userHome, cwd);
        if (json) {
            for (WorkflowService.WorkflowSummary s : rows) {
                System.out.println("{\"name\":\"" + jsonEscape(s.name()) + "\","
                        + "\"source\":\"" + jsonEscape(s.source()) + "\","
                        + "\"description\":\"" + jsonEscape(s.description()) + "\"}");
            }
        } else {
            if (rows.isEmpty()) {
                System.out.println("(no workflows found)");
            } else {
                for (WorkflowService.WorkflowSummary s : rows) {
                    System.out.printf("%-9s %-30s %s%n",
                            s.source() + ">", s.name(), s.description());
                }
            }
        }
        return 0;
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
