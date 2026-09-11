package org.aethercode.permission.grants;

import java.nio.file.Path;
import java.util.Objects;

/**
 * T-210 / T-211 / T-212 / design.md §3.1.2: where the three
 * {@code grants.json} files live.
 *
 * <ul>
 *   <li>User — {@code <UserHome>/.aethercode/grants.json}</li>
 *   <li>Project — {@code <cwd>/.aethercode/grants.json}</li>
 *   <li>Session — {@code <cwd>/.aethercode/sessions/<sid>/grants.json}</li>
 * </ul>
 *
 * <p>The class is just a path factory; it does not touch the file
 * system. Resolution rules match design.md exactly so a session
 * grant is searched before a project grant which is searched before
 * a user grant (see {@link GrantResolver}).
 *
 * <p>Passing a {@code null} {@code userHome} makes the user path
 * {@code null} rather than throwing — the caller is then expected
 * to skip the user layer (typical when running in a sandbox that
 * hides the home directory).
 */
public final class GrantPaths {

    /** Default user-home subdir holding the global grants file. */
    public static final String USER_DIR = ".aethercode";

    /** Per-project subdir (relative to cwd). */
    public static final String PROJECT_DIR = ".aethercode";

    /** Subdir under the project dir holding per-session data. */
    public static final String SESSIONS_SUBDIR = "sessions";

    private GrantPaths() {}

    /** T-210: user-scope file path. May be {@code null} when
     *  {@code userHome} is {@code null}. */
    public static Path userGrantsFile(Path userHome) {
        if (userHome == null) return null;
        return userHome.resolve(USER_DIR).resolve(GrantsFile.FILE_NAME);
    }

    /** T-260: user-scope audit log path (the JSONL log
     *  every grant lifecycle event appends to). May be
     *  {@code null} when {@code userHome} is null. */
    public static Path userAuditLogFile(Path userHome) {
        if (userHome == null) return null;
        return userHome.resolve(USER_DIR).resolve("grants.log.jsonl");
    }

    /** T-211: project-scope file path. {@code cwd} is required —
     *  a project without a working dir is not a project. */
    public static Path projectGrantsFile(Path cwd) {
        Objects.requireNonNull(cwd, "cwd");
        return cwd.resolve(PROJECT_DIR).resolve(GrantsFile.FILE_NAME);
    }

    /** T-212: session-scope file path. {@code sessionId} is
     *  required and must be non-blank; an empty id would map to
     *  the project dir and silently merge scopes. */
    public static Path sessionGrantsFile(Path cwd, String sessionId) {
        Objects.requireNonNull(cwd, "cwd");
        Objects.requireNonNull(sessionId, "sessionId");
        if (sessionId.isBlank()) {
            throw new IllegalArgumentException("sessionId must not be blank");
        }
        return cwd.resolve(PROJECT_DIR)
                .resolve(SESSIONS_SUBDIR)
                .resolve(sessionId)
                .resolve(GrantsFile.FILE_NAME);
    }
}
