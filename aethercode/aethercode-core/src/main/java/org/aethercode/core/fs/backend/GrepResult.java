package org.aethercode.core.fs.backend;

import java.util.List;
import java.util.Optional;

/**
 * Result of a backend grep operation.
 *
 * <p>Mirror of the deepagents <code>GrepResult</code> dataclass. The
 * <code>truncated</code> flag is true when the search stopped early
 * (e.g. hit its time limit) and {@code matches} is therefore incomplete
 * but still valid.</p>
 */
public record GrepResult(
        Optional<String> error,
        Optional<List<GrepMatch>> matches,
        boolean truncated
) {
    public static GrepResult of(List<GrepMatch> matches) {
        return new GrepResult(Optional.empty(), Optional.ofNullable(matches), false);
    }

    public static GrepResult of(List<GrepMatch> matches, boolean truncated) {
        return new GrepResult(Optional.empty(), Optional.ofNullable(matches), truncated);
    }

    public static GrepResult error(String message) {
        return new GrepResult(Optional.ofNullable(message), Optional.empty(), false);
    }
}
