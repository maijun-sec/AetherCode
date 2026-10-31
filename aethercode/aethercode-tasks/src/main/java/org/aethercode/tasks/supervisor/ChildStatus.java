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

import java.util.Optional;

/**
 * prior round (T-301/§4.2 design.md): lifecycle status of a supervised
 * child. Distinct from {@link org.aethercode.tasks.TaskStatus} because:
 * <ul>
 *   <li>the supervisor owns an explicit {@code PAUSED} state (with a
 *       resume / kill / limit-ask fork),</li>
 *   <li>the supervisor never goes back to {@code PENDING} (a child
 *       that hasn't started yet is {@code QUEUED}).</li>
 * </ul>
 *
 * <p>Allowed transitions (see design.md §4.2):
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

    /**
     * Validate a transition. Returns empty for a legal transition,
     * or a description of the illegal one for diagnostics. Same-state
     * transitions are illegal — the supervisor's store treats them
     * as idempotent no-ops, but the state machine itself should
     * not be asked to validate them.
     */
    public Optional<String> validateTransition(ChildStatus next) {
        if (this == next) {
            return Optional.of(this + " -> " + next + " is a no-op; do not validate");
        }
        return switch (this) {
            // QUEUED can advance to RUNNING, or be KILLED before
            // it ever starts (e.g. user aborts mid-spawn).
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
