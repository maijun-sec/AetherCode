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

package org.aethercode.core.middleware;

import org.aethercode.core.runtime.ContentBlock;
import org.aethercode.core.runtime.Message.SystemMessage;
import org.aethercode.core.runtime.ContentBlock.TextBlock;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Utility helpers for middleware that need to manipulate system messages.
 *
 * <p>Java-native port of the deepagents
 * {@code middleware/_utils.py::append_to_system_message} helper. Used
 * by summarization, subagents, and rubric middleware to extend the
 * system prompt with extra instructions without losing the original
 * content.</p>
 */
public final class SystemMessageUtils {
    private SystemMessageUtils() {}

    /**
     * Append {@code text} to {@code systemMessage}, returning a new
     * {@link SystemMessage}.
     *
     * <p>Mirror of deepagents
     * {@code append_to_system_message}: if the existing system message
     * already has content blocks, prepend a blank line; otherwise the
     * appended text becomes the only block.</p>
     */
    public static SystemMessage appendToSystemMessage(SystemMessage systemMessage, String text) {
        List<ContentBlock> newContent = new ArrayList<>(
                Optional.ofNullable(systemMessage).map(SystemMessage::content).orElse(List.of()));
        if (!newContent.isEmpty()) {
            text = "\n\n" + text;
        }
        newContent.add(new TextBlock(text));
        return new SystemMessage(UUID.randomUUID().toString(), newContent);
    }
}
