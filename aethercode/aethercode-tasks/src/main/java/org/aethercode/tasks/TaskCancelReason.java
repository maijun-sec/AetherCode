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

/**
 * reason a task was cancelled. Stored in the task registry
 * alongside the {@link TaskStatus#KILLED} transition. Helps the
 * user audit "why was this task killed" — useful for
 * long-running sessions with many tasks.
 */
public enum TaskCancelReason {
    /** User explicitly cancelled via UI / slash command. */
    USER_REQUEST,
    /** System / watchdog cancelled because the task exceeded its
     *  deadline or used too many resources. */
    DEADLINE_EXCEEDED,
    /** Parent task was killed, so this child is also killed. */
    PARENT_KILLED,
    /** The plan was aborted, all remaining steps killed. */
    PLAN_ABORTED,
    /** Unknown / unspecified reason. The default. */
    OTHER;

    /** human-readable label for UI display. */
    public String label() {
        return switch (this) {
            case USER_REQUEST     -> "by user";
            case DEADLINE_EXCEEDED -> "deadline";
            case PARENT_KILLED    -> "parent killed";
            case PLAN_ABORTED     -> "plan aborted";
            case OTHER            -> "other";
        };
    }
}
