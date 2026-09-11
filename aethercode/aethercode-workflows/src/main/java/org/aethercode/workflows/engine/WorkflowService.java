package org.aethercode.workflows.engine;

import org.aethercode.workflows.SessionRef;
import org.aethercode.workflows.ValidationError;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Phase 2.1 (T-2-04 + T-2-08..T-2-12 / design.md §3.6): the
 * workflow engine's public surface. The supervisor-side
 * {@code DefaultWorkflowHandlers} in
 * {@code org.aethercode.tasks.rpc.workflow} translates JSON-RPC
 * params into calls on this interface; the CLI does the same
 * via its own handler classes. Everything the engine knows
 * about running a workflow lives here so the rest of the
 * codebase never has to reach into the engine's internals.
 *
 * <p>Implementations are responsible for:
 * <ul>
 *   <li>Resolving the workflow file (project dir → user dir →
 *       bundled resources) given a {@code userHome} and {@code cwd}.</li>
 *   <li>Loading and parsing the YAML (or the bundled resource).</li>
 *   <li>Running the validator and reporting findings via
 *       {@link WorkflowServiceException} with the
 *       {@code VALIDATION_FAILED} code.</li>
 *   <li>For {@link #run}: composing the system-prompt-suffix
 *       from the workflow's skills, substituting the
 *       {@code {{...}}} placeholders, and handing the final
 *       prompt to the session spawner.</li>
 *   <li>For {@link #upsert} / {@link #delete}: writing or
 *       removing the workflow file from the project dir
 *       (or, if missing, the user dir).</li>
 * </ul>
 */
public interface WorkflowService {

    /**
     * Enumerate every workflow visible from {@code cwd}, in
     * display order. The {@code source} field is one of
     * {@code "project"}, {@code "user"}, or {@code "bundled"}.
     * Project copies shadow user copies of the same name.
     */
    List<WorkflowSummary> list(Path userHome, Path cwd);

    /**
     * Load a workflow by name. Returns {@code null} if no
     * workflow file is found in the project, user, or bundled
     * resource locations. The {@code validationErrors} field of
     * the result is populated for every issue, even if the
     * file parsed; the caller decides what to do with a
     * workflow that has problems.
     */
    LoadedWorkflow load(Path userHome, Path cwd, String name);

    /**
     * Run a workflow. Throws {@link WorkflowServiceException}
     * with the appropriate {@link WorkflowServiceException.Code}
     * on any failure (not found, bad YAML, validation failed,
     * etc.). On success, returns a {@link SessionRef} whose
     * id is the spawned child id.
     *
     * @param inputs caller-supplied input values, keyed by
     *                workflow input name. Missing required
     *                inputs and unknown types throw.
     */
    SessionRef run(Path userHome, Path cwd, String name,
                   Map<String, Object> inputs);

    /**
     * Write a workflow YAML file. The file is written to the
     * project dir when {@code cwd} resolves to one, otherwise
     * the user dir. The file is created (or overwritten) with
     * the given YAML body, validated, and (if valid) persisted.
     * Throws {@link WorkflowServiceException} with
     * {@code BAD_YAML} / {@code VALIDATION_FAILED} on problems.
     */
    void upsert(Path userHome, Path cwd, String name, String yaml);

    /**
     * Remove a workflow file. Returns {@code true} if a file
     * was actually removed, {@code false} if no file by that
     * name was found.
     */
    boolean delete(Path userHome, Path cwd, String name);

    /**
     * Run the validator without executing the workflow. Returns
     * the {@code List<ValidationError>} (empty when the
     * workflow is valid). The YAML is parsed but no spawner
     * is touched.
     */
    List<ValidationError> lint(Path userHome, Path cwd, String name);

    /**
     * One entry in the {@link #list} response. {@link #source()}
     * is the literal string {@code "project"}, {@code "user"},
     * or {@code "bundled"}.
     */
    record WorkflowSummary(String name, String source, String description) {}

    /**
     * The result of {@link #load}. {@link #yaml()} is the raw
     * YAML body the loader read (useful for the workflow
     * editor's "open" affordance). {@link #parsed()} is the
     * YAML deserialised into a generic map; the schema is
     * defined by the engine. {@link #validationErrors()} is
     * non-null but may be empty.
     */
    record LoadedWorkflow(
            String name,
            String source,
            String yaml,
            Map<String, Object> parsed,
            List<ValidationError> validationErrors
    ) {
        public LoadedWorkflow {
            validationErrors = validationErrors == null ? List.of() : List.copyOf(validationErrors);
            parsed = parsed == null ? Map.of() : Map.copyOf(parsed);
        }
    }
}
