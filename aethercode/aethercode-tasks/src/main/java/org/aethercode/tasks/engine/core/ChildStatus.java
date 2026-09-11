package org.aethercode.tasks.engine.core;

import java.util.Optional;

/**
 * Lifecycle status of a supervised child (long-running session).
 * Modeled after AetherCode's ChildStatus (aethercode-tasks),
 * re-implemented for the deepagents-tasks package.
 *
 * <p>Allowed transitions:
 * <pre>
 *   QUEUED     --spawn-->    RUNNING
 *   RUNNING    --complete--> COMPLETED
 *   RUNNING    --error-->    FAILED
 *   RUNNING    --kill-->     KILLED
 *   RUNNING    --pause-->    PAUSED
 *   PAUSED     --resume-->   RUNNING
 *   PAUSED     --kill-->     KILLED
 *   PAUSED     --limits-->   PAUSED  (stays PAUSED, asks user)
 * </pre>
 */
public enum ChildStatus {
    QUEUED,
    RUNNING,
    PAUSED,
    COMPLETED,
    FAILED,
    KILLED;

    /** True for states that are sticky (no further transitions). */
    public boolean isTerminal() {
        return this == COMPLETED || this == FAILED || this == KILLED;
    }

    /** True iff the child is currently executing work. */
    public boolean isActive() {
        return this == RUNNING;
    }

    /**
     * Validate a transition. Returns empty for a legal transition,
     * or a description of the illegal one for diagnostics. Same-state
     * transitions are reported as no-ops (caller should not validate).
     */
    public Optional<String> validateTransition(ChildStatus next) {
        if (this == next) {
            return Optional.of(this + " -> " + next + " is a no-op; do not validate");
        }
        return switch (this) {
            case QUEUED -> (next == RUNNING || next == KILLED)
                    ? Optional.empty()
                    : Optional.of("QUEUED can only transition to RUNNING/KILLED, got " + next);
            case RUNNING -> (next == PAUSED || next == COMPLETED
                    || next == FAILED || next == KILLED)
                    ? Optional.empty()
                    : Optional.of("RUNNING can only transition to PAUSED/COMPLETED/FAILED/KILLED, got " + next);
            case PAUSED -> (next == RUNNING || next == KILLED)
                    ? Optional.empty()
                    : Optional.of("PAUSED can only transition to RUNNING/KILLED, got " + next);
            case COMPLETED, FAILED, KILLED ->
                    Optional.of(this + " is terminal; cannot transition to " + next);
        };
    }
}
