package org.aethercode.cli;

import picocli.CommandLine;
import picocli.CommandLine.Command;

/**
 * Phase 2.1 / T-2-12 (design.md §3.6, spec.md §11.3): the
 * {@code aethercode workflow ...} CLI surface. Java-A owns
 * the five core subcommands ({@code list|show|run|new|edit});
 * this class is the parent that the {@code ac workflow lint}
 * subcommand (Java-B / T-2-12) hangs off. When Java-A wires
 * in their subcommands, the @Command(subcommands=...) list
 * below is the integration point.
 */
@Command(
        name = "workflow",
        mixinStandardHelpOptions = true,
        description = "Run, edit, and lint named workflow templates (T-2-08..T-2-12).",
        subcommands = {
                WorkflowListCommand.class,
                WorkflowShowCommand.class,
                WorkflowRunCommand.class,
                WorkflowNewCommand.class,
                WorkflowEditCommand.class,
                WorkflowLintCommand.class,
        }
)
public class WorkflowCommand {
    // no instance state — pure parent
}
