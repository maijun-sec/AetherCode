package org.aethercode.tasks;

/**
 * Lifecycle of a {@link Task}. Modelled after Claude Code's {@code TaskStatus}
 * (pending / running / completed / failed / killed). {@code #isTerminal()}
 * mirrors the TS guard used to evict finished tasks from AppState.
 */
public enum TaskStatus {
    PENDING,
    RUNNING,
    COMPLETED,
    FAILED,
    KILLED;

    public boolean isTerminal() {
        return this == COMPLETED || this == FAILED || this == KILLED;
    }
}
