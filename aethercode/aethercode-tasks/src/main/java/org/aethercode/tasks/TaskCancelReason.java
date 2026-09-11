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
