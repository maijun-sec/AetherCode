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

package org.aethercode.acp;

import org.aethercode.acp.schema.ContentBlock;
import org.aethercode.acp.schema.PermissionOption;
import org.aethercode.acp.schema.SessionUpdate;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * ACP client interface (the IDE / frontend side).
 *
 * <p>Mirrors {@code acp.interfaces.Client}. The Java port
 * exposes only the methods {@link AgentServerACP} actually
 * uses; the SDK-side contract in Python additionally covers
 * filesystem operations and other client capabilities, but
 * those are not invoked by the Deep Agent server.</p>
 */
public interface Client {

    /**
     * Push a {@link SessionUpdate} to the client.
     *
     * @param sessionId ACP session id
     * @param update the streamed update
     * @param source free-form source label (e.g. {@code "DeepAgent"})
     */
    void sessionUpdate(String sessionId, SessionUpdate update, String source);

    /**
     * Request permission to run a tool call. The returned outcome
     * is a single {@link PermissionOption} identifier; the SDK
     * also wraps the cancel / selected outcomes, but only the
     * {@code selected} case matters for Deep Agent.
     */
    PermissionOutcome requestPermission(
            String sessionId,
            ToolCallView toolCall,
            List<PermissionOption> options);

    /**
     * Async variant. Default delegates to {@link #requestPermission}.
     */
    default CompletableFuture<PermissionOutcome> arequestPermission(
            String sessionId,
            ToolCallView toolCall,
            List<PermissionOption> options) {
        return CompletableFuture.completedFuture(
                requestPermission(sessionId, toolCall, options));
    }

    /**
     * Permission outcome. Mirrors the
     * {@code RequestPermissionResponse} union from the ACP
     * Python SDK: either the user picked an option
     * ({@code outcome == "selected"}) or cancelled
     * ({@code outcome == "cancelled"}).
     */
    final class PermissionOutcome {
        public enum Kind { SELECTED, CANCELLED }

        private final Kind kind;
        private final String optionId;

        public PermissionOutcome(Kind kind, String optionId) {
            this.kind = kind;
            this.optionId = optionId;
        }

        public Kind outcome() { return kind; }
        public String optionId() { return optionId; }

        public static PermissionOutcome selected(String optionId) {
            return new PermissionOutcome(Kind.SELECTED, optionId);
        }

        public static PermissionOutcome cancelled() {
            return new PermissionOutcome(Kind.CANCELLED, null);
        }
    }

    /**
     * Tool-call view passed to {@link #requestPermission}.
     * Mirrors the {@code tool_call} parameter of
     * {@code session/request_permission}: a {@code ToolCallUpdate}
     * shape with the new title and the raw input the model
     * produced.
     */
    record ToolCallView(
            String toolCallId,
            String title,
            Map<String, Object> rawInput,
            List<ContentBlock> content) {
    }
}
