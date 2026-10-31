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

package org.aethercode.tasks.supervisor;

import java.util.Objects;

/**
 * prior round (T-303/§4.1.1 design.md): a row in the supervisor's
 * {@code subagent_state} table. One row per (child, subagent) pair
 * so a single child can host many subagents (e.g. one per
 * delegated task) without overwriting each other's state.
 *
 * <pre>
 *   CREATE TABLE subagent_state (
 *     child_id TEXT NOT NULL,
 *     subagent_id TEXT NOT NULL,
 *     state TEXT,           -- JSON blob
 *     updated_at INTEGER NOT NULL,
 *     PRIMARY KEY (child_id, subagent_id)
 *   );
 * </pre>
 *
 * <p>{@code state} is a free-form JSON blob the AsyncSubAgent
 * middleware owns; the supervisor only stores and retrieves it.
 */
public record SubagentStateRecord(
        String childId,
        String subagentId,
        String stateJson,
        long updatedAtMs
) {
    public SubagentStateRecord {
        Objects.requireNonNull(childId, "childId");
        Objects.requireNonNull(subagentId, "subagentId");
        if (updatedAtMs <= 0) {
            throw new IllegalArgumentException("updatedAtMs must be > 0, got " + updatedAtMs);
        }
    }
}
