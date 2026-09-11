package org.aethercode.deepagents.chat;

import org.aethercode.core.llm.ChatClient;
import org.aethercode.core.permission.PermissionResult;
import org.aethercode.core.stream.StreamEvent;
import org.aethercode.deepagents.tools.Tool;
import org.aethercode.core.runtime.Message;
import org.aethercode.core.runtime.ContentBlock;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import java.util.stream.Stream;

/**
 * Adapter that bridges AetherCode's production {@link ChatClient} to the
 * deepagents runtime's {@code Function<List<Message>, AIMessage>} contract.
 *
 * <p>Two type-system gaps must be closed for the bridge to compile and
 * run:</p>
 *
 * <ol>
 *   <li><b>Message types differ.</b> The deepagents runtime uses
 *       {@link org.aethercode.core.runtime.Message} (sealed interface with
 *       {@code HumanMessage} / {@code AIMessage} / {@code ToolMessage} /
 *       {@code SystemMessage} records). AetherCode's production wire
 *       protocol uses {@link org.aethercode.core.message.Message} (a flat
 *       record with a {@code Role} enum). The adapter converts between
 *       the two shapes when handing the conversation history to the
 *       chat client, and when reconstructing the
 *       {@link org.aethercode.core.runtime.Message.AIMessage} the
 *       runtime expects.</li>
 *
 *   <li><b>Tool types differ.</b> The deepagents runtime uses
 *       {@link Tool} (sync {@code invoke(Map) → Object}). AetherCode's
 *       production {@link org.aethercode.core.tool.Tool} is async with
 *       a richer surface ({@code call(input, ctx) → CompletableFuture
 *       <ToolResult>}). The adapter wraps each deepagents tool in a
 *       thin AetherCode {@code Tool} shell that delegates
 *       {@code call} → {@code ainvoke().join()} and normalises the
 *       return value into a {@code ToolResult}.</li>
 * </ol>
 *
 * <p><b>System prompt.</b> The deepagents runtime does not pass a
 * separate {@code systemPrompt} argument to the chat model function
 * &mdash; the system prompt is prepended to the message list as a
 * {@link org.aethercode.core.runtime.Message.SystemMessage} when
 * {@link org.aethercode.deepagents.graph.DeepAgent#buildMessages}
 * assembles the view. The adapter peels that leading
 * {@code SystemMessage} off the front, hands the rest to the
 * {@link ChatClient#stream} call as the message history, and passes
 * the extracted text as the dedicated {@code systemPrompt} argument
 * the production interface expects.</p>
 *
 * <p><b>Stream aggregation.</b> The production wire returns
 * {@code Stream<StreamEvent>}; the deepagents runtime wants a single
 * {@code AIMessage}. The adapter drains the stream and builds one
 * {@code AIMessage} per call by concatenating {@code TextDelta}
 * events and collecting {@code ToolUseStart} events. The
 * {@code RunStart}, {@code ToolResult}, {@code SideNote},
 * {@code Usage}, and {@code SubTask*}/ {@code AwaitUserDecision}
 * events are filtered out: the deepagents runtime manages tool
 * dispatch and user-decision handling itself.</p>
 *
 * <p><b>Errors.</b> Errors propagate naturally as exceptions from
 * the underlying {@code Stream}. The {@code RunEnd.stopReason}
 * value is not treated as a terminal-error flag in the production
 * wire shape (it carries a normal string like {@code "end_turn"} or
 * {@code "tool_use"}); the adapter simply stops aggregating on
 * the first {@code RunEnd}.</p>
 *
 * <p>This class is intentionally placed in the
 * {@code org.aethercode.deepagents.chat} sub-package so callers can
 * import it without polluting the main {@code deepagents.graph}
 * namespace.</p>
 */
public final class AetherCodeChatModelAdapter {

    private final ChatClient chatClient;
    private final List<Tool> deepagentsTools;

    /**
     * @param chatClient       the AetherCode production chat client to drive.
     * @param deepagentsTools  the deepagents-side tools the runtime made
     *                         available; the adapter will wrap each one as
     *                         an AetherCode {@code Tool} for the
     *                         {@link ChatClient#stream} call.
     */
    public AetherCodeChatModelAdapter(ChatClient chatClient, List<Tool> deepagentsTools) {
        this.chatClient = Objects.requireNonNull(chatClient, "chatClient");
        this.deepagentsTools = deepagentsTools == null ? List.of() : List.copyOf(deepagentsTools);
    }

    /**
     * Materialise this adapter as the {@code Function<List<Message>,
     * AIMessage>} the deepagents runtime expects. Each invocation
     * calls {@link ChatClient#stream} once and aggregates the events
     * into a single assistant message.
     */
    public Function<List<Message>, Message.AIMessage> asFunction() {
        return this::invoke;
    }

    /**
     * Adapter entry point. Visible for direct testing.
     */
    public Message.AIMessage invoke(List<Message> deepagentsMessages) {
        Objects.requireNonNull(deepagentsMessages, "messages");
        // 1. Peel the leading SystemMessage off, if any, and pass the
        //    remainder as the production-side message history.
        String systemPrompt = "";
        List<Message> chatMsgs = new ArrayList<>(deepagentsMessages.size());
        boolean peeled = false;
        for (Message m : deepagentsMessages) {
            if (!peeled && m instanceof Message.SystemMessage sys) {
                systemPrompt = ContentBlock.flattenText(sys.content());
                peeled = true;
                continue;
            }
            chatMsgs.add(m);
        }
        // 2. Convert the remaining deepagents messages to the
        //    production-side Message records.
        List<org.aethercode.core.message.Message> coreMessages = new ArrayList<>(chatMsgs.size());
        for (Message m : chatMsgs) {
            coreMessages.add(toCoreMessage(m));
        }
        // 3. Wrap deepagents tools in production-side Tool shells.
        List<org.aethercode.core.tool.Tool> coreTools = new ArrayList<>(deepagentsTools.size());
        for (Tool t : deepagentsTools) {
            coreTools.add(new DeepagentsToolWrapper(t));
        }
        // 4. Drive the production chat client.
        Stream<StreamEvent> eventStream = chatClient.stream(coreMessages, systemPrompt, coreTools);
        // 5. Aggregate the stream into a single AIMessage.
        return aggregate(eventStream);
    }

    // -----------------------------------------------------------------
    //  Stream → AIMessage aggregation
    // -----------------------------------------------------------------

    private static Message.AIMessage aggregate(Stream<StreamEvent> eventStream) {
        StringBuilder text = new StringBuilder();
        List<ContentBlock.ToolUseBlock> toolUses = new ArrayList<>();
        java.util.Iterator<StreamEvent> it = eventStream.iterator();
        while (it.hasNext()) {
            StreamEvent ev = it.next();
            if (ev instanceof StreamEvent.TextDelta td) {
                if (td.text() != null) text.append(td.text());
            } else if (ev instanceof StreamEvent.ToolUseStart tu) {
                Map<String, Object> input = tu.input() == null ? Map.of() : tu.input();
                toolUses.add(new ContentBlock.ToolUseBlock(
                        Objects.requireNonNullElse(tu.id(), "tu-" + UUID.randomUUID()),
                        Objects.requireNonNullElse(tu.name(), ""),
                        input));
            } else if (ev instanceof StreamEvent.RunEnd) {
                break;
            } else if (ev instanceof StreamEvent.RunStart
                    || ev instanceof StreamEvent.ToolResult
                    || ev instanceof StreamEvent.SideNote
                    || ev instanceof StreamEvent.Usage
                    || ev instanceof StreamEvent.AwaitUserDecision
                    || ev instanceof StreamEvent.SubTaskStart
                    || ev instanceof StreamEvent.SubTaskEnd) {
                // Not part of the deepagents AIMessage shape; ignore.
                continue;
            } else {
                // Unknown future variant — skip rather than fail loudly.
                continue;
            }
        }
        List<ContentBlock> blocks = new ArrayList<>();
        if (text.length() > 0) {
            blocks.add(ContentBlock.text(text.toString()));
        }
        blocks.addAll(toolUses);
        return new Message.AIMessage("ai-" + UUID.randomUUID(), blocks);
    }

    // -----------------------------------------------------------------
    //  Deepagents → core message conversion
    // -----------------------------------------------------------------

    private static org.aethercode.core.message.Message toCoreMessage(Message m) {
        List<org.aethercode.core.message.ContentBlock> coreBlocks = new ArrayList<>(m.content().size());
        for (ContentBlock b : m.content()) {
            coreBlocks.add(toCoreContentBlock(b));
        }
        org.aethercode.core.message.Role role;
        if (m instanceof Message.HumanMessage) {
            role = org.aethercode.core.message.Role.USER;
        } else if (m instanceof Message.AIMessage) {
            role = org.aethercode.core.message.Role.ASSISTANT;
        } else if (m instanceof Message.SystemMessage) {
            role = org.aethercode.core.message.Role.SYSTEM;
        } else if (m instanceof Message.ToolMessage) {
            role = org.aethercode.core.message.Role.TOOL_RESULT;
        } else {
            // RemoveMessage (and any future variants) is a tombstone
            // and should not normally reach the chat client, but if
            // it does we render it as a system message so the wire
            // contract is still satisfied.
            role = org.aethercode.core.message.Role.SYSTEM;
        }
        // Build metadata so the production side can correlate tool
        // results back to their tool-use ids.
        Map<String, Object> meta = new LinkedHashMap<>();
        if (m instanceof Message.ToolMessage tm) {
            meta.put("tool_use_id", tm.toolCallId());
            if (tm.name().isPresent()) meta.put("tool_name", tm.name().get());
            if (tm.status().isPresent()) meta.put("status", tm.status().get());
        } else if (m instanceof Message.HumanMessage hm) {
            // Pass additional kwargs through so the production side can
            // see e.g. read_file_tool_call_id hints the deepagents
            // middlewares attach.
            meta.putAll(hm.additionalKwargs());
        } else if (m instanceof Message.AIMessage am) {
            if (am.toolCallId().isPresent()) meta.put("tool_call_id", am.toolCallId().get());
        }
        return new org.aethercode.core.message.Message(
                Objects.requireNonNullElse(m.id(), UUID.randomUUID().toString()),
                role,
                coreBlocks,
                null,
                meta);
    }

    private static org.aethercode.core.message.ContentBlock toCoreContentBlock(ContentBlock b) {
        if (b instanceof ContentBlock.TextBlock t) {
            return new org.aethercode.core.message.ContentBlock.TextBlock(
                    t.text() == null ? "" : t.text());
        }
        if (b instanceof ContentBlock.ToolUseBlock tu) {
            return new org.aethercode.core.message.ContentBlock.ToolUseBlock(
                    Objects.requireNonNullElse(tu.id(), ""),
                    Objects.requireNonNullElse(tu.name(), ""),
                    tu.input() == null ? Map.of() : tu.input());
        }
        if (b instanceof ContentBlock.ToolResultBlock tr) {
            return new org.aethercode.core.message.ContentBlock.ToolResultBlock(
                    tr.toolUseId() == null ? "" : tr.toolUseId(),
                    tr.content(),
                    tr.isError());
        }
        if (b instanceof ContentBlock.ImageBlock img) {
            // The production wire doesn't carry inline image bytes in
            // the same shape; render as a tool-result block carrying
            // the base64 so downstream consumers can still see it.
            String mime = img.mimeType() == null ? "image/png" : img.mimeType();
            String b64 = img.data() == null ? "" : java.util.Base64.getEncoder().encodeToString(img.data());
            return new org.aethercode.core.message.ContentBlock.ToolResultBlock(
                    "", "image:" + mime + ";base64," + b64, false);
        }
        if (b instanceof ContentBlock.GenericContentBlock g) {
            // Best-effort: serialise the fields map to its
            // string form ({key=value, ...}) and carry it as a
            // tool-result-shaped block so the production side
            // still has a single concrete subtype. The model
            // gets a string the same way the production-side
            // ToolResult handlers would render it.
            String repr = g.fields() == null ? "{}" : g.fields().toString();
            return new org.aethercode.core.message.ContentBlock.ToolResultBlock(
                    "", repr, false);
        }
        // Unknown variant — fall back to a text block carrying the
        // toString() of the input. The deepagents side's sealed
        // hierarchy is exhaustive today, so this only fires for
        // future additions.
        return new org.aethercode.core.message.ContentBlock.TextBlock(b.toString());
    }

    // -----------------------------------------------------------------
    //  Deepagents tool → AetherCode tool wrapper
    // -----------------------------------------------------------------

    /**
     * Wraps a deepagents-side {@link Tool} so it satisfies the production
     * {@link org.aethercode.core.tool.Tool} contract.
     *
     * <p>The wrapper bridges the sync/async gap by delegating
     * {@link org.aethercode.core.tool.Tool#call} to the deepagents
     * tool's {@code ainvoke} and waiting on the result. The
     * {@code Object} returned by the deepagents tool is normalised
     * into a {@code ToolResult}: strings pass through verbatim; any
     * exception is surfaced as an error result.</p>
     */
    private static final class DeepagentsToolWrapper implements org.aethercode.core.tool.Tool {

        private final Tool deepagents;

        DeepagentsToolWrapper(Tool deepagents) {
            this.deepagents = Objects.requireNonNull(deepagents, "deepagents tool");
        }

        @Override
        public String name() {
            return Objects.requireNonNullElse(deepagents.name(), "");
        }

        @Override
        public String description() {
            return Objects.requireNonNullElse(deepagents.description(), "");
        }

        @Override
        public Map<String, Object> inputSchema() {
            // The deepagents tool uses an untyped {@code Object} for
            // the schema; the production wire wants a Map. Render the
            // most common shapes (Map, String) directly; fall back to
            // a single {"type":"object"} envelope.
            Object schema = deepagents.argsSchema();
            if (schema == null) return Map.of("type", "object");
            if (schema instanceof Map<?, ?> m) {
                Map<String, Object> out = new LinkedHashMap<>();
                for (Map.Entry<?, ?> e : m.entrySet()) {
                    if (e.getKey() instanceof String k) {
                        out.put(k, e.getValue());
                    }
                }
                return out;
            }
            if (schema instanceof String s) {
                // Raw JSON schema string — best-effort, wrap as-is so
                // the production side can decide how to use it.
                return Map.of("type", "object", "raw", s);
            }
            return Map.of("type", "object");
        }

        @Override
        public CompletableFuture<PermissionResult> checkPermissions(
                Map<String, Object> input, CallContext ctx) {
            // The deepagents tool layer has its own permission
            // pipeline (the FilesystemMiddleware permission gate).
            // Defer to a permissive answer on the production-side
            // wrapper so we do not double-deny. The production wire
            // sees the deepagents tool as a regular callable; any
            // additional gating the user wants lives in their
            // middleware (e.g. ToolExclusionMiddleware or
            // HumanInTheLoopMiddleware).
            return CompletableFuture.completedFuture(new PermissionResult.Allow(Map.of()));
        }

        @Override
        public CompletableFuture<ToolResult> call(Map<String, Object> input, CallContext ctx) {
            return deepagents.ainvoke(input == null ? Map.of() : input)
                    .handle((result, err) -> {
                        if (err != null) {
                            Throwable cause = err instanceof java.util.concurrent.CompletionException ce
                                    && ce.getCause() != null ? ce.getCause() : err;
                            String msg = cause.getMessage() == null
                                    ? cause.getClass().getSimpleName()
                                    : cause.getMessage();
                            return ToolResult.error("Error: " + msg);
                        }
                        if (result == null) return ToolResult.of("");
                        if (result instanceof ToolResult tr) return tr;
                        if (result instanceof String s) return ToolResult.of(s);
                        // For structured results, hand the model a
                        // string form (toString) so the runtime can
                        // serialise it back to a tool message. The
                        // deepagents side already does this in
                        // DeepAgent.stringifyResult.
                        return ToolResult.of(result.toString());
                    });
        }
    }
}
