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

package org.aethercode.runtime.message;

import java.util.List;
import java.util.Map;

/**
 * Tool-result content block. Carried inside a {@link ToolMessage} to
 * return a tool invocation result.
 *
 * <p>Mirror of langchain
 * <code>{"type": "tool_result", "tool_use_id": ..., "content": [...], "is_error": bool}</code>.</p>
 */
public record ToolResultBlock(
        String toolUseId,
        List<ContentBlock> content,
        boolean isError
) implements ContentBlock {

    public ToolResultBlock {
        content = content == null ? List.of() : List.copyOf(content);
    }

    /** Builder shortcut for the common case of a single text result. */
    public static ToolResultBlock text(String toolUseId, String text) {
        return new ToolResultBlock(toolUseId, List.of(new TextBlock(text)), false);
    }

    public static ToolResultBlock error(String toolUseId, String text) {
        return new ToolResultBlock(toolUseId, List.of(new TextBlock(text)), true);
    }

    @Override
    public String type() {
        return "tool_result";
    }
}
