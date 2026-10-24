package org.aethercode.core.workflow;

import java.nio.file.Files;
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

    /** Absolute path to one workflow's YAML file.
     *
     * <p>R373: the reader accepts BOTH {@code .yaml} and {@code .yml}
     * ({@link WorkflowReader#list(Path)} filters on either), so the
     * resolver must too. Before this, a workflow stored as
     * {@code foo.yml} was LISTED by the picker but could never be
     * started, written to, or deleted by its name — all three went
     * through this one function, and all three silently missed it and
     * answered "not found".
     *
     * <p>When the name carries no extension, an existing file wins:
     * {@code .yaml} first, then {@code .yml}. When neither exists, the
     * canonical {@code .yaml} is returned so a NEW workflow is created
     * under the same name it will later be addressed by.
     *
     * <p>A name that carries its own extension is used verbatim, so a
     * caller can address {@code foo.yml} exactly — which is what keeps
     * a directory holding BOTH {@code foo.yaml} and {@code foo.yml}
     * unambiguous instead of a coin flip. */
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
        Path dir = workflowDir(cwd);
        if (name.endsWith(".yaml") || name.endsWith(".yml")) {
            return dir.resolve(name).normalize();
        }
        Path yaml = dir.resolve(name + ".yaml").normalize();
        if (Files.exists(yaml)) return yaml;
        Path yml = dir.resolve(name + ".yml").normalize();
        if (Files.exists(yml)) return yml;
        // Neither exists yet: the canonical default.
        return yaml;
    }
}
