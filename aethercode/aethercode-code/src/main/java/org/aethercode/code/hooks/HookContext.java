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

package org.aethercode.code.hooks;

import java.nio.file.Path;
import java.util.UUID;

/**
 * Per-invocation context passed alongside a hook event.
 *
 * <p>Java-native port of the Python
 * {@code deepagents_code.hooks.models.domain.HookContext} record. The
 * context is the smallest set of facts that handlers may need to make a
 * decision without re-resolving session state.</p>
 *
 * @param threadId     active conversation thread id
 * @param cwd          working directory inherited from the session
 * @param promptId     per-turn prompt id, when one is known
 * @param approvalMode client approval policy
 * @param effort       optional model effort level
 * @param agent        optional subagent identity
 */
public record HookContext(
        String threadId,
        Path cwd,
        UUID promptId,
        ApprovalMode approvalMode,
        String effort,
        AgentIdentity agent) {

    public HookContext {
        if (threadId == null || threadId.isBlank()) {
            throw new IllegalArgumentException("threadId must be non-blank");
        }
        if (cwd == null) {
            throw new IllegalArgumentException("cwd must not be null");
        }
        if (approvalMode == null) {
            approvalMode = ApprovalMode.MANUAL;
        }
    }
}
