package org.aethercode.cli;

import org.aethercode.workflows.WorkflowPaths;
import org.aethercode.workflows.engine.StubWorkflowService;
import org.aethercode.workflows.engine.WorkflowService;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.nio.file.Path;
import java.util.concurrent.Callable;

/**
 * Phase 2.1 / T-2-11 (design.md §3.6, spec.md §11.3): the
 * {@code ac workflow new <name>} subcommand. Writes a starter
 * YAML body to the project workflow dir so the user has a
 * starting point for editing. The body is the same template
 * the four shipped workflows use; the only required fields
 * are {@code version} and {@code name}.
 *
 * <p>If a workflow by the same name already exists in either
 * layer, the command refuses (exit 1) — use {@code ac workflow
 * edit} to modify an existing workflow. This avoids accidental
 * clobbering of user-level workflows when the project layer
 * is the intended target.
 */
@Command(
        name = "new",
        mixinStandardHelpOptions = true,
        description = "Create a starter workflow YAML in the project layer (T-2-11).")
public class WorkflowNewCommand implements Callable<Integer> {

    @Parameters(arity = "1", description = "Workflow name (kebab-case, no extension).")
    String name;

    @Option(names = {"--cwd"},
            description = "Project cwd (default: $user.dir).")
    Path cwd = WorkflowPaths.defaultCwd();

    @Option(names = {"--user-home"},
            description = "User home (default: $user.home).")
    Path userHome = WorkflowPaths.defaultUserHome();

    @Option(names = {"--force"},
            description = "Overwrite an existing workflow with the same name.")
    boolean force;

    @Override
    public Integer call() {
        try {
            WorkflowPaths.assertSafeName(name);
        } catch (IllegalArgumentException iae) {
            System.err.println("invalid workflow name: " + iae.getMessage());
            return 2;
        }
        Path existing = WorkflowPaths.resolve(userHome, cwd, name);
        if (existing != null && !force) {
            System.err.println("workflow '" + name + "' already exists at "
                    + existing + " (use --force to overwrite)");
            return 1;
        }
        String body = starterYaml(name);
        WorkflowService service = new StubWorkflowService();
        try {
            service.upsert(userHome, cwd, name, body);
        } catch (RuntimeException re) {
            System.err.println("write failed: " + re.getMessage());
            return 1;
        }
        System.out.println("wrote " + WorkflowPaths.defaultWriteTarget(userHome, cwd, name));
        System.out.println("next: edit it (ac workflow edit " + name + "), then lint (ac workflow lint "
                + name + "), then run (ac workflow run " + name + ").");
        return 0;
    }

    /** The starter template. Kept short on purpose — the
     *  user fills in their own prompts. The placeholder
     *  text in the description prompts the editor to replace
     *  it on the first save. */
    static String starterYaml(String name) {
        return """
                # %s — describe this workflow in one line.
                version: 1
                name: %s
                description: |
                  TODO — replace this description.
                inputs:
                  feature:
                    type: string
                    required: true
                    description: "What this workflow does, in one sentence."
                skills: []
                prompts:
                  - role: system
                    content: |
                      You are an expert assistant. Replace this prompt
                      with the workflow's system message.
                  - role: user
                    content: |
                      Feature: {{inputs.feature}}
                      Working directory: {{cwd}}
                      Today: {{date}}
                      OS: {{os}}
                todos:
                  - "Replace this with the workflow's first step"
                  - "Replace this with the workflow's second step"
                limits:
                  wallClockMs: 14400000
                  tokens: 4000000
                  calls: 200
                """.formatted(name, name);
    }
}
