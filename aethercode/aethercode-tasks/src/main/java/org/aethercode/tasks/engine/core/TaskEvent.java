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

package org.aethercode.tasks.engine.core;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Event emitted by the supervisor (and consumed by the desktop / TUI
 * over JSON-RPC). Modeled after AetherCode's ChildEventRecord but
 * designed for in-process listeners (the JSON-RPC envelope wraps
 * this record for wire transport).
 *
 * <p>Standard event {@link #type()} values:
 * <ul>
 *   <li>{@code task/limit_reached} — a limit was tripped
 *       (payload: which limit, current value, configured max).</li>
 *   <li>{@code task/idle_paused} — a session was paused due to
 *       the idle guard (no LLM call in the configured window).</li>
 *   <li>{@code status_change} — the child moved to a new status.</li>
 *   <li>{@code tool_call} / {@code tool_result} / {@code model_message} /
 *       {@code user_message} / {@code todo_update} / {@code summary}</li>
 * </ul>
 */
public record TaskEvent(
        String id,
        String sessionId,
        long tsMs,
        String type,
        Map<String, Object> payload) {

    public TaskEvent {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(sessionId, "sessionId");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(payload, "payload");
        // Defensive copy so callers cannot mutate the payload after the event is built.
        payload = Map.copyOf(payload);
    }

    public static TaskEvent of(String sessionId, String type, Map<String, Object> payload) {
        return new TaskEvent(UUID.randomUUID().toString(), sessionId,
                System.currentTimeMillis(), type, payload);
    }

    public static TaskEvent of(String sessionId, String type, Map<String, Object> payload, long tsMs) {
        return new TaskEvent(UUID.randomUUID().toString(), sessionId, tsMs, type, payload);
    }

    public Instant ts() { return Instant.ofEpochMilli(tsMs); }

    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("sessionId", sessionId);
        m.put("tsMs", tsMs);
        m.put("type", type);
        m.put("payload", payload);
        return m;
    }
}
