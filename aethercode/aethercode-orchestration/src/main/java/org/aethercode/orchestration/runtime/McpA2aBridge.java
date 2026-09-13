package org.aethercode.orchestration.runtime;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * MCP ↔ A2A message bridge (arXiv:2506.01804, Samsung SDS).
 *
 * <p>The paper proposes a unified framework where the two
 * dominant interop protocols for AI agents — Anthropic's Model
 * Context Protocol (MCP, a tool-call protocol) and Google's
 * Agent2Agent (A2A, a multi-agent message protocol) — share a
 * common envelope. A bridge translates MCP {@code toolCall} /
 * {@code toolResult} messages into A2A {@code message} / {@code
 * artifact} messages, and vice versa.
 *
 * <p>This is the AetherCode Tier-3 implementation. The
 * {@code toA2aMessage} and {@code toMcpToolCall} methods are
 * pure functions: they don't talk to the network, they just
 * transform one record into another. Higher-level dispatch
 * (e.g. actually calling the A2A agent) is the caller's
 * responsibility.
 *
 * <h2>Schema</h2>
 * MCP tool call: {@code {name, args}}
 * A2A message:   {@code {role, parts: [{type, content}]}}
 *
 * The bridge maps {@code name} → A2A role, {@code args} → A2A
 * parts. Going the other way, an A2A message with parts
 * {@code [text]} becomes a {@code text_response} MCP tool call.
 */
public final class McpA2aBridge {

    /** MCP tool call as it appears on the wire. */
    public record McpToolCall(String name, Map<String, Object> args) {
        public McpToolCall {
            name = Objects.requireNonNull(name, "name");
            args = args == null ? Map.of() : Map.copyOf(args);
        }
    }
    /** MCP tool result. */
    public record McpToolResult(String name, Object output, boolean isError) {
        public McpToolResult { Objects.requireNonNull(name, "name"); }
    }
    /** A2A message. */
    public record A2aMessage(String role, List<Map<String, Object>> parts) {
        public A2aMessage {
            role = Objects.requireNonNull(role, "role");
            parts = parts == null ? List.of() : List.copyOf(parts);
        }
    }

    /**
     * Translate an MCP tool call into an A2A message. The role is
     * "user" for any tool call (per the paper's convention).
     * Each arg becomes a {@code data} part; the tool name becomes
     * a {@code text} part header so the receiving A2A agent knows
     * which tool was invoked.
     */
    public A2aMessage toA2aMessage(McpToolCall call) {
        Objects.requireNonNull(call, "call");
        Map<String, Object> header = new HashMap<>();
        header.put("type", "text");
        header.put("content", "tool:" + call.name());
        var parts = new java.util.ArrayList<Map<String, Object>>();
        parts.add(header);
        for (var e : call.args().entrySet()) {
            Map<String, Object> part = new HashMap<>();
            part.put("type", "data");
            Map<String, Object> data = new HashMap<>();
            data.put("name", e.getKey());
            data.put("value", String.valueOf(e.getValue()));
            part.put("content", data);
            parts.add(part);
        }
        return new A2aMessage("user", parts);
    }

    /**
     * Translate an MCP tool result back into an A2A message. On
     * success, the role is "agent" and the output is a single
     * text part. On error, the role is "agent" but with an
     * extra {@code error: true} field so the caller's verifier
     * can react.
     */
    public A2aMessage toA2aMessage(McpToolResult result) {
        Objects.requireNonNull(result, "result");
        Map<String, Object> part = new HashMap<>();
        part.put("type", "text");
        part.put("content", String.valueOf(result.output()));
        if (result.isError()) {
            part.put("error", true);
            part.put("tool", result.name());
        } else {
            part.put("tool", result.name());
        }
        return new A2aMessage("agent", List.of(part));
    }

    /**
     * Translate an A2A message into an MCP tool call. The first
     * text part is treated as the tool name; subsequent data
     * parts become args. Returns null if the message has no
     * usable parts.
     */
    public McpToolCall toMcpToolCall(A2aMessage message) {
        Objects.requireNonNull(message, "message");
        if (message.parts().isEmpty()) return null;
        String name = null;
        Map<String, Object> args = new HashMap<>();
        for (Map<String, Object> part : message.parts()) {
            Object type = part.get("type");
            Object content = part.get("content");
            if ("text".equals(type) && content instanceof String s) {
                if (s.startsWith("tool:")) {
                    name = s.substring("tool:".length());
                } else if (name == null) {
                    name = s; // fallback: first text becomes tool name
                }
            } else if ("data".equals(type) && content instanceof Map<?, ?> data) {
                Object k = data.get("name");
                Object v = data.get("value");
                if (k != null) args.put(String.valueOf(k), v);
            }
        }
        if (name == null) return null;
        return new McpToolCall(name, args);
    }
}
