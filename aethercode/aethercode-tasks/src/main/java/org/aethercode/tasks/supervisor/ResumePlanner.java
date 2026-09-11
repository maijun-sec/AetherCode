package org.aethercode.tasks.supervisor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.SQLException;
import java.util.List;
import java.util.Objects;

/**
 * prior round (T-314/§4.5 design.md): scans the supervisor's
 * {@code children} table for rows that need to be brought back
 * up on startup, and decides the recovery action per row.
 *
 * <p>Resume actions:
 * <ul>
 *   <li>{@link ResumeAction#RESPAWN} — re-launch the child
 *       from its saved state. Used for {@code QUEUED} and
 *       {@code RUNNING} rows that never completed.</li>
 *   <li>{@link ResumeAction#NOOP_PAUSED} — keep the child in
 *       {@code PAUSED}; the user must explicitly resume
 *       (e.g. via {@code task/resume}).</li>
 *   <li>{@link ResumeAction#MARK_FAILED} — if the child
 *       crashed hard and we have no usable state, mark it
 *       {@code FAILED} with a diagnostic error so the user
 *       sees it in {@code task/list} and can decide.</li>
 * </ul>
 *
 * <p>The planner is a pure function of the database — it
 * never spawns processes. The supervisor applies the actions
 * after restart.
 */
public final class ResumePlanner {

    private static final Logger LOG = LoggerFactory.getLogger(ResumePlanner.class);

    private final SupervisorStore store;

    public ResumePlanner(SupervisorStore store) {
        this.store = Objects.requireNonNull(store, "store");
    }

    public List<ChildRecord> findResumable() throws SQLException {
        return store.listResumable();
    }

    public ResumeAction plan(ChildRecord child) {
        return switch (child.status()) {
            case QUEUED, RUNNING -> ResumeAction.RESPAWN;
            case PAUSED -> ResumeAction.NOOP_PAUSED;
            default -> ResumeAction.MARK_FAILED; // unreachable for resumable, defensive
        };
    }

    /**
     * Run the planner against the database, mutating rows in
     * place. The actual process re-spawn is left to the caller
     * (this method is a pure DB transform so tests can call it
     * without spawning a real child process).
     */
    public List<ResumeDecision> planAndApply() throws SQLException {
        List<ChildRecord> resumable = findResumable();
        java.util.ArrayList<ResumeDecision> decisions = new java.util.ArrayList<>();
        for (ChildRecord r : resumable) {
            ResumeAction action = plan(r);
            String reason;
            switch (action) {
                case RESPAWN -> reason = "respawn from state";
                case NOOP_PAUSED -> reason = "user-paused; keep paused";
                case MARK_FAILED -> reason = "no recoverable state";
                default -> reason = "unknown";
            }
            decisions.add(new ResumeDecision(r, action, reason));
        }
        LOG.info("resume planner: {} children to handle on startup", decisions.size());
        return decisions;
    }

    public enum ResumeAction {
        RESPAWN,
        NOOP_PAUSED,
        MARK_FAILED
    }

    /** What the supervisor decided to do with a single row. */
    public record ResumeDecision(ChildRecord child, ResumeAction action, String reason) {}
}
