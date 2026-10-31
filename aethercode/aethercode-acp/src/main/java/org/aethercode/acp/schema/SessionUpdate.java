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

package org.aethercode.acp.schema;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Session update records sent from server to client.
 *
 * <p>Mirrors {@code acp.schema.{AgentMessageChunk,UserMessageChunk,
 * AgentPlanUpdate,AgentThoughtChunk}}. The Java port models the
 * union as a sealed interface so call sites can switch on the
 * concrete variant.</p>
 */
public sealed interface SessionUpdate
        permits SessionUpdate.AgentMessageChunk,
                SessionUpdate.UserMessageChunk,
                SessionUpdate.AgentThoughtChunk,
                SessionUpdate.AgentPlanUpdate,
                SessionUpdate.ToolCallUpdate,
                SessionUpdate.ToolCallStart {

    /**
     * Discriminant: matches the {@code session_update} field
     * on the wire.
     */
    String sessionUpdate();

    /** A streamed chunk of the agent's reply. */
    record AgentMessageChunk(
            String messageId,
            ContentBlock content) implements SessionUpdate {
        public AgentMessageChunk {
            Objects.requireNonNull(content, "content");
            messageId = messageId == null ? "" : messageId;
        }
        @Override public String sessionUpdate() { return "agent_message_chunk"; }
    }

    /** A streamed chunk of the user's message (replay only). */
    record UserMessageChunk(
            String messageId,
            ContentBlock content) implements SessionUpdate {
        public UserMessageChunk {
            Objects.requireNonNull(content, "content");
            messageId = messageId == null ? "" : messageId;
        }
        @Override public String sessionUpdate() { return "user_message_chunk"; }
    }

    /** A streamed chunk of the agent's internal reasoning. */
    record AgentThoughtChunk(
            String messageId,
            ContentBlock content) implements SessionUpdate {
        public AgentThoughtChunk {
            Objects.requireNonNull(content, "content");
            messageId = messageId == null ? "" : messageId;
        }
        @Override public String sessionUpdate() { return "agent_thought_chunk"; }
    }

    /** A full plan update (replaces any prior plan). */
    record AgentPlanUpdate(
            List<PlanEntry> entries) implements SessionUpdate {
        public AgentPlanUpdate {
            entries = entries == null ? List.of() : List.copyOf(entries);
        }
        @Override public String sessionUpdate() { return "plan"; }
    }

    /** A tool-call start update. */
    record ToolCallStart(
            String toolCallId,
            String title,
            String kind,
            String status,
            Map<String, Object> rawInput) implements SessionUpdate {
        public ToolCallStart {
            Objects.requireNonNull(toolCallId, "toolCallId");
            kind = kind == null ? ToolKind.OTHER : kind;
            status = status == null ? "pending" : status;
            rawInput = rawInput == null ? Map.of() : Map.copyOf(rawInput);
        }
        @Override public String sessionUpdate() { return "tool_call_start"; }
    }

    /** A tool-call status / content update. */
    record ToolCallUpdate(
            String toolCallId,
            String title,
            String kind,
            String status,
            Map<String, Object> rawInput) implements SessionUpdate {
        public ToolCallUpdate {
            Objects.requireNonNull(toolCallId, "toolCallId");
            kind = kind == null ? null : kind;
            status = status == null ? null : status;
            rawInput = rawInput == null ? null : Map.copyOf(rawInput);
        }
        @Override public String sessionUpdate() { return "tool_call_update"; }
    }
}
