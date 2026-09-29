package org.aethercode.sdd;

/**
 * R700 — Exception thrown by the SDD module.
 *
 * <p>Thrown when:
 * <ul>
 *   <li>A required bundle resource is missing from the daemon jar
 *       ({@link SddBundleLoader#load})</li>
 *   <li>An RPC receives an invalid phase number or invalid action</li>
 *   <li>The orchestrator cannot persist or read {@code phase-state.json}</li>
 *   <li>The orchestrator refuses a user action (e.g. {@code skip} on a
 *       REQUIRED phase)</li>
 * </ul>
 *
 * <p>The {@code code} field is a stable string for callers (RPC layer,
 * desktop store, TUI state) to switch on without parsing message text.
 */
public class SddException extends RuntimeException {

    public enum Code {
        /** A bundle resource was not found in the daemon jar. */
        BUNDLE_MISSING,
        /** Caller passed an unknown phase number / phase id. */
        INVALID_PHASE,
        /** Caller passed an action that isn't run|approve|modify|skip|abort. */
        INVALID_ACTION,
        /** Tried to skip a REQUIRED phase. */
        SKIP_REQUIRED_PHASE,
        /** phase-state.json was not found at the expected cwd path. */
        STATE_FILE_MISSING,
        /** phase-state.json couldn't be parsed. */
        STATE_FILE_CORRUPT,
        /** Tried to operate on a run that's not in this cwd. */
        RUN_NOT_FOUND,
        /** Caller passed an invalid slug (not kebab-case, >10 chars, etc). */
        INVALID_SLUG,
        /** LLM call / tool call failed inside the orchestrator. */
        CHAT_FAILURE,
        /** Filesystem write failed. */
        IO_FAILURE,
        /** Generic fallback for anything not classified above. */
        INTERNAL;
    }

    private final Code code;

    public SddException(Code code, String message) {
        super(message);
        this.code = code;
    }

    public SddException(Code code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public Code code() { return code; }

    public SddException(Code code, Throwable cause) {
        super(cause);
        this.code = code;
    }
}