package org.aethercode.config;

/**
 * Operation kind for a tool call. The matrix maps {@code tool × path × opKind}
 * to an {@link Action}. Five kinds cover every standard tool:
 * <ul>
 *   <li>{@link #READ} — read-only inspection (file_read, grep, glob, web_fetch read)</li>
 *   <li>{@link #LIST} — enumerate items (glob result, list_tools, list_skills)</li>
 *   <li>{@link #CREATE} — create new content (file_write new, bash touch, mkdir)</li>
 *   <li>{@link #MODIFY} — change existing content (file_edit, file_write overwrite, sed)</li>
 *   <li>{@link #DELETE} — destroy content (rm, file_write to /dev/null, drop table)</li>
 *   <li>{@link #EXEC} — general side-effect command (bash, run_tests, mvn)</li>
 * </ul>
 *
 * <p>Note that {@link #EXEC} is broader than {@link #CREATE}/{@link #MODIFY}/{@link #DELETE}
 * and is used as a fallback for commands that don't fit the file lifecycle (e.g. {@code mvn test},
 * {@code git status}, {@code curl}). The matrix resolution is best-match, not first-match,
 * so a more specific key wins.
 */
public enum OpKind {
    READ,
    LIST,
    CREATE,
    MODIFY,
    DELETE,
    EXEC;

    /** Parse a string case-insensitively, returning {@code null} for unknown. */
    public static OpKind parse(String s) {
        if (s == null) return null;
        try {
            return OpKind.valueOf(s.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * Coarse classification: this kind only inspects, never mutates. Used by
     * permission engine to skip ask-prompt for read-only ops regardless of path.
     */
    public boolean isReadOnly() {
        return this == READ || this == LIST;
    }
}
