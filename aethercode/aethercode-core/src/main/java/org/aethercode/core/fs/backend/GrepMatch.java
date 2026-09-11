package org.aethercode.core.fs.backend;

import java.util.List;
import java.util.Optional;

/**
 * A single match from a grep search.
 *
 * <p>Mirror of the deepagents <code>GrepMatch</code> TypedDict. The
 * <code>context_before</code> and <code>context_after</code> lists are
 * present only when the backend was asked for context lines; both
 * keys are set together on every match or on none.</p>
 */
public record GrepMatch(
        String path,
        int line,
        String text,
        Optional<List<ContextLine>> contextBefore,
        Optional<List<ContextLine>> contextAfter
) {
    public static GrepMatch of(String path, int line, String text) {
        return new GrepMatch(path, line, text, Optional.empty(), Optional.empty());
    }
}
