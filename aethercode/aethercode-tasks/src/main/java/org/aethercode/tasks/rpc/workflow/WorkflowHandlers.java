package org.aethercode.tasks.rpc.workflow;

import java.util.Map;

/**
 * Phase 2.1 / T-2-08..T-2-12 (design.md §3.6): the five
 * workflow/* JSON-RPC methods, grouped behind one interface
 * so {@link org.aethercode.tasks.supervisor.SupervisorRpcServer}
 * can wire them in with the same {@code nullable delegate}
 * pattern it uses for {@code grants/*}.
 *
 * <p>Each method receives the raw params map (already coerced
 * to {@code Map<String, Object>}) and returns a JSON-serializable
 * result. Implementations translate {@link org.aethercode.workflows.engine.WorkflowServiceException}
 * into the standard {@code {ok: false, error: {name, code, message}}}
 * shape so the desktop / TUI can render it consistently.
 */
public interface WorkflowHandlers {

    /** {@code workflow/list} — every visible workflow.
     *  Param: {@code cwd?} (string). */
    Object list(Map<String, Object> params);

    /** {@code workflow/show} — one workflow's YAML + parsed JSON.
     *  Param: {@code name} (string). */
    Object show(Map<String, Object> params);

    /** {@code workflow/run} — spawn a session for the workflow.
     *  Param: {@code name} (string), {@code inputs?} (object),
     *  {@code cwd?} (string). */
    Object run(Map<String, Object> params);

    /** {@code workflow/upsert} — write a workflow YAML file.
     *  Param: {@code name} (string), {@code yaml} (string),
     *  {@code cwd?} (string). */
    Object upsert(Map<String, Object> params);

    /** {@code workflow/delete} — remove a workflow YAML file.
     *  Param: {@code name} (string), {@code cwd?} (string). */
    Object delete(Map<String, Object> params);
}
