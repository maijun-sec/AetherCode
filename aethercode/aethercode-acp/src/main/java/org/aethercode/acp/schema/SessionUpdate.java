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
