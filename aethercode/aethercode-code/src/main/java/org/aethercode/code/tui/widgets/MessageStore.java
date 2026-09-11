package org.aethercode.code.tui.widgets;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Message store for virtualized chat history.
 *
 * <p>Java port of {@code deepagents_code.tui.widgets.message_store}. The
 * Python module provides the data shapes for the chat-history
 * virtualizer: a per-message {@link MessageData} record and a
 * {@link Store} that keeps a sliding window of widgets in the DOM while
 * storing all message data as lightweight records.</p>
 */
public final class MessageStore {

    private MessageStore() {}

    /** Estimated terminal rows for a message whose rendered height is unknown. */
    public static final int DEFAULT_HEIGHT_HINT = 5;

    /** Smallest useful row estimate for spacer and range-height math. */
    public static final int MIN_HEIGHT_HINT = 1;

    /** Protection reason for the currently-streaming message. */
    public static final String ACTIVE_REASON = "active";

    /** Protection reason for a pending/running tool row. */
    public static final String LIVE_REASON = "live";

    /** One message's data, kept as a lightweight record for virtualization. */
    public record MessageData(
            String id,
            MessageType type,
            double timestamp,
            String content,
            Integer heightHint,
            Map<String, Object> toolData,
            String toolStatus,
            String toolOutput,
            Long toolDuration,
            Boolean toolExpanded,
            String toolRejectReason,
            Boolean toolDiffSuperseded,
            String toolDisplayCaveat,
            Boolean toolGroupExpanded,
            Boolean skillExpanded,
            Boolean rubricExpanded,
            Boolean userExpanded,
            Boolean isStreaming) {

        public MessageData {
            toolData = toolData == null ? Map.of() : Map.copyOf(toolData);
        }
    }

    /** Builder for {@link MessageData}. Preserves identity fields by default. */
    public static final class Builder {
        private String id;
        private MessageType type;
        private double timestamp;
        private String content;
        private Integer heightHint;
        private Map<String, Object> toolData;
        private String toolStatus;
        private String toolOutput;
        private Long toolDuration;
        private Boolean toolExpanded;
        private String toolRejectReason;
        private Boolean toolDiffSuperseded;
        private String toolDisplayCaveat;
        private Boolean toolGroupExpanded;
        private Boolean skillExpanded;
        private Boolean rubricExpanded;
        private Boolean userExpanded;
        private Boolean isStreaming;

        public Builder() {}

        public Builder(MessageData base) {
            this.id = base.id();
            this.type = base.type();
            this.timestamp = base.timestamp();
            this.content = base.content();
            this.heightHint = base.heightHint();
            this.toolData = base.toolData() == null ? new LinkedHashMap<>() : new LinkedHashMap<>(base.toolData());
            this.toolStatus = base.toolStatus();
            this.toolOutput = base.toolOutput();
            this.toolDuration = base.toolDuration();
            this.toolExpanded = base.toolExpanded();
            this.toolRejectReason = base.toolRejectReason();
            this.toolDiffSuperseded = base.toolDiffSuperseded();
            this.toolDisplayCaveat = base.toolDisplayCaveat();
            this.toolGroupExpanded = base.toolGroupExpanded();
            this.skillExpanded = base.skillExpanded();
            this.rubricExpanded = base.rubricExpanded();
            this.userExpanded = base.userExpanded();
            this.isStreaming = base.isStreaming();
        }

        public Builder id(String id) { this.id = id; return this; }
        public Builder type(MessageType type) { this.type = type; return this; }
        public Builder timestamp(double t) { this.timestamp = t; return this; }
        public Builder content(String c) { this.content = c; return this; }
        public Builder heightHint(Integer h) { this.heightHint = h; return this; }
        public Builder toolStatus(String s) { this.toolStatus = s; return this; }
        public Builder toolOutput(String o) { this.toolOutput = o; return this; }
        public Builder toolDuration(Long d) { this.toolDuration = d; return this; }
        public Builder toolExpanded(Boolean e) { this.toolExpanded = e; return this; }
        public Builder toolRejectReason(String r) { this.toolRejectReason = r; return this; }
        public Builder isStreaming(Boolean s) { this.isStreaming = s; return this; }

        public MessageData build() {
            return new MessageData(id, type, timestamp, content, heightHint, toolData,
                    toolStatus, toolOutput, toolDuration, toolExpanded, toolRejectReason,
                    toolDiffSuperseded, toolDisplayCaveat, toolGroupExpanded, skillExpanded,
                    rubricExpanded, userExpanded, isStreaming);
        }
    }

    /** Store: holds the full message list and a sliding window of mounted widgets. */
    public static final class Store {
        private final java.util.List<MessageData> messages = new java.util.ArrayList<>();
        private final java.util.Set<String> protectedIds = new java.util.LinkedHashSet<>();
        private int windowSize = 50;

        public List<MessageData> messages() { return List.copyOf(messages); }
        public int size() { return messages.size(); }

        public void setWindowSize(int windowSize) {
            this.windowSize = Math.max(1, windowSize);
        }

        public void add(MessageData message) {
            messages.add(message);
        }

        public void update(String id, java.util.function.Consumer<Builder> mutator) {
            for (int i = 0; i < messages.size(); i++) {
                MessageData m = messages.get(i);
                if (m.id().equals(id)) {
                    Builder b = new Builder(m);
                    mutator.accept(b);
                    messages.set(i, b.build());
                    return;
                }
            }
        }

        public void remove(String id) {
            messages.removeIf(m -> m.id().equals(id));
            protectedIds.remove(id);
        }

        /** Mark a message id as protected (cannot be unmounted while in the window). */
        public void protect(String id, String reason) {
            protectedIds.add(id);
        }

        public void unprotect(String id) {
            protectedIds.remove(id);
        }

        public boolean isProtected(String id) {
            return protectedIds.contains(id);
        }

        /** Return the indices of messages that should be mounted in the window. */
        public int[] mountedRange() {
            if (messages.isEmpty()) return new int[0];
            int total = messages.size();
            int window = Math.min(windowSize, total);
            int start = Math.max(0, total - window);
            int[] range = new int[window];
            for (int i = 0; i < window; i++) range[i] = start + i;
            return range;
        }
    }

    /**
     * Apply an update to a {@link MessageData} while preserving the identity
     * fields ({@code id}, {@code type}, {@code timestamp}). The mutator may
     * freely change any other field.
     */
    public static MessageData mutate(MessageData data,
                                     java.util.function.Consumer<Builder> mutator) {
        Builder b = new Builder(data);
        mutator.accept(b);
        return b.build();
    }
}
