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
 * Phase 2.1 / T-2-09 (design.md §3.6, spec.md §11.3): the
 * {@code ac workflow show <name>} subcommand. Prints the
 * raw YAML body of one workflow and (in human mode) a brief
 * summary line at the top.
 *
 * <p>Output shape (human mode):
 * <pre>
 *   name:        tdd-feature
 *   source:      project
 *   description: Write a failing test, implement to green, refactor.
 *   --- yaml ---
 *   version: 1
 *   name: tdd-feature
 *   ...
 * </pre>
 *
 * <p>Output shape (--yaml-only mode): just the YAML body, no
 * header — useful for {@code ac workflow show tdd-feature --yaml-only | ac workflow edit ...}.
 */
@Command(
        name = "show",
        mixinStandardHelpOptions = true,
        description = "Show the raw YAML for a named workflow (T-2-09).")
public class WorkflowShowCommand implements Callable<Integer> {

    @Parameters(arity = "1", description = "Workflow name (kebab-case, no extension).")
    String name;

    @Option(names = {"--cwd"},
            description = "Project cwd (default: $user.dir).")
    Path cwd = WorkflowPaths.defaultCwd();

    @Option(names = {"--user-home"},
            description = "User home (default: $user.home).")
    Path userHome = WorkflowPaths.defaultUserHome();

    @Option(names = {"--yaml-only"},
            description = "Print just the YAML body, no header.")
    boolean yamlOnly;

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
            return wse.code().rpcCode() == -32101 ? 2 : 1;
        } catch (RuntimeException re) {
            System.err.println("show failed: " + re.getMessage());
            return 2;
        }
        if (loaded == null) {
            System.err.println("workflow '" + name + "' not found "
                    + "(searched " + WorkflowPaths.userDir(userHome) + " and "
                    + WorkflowPaths.projectDir(cwd) + ")");
            return 2;
        }
        if (yamlOnly) {
            System.out.print(loaded.yaml());
            if (!loaded.yaml().endsWith("\n")) System.out.println();
        } else {
            System.out.println("name:        " + loaded.name());
            System.out.println("source:      " + loaded.source());
            // parsed is a generic map; pull a one-line description if present.
            Object desc = loaded.parsed() == null ? null
                    : loaded.parsed().get("description");
            if (desc != null) {
                String d = String.valueOf(desc);
                if (!d.isBlank()) System.out.println("description: " + d);
            }
            System.out.println("--- yaml ---");
            System.out.print(loaded.yaml());
            if (!loaded.yaml().endsWith("\n")) System.out.println();
        }
        return 0;
    }
}
