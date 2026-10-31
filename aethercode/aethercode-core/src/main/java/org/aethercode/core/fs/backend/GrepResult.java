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
