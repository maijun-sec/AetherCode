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
 * A system / developer message.
 *
 * <p>Mirror of langchain's <code>SystemMessage</code>. The content can be
 * either a plain string (most common) or a list of {@link ContentBlock}.</p>
 */
public record SystemMessage(
        String id,
        Optional<String> name,
        Object content,                  // String | List<ContentBlock>
        List<ContentBlock> contentBlocks
) implements Message {

    public SystemMessage {
        if (content == null) {
            throw new IllegalArgumentException("SystemMessage.content is required");
        }
        contentBlocks = contentBlocks == null ? List.of() : List.copyOf(contentBlocks);
    }

    public SystemMessage(String content) {
        this(java.util.UUID.randomUUID().toString(),
             Optional.empty(),
             content,
             List.of(new TextBlock(content)));
    }

    public SystemMessage(List<ContentBlock> blocks) {
        this(java.util.UUID.randomUUID().toString(),
             Optional.empty(),
             blocks,
             blocks);
    }

    @Override
    public String role() {
        return "system";
    }

    /** Helper: returns the plain-text content if all blocks are {@link TextBlock}. */
    public Optional<String> text() {
        if (content instanceof String s) {
            return Optional.of(s);
        }
        if (!contentBlocks.isEmpty() && contentBlocks.stream().allMatch(b -> b instanceof TextBlock)) {
            return Optional.of(contentBlocks.stream()
                    .map(b -> ((TextBlock) b).text())
                    .reduce("", (a, b) -> a + b));
        }
        return Optional.empty();
    }
}
