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

import org.aethercode.core.runtime.Message;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.aethercode.core.runtime.Message.AIMessage;
import org.aethercode.core.runtime.Message.HumanMessage;
import org.aethercode.core.runtime.Message.ToolMessage;
import org.aethercode.core.runtime.ContentBlock.ToolUseBlock;

/**
 * Reorder an {@code AIMessage} tool-call batch so that any
 * synthetic media {@link HumanMessage} emitted by a
 * {@code read_file} video frame extraction is moved behind
 * the full {@link ToolMessage} batch.
 *
 * <p>Java-native port of
 * {@code deepagents.middleware.filesystem._move_media_results_after_tool_results}
 * and the
 * {@code _is_read_file_media_result} helper.</p>
 *
 * <p>Tool-call providers require every {@code ToolMessage} for
 * an assistant tool-call batch to arrive before any non-tool
 * message. Video reads attach sampled frames as a synthetic
 * {@code HumanMessage}; when multiple tools run in the same
 * turn this helper keeps those attachments behind the full
 * batch.</p>
 */
public final class MediaResultReorderer {
    private MediaResultReorderer() {}

    /**
     * Key on {@link HumanMessage#additionalKwargs()}
     * that flags a message as carrying a {@code read_file} media
     * result (sampled video frames, attached images, etc.).
     */
    public static final String READ_FILE_MEDIA_RESULT = "read_file_media_result";

    /**
     * Return whether {@code message} carries media emitted by
     * a {@code read_file} tool result.
     */
    public static boolean isReadFileMediaResult(Message message) {
        if (!(message instanceof HumanMessage hm)) return false;
        Map<String, Object> kw = hm.additionalKwargs();
        if (kw == null) return false;
        Object v = kw.get(READ_FILE_MEDIA_RESULT);
        return v instanceof Boolean b && b;
    }

    /**
     * Reorder an AIMessage tool-call batch so any media
     * attachment appears after the full ToolMessage batch.
     */
    public static List<Message> moveMediaResultsAfterToolResults(List<Message> messages) {
        if (messages == null || messages.isEmpty()) {
            return messages == null ? List.of() : messages;
        }
        List<Message> reordered = new ArrayList<>();
        int i = 0;
        while (i < messages.size()) {
            Message current = messages.get(i);
            reordered.add(current);
            i++;
            if (!(current instanceof AIMessage ai) || !hasToolCalls(ai)) {
                continue;
            }
            List<Message> batch = new ArrayList<>();
            while (i < messages.size()) {
                Message next = messages.get(i);
                if (next instanceof ToolMessage || isReadFileMediaResult(next)) {
                    batch.add(next);
                    i++;
                    continue;
                }
                break;
            }
            if (!batch.isEmpty()) {
                // First all ToolMessages, then the media HumanMessages.
                for (Message m : batch) {
                    if (m instanceof ToolMessage) reordered.add(m);
                }
                for (Message m : batch) {
                    if (isReadFileMediaResult(m)) reordered.add(m);
                }
            }
        }
        return reordered;
    }

    private static boolean hasToolCalls(AIMessage ai) {
        // AIMessage.content() is a List<ContentBlock>; a
        // ToolUseBlock signals a tool call.
        for (org.aethercode.core.runtime.ContentBlock b : ai.content()) {
            if (b instanceof org.aethercode.core.runtime.ContentBlock.ToolUseBlock) return true;
        }
        return false;
    }
}
