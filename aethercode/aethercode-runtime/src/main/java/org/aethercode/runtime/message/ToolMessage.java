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
import java.util.Optional;

/**
 * A tool result message — the LLM's view of a tool invocation.
 *
 * <p>Mirror of langchain's <code>ToolMessage</code>. <code>toolCallId</code>
 * matches a {@link ToolCall} from the preceding {@link AIMessage};
 * <code>content</code> is the stringified result the model sees (or a
 * list of {@link ContentBlock} for multimodal results); <code>artifact</code>
 * is an optional machine-readable payload that does not enter the model
 * context (e.g. an {@code ExecuteResponse} from a sandbox shell).</p>
 */
public record ToolMessage(
        String id,
        Optional<String> name,
        String toolCallId,
        Object content,                          // String | List<ContentBlock>
        List<ContentBlock> contentBlocks,
        boolean isError,
        Object artifact
) implements Message {

    public ToolMessage {
        if (toolCallId == null || toolCallId.isEmpty()) {
            throw new IllegalArgumentException("ToolMessage.toolCallId is required");
        }
        if (content == null) {
            throw new IllegalArgumentException("ToolMessage.content is required");
        }
        contentBlocks = contentBlocks == null ? List.of() : List.copyOf(contentBlocks);
    }

    public static ToolMessage of(String toolCallId, String content) {
        return new ToolMessage(
                java.util.UUID.randomUUID().toString(),
                Optional.empty(),
                toolCallId,
                content,
                List.of(new TextBlock(content)),
                false,
                null);
    }

    public static ToolMessage error(String toolCallId, String content) {
        return new ToolMessage(
                java.util.UUID.randomUUID().toString(),
                Optional.empty(),
                toolCallId,
                content,
                List.of(new TextBlock(content)),
                true,
                null);
    }

    public static ToolMessage of(String toolCallId, String content, Object artifact) {
        return new ToolMessage(
                java.util.UUID.randomUUID().toString(),
                Optional.empty(),
                toolCallId,
                content,
                List.of(new TextBlock(content)),
                false,
                artifact);
    }

    @Override
    public String role() {
        return "tool";
    }
}
