package org.aethercode.tasks.lifecycle;

import org.aethercode.tasks.supervisor.ChildRecord;
import org.aethercode.tasks.supervisor.ChildStatus;
import org.aethercode.tasks.supervisor.SupervisorStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.SQLException;
import java.util.Objects;
import java.util.Optional;

/**
 * prior round (T-330..T-333/§4.2 design.md): wraps a {@link SupervisorStore}
 * and exposes typed lifecycle actions (spawn, pause, resume, kill,
 * complete, fail) that map onto the underlying state-machine
 * transitions.
 *
 * <p>Every action:
 * <ol>
 *   <li>validates the transition via {@link ChildStatus#validateTransition},</li>
 *   <li>mutates the row (state + timestamps),</li>
 *   <li>appends a {@code status_change} event for the replay log.</li>
 * </ol>
 *
 * <p>Pause / resume / kill are idempotent in the sense that
 * re-issuing the same action on a child already in the target
 * state is a no-op (no event, no row write) — but a different-state
 * transition is rejected.
 *
 * <p>This class is the entry point used by both the
 * {@code AsyncSubAgent} driver (which calls
 * {@link #markRunning}/{@link #markCompleted}/{@link #markFailed})
 * and the limits/streaming layers (which call
 * {@link #pause} / {@link #kill} when a cap is tripped).
 */
public final class TaskStateMachine {

    private static final Logger LOG = LoggerFactory.getLogger(TaskStateMachine.class);

    private final SupervisorStore store;

    public TaskStateMachine(SupervisorStore store) {
        this.store = Objects.requireNonNull(store, "store");
    }

    public SupervisorStore store() { return store; }

    /**
     * The current state of {@code childId}, or empty if the row
     * has been deleted.
     */
    public Optional<TaskState> currentState(String childId) throws SQLException {
        return store.getChild(childId).map(r -> TaskState.fromSupervisor(r.status()));
    }

    // -- transitions ------------------------------------------------------

    /**
     * Transition a {@code QUEUED} child into {@code RUNNING}.
     * Refuses to start a child that is already running, paused,
     * or in a terminal state.
     */
    public TaskState markRunning(String childId) throws SQLException {
        return transition(childId, TaskState.RUNNING, "{\"to\":\"RUNNING\",\"reason\":\"start\"}");
    }

    /**
     * Transition a {@code RUNNING} child into {@code COMPLETED}
     * with an optional result string.
     */
    public TaskState markCompleted(String childId, String resultNote) throws SQLException {
        String payload = "{\"to\":\"COMPLETED\",\"reason\":\"complete\""
                + (resultNote == null ? "" : ",\"note\":\"" + escape(resultNote) + "\"")
                + "}";
        return transition(childId, TaskState.COMPLETED, payload);
    }

    /**
     * Transition a {@code RUNNING} child into {@code FAILED}.
     */
    public TaskState markFailed(String childId, String error) throws SQLException {
        if (error != null) store.setError(childId, error);
        return transition(childId, TaskState.FAILED,
                "{\"to\":\"FAILED\",\"reason\":\"" + escape(error == null ? "" : error) + "\"}");
    }

    /**
     * T-332: resume a {@code PAUSED} child. Returns the new state
     * (always {@link TaskState#RUNNING}). Throws if the child is
     * not currently paused.
     */
    public TaskState resume(String childId) throws SQLException {
        Optional<ChildRecord> before = store.getChild(childId);
        if (before.isEmpty()) {
            throw new IllegalArgumentException("unknown childId: " + childId);
        }
        ChildStatus prev = before.get().status();
        if (prev != ChildStatus.PAUSED) {
            // T-332 is explicit: resume only works on a PAUSED
            // child. Other states (QUEUED / RUNNING / terminal)
            // are wrong for "resume" — the user should use
            // markRunning / kill instead. We reject with a clear
            // message so the agent loop doesn't accidentally
            // reanimate a terminal row.
            throw new IllegalStateException("child " + childId
                    + " is not PAUSED, got " + prev);
        }
        store.updateStatus(childId, ChildStatus.RUNNING);
        store.appendEvent(childId, "status_change",
                "{\"to\":\"RUNNING\",\"reason\":\"resume\"}");
        LOG.info("child {} {} -> {}", childId, prev, TaskState.RUNNING);
        return TaskState.RUNNING;
    }

    /**
     * T-331: pause a {@code RUNNING} child. Used by the limits
     * enforcer and by the user via the {@code task/pause} RPC
     * (Round 1's RPC surface already covers {@code task/kill} and
     * {@code task/resume}; pause is the supervisor-internal
     * counterpart).
     *
     * <p>If the child is already paused this is a no-op and the
     * current state is returned.
     */
    public TaskState pause(String childId, String reason) throws SQLException {
        Optional<TaskState> cur = currentState(childId);
        if (cur.isEmpty()) throw new IllegalArgumentException("unknown childId: " + childId);
        if (cur.get() == TaskState.PAUSED) return TaskState.PAUSED;
        String payload = "{\"to\":\"PAUSED\",\"reason\":\"" + escape(reason == null ? "" : reason) + "\"}";
        return transition(childId, TaskState.PAUSED, payload);
    }

    /**
     * T-333: kill a child in any non-terminal state. A no-op when
     * the child is already terminal (the caller gets the current
     * state back).
     */
    public TaskState kill(String childId, String reason) throws SQLException {
        Optional<TaskState> cur = currentState(childId);
        if (cur.isEmpty()) throw new IllegalArgumentException("unknown childId: " + childId);
        if (cur.get().isTerminal()) return cur.get();
        if (reason != null) store.setError(childId, reason);
        String payload = "{\"to\":\"KILLED\",\"reason\":\"" + escape(reason == null ? "" : reason) + "\"}";
        return transition(childId, TaskState.KILLED, payload);
    }

    // -- helpers ----------------------------------------------------------

    /**
     * Apply the state transition, append the event, and return the
     * new state. Throws {@link IllegalStateException} if the
     * transition is not allowed by the state machine.
     */
    private TaskState transition(String childId, TaskState next, String statusChangeJson)
            throws SQLException {
        Optional<ChildRecord> before = store.getChild(childId);
        if (before.isEmpty()) {
            throw new IllegalArgumentException("unknown childId: " + childId);
        }
        ChildStatus prev = before.get().status();
        Optional<String> err = prev.validateTransition(next.toSupervisor());
        if (err.isPresent()) {
            throw new IllegalStateException("child " + childId + ": " + err.get());
        }
        store.updateStatus(childId, next.toSupervisor());
        store.appendEvent(childId, "status_change", statusChangeJson);
        LOG.info("child {} {} -> {}", childId, prev, next);
        return next;
    }

    private static String escape(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length() + 4);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
                }
            }
        }
        return sb.toString();
    }
}
