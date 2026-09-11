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
