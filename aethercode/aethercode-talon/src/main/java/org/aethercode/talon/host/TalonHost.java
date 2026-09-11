package org.aethercode.talon.host;

import org.aethercode.talon.JsonUtils;
import org.aethercode.talon.Observability;
import org.aethercode.talon.Speech;
import org.aethercode.talon.TalonConfig;
import org.aethercode.talon.channels.ChannelBase;
import org.aethercode.talon.cron.CronJob;
import org.aethercode.talon.interfaces.AgentRequest;
import org.aethercode.talon.interfaces.AgentResult;
import org.aethercode.talon.interfaces.AgentRuntime;
import org.aethercode.talon.interfaces.ChannelAdapter;
import org.aethercode.talon.interfaces.ChannelMedia;
import org.aethercode.talon.interfaces.ChannelMessage;
import org.aethercode.talon.interfaces.ChannelReaction;
import org.aethercode.talon.interfaces.CronScheduler;
import org.aethercode.talon.interfaces.ReactionChannelAdapter;
import org.aethercode.talon.interfaces.SendResult;
import org.aethercode.talon.interfaces.ToolApprovalDecision;
import org.aethercode.talon.interfaces.ToolApprovalRequest;
import org.aethercode.talon.media.MediaUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Runtime host that coordinates Talon components in one event loop.
 *
 * <p>Java-native port of {@code deepagents_talon.host.TalonHost}. The
 * host owns the agent runtime, channel adapters, optional cron
 * scheduler, and voice transcriber; it serializes turns per
 * conversation, dispatches tool-approval prompts over the originating
 * channel, and delivers the agent's text/media reply through the
 * channel's outbound APIs.</p>
 */
public class TalonHost {

    private static final Logger log = LoggerFactory.getLogger(TalonHost.class);

    private static final String STOP_COMMAND = "/stop";
    private static final String NEW_COMMAND = "/new";
    private static final String NEW_CONVERSATION_MESSAGE = "Started a fresh conversation.";
    private static final Set<String> APPROVE_REPLIES = Set.of(
            "approve", "approved", "yes", "y");
    private static final Set<String> DENY_REPLIES = Set.of(
            "deny", "denied", "reject", "rejected", "no", "n");
    private static final String RESET_THREAD_SEPARATOR = ":talon-reset:";
    private static final String APPROVAL_LOG_RAW_IDS_ENV = "DEEPAGENTS_TALON_APPROVAL_LOG_RAW_IDS";

    private final TalonConfig config;
    private final AgentRuntime agent;
    private final List<ChannelAdapter> channels;
    private CronScheduler scheduler;
    private final Speech.VoiceTranscriber voiceTranscriber;

    private final Map<String, Object> locks = new ConcurrentHashMap<>();
    private final Map<String, ScheduledFuture<?>> conversationTasks = new ConcurrentHashMap<>();
    private final Map<String, Integer> conversationResets = new ConcurrentHashMap<>();
    private final Map<String, PendingToolApproval> pendingApprovals = new ConcurrentHashMap<>();

    private volatile boolean running;
    private final Object runningLock = new Object();
    private final AtomicInteger schedulerThreadCounter = new AtomicInteger();
    private ScheduledExecutorService shutdownExecutor;

    public TalonHost(TalonConfig config,
                     AgentRuntime agent,
                     List<ChannelAdapter> channels,
                     CronScheduler scheduler,
                     Speech.VoiceTranscriber voiceTranscriber) {
        this.config = config;
        this.agent = agent;
        this.channels = channels == null ? List.of() : List.copyOf(channels);
        this.scheduler = scheduler;
        this.voiceTranscriber = voiceTranscriber;
    }

    public TalonHost(TalonConfig config, AgentRuntime agent, List<ChannelAdapter> channels) {
        this(config, agent, channels, null, null);
    }

    public CronScheduler scheduler() {
        return scheduler;
    }

    public void setScheduler(CronScheduler scheduler) {
        this.scheduler = scheduler;
    }

    public boolean running() {
        return running;
    }

    public List<ChannelAdapter> channels() {
        return Collections.unmodifiableList(channels);
    }

    public TalonConfig config() {
        return config;
    }

    public AgentRuntime agent() {
        return agent;
    }

    public Speech.VoiceTranscriber voiceTranscriber() {
        return voiceTranscriber;
    }

    public CompletableFuture<Void> start() {
        synchronized (runningLock) {
            if (running) {
                return CompletableFuture.completedFuture(null);
            }
        }
        config.ensureHome();
        return agent.start()
                .thenCompose(v -> startChannels())
                .thenCompose(v -> startScheduler())
                .thenRun(() -> {
                    running = true;
                    log.info("Talon host started for assistant {}", config.assistantId());
                });
    }

    public CompletableFuture<Void> stop() {
        synchronized (runningLock) {
            if (!running) {
                return CompletableFuture.completedFuture(null);
            }
            running = false;
        }
        CompletableFuture<Void> result = cancelAll()
                .thenCompose(v -> stopChannels())
                .thenCompose(v -> stopScheduler())
                .thenCompose(v -> agent.stop())
                .thenRun(() -> log.info("Talon host stopped for assistant {}",
                        config.assistantId()));
        if (shutdownExecutor != null) {
            shutdownExecutor.shutdownNow();
        }
        return result;
    }

    public CompletableFuture<Void> runUntilStopped() {
        return start().thenRun(() -> {
            // Block until cancelled. The host's only event loop is the
            // background tasks for channels and the cron scheduler.
            try {
                Thread.currentThread().join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
    }

    public void requestShutdown() {
        running = false;
    }

    public CompletableFuture<Void> receiveMessage(ChannelAdapter channel, ChannelMessage message) {
        if (channel == null || message == null) {
            return CompletableFuture.completedFuture(null);
        }
        return channelProvider(channel).thenCompose(provider -> {
            String command = commandName(message.text());
            String conversationId = message.conversationId();
            String root = conversationRoot(provider != null ? provider : channelClass(channel),
                    conversationId);
            String agentConversationId = agentConversationId(root);

            if (NEW_COMMAND.equals(command)) {
                return startNewConversation(channel, conversationId, root);
            }
            if (STOP_COMMAND.equals(command)) {
                return cancelConversation(channel, agentConversationId, conversationId);
            }
            PendingToolApproval pending = pendingApprovals.get(agentConversationId);
            if (pending != null) {
                return handleToolApprovalReply(channel, message, pending);
            }
            return CompletableFuture.runAsync(
                    () -> runAgentTurn(channel, message, agentConversationId, provider));
        });
    }

    public CompletableFuture<Void> receiveReaction(ChannelAdapter channel, ChannelReaction reaction) {
        if (channel == null || reaction == null) {
            return CompletableFuture.completedFuture(null);
        }
        return channelProvider(channel).thenCompose(provider -> {
            String providerKey = provider != null ? provider : channelClass(channel);
            String root = conversationRoot(providerKey, reaction.conversationId());
            String agentConversationId = agentConversationId(root);
            PendingToolApproval pending = pendingApprovals.get(agentConversationId);
            if (pending == null) {
                logToolApprovalReaction(config.env(),
                        new ReactionAudit(reaction, providerKey, null, "ignored",
                                "no_pending_approval"));
                return CompletableFuture.completedFuture(null);
            }
            return CompletableFuture.runAsync(
                    () -> handleToolApprovalReaction(reaction, pending, providerKey));
        });
    }

    public CompletableFuture<String> runScheduledJob(CronJob job) {
        if (job == null) {
            return CompletableFuture.completedFuture("");
        }
        String root = conversationRoot(
                job.origin().channel().orElse("cron"),
                job.origin().conversationId());
        String conversationId = agentConversationId(root);
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("channel", job.origin().channel().orElse(null));
        metadata.put("cron_job_id", job.id());
        metadata.put("cron_job_name", job.name());
        metadata.put("origin_conversation_id", job.origin().conversationId());
        metadata.put("cron_origin_message_id", job.origin().messageId().orElse(null));
        metadata.put("trigger", "cron");
        return invokeAgent(conversationId, job.prompt(), metadata, null)
                .thenApply(r -> r.text());
    }

    public CompletableFuture<Void> deliverScheduledResult(ChannelAdapter channel, CronJob job, String text) {
        if (channel == null || job == null) {
            return CompletableFuture.completedFuture(null);
        }
        return channelProvider(channel).thenCompose(provider -> {
            if (job.origin().channel().isEmpty() || provider != null
                    && provider.equals(job.origin().channel().get())) {
                return sendWithRetry(() -> channel.sendMessage(job.origin().conversationId(), text));
            }
            return CompletableFuture.completedFuture(null);
        });
    }

    // -----------------------------------------------------------------------
    // Internals
    // -----------------------------------------------------------------------

    private void runAgentTurn(ChannelAdapter channel, ChannelMessage message,
                              String agentConversationId, String provider) {
        try {
            ChannelMessage transcribed = Speech.transcribeVoiceMessage(voiceTranscriber, message);
            ChannelMessage prepared = prepareInboundMessage(transcribed);
            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("channel", provider);
            if (prepared.senderId().isPresent()) {
                metadata.put("sender_id", prepared.senderId().get());
            }
            if (prepared.messageId().isPresent()) {
                metadata.put("message_id", prepared.messageId().get());
            }
            for (Map.Entry<String, Object> entry : prepared.metadata().entrySet()) {
                metadata.putIfAbsent(entry.getKey(), entry.getValue());
            }
            String originConversationId = originConversationId(prepared);
            if (!originConversationId.equals(agentConversationId)) {
                metadata.put("origin_conversation_id", originConversationId);
            }
            Object content = MediaUtils.buildModelContent(prepared.text(),
                    new LinkedHashMap<>(prepared.metadata()));
            if (content != null && !content.equals(prepared.text())) {
                metadata.put("model_content", content);
            }
            try {
                channel.sendTyping(prepared.conversationId());
            } catch (Exception e) {
                log.debug("Could not send typing indicator", e);
            }
            AgentResult result = invokeAgent(agentConversationId, prepared.text(),
                    metadata,
                    request -> requestToolApproval(channel, request,
                            channelKey(channel, provider),
                            prepared.conversationId(),
                            prepared.senderId().orElse(null))).join();
            deliverAgentResult(channel, prepared.conversationId(), result);
        } catch (CompletionException e) {
            log.error("Agent turn failed for conversation {}", agentConversationId, e);
        } catch (RuntimeException e) {
            log.error("Agent turn failed for conversation {}", agentConversationId, e);
        }
    }

    private CompletableFuture<AgentResult> invokeAgent(String conversationId, String text,
                                                      Map<String, Object> metadata,
                                                      Function<ToolApprovalRequest, CompletableFuture<ToolApprovalDecision>> approvalHandler) {
        return CompletableFuture.supplyAsync(() -> {
            Object lock = locks.computeIfAbsent(conversationId, k -> new Object());
            synchronized (lock) {
                Map<String, Object> meta = new LinkedHashMap<>(metadata);
                meta.put("assistant_id", config.assistantId());
                AgentRequest request = new AgentRequest(conversationId, text, meta,
                        approvalHandler == null ? null : approvalHandler::apply);
                try {
                    return agent.invoke(request).join();
                } catch (CompletionException e) {
                    log.error("Unhandled agent error in conversation {}", conversationId, e);
                    throw e;
                } catch (RuntimeException e) {
                    log.error("Unhandled agent error in conversation {}", conversationId, e);
                    throw e;
                }
            }
        });
    }

    private CompletableFuture<Void> startNewConversation(ChannelAdapter channel, String conversationId,
                                                        String root) {
        String currentId = agentConversationId(root);
        cancelConversationTasks(currentId);
        conversationResets.merge(root, 1, Integer::sum);
        return sendWithRetry(() -> channel.sendMessage(conversationId, NEW_CONVERSATION_MESSAGE));
    }

    private CompletableFuture<Void> cancelConversation(ChannelAdapter channel, String agentConversationId,
                                                      String replyConversationId) {
        boolean cancelled = cancelConversationTasks(agentConversationId);
        String text = cancelled ? "Stopped current run." : "No in-flight run to stop.";
        return sendWithRetry(() -> channel.sendMessage(replyConversationId, text));
    }

    private boolean cancelConversationTasks(String conversationId) {
        Set<ScheduledFuture<?>> tasks = new HashSet<>();
        ScheduledFuture<?> existing = conversationTasks.remove(conversationId);
        if (existing != null) {
            tasks.add(existing);
        }
        if (tasks.isEmpty()) {
            return false;
        }
        for (ScheduledFuture<?> t : tasks) {
            t.cancel(true);
        }
        return true;
    }

    private CompletableFuture<Void> cancelAll() {
        List<ScheduledFuture<?>> all = new ArrayList<>(conversationTasks.values());
        conversationTasks.clear();
        for (ScheduledFuture<?> t : all) {
            t.cancel(true);
        }
        for (PendingToolApproval p : pendingApprovals.values()) {
            p.future.cancel(true);
        }
        pendingApprovals.clear();
        return CompletableFuture.completedFuture(null);
    }

    private CompletableFuture<Void> startChannels() {
        List<CompletableFuture<Void>> tasks = new ArrayList<>();
        for (ChannelAdapter channel : channels) {
            channel.setMessageHandler(message -> receiveMessage(channel, message));
            if (channel instanceof ReactionChannelAdapter rca) {
                rca.setReactionHandler(reaction -> receiveReaction(channel, reaction));
            }
            tasks.add(channel.start());
        }
        return CompletableFuture.allOf(tasks.toArray(new CompletableFuture<?>[0]));
    }

    private CompletableFuture<Void> stopChannels() {
        List<CompletableFuture<Void>> tasks = new ArrayList<>();
        for (int i = channels.size() - 1; i >= 0; i--) {
            tasks.add(channels.get(i).stop());
        }
        return CompletableFuture.allOf(tasks.toArray(new CompletableFuture<?>[0]));
    }

    private CompletableFuture<Void> startScheduler() {
        return (scheduler == null) ? CompletableFuture.completedFuture(null) : scheduler.start();
    }

    private CompletableFuture<Void> stopScheduler() {
        return (scheduler == null) ? CompletableFuture.completedFuture(null) : scheduler.stop();
    }

    private CompletableFuture<Void> sendWithRetry(
            Supplier<CompletableFuture<SendResult>> sendFn) {
        return ChannelBase.sendWithRetry(sendFn).thenAccept(r -> {});
    }

    private void deliverAgentResult(ChannelAdapter channel, String conversationId, AgentResult result) {
        MediaUtils.MarkdownResult md = MediaUtils.extractMarkdownMedia(result.text());
        if (md.refs().isEmpty()) {
            if (result.text() != null && !result.text().isEmpty()) {
                sendWithRetry(() -> channel.sendMessage(conversationId, result.text())).join();
            }
            return;
        }
        Path root = ChannelBase.outboundMediaRootFromEnv(config.env());
        List<ChannelMedia> media = new ArrayList<>();
        List<String> failed = new ArrayList<>();
        for (int i = 0; i < md.refs().size(); i++) {
            MediaUtils.MarkdownMediaRef ref = md.refs().get(i);
            String caption = (i == 0 && !md.text().isEmpty()) ? md.text()
                    : Optional.ofNullable(ref.alt()).filter(a -> !a.isBlank()).orElse(null);
            try {
                media.add(MediaUtils.outboundChannelMedia(ref, caption, root));
            } catch (RuntimeException e) {
                failed.add(Optional.ofNullable(ref.alt()).filter(a -> !a.isBlank())
                        .orElse(Optional.ofNullable(ref.path())
                                .map(Path::getFileName)
                                .map(Path::toString)
                                .orElse("attachment")));
            }
        }
        String text = withFailedAttachmentText(md.text(), failed);
        boolean sent = false;
        List<String> sendFailed = new ArrayList<>();
        for (int i = 0; i < media.size(); i++) {
            ChannelMedia item = media.get(i);
            ChannelMedia payload = mediaWithFallbackCaption(item, text, i == 0);
            try {
                SendResult sr = ChannelBase.sendWithRetry(
                        () -> channel.sendMedia(conversationId, payload)).join();
                if (sr != null && sr.success()) {
                    sent = true;
                } else {
                    log.warn("Could not send outbound media: {} ({})", payload.path(),
                            sr == null ? "unknown" : sr.error().orElse("unknown"));
                    sendFailed.add(payload.caption().orElse(payload.path().getFileName().toString()));
                }
            } catch (CompletionException e) {
                log.warn("Could not send outbound media: {}", payload.path(), e);
                sendFailed.add(payload.caption().orElse(payload.path().getFileName().toString()));
            }
        }
        if (text != null && !text.isEmpty() && !sent) {
            try {
                sendWithRetry(() -> channel.sendMessage(conversationId, text)).join();
            } catch (CompletionException e) {
                log.warn("Could not send fallback text after media failure", e);
            }
        } else if (!sendFailed.isEmpty() && sent) {
            try {
                sendWithRetry(() -> channel.sendMessage(conversationId,
                        "_(Could not attach: " + String.join(", ", sendFailed) + ".)_")).join();
            } catch (CompletionException e) {
                log.warn("Could not send media failure message", e);
            }
        }
    }

    private CompletableFuture<ToolApprovalDecision> requestToolApproval(
            ChannelAdapter channel, ToolApprovalRequest approval,
            String provider, String replyConversationId, String senderId) {
        CompletableFuture<ToolApprovalDecision> future = new CompletableFuture<>();
        PendingToolApproval pending = new PendingToolApproval(future, provider,
                replyConversationId, approval.conversationId(), senderId);
        pendingApprovals.put(approval.conversationId(), pending);
        sendWithRetry(() -> channel.sendMessage(replyConversationId,
                formatToolApprovalPrompt(approval)))
                .whenComplete((value, error) -> {
                    // The SendResult is already consumed; the operator's
                    // reply is the future the agent is awaiting.
                    if (error != null) {
                        log.warn("Could not send tool approval prompt", error);
                    }
                });
        future.whenComplete((value, error) -> {
            if (pendingApprovals.get(approval.conversationId()) == pending) {
                pendingApprovals.remove(approval.conversationId());
            }
        });
        return future;
    }

    private CompletableFuture<Void> handleToolApprovalReply(ChannelAdapter channel,
                                                            ChannelMessage message,
                                                            PendingToolApproval pending) {
        if (pending.senderId != null && message.senderId().isPresent()
                && !pending.senderId.equals(message.senderId().get())) {
            return sendWithRetry(() -> channel.sendMessage(message.conversationId(),
                    "Only the operator who started this run can approve or deny it."));
        }
        ToolApprovalDecision decision = parseToolApprovalReply(message.text());
        if (decision == null) {
            return sendWithRetry(() -> channel.sendMessage(message.conversationId(),
                    "Reply `approve` to run the tool call or `deny` to skip it."));
        }
        if (!pending.future.isDone()) {
            pending.future.complete(decision);
        }
        return CompletableFuture.completedFuture(null);
    }

    private void handleToolApprovalReaction(ChannelReaction reaction,
                                           PendingToolApproval pending,
                                           String provider) {
        ToolApprovalDecision decision = parseToolApprovalReaction(reaction.emoji());
        String resolution = reactionMismatchResolution(reaction, pending,
                provider, decision);
        if (resolution != null) {
            logToolApprovalReaction(config.env(), new ReactionAudit(reaction, provider, decision,
                    "ignored", resolution));
            return;
        }
        logToolApprovalReaction(config.env(), new ReactionAudit(reaction, provider, decision,
                "matched", "operator_reaction"));
        if (!pending.future.isDone()) {
            pending.future.complete(decision);
        }
    }

    // -----------------------------------------------------------------------
    // Internal helpers
    // -----------------------------------------------------------------------

    private CompletableFuture<String> channelProvider(ChannelAdapter channel) {
        try {
            return channel.status()
                    .thenApply(s -> s.provider())
                    .exceptionally(t -> {
                        log.warn("Could not resolve channel provider for agent metadata", t);
                        return null;
                    });
        } catch (RuntimeException e) {
            return CompletableFuture.completedFuture(null);
        }
    }

    private String channelClass(ChannelAdapter channel) {
        return channel.getClass().getSimpleName();
    }

    private String channelKey(ChannelAdapter channel, String provider) {
        return provider != null ? provider : channelClass(channel);
    }

    private String agentConversationId(String root) {
        int reset = conversationResets.getOrDefault(root, 0);
        if (reset == 0) {
            return root;
        }
        return root + RESET_THREAD_SEPARATOR + reset;
    }

    private String conversationRoot(String provider, String conversationId) {
        if (channels.size() <= 1) {
            return conversationId;
        }
        return provider + ":" + conversationId;
    }

    private static String commandName(String text) {
        if (text == null) {
            return null;
        }
        String[] parts = text.strip().split("\\s+", 2);
        if (parts.length == 0 || parts[0].isEmpty()) {
            return null;
        }
        String first = parts[0].toLowerCase();
        if (!first.startsWith("/")) {
            return null;
        }
        return first.split("@", 2)[0];
    }

    private static String originConversationId(ChannelMessage message) {
        Object origin = message.metadata().get("chat_id_from");
        if (origin instanceof String s && !s.isEmpty()) {
            return s;
        }
        return message.conversationId();
    }

    private static ChannelMessage prepareInboundMessage(ChannelMessage message) {
        String text = MediaUtils.buildInboundText(message.text(),
                new LinkedHashMap<>(message.metadata()));
        if (text.equals(message.text())) {
            return message;
        }
        Map<String, Object> newMeta = new LinkedHashMap<>(message.metadata());
        newMeta.put("media_text_augmented", true);
        return new ChannelMessage(message.conversationId(), text,
                message.senderId().orElse(null),
                message.messageId().orElse(null), newMeta);
    }

    private static ChannelMedia mediaWithFallbackCaption(ChannelMedia media, String fallback,
                                                         boolean isFirst) {
        if (!isFirst || media.caption().isPresent() || fallback == null || fallback.isEmpty()) {
            return media;
        }
        return new ChannelMedia(media.path(), media.mediaType(), fallback);
    }

    private static String withFailedAttachmentText(String text, List<String> failed) {
        if (failed.isEmpty()) {
            return text;
        }
        return (text == null ? "" : text.strip() + "\n\n_(Could not attach: "
                + String.join(", ", failed) + ".)_").strip();
    }

    private static String formatToolApprovalPrompt(ToolApprovalRequest approval) {
        StringBuilder sb = new StringBuilder("Tool approval required.\n");
        int idx = 1;
        for (Map<String, Object> action : approval.actionRequests()) {
            Object name = action.get("name");
            String toolName = (name instanceof String s && !s.isEmpty()) ? s : "unknown";
            sb.append(idx).append(". `").append(toolName).append("`\n");
            Object args = action.get("args");
            if (args instanceof Map<?, ?> argMap && !argMap.isEmpty()) {
                sb.append("Args: `").append(JsonUtils.toJson(argMap)).append("`\n");
            } else if (args != null && !(args instanceof Map<?, ?> m2 && m2.isEmpty())) {
                sb.append("Args: `").append(args).append("`\n");
            }
            idx++;
        }
        sb.append("Reply `👍` / `approve` to run or `👎` / `deny` to skip.");
        return sb.toString();
    }

    private static ToolApprovalDecision parseToolApprovalReply(String text) {
        if (text == null) {
            return null;
        }
        String normalized = text.strip().toLowerCase()
                .replaceAll("[.!? ]+$", "");
        if (normalized.isEmpty()) {
            return null;
        }
        String first = normalized.split("\\s+", 2)[0];
        ToolApprovalDecision reaction = parseToolApprovalReaction(first);
        if (reaction != null) {
            return reaction;
        }
        if (APPROVE_REPLIES.contains(first)) {
            return ToolApprovalDecision.APPROVE;
        }
        if (DENY_REPLIES.contains(first)) {
            return ToolApprovalDecision.REJECT;
        }
        return null;
    }

    private static ToolApprovalDecision parseToolApprovalReaction(String emoji) {
        if (emoji == null) {
            return null;
        }
        String normalized = normalizeReactionEmoji(emoji);
        if ("\uD83D\uDC4D".equals(normalized)) {
            return ToolApprovalDecision.APPROVE;
        }
        if ("\uD83D\uDC4E".equals(normalized)) {
            return ToolApprovalDecision.REJECT;
        }
        return null;
    }

    private static String normalizeReactionEmoji(String emoji) {
        if (emoji == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        Set<Integer> skinTones = Set.of(
                0x1F3FB, 0x1F3FC, 0x1F3FD, 0x1F3FE, 0x1F3FF);
        for (int i = 0; i < emoji.length(); ) {
            int cp = emoji.codePointAt(i);
            i += Character.charCount(cp);
            if (cp == 0xFE0F) {
                continue;
            }
            if (skinTones.contains(cp)) {
                continue;
            }
            sb.appendCodePoint(cp);
        }
        return sb.toString().strip();
    }

    private static String reactionMismatchResolution(ChannelReaction reaction,
                                                    PendingToolApproval pending,
                                                    String provider,
                                                    ToolApprovalDecision decision) {
        List<Object> checks = List.of(
                decision == null ? "unsupported_emoji" : null,
                pending.promptMessageId == null ? "missing_prompt_message_id" : null,
                !provider.equals(pending.provider) ? "provider_mismatch" : null,
                !reaction.conversationId().equals(pending.channelConversationId)
                        ? "conversation_mismatch" : null,
                !reaction.messageId().equals(pending.promptMessageId)
                        ? "message_mismatch" : null,
                pending.senderId != null && reaction.senderId().isEmpty() ? "sender_missing" : null,
                pending.senderId != null && reaction.senderId().isPresent()
                        && !pending.senderId.equals(reaction.senderId().get())
                        ? "sender_mismatch" : null);
        for (Object failed : checks) {
            if (failed != null) {
                return (String) failed;
            }
        }
        return null;
    }

    private void logToolApprovalReaction(Map<String, String> env, ReactionAudit audit) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("provider", audit.provider());
        fields.put("channel_conversation_ref", Observability.stableLogRef(audit.reaction.conversationId()));
        fields.put("prompt_message_ref", Observability.stableLogRef(audit.reaction.messageId()));
        fields.put("emoji", audit.reaction.emoji());
        if (audit.decision() != null) {
            fields.put("decision", audit.decision().value());
        }
        fields.put("match_status", audit.matchStatus());
        fields.put("resolution", audit.resolution());
        if (audit.reaction.senderId().isPresent()) {
            fields.put("reacting_sender_ref",
                    Observability.stableLogRef(audit.reaction.senderId().get()));
        }
        if (approvalLogRawIds(env)) {
            fields.put("raw_channel_conversation_id", audit.reaction.conversationId());
            fields.put("raw_prompt_message_id", audit.reaction.messageId());
            if (audit.reaction.senderId().isPresent()) {
                fields.put("raw_reacting_sender_id", audit.reaction.senderId().get());
            }
        }
        Observability.logEvent(log, "tool_approval.reaction", fields);
    }

    private static boolean approvalLogRawIds(Map<String, String> env) {
        return "true".equalsIgnoreCase(env.getOrDefault(APPROVAL_LOG_RAW_IDS_ENV, ""));
    }

    // -----------------------------------------------------------------------
    // Internal records
    // -----------------------------------------------------------------------

    private static final class PendingToolApproval {
        final CompletableFuture<ToolApprovalDecision> future;
        final String provider;
        final String channelConversationId;
        final String agentConversationId;
        final String senderId;
        volatile String promptMessageId;

        PendingToolApproval(CompletableFuture<ToolApprovalDecision> future,
                            String provider,
                            String channelConversationId,
                            String agentConversationId,
                            String senderId) {
            this.future = future;
            this.provider = provider;
            this.channelConversationId = channelConversationId;
            this.agentConversationId = agentConversationId;
            this.senderId = senderId;
        }
    }

    private record ReactionAudit(
            ChannelReaction reaction,
            String provider,
            ToolApprovalDecision decision,
            String matchStatus,
            String resolution) {
    }
}
