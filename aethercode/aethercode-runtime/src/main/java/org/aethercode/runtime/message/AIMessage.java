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
 * A message produced by the LLM.
 *
 * <p>Mirror of langchain's <code>AIMessage</code>. Carries text content
 * (or {@link ContentBlock}s for multimodal output), zero-or-more
 * {@link ToolCall}s, an optional reasoning string (for Anthropic extended
 * thinking), and optional token {@link Usage}.</p>
 */
public record AIMessage(
        String id,
        Optional<String> name,
        Object content,                          // String | List<ContentBlock>
        List<ContentBlock> contentBlocks,
        List<ToolCall> toolCalls,
        Optional<String> reasoning,
        Optional<Usage> usage
) implements Message {

    public AIMessage {
        contentBlocks = contentBlocks == null ? List.of() : List.copyOf(contentBlocks);
        toolCalls     = toolCalls     == null ? List.of() : List.copyOf(toolCalls);
    }

    public static AIMessage of(String content) {
        return new AIMessage(
                java.util.UUID.randomUUID().toString(),
                Optional.empty(),
                content,
                List.of(new TextBlock(content)),
                List.of(),
                Optional.empty(),
                Optional.empty());
    }

    public static AIMessage of(String content, List<ToolCall> toolCalls) {
        return new AIMessage(
                java.util.UUID.randomUUID().toString(),
                Optional.empty(),
                content == null ? "" : content,
                content == null ? List.of() : List.of(new TextBlock(content)),
                toolCalls,
                Optional.empty(),
                Optional.empty());
    }

    public static AIMessage withToolCalls(List<ToolCall> toolCalls) {
        return new AIMessage(
                java.util.UUID.randomUUID().toString(),
                Optional.empty(),
                "",
                List.of(),
                toolCalls,
                Optional.empty(),
                Optional.empty());
    }

    public static AIMessage withReasoning(String reasoning, String content, List<ToolCall> toolCalls) {
        return new AIMessage(
                java.util.UUID.randomUUID().toString(),
                Optional.empty(),
                content == null ? "" : content,
                content == null ? List.of() : List.of(new TextBlock(content)),
                toolCalls == null ? List.of() : toolCalls,
                Optional.ofNullable(reasoning),
                Optional.empty());
    }

    @Override
    public String role() {
        return "ai";
    }

    public boolean hasToolCalls() {
        return !toolCalls.isEmpty();
    }
}
