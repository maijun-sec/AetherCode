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

package org.aethercode.code.hooks.models;

import java.nio.file.Path;
import java.util.UUID;

/**
 * Per-invocation context passed alongside a hook event.
 *
 * <p>Java 21 port of the Python
 * {@code deepagents_code.hooks.models.domain.HookContext} record.</p>
 */
public record HookContext(
        String threadId,
        Path cwd,
        UUID promptId,
        String approvalMode,
        String effort,
        AgentIdentity agent,
        String transcriptRevision) {

    public HookContext {
        if (threadId == null || threadId.isBlank()) {
            throw new IllegalArgumentException("threadId must be non-blank");
        }
        if (cwd == null) {
            throw new IllegalArgumentException("cwd must not be null");
        }
    }

    /** Convenience: a context with all optional fields unset. */
    public static HookContext of(String threadId, Path cwd) {
        return new HookContext(threadId, cwd, null, "manual", null, null, null);
    }
}
