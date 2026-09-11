package org.aethercode.core.middleware;

import org.aethercode.core.fs.backend.BackendProtocol;
import org.aethercode.core.fs.backend.CompositeBackend;
import org.aethercode.core.fs.backend.EditResult;
import org.aethercode.core.fs.backend.FileDownloadResponse;
import org.aethercode.core.fs.backend.FileUploadResponse;
import org.aethercode.core.fs.backend.WriteResult;
import org.aethercode.core.runtime.AgentState;
import org.aethercode.core.runtime.ContentBlock;
import org.aethercode.core.runtime.Message;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.aethercode.core.runtime.Message.HumanMessage;

/**
 * Pure-logic helpers for backend history offloading used by the
 * summarization middleware.
 *
 * <p>Java-native port of the {@code _DeepAgentsSummarizationMiddleware}
 * instance methods {@code _get_session_id}, {@code _get_history_path},
 * {@code _is_summary_message}, {@code _filter_summary_messages},
 * {@code _build_new_messages_with_path}, and
 * {@code _offload_to_backend} in
 * {@code deepagents.middleware.summarization}. Each helper is a static
 * method that takes its inputs as parameters so the same logic can be
 * exercised from the middleware, the compact tool, and unit tests
 * without standing up the full langchain runtime.</p>
 */
public final class SummarizationHistory {

    private static final Logger log = Logger.getLogger(SummarizationHistory.class.getName());

    /** ISO-8601 timestamp formatter (UTC, second precision). */
    public static final DateTimeFormatter ISO_8601 = DateTimeFormatter
            .ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'")
            .withZone(ZoneOffset.UTC);

    /** Default per-invocation session id prefix. */
    public static final String SESSION_ID_PREFIX = "session_";

    private SummarizationHistory() {}

    // -----------------------------------------------------------------
    //  Path / session id resolution
    // -----------------------------------------------------------------

    /**
     * Compute the backend's artifacts root. Defaults to {@code "/"} when
     * {@code backend} is not a {@link CompositeBackend}.
     */
    public static String artifactsRoot(BackendProtocol backend) {
        if (backend instanceof CompositeBackend cb) {
            String r = cb.artifactsRoot();
            if (r == null) return "/";
            return r;
        }
        return "/";
    }

    /**
     * Build the per-instance history-path prefix
     * {@code <root>/conversation_history}. The trailing slash is
     * stripped from the root so the join is well-formed regardless of
     * whether the user supplied {@code "/"}, {@code ""}, or
     * {@code "/foo/"}.
     */
    public static String historyPathPrefix(BackendProtocol backend) {
        String root = artifactsRoot(backend);
        String trimmed = root.endsWith("/")
                ? root.substring(0, root.length() - 1)
                : root;
        return trimmed + "/conversation_history";
    }

    /**
     * Build the per-instance media-path prefix
     * {@code <root>/conversation_history/media}.
     */
    public static String mediaPathPrefix(BackendProtocol backend) {
        return historyPathPrefix(backend) + "/media";
    }

    /**
     * Return the path the offloaded history is written to for
     * {@code sessionId}.
     */
    public static String historyPath(BackendProtocol backend, String sessionId) {
        return historyPathPrefix(backend) + "/" + sessionId + ".md";
    }

    /**
     * Resolve the session id naming the offload history file.
     *
     * <p>Reuses a previously persisted {@code _summarization_session_id}
     * so history appends to one file across turns; otherwise generates a
     * fresh id, which the caller persists in the state update so later
     * turns reuse it.</p>
     *
     * <p>The id is internal and scoped per graph invocation, so each
     * invocation &mdash; including each sub-agent &mdash; gets its own
     * history file.</p>
     */
    public static String getSessionId(Map<String, Object> state) {
        if (state != null) {
            Object existing = state.get("_summarization_session_id");
            if (existing instanceof String s && !s.isEmpty()) {
                return s;
            }
        }
        // Full uuid4 entropy: history filenames must not collide across
        // independent sessions sharing a backend, or their evicted
        // history mixes.
        return SESSION_ID_PREFIX + UUID.randomUUID().toString().replace("-", "");
    }

    // -----------------------------------------------------------------
    //  Summary-message classification
    // -----------------------------------------------------------------

    /**
     * Check whether {@code msg} is a previous summarization message.
     *
     * <p>Summary messages are {@link HumanMessage} objects with
     * {@code lc_source='summarization'} in
     * {@link HumanMessage#additionalKwargs()}. These should be
     * filtered from offloads to avoid redundant storage during chained
     * summarization.</p>
     */
    public static boolean isSummaryMessage(Message msg) {
        if (!(msg instanceof HumanMessage hm)) {
            return false;
        }
        Object source = hm.additionalKwargs().get("lc_source");
        return "summarization".equals(source);
    }

    /**
     * Filter out previous summary messages from {@code messages}.
     *
     * <p>When chained summarization occurs, we don't want to re-offload
     * the previous summary {@code HumanMessage} since the original
     * messages are already stored in the backend.</p>
     */
    public static List<Message> filterSummaryMessages(List<Message> messages) {
        if (messages == null || messages.isEmpty()) {
            return messages == null ? List.of() : messages;
        }
        List<Message> out = new ArrayList<>(messages.size());
        for (Message m : messages) {
            if (!isSummaryMessage(m)) {
                out.add(m);
            }
        }
        return out;
    }

    // -----------------------------------------------------------------
    //  Build the new summary message(s)
    // -----------------------------------------------------------------

    /**
     * Build the summary message with optional file path reference.
     *
     * <p>Returns a single-element list with one
     * {@link HumanMessage} carrying
     * {@code lc_source='summarization'} in
     * {@link HumanMessage#additionalKwargs()} so the next
     * compaction pass can recognise and skip it.</p>
     */
    public static List<Message> buildNewMessagesWithPath(String summary, String filePath) {
        String body;
        if (filePath != null) {
            body = "You are in the middle of a conversation that has been summarized.\n\n"
                    + "The full conversation history has been saved to " + filePath
                    + " should you need to refer back to it for details.\n\n"
                    + "A condensed summary follows:\n\n"
                    + "<summary>\n" + summary + "\n</summary>";
        } else {
            body = "Here is a summary of the conversation to date:\n\n" + summary;
        }
        HumanMessage msg = new HumanMessage(
                "summarization-" + UUID.randomUUID(),
                List.of(ContentBlock.text(body)),
                java.util.Optional.empty(),
                Map.of("lc_source", SummarizationPrompts.SUMMARIZATION_MESSAGE_SOURCE));
        return List.of(msg);
    }

    // -----------------------------------------------------------------
    //  Apply a prior event to the raw message list
    // -----------------------------------------------------------------

    /**
     * Reconstruct the effective message list from a raw state message
     * list and a prior summarization event.
     *
     * <p>When a prior summarization event exists, the effective
     * conversation is the summary message followed by all messages from
     * {@code cutoff_index} onward. If the event is {@code null} or
     * malformed, the raw list is returned unchanged.</p>
     */
    @SuppressWarnings("unchecked")
    public static List<Message> applyEventToMessages(List<Message> messages,
                                                     Map<String, Object> event) {
        if (event == null) {
            return messages == null ? List.of() : new ArrayList<>(messages);
        }
        Object summaryObj;
        Integer cutoffIdx;
        try {
            summaryObj = event.get("summary_message");
            Object cutoff = event.get("cutoff_index");
            if (!(cutoff instanceof Integer ci)) {
                log.log(Level.WARNING,
                        "Malformed _summarization_event: cutoff_index is not int: {0}",
                        cutoff);
                return messages == null ? List.of() : new ArrayList<>(messages);
            }
            cutoffIdx = ci;
        } catch (RuntimeException exc) {
            log.log(Level.WARNING,
                    "Malformed _summarization_event (missing keys): {0}",
                    exc.toString());
            return messages == null ? List.of() : new ArrayList<>(messages);
        }
        if (summaryObj == null) {
            log.warning("Malformed _summarization_event: summary_message is null");
            return messages == null ? List.of() : new ArrayList<>(messages);
        }
        if (!(summaryObj instanceof Message summary)) {
            log.log(Level.WARNING,
                    "_summarization_event.summary_message is not a Message: {0}",
                    summaryObj.getClass().getName());
            return messages == null ? List.of() : new ArrayList<>(messages);
        }
        List<Message> base = messages == null ? new ArrayList<>() : new ArrayList<>(messages);
        if (cutoffIdx > base.size()) {
            log.log(Level.WARNING,
                    "Summarization cutoff_index {0} exceeds message count {1}; remaining slice will be empty",
                    new Object[]{cutoffIdx, base.size()});
            List<Message> out = new ArrayList<>();
            out.add(summary);
            return out;
        }
        List<Message> out = new ArrayList<>();
        out.add(summary);
        out.addAll(base.subList(cutoffIdx, base.size()));
        return out;
    }

    // -----------------------------------------------------------------
    //  Offload: append-mode markdown history with timestamped sections
    // -----------------------------------------------------------------

    /**
     * Persist {@code messages} to the backend before summarization.
     *
     * <p>Appends evicted messages to a single markdown file per
     * session. Each summarization event adds a new section with a
     * timestamp header. Previous summary messages are filtered out to
     * avoid redundant storage during chained summarization events.</p>
     *
     * <p>A {@code null} return is non-fatal; callers may proceed
     * without the offloaded history. The caller is expected to have
     * rewritten inline media to path references upstream.</p>
     *
     * @return the file path where history was offloaded, or
     *         {@code null} on failure
     */
    public static String offloadToBackend(BackendProtocol backend,
                                          List<Message> messages,
                                          String sessionId,
                                          Instant now) {
        String path = historyPath(backend, sessionId);
        List<Message> filtered = filterSummaryMessages(messages);
        String newSection = formatSection(filtered, now);

        // Read existing content (if any) and append.
        String existingContent = "";
        try {
            List<FileDownloadResponse> responses = backend.downloadFiles(List.of(path));
            if (responses != null && !responses.isEmpty()) {
                FileDownloadResponse r = responses.get(0);
                if (r.error().isEmpty() && r.content().isPresent()) {
                    existingContent = new String(r.content().get(), StandardCharsets.UTF_8);
                }
            }
        } catch (RuntimeException e) {
            // File likely doesn't exist yet, but log for observability.
            log.log(Level.FINE,
                    "Exception reading existing history from {0} (treating as new file): {1}: {2}",
                    new Object[]{path, e.getClass().getSimpleName(), e.getMessage()});
        }
        String combined = existingContent + newSection;

        Optional<String> writeError;
        try {
            if (existingContent.isEmpty()) {
                WriteResult wr = backend.write(path, combined);
                writeError = wr == null ? Optional.of("backend returned null") : wr.error();
            } else {
                EditResult er = backend.edit(path, existingContent, combined, false);
                writeError = er == null ? Optional.of("backend returned null") : er.error();
            }
        } catch (RuntimeException e) {
            log.log(Level.WARNING,
                    "Exception offloading conversation history to {0} ({1} messages): {2}: {3}",
                    new Object[]{path, filtered.size(),
                            e.getClass().getSimpleName(), e.getMessage()});
            return null;
        }
        if (writeError.isPresent()) {
            log.log(Level.WARNING,
                    "Failed to offload conversation history to {0} ({1} messages): {2}",
                    new Object[]{path, filtered.size(), writeError.get()});
            return null;
        }
        log.log(Level.FINE, "Offloaded {0} messages to {1}",
                new Object[]{filtered.size(), path});
        return path;
    }

    /**
     * Async twin of {@link #offloadToBackend}. Default delegates to
     * the sync variant via {@code .join()}.
     */
    public static java.util.concurrent.CompletableFuture<String> aoffloadToBackend(
            BackendProtocol backend, List<Message> messages, String sessionId, Instant now) {
        return java.util.concurrent.CompletableFuture.supplyAsync(
                () -> offloadToBackend(backend, messages, sessionId, now));
    }

    /**
     * Format a single offload section: a {@code ## Summarized at <ts>}
     * header followed by the XML-serialised message list. Mirrors
     * LangChain's {@code get_buffer_string(filtered_messages, format="xml")}.
     */
    public static String formatSection(List<Message> messages, Instant now) {
        StringBuilder sb = new StringBuilder();
        sb.append("## Summarized at ").append(ISO_8601.format(now)).append("\n\n");
        for (Message m : messages) {
            sb.append(formatMessageXml(m));
        }
        sb.append("\n");
        return sb.toString();
    }

    /**
     * XML-style serialisation of a single message. Mirrors the
     * {@code get_buffer_string(messages, format='xml')} output shape:
     * a {@code <message type="..." id="...">...</message>} envelope
     * whose body is the message's text content, with content-block
     * media rendered as a reference tag rather than inline bytes.
     */
    public static String formatMessageXml(Message m) {
        StringBuilder sb = new StringBuilder();
        sb.append("<message type=\"").append(m.role()).append("\"");
        if (m.id() != null && !m.id().isEmpty()) {
            sb.append(" id=\"").append(m.id()).append("\"");
        }
        sb.append(">");
        appendMessageBodyXml(sb, m);
        sb.append("</message>\n");
        return sb.toString();
    }

    private static void appendMessageBodyXml(StringBuilder sb, Message m) {
        if (m.content() == null) return;
        for (ContentBlock b : m.content()) {
            if (b instanceof ContentBlock.TextBlock t) {
                sb.append(escapeXml(t.text()));
            } else if (b instanceof ContentBlock.GenericContentBlock g) {
                String type = g.type();
                // OpenAI-style `image_url` block: a top-level
                // `image_url` field whose `url` is the reference.
                Object imageUrl = g.get("image_url");
                if (imageUrl instanceof Map<?, ?> iu) {
                    Object innerUrl = iu.get("url");
                    if (innerUrl instanceof String s && !s.isEmpty()) {
                        sb.append("<image_url url=\"")
                                .append(escapeXml(s)).append("\" />");
                        continue;
                    }
                }
                Object url = g.get("url");
                if (url instanceof String s && !s.isEmpty()) {
                    sb.append('<').append(type == null ? "media" : type)
                            .append(" url=\"").append(escapeXml(s)).append("\" />");
                } else {
                    Object base64 = g.get("base64");
                    if (base64 instanceof String s && !s.isEmpty()) {
                        // Inline data: blocks are normally rewritten to
                        // path references upstream, but a stray
                        // base64 block is preserved as an image
                        // element here for completeness.
                        Object mt = g.get("mime_type");
                        sb.append("<image mime_type=\"")
                                .append(mt == null ? "application/octet-stream" : mt)
                                .append("\" length=\"").append(s.length()).append("\" />");
                    }
                }
            } else if (b instanceof ContentBlock.ImageBlock ib) {
                sb.append("<image mime_type=\"")
                        .append(ib.mimeType() == null ? "image/png" : ib.mimeType())
                        .append("\" length=\"").append(ib.data().length).append("\" />");
            } else if (b instanceof ContentBlock.ToolUseBlock tu) {
                sb.append("<tool_use name=\"").append(escapeXml(tu.name())).append("\">");
                sb.append(escapeXml(String.valueOf(tu.input())));
                sb.append("</tool_use>");
            } else if (b instanceof ContentBlock.ToolResultBlock tr) {
                sb.append("<tool_result tool_use_id=\"")
                        .append(escapeXml(tr.toolUseId())).append("\">");
                sb.append(escapeXml(String.valueOf(tr.content())));
                sb.append("</tool_result>");
            }
        }
    }

    private static String escapeXml(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '<' -> sb.append("&lt;");
                case '>' -> sb.append("&gt;");
                case '&' -> sb.append("&amp;");
                case '"' -> sb.append("&quot;");
                case '\'' -> sb.append("&apos;");
                default -> sb.append(c);
            }
        }
        return sb.toString();
    }
}
