package org.aethercode.memory;

/**
 * R230 (G10): sensitivity label for memory entries.
 *
 * <p>Drives the {@link MemoryRecall} PII filter — entries with
 * {@link #PII} or {@link #SENSITIVE} are hidden from the default
 * recall unless the caller explicitly opts in.
 *
 * <p>Default for new entries (whether user-typed or LLM-extracted)
 * is {@link #INTERNAL}. The caller (or the LLM extraction prompt)
 * is expected to upgrade the label when the content looks sensitive
 * (auth tokens, emails, file paths under {@code ~/.ssh}, etc.).
 */
public enum Sensitivity {
    /** Safe to share, log, send in any RPC. Examples: project name, build command. */
    PUBLIC,
    /** Default. Safe to recall and persist; not PII but not for public export. */
    INTERNAL,
    /** Should not be recalled into prompts that leave the local machine. */
    SENSITIVE,
    /** Personally identifying information. Hidden by default in recall. */
    PII;

    /** True iff this entry should be hidden from a default {@code recall()} call. */
    public boolean isHiddenByDefault() {
        return this == PII || this == SENSITIVE;
    }

    /** Parse a free-form string (e.g. from RPC input) into an enum, defaulting to INTERNAL. */
    public static Sensitivity parseOrDefault(String s) {
        if (s == null || s.isBlank()) return INTERNAL;
        try { return Sensitivity.valueOf(s.trim().toUpperCase()); }
        catch (IllegalArgumentException e) { return INTERNAL; }
    }
}
