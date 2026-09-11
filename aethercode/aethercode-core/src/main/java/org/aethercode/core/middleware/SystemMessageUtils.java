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
