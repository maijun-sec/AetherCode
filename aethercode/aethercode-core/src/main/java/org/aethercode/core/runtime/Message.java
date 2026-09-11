package org.aethercode.core.runtime;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The message types exchanged between the agent and the model.
 *
 * <p>Java-native port of LangChain's {@code BaseMessage} hierarchy. The
 * shape is intentionally minimal: every message has an {@code id}, a
 * {@code role}, and one or more {@link ContentBlock}s. We do not mirror
 * the full Pydantic-typed-discriminated-union machinery &mdash; the model
 * adapter is responsible for converting these records into provider
 * payloads (OpenAI chat messages, Anthropic content blocks, etc.).</p>
 */
public interface Message {

    /** Stable identifier; reused by the reducer to dedupe writes. */
    String id();

    /** One of {@code "human"}, {@code "ai"}, {@code "tool"}, {@code "system"}. */
    String role();

    /** Ordered list of content blocks. */
    List<ContentBlock> content();

    // -----------------------------------------------------------------
    //  Concrete variants
    // -----------------------------------------------------------------

    /** Message from the user. */
    record HumanMessage(String id, List<ContentBlock> content,
                        java.util.Optional<String> evictedTo,
                        Map<String, Object> additionalKwargs) implements Message {
        public HumanMessage {
            content = content == null ? List.of() : List.copyOf(content);
            evictedTo = evictedTo == null ? java.util.Optional.empty() : evictedTo;
            additionalKwargs = additionalKwargs == null ? Map.of() : Map.copyOf(additionalKwargs);
        }
        /** Backward-compatible 2-arg constructor. */
        public HumanMessage(String id, List<ContentBlock> content) {
            this(id, content, java.util.Optional.empty(), Map.of());
        }
        /** Backward-compatible 3-arg constructor. */
        public HumanMessage(String id, List<ContentBlock> content,
                            java.util.Optional<String> evictedTo) {
            this(id, content, evictedTo, Map.of());
        }
        /** Convenience constructor with extra kwargs. */
        public HumanMessage(String id, List<ContentBlock> content,
                            java.util.Optional<String> evictedTo,
                            Map<String, Object> additionalKwargs,
                            String toolCallId, String toolCallPath) {
            this(id, content, evictedTo,
                    merge(additionalKwargs, toolCallId, toolCallPath));
        }
        private static Map<String, Object> merge(Map<String, Object> base,
                                                 String toolCallId, String toolCallPath) {
            java.util.LinkedHashMap<String, Object> out = new java.util.LinkedHashMap<>(
                    base == null ? Map.of() : base);
            if (toolCallId != null && !toolCallId.isEmpty()) {
                out.put("read_file_tool_call_id", toolCallId);
            }
            if (toolCallPath != null && !toolCallPath.isEmpty()) {
                out.put("read_file_path", toolCallPath);
            }
            return out;
        }
        @Override public String role() { return "human"; }
    }

    /** Message from the model. May carry tool-use blocks and
     *  usage metadata reported by the underlying provider
     *  (Anthropic, OpenAI, Google). The {@code usageMetadata}
     *  map mirrors LangChain's {@code AIMessage.usage_metadata}
     *  shape ({@code input_tokens}, {@code output_tokens},
     *  {@code total_tokens}). */
    record AIMessage(String id, List<ContentBlock> content, Optional<String> toolCallId,
                     Map<String, Object> usageMetadata) implements Message {
        public AIMessage {
            content = content == null ? List.of() : List.copyOf(content);
            usageMetadata = usageMetadata == null ? Map.of() : Map.copyOf(usageMetadata);
        }
        public AIMessage(String id, List<ContentBlock> content) {
            this(id, content, Optional.empty(), Map.of());
        }
        public AIMessage(String id, List<ContentBlock> content, Optional<String> toolCallId) {
            this(id, content, toolCallId, Map.of());
        }
        @Override public String role() { return "ai"; }
    }

    /** Result of a tool invocation. */
    record ToolMessage(String id,
                       String toolCallId,
                       List<ContentBlock> content,
                       Optional<String> name,
                       Optional<String> status,
                       Optional<Object> artifact,
                       Map<String, Object> additionalKwargs,
                       Map<String, Object> responseMetadata) implements Message {
        public ToolMessage {
            content = content == null ? List.of() : List.copyOf(content);
            name = name == null ? Optional.empty() : name;
            status = status == null ? Optional.empty() : status;
            artifact = artifact == null ? Optional.empty() : artifact;
            additionalKwargs = additionalKwargs == null ? Map.of() : Map.copyOf(additionalKwargs);
            responseMetadata = responseMetadata == null ? Map.of() : Map.copyOf(responseMetadata);
        }
        /** Backward-compatible 3-arg constructor. */
        public ToolMessage(String id, String toolCallId, List<ContentBlock> content) {
            this(id, toolCallId, content, Optional.empty(), Optional.empty(),
                    Optional.empty(), Map.of(), Map.of());
        }
        @Override public String role() { return "tool"; }
    }

    /** System message (model instructions). */
    record SystemMessage(String id, List<ContentBlock> content) implements Message {
        public SystemMessage {
            content = content == null ? List.of() : List.copyOf(content);
        }
        @Override public String role() { return "system"; }
    }

    /**
     * Tombstone that removes a previously-stored message by id. The reducer
     * treats this as a delete; the runtime may translate to a no-op on
     * providers that don't support removal.
     */
    record RemoveMessage(String id) implements Message {
        @Override public List<ContentBlock> content() { return List.of(); }
        @Override public String role() { return "remove"; }
    }

    // -----------------------------------------------------------------
    //  Sentinel ids
    // -----------------------------------------------------------------

    /** Sentinel for {@link RemoveMessage#id()} that clears the entire message list. */
    String REMOVE_ALL_MESSAGES = "__remove_all_messages__";
}
