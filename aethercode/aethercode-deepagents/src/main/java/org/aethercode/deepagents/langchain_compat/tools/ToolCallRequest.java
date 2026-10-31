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

package org.aethercode.deepagents.langchain_compat.tools;

import java.util.Map;
import java.util.Objects;
import org.aethercode.core.runtime.Message.ToolMessage;

/**
 * LangChain-compatible tool call request.
 *
 * <p>Java-native port of
 * {@code langchain.tools.tool_node.ToolCallRequest}. Carries the
 * tool name, the parsed arguments, and the tool call id; the
 * runtime converts this to a {@code ToolMessage} after invoking
 * the tool. Optionally carries the {@code type} discriminator
 * (e.g. {@code "tool_call"} or {@code "invalid_tool_call"}).</p>
 */
public record ToolCallRequest(
        String toolName,
        Map<String, Object> args,
        String toolCallId,
        String type) {

    public ToolCallRequest {
        Objects.requireNonNull(toolName, "toolName");
        args = args == null ? Map.of() : Map.copyOf(args);
        type = type == null ? "tool_call" : type;
    }

    public ToolCallRequest(String toolName, Map<String, Object> args, String toolCallId) {
        this(toolName, args, toolCallId, "tool_call");
    }

    public boolean isInvalid() {
        return "invalid_tool_call".equals(type);
    }
}
