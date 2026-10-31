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

package org.aethercode.code.tui.widgets;

/**
 * Message types in the chat history.
 *
 * <p>Java port of {@code deepagents_code.tui.widgets.message_store.MessageType}
 * (the Python enum is a {@code StrEnum}).</p>
 */
public enum MessageType {
    /** Input authored by the human, rendered above the agent's response. */
    USER,
    /** Streamed agent response rendered with markdown. */
    ASSISTANT,
    /** Record of a tool invocation, including its args, status, and output. */
    TOOL,
    /** Lazy summary retaining completed tool rows as data until expanded. */
    TOOL_GROUP,
    /** Record of a skill invocation, carrying its {@code SKILL.md} body and metadata. */
    SKILL,
    /** Error surfaced to the user (e.g., a failed tool call or SDK exception). */
    ERROR,
    /** App-status note from the app itself (version info, command feedback). */
    APP,
    /** Rubric grader result with a compact summary and expandable details. */
    RUBRIC,
    /** Notification that the prior conversation was summarized/offloaded. */
    SUMMARIZATION,
    /** Unified diff preview attached to a file-modifying tool call. */
    DIFF;

    public String asPythonValue() {
        return name().toLowerCase();
    }

    public static MessageType fromPythonValue(String value) {
        if (value == null) return USER;
        return switch (value.toLowerCase()) {
            case "user" -> USER;
            case "assistant" -> ASSISTANT;
            case "tool" -> TOOL;
            case "tool_group" -> TOOL_GROUP;
            case "skill" -> SKILL;
            case "error" -> ERROR;
            case "app" -> APP;
            case "rubric" -> RUBRIC;
            case "summarization" -> SUMMARIZATION;
            case "diff" -> DIFF;
            default -> USER;
        };
    }
}
