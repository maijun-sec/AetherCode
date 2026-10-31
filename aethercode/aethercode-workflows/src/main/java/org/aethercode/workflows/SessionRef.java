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

package org.aethercode.workflows;

import java.util.Objects;

/**
 * Lightweight reference to a session spawned by a workflow run.
 * Mirrors the {@code session/spawn} JSON-RPC return shape: an id
 * plus the cwd / model / workflow name that produced it.
 *
 * <p>Defined in this module because the spec doesn't pick a home
 * for it (aethercode-protocol doesn't currently have a session
 * model; the task-supervisor's {@code task/spawn} returns
 * {@code childId} only). The CLI's {@code ac workflow run}
 * prints the id and stores the rest so a follow-up
 * {@code ac session show <id>} can find the right row.
 */
public record SessionRef(
        String id,
        String cwd,
        String model,
        String workflowName,
        java.util.Map<String, Object> config
) {

    public SessionRef {
        Objects.requireNonNull(id, "id");
        if (cwd == null) cwd = "";
        if (model == null) model = "";
        if (workflowName == null) workflowName = "";
        if (config == null) config = java.util.Map.of();
    }

    /** Compact "id (workflow=name)" form for log lines. */
    public String shortLabel() {
        if (workflowName.isEmpty()) return id;
        return id + " (workflow=" + workflowName + ")";
    }
}
