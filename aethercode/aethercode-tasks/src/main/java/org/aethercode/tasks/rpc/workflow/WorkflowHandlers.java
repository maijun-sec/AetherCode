/*
 * Copyright (c) 2026 maijun
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

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
