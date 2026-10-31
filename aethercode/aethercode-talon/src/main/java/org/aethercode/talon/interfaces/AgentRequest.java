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

package org.aethercode.talon.interfaces;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

/**
 * Agent invocation request from a channel or scheduler.
 *
 * <p>Java-native port of {@code deepagents_talon.interfaces.AgentRequest}.</p>
 */
public record AgentRequest(
        String conversationId,
        String text,
        Map<String, Object> metadata,
        ApprovalHandler approvalHandler) {

    public AgentRequest {
        if (conversationId == null) {
            throw new IllegalArgumentException("conversationId must not be null");
        }
        if (text == null) {
            throw new IllegalArgumentException("text must not be null");
        }
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }

    public AgentRequest(String conversationId, String text) {
        this(conversationId, text, Map.of(), null);
    }

    public AgentRequest(String conversationId, String text, Map<String, Object> metadata) {
        this(conversationId, text, metadata, null);
    }

    /**
     * Functional surface for the approval handler.
     */
    @FunctionalInterface
    public interface ApprovalHandler extends Function<ToolApprovalRequest, CompletableFuture<ToolApprovalDecision>> {
    }
}
