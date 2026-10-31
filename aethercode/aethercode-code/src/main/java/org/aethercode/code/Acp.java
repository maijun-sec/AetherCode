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

package org.aethercode.code;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * Dcode-specific ACP approval-mode adapter.
 *
 * <p>Java-native port of the Python {@code deepagents_code.acp} module.
 * The ACP server delivers user prompts and approval-mode state through
 * the {@code deepagents-acp} graph; this class wraps the
 * classifier/approval-mode surface the server builds.</p>
 */
public final class Acp {
    private Acp() {}

    private static final Logger LOG = LoggerFactory.getLogger(Acp.class);

    /** Approve a tool call (used by the ACP auto-mode). */
    public static CompletionStage<Map<String, Object>> approve(String threadId, String mode) {
        // The Java port returns a placeholder shape; the full implementation
        // is part of the deepagents-acp port.
        return CompletableFuture.completedFuture(Map.of(
                "thread_id", threadId == null ? "" : threadId,
                "approval_mode", mode == null ? ApprovalMode.Mode.AUTO.value() : mode,
                "turn_id", UUID.randomUUID().toString()));
    }

    /** Reject a tool call. */
    public static CompletionStage<Map<String, Object>> reject(String threadId, String reason) {
        return CompletableFuture.completedFuture(Map.of(
                "thread_id", threadId == null ? "" : threadId,
                "approval_mode", ApprovalMode.Mode.MANUAL.value(),
                "reason", reason == null ? "" : reason));
    }
}
