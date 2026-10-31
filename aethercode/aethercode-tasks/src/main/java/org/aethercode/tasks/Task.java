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

package org.aethercode.tasks;

import java.util.Objects;

/**
 * A unit of work. Identity is a prefixed random ID (e.g. {@code u-3a7f9b2c},
 * {@code a-c4d2e1f0}). Tasks form a tree: a user query creates a
 * {@link TaskType#USER} task, which can spawn child {@link TaskType#AGENT}
 * tasks via AgentTool, which themselves can spawn more agents.
 *
 * <p>Tasks are <em>not</em> bound to a specific agent — a subagent is an
 * instance of an Agent running a Task, and the same Agent can be reused
 * across many tasks. The user explicitly asked for this: context is
 * associated with the task ID, not the agent.
 *
 * <p>The record is immutable; lifecycle transitions go through
 * {@link TaskRegistry}.
 */
public record Task(
        String id,
        TaskType type,
        TaskStatus status,
        String description,
        String parentTaskId,
        long createdAtMs,
        long endedAtMs
) {
    public Task {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(description, "description");
        if (createdAtMs <= 0) {
            throw new IllegalArgumentException("createdAtMs must be > 0, got " + createdAtMs);
        }
    }

    public static String generateId(TaskType type, java.util.random.RandomGenerator rng) {
        // 8 chars from the 36-char alphabet gives 36^8 ≈ 2.8T combinations,
        // mirroring the TS source's defense against symlink brute force.
        char[] alphabet = "0123456789abcdefghijklmnopqrstuvwxyz".toCharArray();
        StringBuilder sb = new StringBuilder(10);
        sb.append(type.idPrefix()).append('-');
        for (int i = 0; i < 8; i++) {
            sb.append(alphabet[rng.nextInt(alphabet.length)]);
        }
        return sb.toString();
    }

    public boolean isRoot() { return parentTaskId == null; }
}
