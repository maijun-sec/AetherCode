package org.aethercode.permission.prompt;

/**
 * T-251 / spec.md §3.3: the discriminator for the 10-option
 * consent matrix.
 *
 * <p>The 8 standard options fall into the
 * {@link #STANDARD} bucket (1-8 in the TUI list). The 2
 * category-specific wildcard options are {@link #WILDCARD}
 * (9-10). The "once" options are a sub-kind of STANDARD — they
 * are differentiated by scope = SESSION but never persisted to
 * disk (the runtime uses {@code SessionMemory} instead).
 */
public enum OptionKind {
    /** Options 1-2: ephemeral, per-call only. */
    ONCE,
    /** Options 3-8: persisted to grants.json at the
     *  matching scope. */
    STANDARD,
    /** Options 9-10: category wildcard — covers every future
     *  call whose categories include the wildcard sub-category. */
    WILDCARD
}
