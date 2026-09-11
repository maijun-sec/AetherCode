package org.aethercode.core.fs.backend;

import java.util.Optional;

/**
 * Result of a shell command execution.
 *
 * <p>Mirror of the deepagents <code>ExecuteResponse</code> dataclass:
 * combined stdout/stderr, an exit code (0 = success, non-zero =
 * failure, empty = could not determine), and a truncation flag.</p>
 */
public record ExecuteResponse(
        String output,
        Optional<Integer> exitCode,
        boolean truncated
) {
    public ExecuteResponse {
        if (output == null) {
            throw new IllegalArgumentException("ExecuteResponse.output is required");
        }
    }

    public static ExecuteResponse of(String output, int exitCode) {
        return new ExecuteResponse(output, Optional.of(exitCode), false);
    }

    public static ExecuteResponse of(String output, int exitCode, boolean truncated) {
        return new ExecuteResponse(output, Optional.of(exitCode), truncated);
    }

    public static ExecuteResponse unknown(String output) {
        return new ExecuteResponse(output, Optional.empty(), false);
    }
}
