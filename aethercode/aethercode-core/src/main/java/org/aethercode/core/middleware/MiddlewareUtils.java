package org.aethercode.core.middleware;

import org.aethercode.core.runtime.ContentBlock;
import org.aethercode.core.runtime.Message;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.aethercode.core.runtime.Message.SystemMessage;

/**
 * Utility helpers for middleware.
 *
 * <p>Java-native port of the Python {@code deepagents.middleware._utils}
 * module. The single helper {@link #appendToSystemMessage} mirrors the
 * Python {@code append_to_system_message} function.</p>
 */
public final class MiddlewareUtils {
    private MiddlewareUtils() {}

    /**
     * Append text to a system message.
     *
     * @param systemMessage Existing system message, or {@code null} when
     *                       the agent has none yet.
     * @param text          Text to append.
     * @return A new system message with the text appended as an additional
     *         content block. A {@code \n\n} separator is inserted before
     *         the new text when the existing message already has content.
     */
    public static SystemMessage appendToSystemMessage(
            SystemMessage systemMessage, String text) {
        Objects.requireNonNull(text, "text");
        List<ContentBlock> newContent = new ArrayList<>();
        if (systemMessage != null) {
            newContent.addAll(systemMessage.content());
        }
        String effective = text;
        if (!newContent.isEmpty()) {
            effective = "\n\n" + text;
        }
        newContent.add(ContentBlock.text(effective));
        return new SystemMessage(null, newContent);
    }
}
