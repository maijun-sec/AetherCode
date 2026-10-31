/*
 * Copyright (c) 2026 maijun
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

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
