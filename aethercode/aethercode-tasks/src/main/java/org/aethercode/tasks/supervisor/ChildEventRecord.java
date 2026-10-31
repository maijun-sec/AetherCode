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
 * prior round (T-302/§4.1.1 design.md): a row in the supervisor's
 * {@code child_events} table. Events are append-only and
 * monotonically increasing by {@code id}. The id is the
 * replay cursor used by {@code task/events since=N}.
 *
 * <pre>
 *   CREATE TABLE child_events (
 *     id INTEGER PRIMARY KEY AUTOINCREMENT,
 *     child_id TEXT NOT NULL,
 *     ts INTEGER NOT NULL,
 *     type TEXT NOT NULL,
 *     payload TEXT NOT NULL,
 *     FOREIGN KEY (child_id) REFERENCES children(id)
 *   );
 *   CREATE INDEX idx_child_events ON child_events(child_id, id DESC);
 * </pre>
 *
 * <p>Well-known {@link #type} values (from design.md §4.4):
 * {@code model_message}, {@code tool_call}, {@code tool_result},
 * {@code todo_update}, {@code file_edit}, {@code status_change}.
 * The type is a free string so new event kinds can be added without
 * a schema migration; the supervisor forwards them to subscribers.
 */
public record ChildEventRecord(
        long id,
        String childId,
        long tsMs,
        String type,
        String payloadJson
) {
    public ChildEventRecord {
        Objects.requireNonNull(childId, "childId");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(payloadJson, "payloadJson");
        if (tsMs <= 0) {
            throw new IllegalArgumentException("tsMs must be > 0, got " + tsMs);
        }
    }

    public static final String TYPE_MODEL_MESSAGE = "model_message";
    public static final String TYPE_TOOL_CALL = "tool_call";
    public static final String TYPE_TOOL_RESULT = "tool_result";
    public static final String TYPE_TODO_UPDATE = "todo_update";
    public static final String TYPE_FILE_EDIT = "file_edit";
    public static final String TYPE_STATUS_CHANGE = "status_change";
    public static final String TYPE_LIMITS_HIT = "limits_hit";
    public static final String TYPE_ERROR = "error";
}
