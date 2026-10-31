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

package org.aethercode.a2a.schema;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * the fundamental unit of work in A2A. Every message/send
 * invocation produces (or resumes) a task; the task carries the
 * full status, history, and any artifacts produced along the
 * way. Identified by a UUID.
 *
 * <p>Tasks are stateful — once created, the client polls with
 * {@code tasks/get} (or subscribes via {@code message/stream})
 * until {@link TaskStatus#isTerminal()} is true.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Task(
        @JsonProperty("id") String id,
        @JsonProperty("contextId") String contextId,
        @JsonProperty("status") TaskStatus status,
        @JsonProperty("artifacts") List<Artifact> artifacts,
        @JsonProperty("history") List<Message> history) {

    public Task {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(status, "status");
        if (artifacts == null) artifacts = new ArrayList<>();
        if (history == null) history = new ArrayList<>();
    }

    public static Task newTask(String contextId, TaskStatus initialStatus) {
        return new Task(UUID.randomUUID().toString(), contextId, initialStatus,
                new ArrayList<>(), new ArrayList<>());
    }

    /** Immutable copy with the given status. */
    public Task withStatus(TaskStatus newStatus) {
        return new Task(id, contextId, newStatus, artifacts, history);
    }

    /** Immutable copy with a new history list (for stream-push of a single message). */
    public Task withAppendedMessage(Message m) {
        List<Message> h = new ArrayList<>(history);
        h.add(m);
        return new Task(id, contextId, status, artifacts, h);
    }

    /** Immutable copy with a new artifact appended. */
    public Task withAppendedArtifact(Artifact a) {
        List<Artifact> aa = new ArrayList<>(artifacts);
        aa.add(a);
        return new Task(id, contextId, status, aa, history);
    }

    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("contextId", contextId);
        m.put("status", status.toMap());
        if (!artifacts.isEmpty()) m.put("artifacts", artifacts.stream().map(Artifact::toMap).toList());
        if (!history.isEmpty()) m.put("history", history.stream().map(Message::toMap).toList());
        m.put("kind", "task");
        return m;
    }
}
