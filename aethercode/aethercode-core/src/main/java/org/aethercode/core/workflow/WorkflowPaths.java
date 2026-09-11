package org.aethercode.core.workflow;

import java.nio.file.Path;

/**
 * resolves the on-disk location of workflow files. Workflows are
 * user-authored YAML pipelines the desktop can pick from the input bar
 * and the engine can step through.
 *
 * <p>Layout:
 * <ul>
 *   <li>{@code <cwd>/.aethercode/workflows/<name>.yaml} — the
 *       per-project workflow library. The desktop reads this list
 *       for the input bar's workflow selector and the engine
 *       reads the file when a workflow is invoked.
 * </ul>
 *
 * <p>Mirrors the layered style of {@code MemoryPaths} but stays
 * single-scope: there is no USER / PROJECT / LOCAL split for
 * workflows in R102. A future prior round+ could add global USER-scope
 * workflows the same way memory has them.
 */
public final class WorkflowPaths {

    /** Subdirectory under cwd where workflow YAMLs live. */
    public static final String WORKFLOW_DIR = ".aethercode";
    public static final String WORKFLOW_SUBDIR = "workflows";

    private WorkflowPaths() {}

    /** Absolute path to the workflow library for {@code cwd}. */
    public static Path workflowDir(Path cwd) {
        return cwd.resolve(WORKFLOW_DIR).resolve(WORKFLOW_SUBDIR);
    }

    /** Absolute path to one workflow's YAML file. */
    public static Path workflowFile(Path cwd, String name) {
        // Sanitise: refuse path separators and `..` so a hostile
        // RPC param cannot escape the workflow dir.
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("workflow name is required");
        }
        if (name.contains("..") || name.contains("/") || name.contains("\\")
                || name.contains("\0")) {
            throw new IllegalArgumentException("invalid workflow name: " + name);
        }
        if (!name.endsWith(".yaml") && !name.endsWith(".yml")) {
            // Auto-append so a `/workflow run foo` RPC param doesn't
            // need to include the extension.
            name = name + ".yaml";
        }
        return workflowDir(cwd).resolve(name).normalize();
    }
}
