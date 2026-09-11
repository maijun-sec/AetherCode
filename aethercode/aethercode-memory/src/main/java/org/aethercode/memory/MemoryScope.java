package org.aethercode.memory;

/**
 * Memory scope. Modelled after the TS {@code AgentMemoryScope} — three
 * discrete visibility levels for the layered memory system, plus an
 * {@code AUTO} choice for callers that don't want to think about it.
 *
 * <p>R127 adds {@link #SESSION} for per-session memory that follows the
 * session rather than the project: when a user switches the cwd, the
 * session's memory stays with the session. This is the third layer in
 * the layered memory model:
 *
 * <ol>
 *   <li>{@code USER} — global, user-wide, lives in {@code ~/.aethercode/}.</li>
 *   <li>{@code PROJECT} — per-project, lives in {@code <cwd>/.aethercode/}.</li>
 *   <li>{@code SESSION} — per-session, lives in the daemon's local DB
 *       ({@code ~/.aethercode/sessions.db}).</li>
 * </ol>
 *
 * The {@code LOCAL} scope is preserved for the older dual-file layout
 * (the project-level "local to this machine" twin of PROJECT). SESSION
 * is the new third layer and is what the user asked for in the prior round
 * brief.
 */
public enum MemoryScope {
    /** Cross-project, user-wide memory. Stored under {@code <memoryBase>/agent-memory/<agentType>/}. */
    USER,
    /** Project-wide, shared via git. Stored under {@code <cwd>/.aethercode/agent-memory/<agentType>/}. */
    PROJECT,
    /** per-session memory. Stored in the daemon's local DB
     *  ({@code ~/.aethercode/sessions.db}, table {@code session_memory}).
     *  Indexed by session id + key. Project memory follows the
     *  cwd; session memory follows the session even when the
     *  cwd changes. */
    SESSION,
    /** Per-machine local. Stored under {@code <cwd>/.aethercode/agent-memory-local/<agentType>/}. */
    LOCAL,
    /** caller doesn't care — resolve at runtime. The
     *  default mapping is PROJECT for in-repo work, USER
     *  otherwise. Use {@link #resolve(java.nio.file.Path)} to
     *  convert. SESSION is never returned by resolve() because
     *  it requires a sessionId, not a cwd. */
    AUTO;

    /** resolve AUTO to a concrete scope based on whether
     *  {@code cwd} is inside a git repository. Returns the
     *  argument unchanged if it's not AUTO. */
    public MemoryScope resolve(java.nio.file.Path cwd) {
        if (this != AUTO) return this;
        if (cwd == null) return USER;
        return isInsideGitRepo(cwd) ? PROJECT : USER;
    }

    private static boolean isInsideGitRepo(java.nio.file.Path cwd) {
        java.nio.file.Path p = cwd.toAbsolutePath();
        while (p != null) {
            if (java.nio.file.Files.isDirectory(p.resolve(".git"))) return true;
            p = p.getParent();
        }
        return false;
    }
}

