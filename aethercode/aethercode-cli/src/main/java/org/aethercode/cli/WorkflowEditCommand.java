package org.aethercode.cli;

import org.aethercode.workflows.WorkflowPaths;
import org.aethercode.workflows.engine.StubWorkflowService;
import org.aethercode.workflows.engine.WorkflowService;
import org.aethercode.workflows.engine.WorkflowServiceException;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.nio.file.Path;
import java.util.concurrent.Callable;

/**
 * Phase 2.1 / T-2-11 (design.md §3.6, spec.md §11.3): the
 * {@code ac workflow edit <name>} subcommand. Prints the
 * workflow's YAML body on stdout and the path the user
 * should edit on stderr, then exits 0 — the actual editing
 * is the user's job (their $EDITOR opens the file).
 *
 * <p>The command is deliberately side-effect-free: the user
 * edits the file, runs {@code ac workflow lint <name>}, then
 * {@code ac workflow run <name>}. This keeps the CLI
 * predictable (no surprise writes) and lets the user use
 * whatever editor they prefer (vim, emacs, VS Code, sed).
 *
 * <p>Output shape:
 * <pre>
 *   # stdout: full YAML body of the workflow
 *   $ ac workflow edit tdd-feature &gt; /tmp/tdd.yaml
 *
 *   # stderr: a hint with the on-disk path
 *   $ ac workflow edit tdd-feature
 *   /home/me/proj/.aethercode/workflows/tdd-feature.yaml
 * </pre>
 */
@Command(
        name = "edit",
        mixinStandardHelpOptions = true,
        description = "Print a workflow's YAML so the user can edit it (T-2-11).")
public class WorkflowEditCommand implements Callable<Integer> {

    @Parameters(arity = "1", description = "Workflow name (kebab-case, no extension).")
    String name;

    @Option(names = {"--cwd"},
            description = "Project cwd (default: $user.dir).")
    Path cwd = WorkflowPaths.defaultCwd();

    @Option(names = {"--user-home"},
            description = "User home (default: $user.home).")
    Path userHome = WorkflowPaths.defaultUserHome();

    @Override
    public Integer call() {
        try {
            WorkflowPaths.assertSafeName(name);
        } catch (IllegalArgumentException iae) {
            System.err.println("invalid workflow name: " + iae.getMessage());
            return 2;
        }
        WorkflowService service = new StubWorkflowService();
        WorkflowService.LoadedWorkflow loaded;
        try {
            loaded = service.load(userHome, cwd, name);
        } catch (WorkflowServiceException wse) {
            System.err.println(wse.getMessage());
            return wse.code() == WorkflowServiceException.Code.NOT_FOUND ? 2 : 1;
        } catch (RuntimeException re) {
            System.err.println("load failed: " + re.getMessage());
            return 1;
        }
        if (loaded == null) {
            System.err.println("workflow '" + name + "' not found "
                    + "(use `ac workflow new " + name + "` to create a starter)");
            return 2;
        }
        // stdout: the YAML body. The user can pipe it to
        // their editor (`ac workflow edit foo > /tmp/foo.yaml`).
        System.out.print(loaded.yaml());
        if (!loaded.yaml().endsWith("\n")) System.out.println();
        // stderr: a hint with the on-disk path. The path comes
        // from the resolve() helper so we can show the user
        // exactly which file the engine will load on the next run.
        Path onDisk = WorkflowPaths.resolve(userHome, cwd, name);
        if (onDisk != null) {
            System.err.println("# edit me: " + onDisk);
        } else {
            // Bundled-only workflow (e.g. a shipped one). Tell
            // the user how to shadow it.
            System.err.println("# workflow '" + name
                    + "' is a bundled resource. To customise it, run "
                    + "`ac workflow new " + name + " --force` first.");
        }
        return 0;
    }
}
