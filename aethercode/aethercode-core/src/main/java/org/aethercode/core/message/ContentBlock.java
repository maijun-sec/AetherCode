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

package org.aethercode.core.message;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

import java.util.Map;

/**
 * A single content block inside a message. Modelled as a sealed interface so Jackson, the
 * runtime, and pattern matching all agree on the variant set.
 *
 * <p>Three variants cover what the Anthropic Messages API expects: text, tool use (model -> tool),
 * and tool result (tool -> model, carried in a user message).
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes({
        @JsonSubTypes.Type(value = ContentBlock.TextBlock.class,      name = "text"),
        @JsonSubTypes.Type(value = ContentBlock.ToolUseBlock.class,   name = "tool_use"),
        @JsonSubTypes.Type(value = ContentBlock.ToolResultBlock.class,name = "tool_result")
})
public sealed interface ContentBlock
        permits ContentBlock.TextBlock, ContentBlock.ToolUseBlock, ContentBlock.ToolResultBlock {

    String type();

    /** Plain text. */
    record TextBlock(String text) implements ContentBlock {
        public TextBlock { if (text == null) text = ""; }
        public String type() { return "text"; }
    }

    /**
     * Model-emitted tool invocation. The model asks the runtime to run {@code name} with
     * {@code input}, returning its result as a {@link ToolResultBlock} attached to a user message.
     */
    record ToolUseBlock(String id, String name, Map<String, Object> input) implements ContentBlock {
        public ToolUseBlock {
            if (id == null || id.isBlank())   throw new IllegalArgumentException("tool_use id is required");
            if (name == null || name.isBlank()) throw new IllegalArgumentException("tool_use name is required");
            if (input == null) input = Map.of();
        }
        public String type() { return "tool_use"; }
    }

    /**
     * Tool-side response carried in a user message. {@code isError} becomes the wire format
     * {@code is_error} for the API.
     */
    record ToolResultBlock(String toolUseId, Object content, boolean isError) implements ContentBlock {
        public ToolResultBlock(String toolUseId, Object content) { this(toolUseId, content, false); }
        public String type() { return "tool_result"; }
    }
}
