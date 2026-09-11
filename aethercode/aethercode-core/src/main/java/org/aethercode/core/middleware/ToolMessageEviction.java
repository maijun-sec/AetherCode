package org.aethercode.core.middleware;

import org.aethercode.core.fs.backend.BackendProtocol;
import org.aethercode.core.fs.backend.BackendUtils;
import org.aethercode.core.runtime.ContentBlock;
import org.aethercode.core.runtime.Message;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Pattern;
import org.aethercode.core.runtime.Message.ToolMessage;

/**
 * Shared helpers for evicting/clipping large tool message content with a
 * head+tail preview.
 *
 * <p>Java-native port of
 * {@code deepagents.middleware._message_eviction}. Used by:</p>
 * <ul>
 *   <li>{@code FilesystemMiddleware} &mdash; proactive per-tool-call offload
 *       when a tool result exceeds its configured size threshold.</li>
 *   <li>{@code SummarizationMiddleware} &mdash; reactive tail-clipping in the
 *       fallback summarization path after a
 *       {@code ContextOverflowError}.</li>
 * </ul>
 *
 * <p>The helpers operate on {@link ToolMessage} (extended in prior round.5 to
 * carry the {@code name}, {@code status}, {@code artifact},
 * {@code additionalKwargs}, and {@code responseMetadata} fields the Python
 * port needs to preserve) and {@link BackendProtocol} (the same
 * {@code write}/{@code awrite} surface used elsewhere).</p>
 */
public final class ToolMessageEviction {
    private ToolMessageEviction() {}

    /**
     * Head+tail preview template injected into an evicted ToolMessage.
     * Mirrors the Python port's {@code TOO_LARGE_TOOL_MSG} constant.
     */
    public static final String TOO_LARGE_TOOL_MSG_TEMPLATE =
            "Tool result too large, the result of this tool call %s was saved in the filesystem at this path: %s\n"
                    + "\n"
                    + "You can read the result from the filesystem by using the read_file tool, but make sure to only read part of the result at a time.\n"
                    + "\n"
                    + "You can do this by specifying an offset and limit in the read_file tool call. For example, to read the first 100 lines, you can use the read_file tool with offset=0 and limit=100.\n"
                    + "\n"
                    + "Here is a preview showing the head and tail of the result (lines of the form `... [N lines truncated] ...` indicate omitted lines in the middle of the content):\n"
                    + "\n"
                    + "%s\n";

    private static final Pattern LINE_SPLIT_RE = Pattern.compile("\\r?\\n");

    // -----------------------------------------------------------------
    // Content preview
    // -----------------------------------------------------------------

    /**
     * Create a head/tail preview of {@code contentStr} using
     * {@link BackendUtils#formatContentWithLineNumbers} for the gutter
     * formatting. The Python port uses 5 head + 5 tail lines by default.
     */
    public static String createContentPreview(String contentStr) {
        return createContentPreview(contentStr, 5, 5);
    }

    /**
     * Create a head/tail preview of {@code contentStr}, with the head
     * and tail truncated to {@code headLines} / {@code tailLines}
     * lines respectively. Each individual line is truncated to
     * 1000 chars so an extremely long single line cannot blow up the
     * preview.
     */
    public static String createContentPreview(String contentStr, int headLines, int tailLines) {
        String[] lines = LINE_SPLIT_RE.split(contentStr);
        if (lines.length <= headLines + tailLines) {
            List<String> shortLines = new ArrayList<>(lines.length);
            for (String l : lines) shortLines.add(truncateToChars(l, 1000));
            return BackendUtils.formatContentWithLineNumbers(shortLines, 1);
        }
        List<String> head = new ArrayList<>(headLines);
        for (int i = 0; i < headLines; i++) head.add(truncateToChars(lines[i], 1000));
        List<String> tail = new ArrayList<>(tailLines);
        for (int i = lines.length - tailLines; i < lines.length; i++) tail.add(truncateToChars(lines[i], 1000));

        String headSample = BackendUtils.formatContentWithLineNumbers(head, 1);
        String truncationNotice = "\n... [" + (lines.length - headLines - tailLines) + " lines truncated] ...\n";
        String tailSample = BackendUtils.formatContentWithLineNumbers(tail, lines.length - tailLines + 1);
        return headSample + truncationNotice + tailSample;
    }

    private static String truncateToChars(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max);
    }

    // -----------------------------------------------------------------
    // Text extraction
    // -----------------------------------------------------------------

    /**
     * Extract the text content from a message's content blocks. Joins
     * all text blocks and ignores non-text blocks (images, audio,
     * etc.) so binary payloads don't inflate the size measurement.
     *
     * <p>Mirrors the Python port's
     * {@code _extract_text_from_message}.</p>
     */
    public static String extractTextFromMessage(Message message) {
        if (message == null) return "";
        StringBuilder sb = new StringBuilder();
        for (ContentBlock b : message.content()) {
            if (b instanceof ContentBlock.TextBlock t) {
                if (sb.length() > 0) sb.append('\n');
                sb.append(t.text());
            }
        }
        return sb.toString();
    }

    // -----------------------------------------------------------------
    // Eviction builders
    // -----------------------------------------------------------------

    /**
     * Build the replacement content for an evicted message,
     * preserving any non-text blocks (images, audio, etc.).
     *
     * <p>If the message's content is a single text block (the common
     * case), returns the replacement text directly. Otherwise returns
     * a list of content blocks: a single text block carrying the
     * replacement, followed by the original non-text blocks.</p>
     */
    public static Object buildEvictedContent(ToolMessage message, String replacementText) {
        List<ContentBlock> original = message.content();
        // Collect non-text blocks.
        List<ContentBlock> media = new ArrayList<>();
        boolean hasNonText = false;
        for (ContentBlock b : original) {
            if (!(b instanceof ContentBlock.TextBlock)) {
                media.add(b);
                hasNonText = true;
            }
        }
        if (!hasNonText) {
            return replacementText;
        }
        List<ContentBlock> out = new ArrayList<>(media.size() + 1);
        out.add(ContentBlock.text(replacementText));
        out.addAll(media);
        return out;
    }

    /**
     * Build a replacement {@link ToolMessage} that carries
     * {@code evictedContent} while preserving identity fields (id,
     * tool_call_id, name, status, artifact, additional_kwargs,
     * response_metadata).
     */
    public static ToolMessage buildEvictedToolMessage(ToolMessage message,
                                                              Object evictedContent) {
        List<ContentBlock> content;
        if (evictedContent instanceof String s) {
            content = List.of(ContentBlock.text(s));
        } else if (evictedContent instanceof List<?> list) {
            // Assume list of ContentBlock.
            @SuppressWarnings("unchecked")
            List<ContentBlock> cast = (List<ContentBlock>) list;
            content = List.copyOf(cast);
        } else {
            // Fallback: stringify.
            content = List.of(ContentBlock.text(String.valueOf(evictedContent)));
        }
        return new ToolMessage(
                message.id(),
                message.toolCallId(),
                content,
                message.name(),
                message.status(),
                message.artifact(),
                message.additionalKwargs(),
                message.responseMetadata());
    }

    // -----------------------------------------------------------------
    // Offload helpers
    // -----------------------------------------------------------------

    /**
     * Write {@code contentStr} to {@code {largeToolResultsPrefix}/{sanitized-id}}
     * on the supplied backend and return a clipped replacement
     * {@link ToolMessage} carrying the
     * {@link #TOO_LARGE_TOOL_MSG_TEMPLATE} head/tail preview.
     *
     * <p>Returns {@code null} if the backend write fails &mdash;
     * the caller should keep the original message in that case.</p>
     */
    public static ToolMessage offloadToolMessageContent(ToolMessage message,
                                                               String contentStr,
                                                               BackendProtocol backend,
                                                               String largeToolResultsPrefix) {
        String sanitizedId = sanitizeToolCallId(message.toolCallId());
        String filePath = largeToolResultsPrefix + "/" + sanitizedId;
        var result = backend.write(filePath, contentStr);
        if (result == null || result.error().isPresent()) {
            return null;
        }
        String preview = createContentPreview(contentStr);
        String replacement = String.format(TOO_LARGE_TOOL_MSG_TEMPLATE,
                Optional.ofNullable(message.toolCallId()).orElse(""), filePath, preview);
        return buildEvictedToolMessage(message, buildEvictedContent(message, replacement));
    }

    /**
     * Async variant of {@link #offloadToolMessageContent}.
     */
    public static CompletableFuture<ToolMessage> aoffloadToolMessageContent(
            ToolMessage message,
            String contentStr,
            BackendProtocol backend,
            String largeToolResultsPrefix) {
        String sanitizedId = sanitizeToolCallId(message.toolCallId());
        String filePath = largeToolResultsPrefix + "/" + sanitizedId;
        return backend.awrite(filePath, contentStr).thenApply(result -> {
            if (result == null || result.error().isPresent()) return null;
            String preview = createContentPreview(contentStr);
            String replacement = String.format(TOO_LARGE_TOOL_MSG_TEMPLATE,
                    Optional.ofNullable(message.toolCallId()).orElse(""), filePath, preview);
            return buildEvictedToolMessage(message, buildEvictedContent(message, replacement));
        });
    }

    private static String sanitizeToolCallId(String id) {
        if (id == null) return "unknown";
        // Match the Python port's `sanitize_tool_call_id` (already in BackendUtils):
        // replace `/`, `\`, and `.` with `_`.
        return BackendUtils.sanitizeToolCallId(id);
    }
}
