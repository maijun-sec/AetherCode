package org.aethercode.sdk;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * prior round (Option A): per-session specification.
 *
 * <p>previously-M every session was identified by a
 * single {@code sessionId} string. The daemon itself
 * was bound to one cwd (the {@code --cwd} flag);
 * multi-project support meant multiple daemon
 * processes, one per project.
 *
 * <p>prior round.1 introduces a {@code SessionSpec} record
 * that captures the session's identity + working
 * directory. The daemon's cwd is now the DEFAULT
 * (used when the spec doesn't carry one) and
 * individual sessions can override.
 *
 * <p>This is the foundation for the prior round family:
 * <ul>
 *   <li><b>prior round.1 (this R)</b>: spec carries
 *       {@code sessionId} + {@code cwd}. The
 *       {@code EngineFactory} reads {@code spec.cwd()}
 *       when building a new engine.</li>
 *   <li><b>prior round.2</b>: {@code Engine.query(sessionId, ...)}
 *       routes the per-session cwd through the file /
 *       shell tools so the model sees the right
 *       project root.</li>
 *   <li><b>prior round.3</b>: HTTP+WS switch adds
 *       {@code POST /createSession({cwd})} so
 *       external clients (TUI, IDE plugin) can create
 *       sessions with explicit cwds.</li>
 * </ul>
 *
 * <p>Backward compatibility: the existing
 * {@code createSession(String sessionId)} overload
 * still works; it builds a default {@code SessionSpec}
 * that inherits the daemon's cwd. legacy-M
 * clients see no change.
 */
public record SessionSpec(
        String sessionId,
        /** prior round.1: per-session cwd. May be null — the
         *  daemon's default cwd is used in that case. */
        String cwd,
        /** prior round.5 (Option B): optional worktree name.
         *  When non-null, the engine will operate on
         *  {@code <daemon-worktree-root>/<worktreeName>}
         *  as its cwd (after a {@code git worktree add}).
         *  Mutually exclusive with {@code cwd}. */
        String worktree,
        /** Optional: model id to use for this session.
         *  Overrides the daemon's default model. May be
         *  null. */
        String model
) {
    public SessionSpec {
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalArgumentException("sessionId must not be blank");
        }
        // prior round.1: blank strings are normalised to
        // null. The wire format and the engine
        // both treat null as "absent" — keeping
        // "" around would require every consumer
        // to remember to .isBlank() before .null.
        if (cwd != null && cwd.isBlank()) cwd = null;
        if (worktree != null && worktree.isBlank()) worktree = null;
        if (model != null && model.isBlank()) model = null;
        // cwd and worktree are mutually exclusive
        if (cwd != null && worktree != null) {
            throw new IllegalArgumentException("cwd and worktree are mutually exclusive");
        }
    }

    /** Convenience: spec with only a sessionId (uses
     *  daemon defaults for cwd/worktree/model). */
    public static SessionSpec of(String sessionId) {
        return new SessionSpec(sessionId, null, null, null);
    }

    /** Convenience: spec with sessionId + cwd. */
    public static SessionSpec withCwd(String sessionId, String cwd) {
        return new SessionSpec(sessionId, cwd, null, null);
    }

    /** Convenience: spec with sessionId + worktree. */
    public static SessionSpec withWorktree(String sessionId, String worktree) {
        return new SessionSpec(sessionId, null, worktree, null);
    }

    public String effectiveCwd(String defaultCwd) {
        if (cwd != null && !cwd.isBlank()) return cwd;
        return defaultCwd;
    }

    /** prior round.5: convenience for "this session is a
     *  worktree". The worktree name is required
     *  (no daemon default). */
    public boolean isWorktree() {
        return worktree != null;
    }

    /** Wire snapshot for the {@code listSessions} RPC. */
    public Map<String, Object> toWireSnapshot() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("sessionId", sessionId);
        m.put("cwd", cwd);
        m.put("worktree", worktree);
        m.put("model", model);
        return m;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof SessionSpec s)) return false;
        return Objects.equals(sessionId, s.sessionId)
                && Objects.equals(cwd, s.cwd)
                && Objects.equals(worktree, s.worktree)
                && Objects.equals(model, s.model);
    }

    @Override
    public int hashCode() {
        return Objects.hash(sessionId, cwd, worktree, model);
    }
}
