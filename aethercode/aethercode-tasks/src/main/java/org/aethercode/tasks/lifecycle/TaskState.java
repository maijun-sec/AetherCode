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

package org.aethercode.tasks.lifecycle;

import org.aethercode.tasks.supervisor.ChildStatus;

/**
 * prior round (T-330/§4.2 design.md): explicit, type-safe alias around
 * {@link ChildStatus} that lives in the {@code lifecycle} sub-package
 * so consumers that need lifecycle semantics (state-machine checks,
 * pause / kill / resume helpers) don't have to import
 * {@code supervisor.ChildStatus} directly.
 *
 * <p>The values mirror {@link ChildStatus} 1:1 — they are the same
 * six states. The alias exists for two reasons:
 * <ol>
 *   <li>It groups the state-machine code (this class,
 *       {@link TaskStateMachine}, {@link LimitsHitService}) under
 *       a single package.</li>
 *   <li>It lets the lifecycle API be stable even if the supervisor
 *       schema later renames a status (we only have to update
 *       the {@link #fromSupervisor} / {@link #toSupervisor} bridges).</li>
 * </ol>
 */
public enum TaskState {
    QUEUED,
    RUNNING,
    PAUSED,
    COMPLETED,
    FAILED,
    KILLED;

    /** True for states with no outgoing transitions. */
    public boolean isTerminal() {
        return this == COMPLETED || this == FAILED || this == KILLED;
    }

    public ChildStatus toSupervisor() {
        return switch (this) {
            case QUEUED    -> ChildStatus.QUEUED;
            case RUNNING   -> ChildStatus.RUNNING;
            case PAUSED    -> ChildStatus.PAUSED;
            case COMPLETED -> ChildStatus.COMPLETED;
            case FAILED    -> ChildStatus.FAILED;
            case KILLED    -> ChildStatus.KILLED;
        };
    }

    public static TaskState fromSupervisor(ChildStatus s) {
        return switch (s) {
            case QUEUED    -> TaskState.QUEUED;
            case RUNNING   -> TaskState.RUNNING;
            case PAUSED    -> TaskState.PAUSED;
            case COMPLETED -> TaskState.COMPLETED;
            case FAILED    -> TaskState.FAILED;
            case KILLED    -> TaskState.KILLED;
        };
    }
}
