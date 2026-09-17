package org.aethercode.memory;

import org.aethercode.core.llm.ChatClient;
import org.aethercode.core.message.Message;
import org.aethercode.core.message.Role;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;

/**
 * layered memory lifecycle orchestrator.
 *
 * <p>Threads every "memory write" / "memory read" / "memory evolve"
 * event into the session execution pipeline so memory is
 * <i>continuously</i> updated, not just statically configured.
 *
 * <p>Tier model (the "分层分级" the user asked for):
 * <ul>
 *   <li><b>Tier 0 — per tool call (free)</b>: {@link #onToolCall(String)}
 *       records the name for the next recall signal. No DB write.</li>
 *   <li><b>Tier 1 — per query (free)</b>: {@link #onQueryStart} creates
 *       a {@link WorkingMemoryBuffer}, audits, bumps recall hit
 *       counters, and runs the decay pass if its interval is due.
 *       {@link #onQueryEnd} clears the buffer, audits, and may
 *       trigger Tier-2 / Tier-3 extraction.</li>
 *   <li><b>Tier 2 — per successful query (heuristic, no LLM)</b>:
 *       {@link #extractCaseFromTranscript} auto-writes a Case-based
 *       experience when the query produced a final assistant
 *       message and at least one tool call. Body is the last
 *       assistant turn + tool names. No LLM cost.</li>
 *   <li><b>Tier 3 — per pattern (heuristic + LLM, gated)</b>:
 *       {@link #maybeExtractStrategy} fires when the transcript
 *       matches a strategy pattern (e.g. "always", "remember to",
 *       "use X for Y", or ≥3 calls to the same tool). The LLM is
 *       called via {@link MemoryExtractor}'s forked subagent. Cost
 *       amortised — at most one LLM call per session.</li>
 *   <li><b>Tier 4 — background periodic</b>:
 *       {@link #runPeriodicDecay} runs on a daemon thread every
 *       {@code decayIntervalMs}. Calls
 *       {@link ForgettingPolicy#runDecayPass} on the user and
 *       project stores (the latter only if a {@code projectCwd} is
 *       set). All passes are throttled by item count.</li>
 * </ul>
 *
 * <p>Best-effort: every method swallows + logs its own exceptions.
 * The agent loop must not break because the memory layer hiccupped.
 */
public final class MemoryLifecycle {

    private static final Logger LOG = LoggerFactory.getLogger(MemoryLifecycle.class);

    /** Default Tier-4 decay interval: 5 minutes. */
    public static final long DEFAULT_DECAY_INTERVAL_MS = 5L * 60 * 1000;
    /** Default cap on items per periodic decay pass. */
    public static final int DEFAULT_DECAY_MAX_ITEMS = 100;
    /** Default cap on Tier-2 case extraction: at most this many chars in the body. */
    public static final int DEFAULT_CASE_BODY_MAX_CHARS = 2000;
    /** Default cap on Tier-3 strategy extraction: same as case for now. */
    public static final int DEFAULT_STRATEGY_BODY_MAX_CHARS = 1500;
    /** Default minimum tool calls required before a query is considered "experience-worthy". */
    public static final int DEFAULT_MIN_TOOL_CALLS_FOR_EXTRACTION = 1;
    /** Default strategy pattern: phrases that suggest the user is teaching a rule. */
    private static final Pattern STRATEGY_PATTERN = Pattern.compile(
            "(?i)\\b(always|never|remember|keep in mind|note that|important:|rule:|"
                    + "going forward|from now on|use .+ for|in general|don't forget)\\b"
    );

    /** Configuration knob bundle. */
    public record Config(
            boolean enabled,
            long decayIntervalMs,
            int decayMaxItems,
            int caseBodyMaxChars,
            int strategyBodyMaxChars,
            int minToolCallsForExtraction,
            boolean extractCaseOnSuccess,
            boolean extractStrategyGated
    ) {
        public static Config defaults() {
            return new Config(true,
                    DEFAULT_DECAY_INTERVAL_MS,
                    DEFAULT_DECAY_MAX_ITEMS,
                    DEFAULT_CASE_BODY_MAX_CHARS,
                    DEFAULT_STRATEGY_BODY_MAX_CHARS,
                    DEFAULT_MIN_TOOL_CALLS_FOR_EXTRACTION,
                    true,
                    true);
        }
    }

    /** Cumulative counters (read-only snapshot via {@link #stats()}). */
    public record Stats(
            long queriesStarted,
            long queriesCompleted,
            long queriesFailed,
            long recallHits,
            long caseExtractions,
            long strategyExtractions,
            long decayPasses,
            long decayItemsRemoved,
            long workingBufferClears
    ) {
        Stats add(Stats o) {
            return new Stats(
                    queriesStarted + o.queriesStarted,
                    queriesCompleted + o.queriesCompleted,
                    queriesFailed + o.queriesFailed,
                    recallHits + o.recallHits,
                    caseExtractions + o.caseExtractions,
                    strategyExtractions + o.strategyExtractions,
                    decayPasses + o.decayPasses,
                    decayItemsRemoved + o.decayItemsRemoved,
                    workingBufferClears + o.workingBufferClears);
        }
    }

    // ---------- collaborators ----------
    private final LayeredMemoryStore store;
    private final ForgettingPolicy forgettingPolicy;
    private final MemoryAudit audit;
    private volatile MemoryExtractor memoryExtractor; // R232: optional, lazy-wired by daemon
    private volatile ChatClient strategyChatClient; // prior round: optional, lazy-wired for strategy extraction
    private final Config config;

    // ---------- per-engine state ----------
    private final String sessionId;
    private final String agentType;
    private final AtomicLong queriesStarted = new AtomicLong();
    private final AtomicLong queriesCompleted = new AtomicLong();
    private final AtomicLong queriesFailed = new AtomicLong();
    private final AtomicLong recallHits = new AtomicLong();
    private final AtomicLong caseExtractions = new AtomicLong();
    private final AtomicLong strategyExtractions = new AtomicLong();
    private final AtomicLong decayPasses = new AtomicLong();
    private final AtomicLong decayItemsRemoved = new AtomicLong();
    private final AtomicLong workingBufferClears = new AtomicLong();

    // ---------- per-query state ----------
    private WorkingMemoryBuffer currentBuffer;
    private String currentQueryId;
    private String currentUserInput;
    private Instant lastDecayAt = Instant.EPOCH;
    private final List<String> toolNamesInCurrentQuery = new ArrayList<>();
    private String currentProjectCwd; // optional; set via setProjectCwd before onQueryStart

    // ---------- background ----------
    private final ScheduledExecutorService scheduler;
    private final AtomicBoolean schedulerRunning = new AtomicBoolean(false);

    public MemoryLifecycle(String sessionId, String agentType,
                           LayeredMemoryStore store,
                           ForgettingPolicy forgettingPolicy,
                           MemoryAudit audit,
                           Config config) {
        this.sessionId = sessionId == null ? "anon-" + UUID.randomUUID() : sessionId;
        this.agentType = agentType == null ? "default" : agentType;
        this.store = store;
        this.forgettingPolicy = forgettingPolicy == null ? ForgettingPolicy.defaults() : forgettingPolicy;
        this.audit = audit;
        this.config = config == null ? Config.defaults() : config;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "memory-lifecycle");
            t.setDaemon(true);
            return t;
        });
    }

    public String sessionId() { return sessionId; }
    public String agentType() { return agentType; }
    public Config config() { return config; }

    /**
     * accessor for the underlying {@link LayeredMemoryStore}. The engine uses this in
     * {@code AetherCodeEngine.buildProjectMemorySection} (R280) to read PROJECT_MEMORY.md
     * for the system-prompt injection. Returns {@code null} when no store was
     * wired (the default disabled no-op lifecycle). Never null in production.
     */
    public LayeredMemoryStore store() { return store; }

    /**
     * install the {@link MemoryExtractor} (used to write
     * SESSION-scope MEMORY.md after long conversations). Lazy-wired
     * by the daemon after the lifecycle is constructed. Idempotent
     * — a second call with a null value is a no-op.
     */
    public void setMemoryExtractor(MemoryExtractor extractor) {
        if (extractor == null) return;
        this.memoryExtractor = extractor;
    }

    public Optional<MemoryExtractor> memoryExtractor() {
        return Optional.ofNullable(memoryExtractor);
    }

    /**
     * install the {@link ChatClient} used by Tier-3 strategy
     * extraction. When wired, {@link #maybeExtractStrategy} makes a
     * focused LLM call to distil a transferable strategy from the
     * transcript. Idempotent: a second call with null is a no-op.
     */
    public void setStrategyChatClient(ChatClient chatClient) {
        if (chatClient == null) return;
        this.strategyChatClient = chatClient;
    }

    public Optional<ChatClient> strategyChatClient() {
        return Optional.ofNullable(strategyChatClient);
    }
    public Stats stats() {
        return new Stats(
                queriesStarted.get(), queriesCompleted.get(), queriesFailed.get(),
                recallHits.get(), caseExtractions.get(), strategyExtractions.get(),
                decayPasses.get(), decayItemsRemoved.get(), workingBufferClears.get());
    }

    /** Optional: bind the project cwd so project-scope experience + decay are routed correctly. */
    public void setProjectCwd(String cwd) {
        this.currentProjectCwd = cwd;
    }

    public Optional<WorkingMemoryBuffer> currentBuffer() {
        return Optional.ofNullable(currentBuffer);
    }

    // ----------------------------------------------------------------
    // Tier 1: onQueryStart
    // ----------------------------------------------------------------

    /**
     * Hook called by {@code AetherCodeEngine.query(...)} before the inner
     * {@code QueryEngine.query} runs.
     *
     * <p>Creates a fresh {@link WorkingMemoryBuffer} for this query,
     * logs the start to the audit log, and runs the periodic decay
     * if its interval is due.
     */
    public WorkingMemoryBuffer onQueryStart(String userInput) {
        if (!config.enabled) return new WorkingMemoryBuffer(sessionId, "noop");
        currentQueryId = UUID.randomUUID().toString();
        currentUserInput = userInput == null ? "" : userInput;
        toolNamesInCurrentQuery.clear();
        currentBuffer = new WorkingMemoryBuffer(sessionId, currentQueryId);
        queriesStarted.incrementAndGet();
        if (audit != null) {
            audit.record("agent:" + agentType, MemoryAudit.Action.WRITE,
                    null, null, "buffer", sessionId, MemoryAudit.Decision.ALLOW, null);
        }
        // Throttled decay
        if (Duration.between(lastDecayAt, Instant.now()).toMillis() >= config.decayIntervalMs) {
            runPeriodicDecay();
        }
        return currentBuffer;
    }

    // ----------------------------------------------------------------
    // Tier 0: onToolCall
    // ----------------------------------------------------------------

    /**
     * Hook called by the tool executor (or by the engine's tool-name
     * tracker) for every tool invocation. Records the name for the
     * next recall's "recent tools" signal and audits.
     */
    public void onToolCall(String toolName) {
        if (!config.enabled) return;
        if (toolName == null || toolName.isBlank()) return;
        toolNamesInCurrentQuery.add(toolName);
        if (audit != null) {
            audit.record(null, MemoryAudit.Action.WRITE, null, null, "tool-call",
                    sessionId, MemoryAudit.Decision.ALLOW,
                    java.util.Map.of("tool", toolName, "queryId", currentQueryId == null ? "" : currentQueryId));
        }
    }

    // ----------------------------------------------------------------
    // Tier 1: onMemoryRecallHit
    // ----------------------------------------------------------------

    /**
     * Hook called by the memory recall path whenever a memory file
     * is surfaced into the system prompt. Bumps its
     * {@code accessCount} (Tier-3 in the lifecycle) and audits.
     */
    public void onMemoryRecallHit(String scope, String key, String itemId) {
        if (!config.enabled) return;
        recallHits.incrementAndGet();
        if (audit != null) {
            audit.record("recall", MemoryAudit.Action.RECALL,
                    org.aethercode.memory.MemoryScope.valueOf(scope == null ? "USER" : scope.toUpperCase()),
                    key, "hit", sessionId, MemoryAudit.Decision.ALLOW,
                    java.util.Map.of("itemId", itemId == null ? "" : itemId,
                                     "queryId", currentQueryId == null ? "" : currentQueryId));
        }
        // Note: actual touch() of the file-backed item is the caller's
        // responsibility (we don't have the FileBackedMemory handle
        // here). The engine should call {@code store.touch(id)} after
        // surfacing. We just bump our own counter.
    }

    // ----------------------------------------------------------------
    // Tier 1: onMemoryWrite
    // ----------------------------------------------------------------

    /**
     * Hook called by the memory write path (any scope, any kind).
     * Audits. If the scope is project, may also bump the
     * project-decay counter to be more aggressive.
     */
    public void onMemoryWrite(String scope, String key, String kind, String sensitivity) {
        if (!config.enabled) return;
        if (audit != null) {
            audit.record(null, MemoryAudit.Action.WRITE,
                    scope == null ? null : org.aethercode.memory.MemoryScope.valueOf(scope.toUpperCase()),
                    key, kind, sessionId, MemoryAudit.Decision.ALLOW,
                    sensitivity == null ? null : java.util.Map.of("sensitivity", sensitivity));
        }
    }

    // ----------------------------------------------------------------
    // Tier 1: onQueryEnd
    // ----------------------------------------------------------------

    /**
     * Hook called by {@code AetherCodeEngine.query(...)} after the
     * stream ends (or the user explicitly closes the query).
     *
     * <p>Performs the cheap, deterministic post-query housekeeping:
     * audit, working-buffer clear, and Tier-2 / Tier-3 experience
     * extraction.
     *
     * @param success {@code true} iff the query ended in a clean
     *                {@code end_turn} (or another non-error stop reason)
     * @param transcript the full message log (may be empty)
     * @param extraExperienceSink optional. When non-null, extracted
     *        experiences are appended here in addition to the store.
     *        Used by tests; production passes {@code null}.
     */
    public QueryEndReport onQueryEnd(boolean success, List<Message> transcript,
                                     List<ExperienceRecord> extraExperienceSink) {
        if (!config.enabled) {
            return new QueryEndReport(false, false, null, null);
        }
        Instant now = Instant.now();
        String stopReason = success ? "end_turn" : "error";
        if (success) {
            queriesCompleted.incrementAndGet();
        } else {
            queriesFailed.incrementAndGet();
        }
        if (audit != null) {
            audit.record(null, MemoryAudit.Action.WRITE, null, null, "query-end",
                    sessionId,
                    success ? MemoryAudit.Decision.ALLOW : MemoryAudit.Decision.DENY,
                    java.util.Map.of("stopReason", stopReason,
                            "toolCalls", Integer.toString(toolNamesInCurrentQuery.size()),
                            "queryId", currentQueryId == null ? "" : currentQueryId));
        }
        // Tier 2: case-based extraction (no LLM, heuristic)
        ExperienceRecord caseRec = null;
        if (success && config.extractCaseOnSuccess
                && toolNamesInCurrentQuery.size() >= config.minToolCallsForExtraction) {
            caseRec = extractCaseFromTranscript(transcript, now);
            if (caseRec != null) {
                caseExtractions.incrementAndGet();
                if (store != null) {
                    if (currentProjectCwd != null) {
                        store.appendProjectExperience(currentProjectCwd, caseRec);
                    } else {
                        store.appendUserExperience(caseRec);
                    }
                }
                if (extraExperienceSink != null) extraExperienceSink.add(caseRec);
            }
        }
        // Tier 3: strategy-based extraction (heuristic + optional LLM)
        ExperienceRecord stratRec = null;
        if (success && config.extractStrategyGated && transcript != null) {
            // Heuristic gate: transcript mentions a strategy keyword
            // OR the same tool was called ≥3 times.
            boolean shouldRun = false;
            for (Message m : transcript) {
                if (m.textContent() != null && STRATEGY_PATTERN.matcher(m.textContent()).find()) {
                    shouldRun = true; break;
                }
            }
            if (!shouldRun) {
                java.util.Map<String, Integer> counts = new java.util.HashMap<>();
                for (String n : toolNamesInCurrentQuery) {
                    counts.merge(n, 1, Integer::sum);
                }
                for (int c : counts.values()) if (c >= 3) { shouldRun = true; break; }
            }
            if (shouldRun) {
                stratRec = maybeExtractStrategy(transcript, now);
                if (stratRec != null) {
                    strategyExtractions.incrementAndGet();
                    if (store != null) {
                        if (currentProjectCwd != null) {
                            store.appendProjectExperience(currentProjectCwd, stratRec);
                        } else {
                            store.appendUserExperience(stratRec);
                        }
                    }
                    if (extraExperienceSink != null) extraExperienceSink.add(stratRec);
                }
            }
        }
        // Clear the working buffer (free the LRU space)
        if (currentBuffer != null) {
            currentBuffer.clear();
            workingBufferClears.incrementAndGet();
        }
        // optional MemoryExtractor pass. When wired (daemon-side)
        // and the transcript crosses the threshold (10k tokens for the
        // first pass, 5k between subsequent passes, 3+ tool calls in
        // between — matches the existing MemoryExtractor.shouldExtract
        // semantics), the LLM-backed subagent writes a long-form
        // summary to the session's MEMORY.md. Best-effort: failures
        // are logged but never thrown.
        if (memoryExtractor != null && transcript != null && !transcript.isEmpty()) {
            try {
                if (memoryExtractor.shouldExtract(transcript)) {
                    boolean wrote = memoryExtractor.extract(transcript);
                    if (audit != null) {
                        audit.record(null, MemoryAudit.Action.WRITE,
                                null, memoryExtractor.memoryFile().toString(),
                                "session-memory", sessionId,
                                wrote ? MemoryAudit.Decision.ALLOW : MemoryAudit.Decision.DENY,
                                java.util.Map.of("path", memoryExtractor.memoryFile().toString()));
                    }
                }
            } catch (Exception extractEx) {
                LOG.warn("memory extractor pass failed: {}", extractEx.getMessage());
            }
        }
        // R280: auto-append a project-memory session-change entry on
        // successful query end. Best-effort. The summary is derived from
        // the last assistant turn's text content (heuristic, no LLM).
        // When currentProjectCwd is null (session ran without project
        // context), we skip — project memory is project-scoped by design.
        if (success && store != null && currentProjectCwd != null
                && transcript != null && !transcript.isEmpty()) {
            try {
                String summary = R280DeriveSessionSummary.fromTranscript(transcript);
                if (summary != null && !summary.isBlank()) {
                    store.appendSessionChange(currentProjectCwd, sessionId, summary);
                    if (audit != null) {
                        audit.record(null, MemoryAudit.Action.WRITE,
                                MemoryScope.PROJECT, "session-change", "session-change",
                                sessionId, MemoryAudit.Decision.ALLOW,
                                java.util.Map.of("cwd", currentProjectCwd,
                                        "summaryLen", Integer.toString(summary.length())));
                    }
                }
            } catch (Exception projEx) {
                LOG.warn("R280 appendSessionChange failed: {}", projEx.getMessage());
            }
        }
        return new QueryEndReport(
                caseRec != null,
                stratRec != null,
                caseRec,
                stratRec);
    }

    public record QueryEndReport(
            boolean caseExtracted,
            boolean strategyExtracted,
            ExperienceRecord caseRecord,
            ExperienceRecord strategyRecord
    ) {}

    // ----------------------------------------------------------------
    // Tier 2 helper: extractCaseFromTranscript
    // ----------------------------------------------------------------

    /**
     * Build a Case-based experience record from the transcript.
     *
     * <p>Body = last assistant message + a short "tools used" line.
     * Title = first user message, truncated to 80 chars. No LLM call.
     *
     * @return the new record, or {@code null} if the transcript is
     *         too sparse to bother (no user/assistant turn, or both
     *         bodies empty).
     */
    public ExperienceRecord extractCaseFromTranscript(List<Message> transcript, Instant now) {
        if (transcript == null || transcript.isEmpty()) return null;
        Message firstUser = null;
        Message lastAssistant = null;
        for (Message m : transcript) {
            if (m.role() == Role.USER && firstUser == null) firstUser = m;
            if (m.role() == Role.ASSISTANT) lastAssistant = m;
        }
        if (firstUser == null) return null;
        String title = truncate(firstUser.textContent() == null ? "(empty)" : firstUser.textContent().strip(), 80);
        StringBuilder body = new StringBuilder();
        if (lastAssistant != null && lastAssistant.textContent() != null
                && !lastAssistant.textContent().isBlank()) {
            body.append("## Last assistant response\n\n")
                .append(truncate(lastAssistant.textContent().strip(), config.caseBodyMaxChars))
                .append("\n\n");
        }
        if (!toolNamesInCurrentQuery.isEmpty()) {
            body.append("## Tools used\n\n");
            java.util.Map<String, Integer> counts = new java.util.LinkedHashMap<>();
            for (String n : toolNamesInCurrentQuery) counts.merge(n, 1, Integer::sum);
            for (var e : counts.entrySet()) body.append("- ").append(e.getKey()).append(" × ").append(e.getValue()).append("\n");
            body.append("\n");
        }
        if (body.length() == 0) return null;
        List<String> tags = new ArrayList<>(toolNamesInCurrentQuery.size());
        for (String n : toolNamesInCurrentQuery) {
            if (!tags.contains(n)) tags.add(n);
            if (tags.size() >= 8) break;
        }
        return new ExperienceRecord(
                UUID.randomUUID().toString(),
                ExperienceKind.CASE,
                title,
                body.toString(),
                now,
                sessionId,
                currentUserInput == null ? title : truncate(currentUserInput, 200),
                "success",
                0.5, 0L,
                tags,
                List.of()
        );
    }

    /**
     * Tier 3 helper. prior round: LLM-backed strategy extraction.
     *
     * <p>Builds a focused prompt that asks the model to distil a
     * transferable strategy from the transcript. Requires
     * {@link #strategyChatClient} to be wired. Returns null when:
     * <ul>
     *   <li>strategy chat client is null</li>
     *   <li>transcript is empty or too short to extract from</li>
     *   <li>the LLM call fails or returns empty text</li>
     * </ul>
     *
     * <p>Best-effort: never throws. The LLM call is bounded by
     * {@code strategyBodyMaxChars} (default 1500) so a runaway
     * response can't blow the system prompt later.
     */
    public ExperienceRecord maybeExtractStrategy(List<Message> transcript, Instant now) {
        if (strategyChatClient == null) return null;
        if (transcript == null || transcript.isEmpty()) return null;
        // Take the last 6 messages (or fewer) — enough context for
        // the LLM to see the resolution, cheap enough to fit in a
        // small prompt.
        int n = Math.min(6, transcript.size());
        StringBuilder convo = new StringBuilder();
        for (int i = transcript.size() - n; i < transcript.size(); i++) {
            Message m = transcript.get(i);
            if (m == null) continue;
            String text = m.textContent();
            if (text == null) text = "";
            convo.append("[").append(m.role()).append("] ")
                 .append(text.strip().replace("\n", " "))
                 .append("\n");
        }
        if (convo.length() < 30) return null;  // too short to learn from
        String response;
        try {
            response = runStrategyChat(buildStrategyPrompt(convo));
        } catch (Exception e) {
            LOG.warn("strategy chat call failed: {}", e.getMessage());
            return null;
        }
        if (response == null || response.isBlank() || "NONE".equalsIgnoreCase(response.trim())) {
            return null;
        }
        String strategy = response.trim();
        if (strategy.length() > config.strategyBodyMaxChars) {
            strategy = strategy.substring(0, config.strategyBodyMaxChars - 1) + "…";
        }
        String title = "strategy: " + truncate(strategy, 80);
        String body = "## Distilled strategy\n\n" + strategy +
                "\n\n## Source conversation (last " + n + " messages)\n\n" + convo;
        List<String> tags = extractTags(strategy, 6);
        ExperienceRecord rec = new ExperienceRecord(
                java.util.UUID.randomUUID().toString(),
                ExperienceKind.STRATEGY,
                title,
                body,
                now == null ? Instant.now() : now,
                sessionId,
                currentUserInput == null ? title : truncate(currentUserInput, 200),
                "success",
                0.5, 0L,
                tags,
                List.of());
        if (audit != null) {
            audit.record(null, MemoryAudit.Action.WRITE, null, rec.id(), "experience",
                    sessionId, MemoryAudit.Decision.ALLOW,
                    java.util.Map.of("kind", "strategy", "title", title));
        }
        return rec;
    }

    private static String buildStrategyPrompt(StringBuilder convo) {
        return "You are distilling a transferable strategy from a recent agent " +
                "session. Look at the conversation below and write a short, generalizable " +
                "rule or workflow that would help a future agent handle a similar task. " +
                "Keep it under 200 characters. Format: a single line, no prefix. " +
                "If the conversation is too trivial to abstract, return the literal " +
                "string \"NONE\".\n\n" +
                "Conversation:\n" + convo;
    }

    /**
     * Run the strategy chat with a tight text response. Drain the
     * {@code Stream<StreamEvent>} synchronously to assemble the final text.
     */
    private String runStrategyChat(String prompt) {
        if (strategyChatClient == null) return null;
        List<Message> req = new ArrayList<>();
        req.add(Message.userText(prompt));
        StringBuilder out = new StringBuilder();
        java.util.stream.Stream<org.aethercode.core.stream.StreamEvent> stream =
                strategyChatClient.stream(req,
                        "You distil transferable strategies from agent sessions. Be concise.",
                        List.of());
        for (java.util.Iterator<org.aethercode.core.stream.StreamEvent> it = stream.iterator();
             it.hasNext(); ) {
            org.aethercode.core.stream.StreamEvent ev = it.next();
            if (ev instanceof org.aethercode.core.stream.StreamEvent.TextDelta td) {
                out.append(td.text());
            } else if (ev instanceof org.aethercode.core.stream.StreamEvent.RunEnd) {
                break;
            }
        }
        return out.toString();
    }

    // ----------------------------------------------------------------
    // Experience recall
    // ----------------------------------------------------------------

    /**
     * Default cap for {@link #recallExperience(String, String, int)}.
     * Top-3 from each scope (user + project) keeps the system-prompt
     * section bounded even if the experience store grows large.
     */
    public static final int DEFAULT_RECALL_PER_SCOPE = 3;

    /**
     * recall top-K experience records relevant to the current
     * user input. Pulls from both user-scope and project-scope stores,
     * filters by token overlap (cheap), and bumps {@code utility} on
     * every hit so the next call sees a re-ranked list.
     *
     * <p>Best-effort: returns an empty list if the store is unavailable
     * or no experience is recorded yet.
     *
     * @param userInput the user's current query (used as the recall signal)
     * @param k         cap per scope (use {@link #DEFAULT_RECALL_PER_SCOPE})
     * @return merged list of recalled experiences, most relevant first
     */
    public List<ExperienceRecord> recallExperience(String userInput, int k) {
        if (!config.enabled) return List.of();
        if (store == null) return List.of();
        if (k <= 0) k = DEFAULT_RECALL_PER_SCOPE;
        List<String> tagFilter = extractTags(userInput, 6);
        // user scope
        List<ExperienceRecord> userHits = filterByOverlap(store.listUserExperience(k), userInput);
        // project scope (if bound)
        List<ExperienceRecord> projectHits = List.of();
        if (currentProjectCwd != null) {
            projectHits = filterByOverlap(store.listProjectExperience(currentProjectCwd, k), userInput);
        }
        // Merge: project first if its top-1 is more relevant than user top-1
        // (heuristic: the user is asking about the current project).
        List<ExperienceRecord> merged = new ArrayList<>(k * 2);
        int ui = 0, pi = 0;
        while (merged.size() < k * 2 && (ui < userHits.size() || pi < projectHits.size())) {
            // Project gets a slight priority on its first hit (assumes the
            // user is asking about the current project, not global
            // conventions). Beyond the first, alternate by utility rank.
            if (pi < projectHits.size() && (ui >= userHits.size() || pi == 0)) {
                merged.add(projectHits.get(pi++));
            } else if (ui < userHits.size()) {
                merged.add(userHits.get(ui++));
            } else {
                merged.add(projectHits.get(pi++));
            }
        }
        // Trim to final cap (k from each = 2k max)
        if (merged.size() > k * 2) merged = merged.subList(0, k * 2);
        // Bump utility on every hit so the next call sees a re-ranked list.
        for (ExperienceRecord r : merged) {
            store.recordExperienceUse(currentProjectCwd, r.id());
        }
        if (audit != null && !merged.isEmpty()) {
            audit.record(null, MemoryAudit.Action.RECALL, null, null, "experience",
                    sessionId, MemoryAudit.Decision.ALLOW,
                    java.util.Map.of("count", Integer.toString(merged.size()),
                            "userK", Integer.toString(userHits.size()),
                            "projectK", Integer.toString(projectHits.size())));
        }
        return merged;
    }

    /**
     * Cheap token-overlap filter: drop records whose combined title+tags
     * share no token with the user input. Keeps the order from
     * {@link ExperienceStore#topK} (utility desc).
     */
    private static List<ExperienceRecord> filterByOverlap(List<ExperienceRecord> source, String userInput) {
        if (source == null || source.isEmpty()) return List.of();
        if (userInput == null || userInput.isBlank()) return source;
        java.util.Set<String> qTokens = tokenize(userInput.toLowerCase());
        if (qTokens.isEmpty()) return source;
        List<ExperienceRecord> kept = new ArrayList<>();
        for (ExperienceRecord r : source) {
            String bag = (r.title() + " " + String.join(" ", r.tags())).toLowerCase();
            java.util.Set<String> rTokens = tokenize(bag);
            for (String t : rTokens) {
                if (qTokens.contains(t)) {
                    kept.add(r);
                    break;
                }
            }
        }
        return kept.isEmpty() ? source : kept;
    }

    /** Build a tag filter from the user input — first 6 non-stopword tokens. */
    private static List<String> extractTags(String userInput, int max) {
        if (userInput == null || userInput.isBlank()) return List.of();
        java.util.LinkedHashSet<String> out = new java.util.LinkedHashSet<>();
        for (String t : userInput.toLowerCase().split("[^a-z0-9一-龥]+")) {
            if (t.length() < 3) continue;
            if (STOPWORDS.contains(t)) continue;
            out.add(t);
            if (out.size() >= max) break;
        }
        return new ArrayList<>(out);
    }

    private static final java.util.Set<String> STOPWORDS = java.util.Set.of(
            "the", "and", "for", "are", "but", "not", "you", "all", "can", "had",
            "her", "was", "one", "our", "out", "day", "get", "has", "him", "his",
            "how", "its", "may", "new", "now", "old", "see", "two", "way", "who",
            "boy", "did", "use", "what", "when", "your", "this", "that",
            "with", "from", "have", "will", "would", "there", "their", "which",
            "about", "could", "should", "shouldn't", "don't", "doesn't", "isn't"
    );

    private static java.util.Set<String> tokenize(String s) {
        java.util.Set<String> out = new java.util.HashSet<>();
        if (s == null) return out;
        for (String t : s.split("[^a-z0-9一-龥]+")) {
            if (t.length() >= 2) out.add(t);
        }
        return out;
    }

    /**
     * render a list of recalled experiences as a markdown section
     * suitable for the system prompt. Empty string when the list is
     * null or empty.
     */
    public static String renderExperienceSection(List<ExperienceRecord> records) {
        if (records == null || records.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        sb.append("## Past experience (auto-recalled)\n");
        int i = 1;
        for (ExperienceRecord r : records) {
            sb.append("\n### ").append(i++).append(". ")
                    .append(r.title() == null ? "(untitled)" : r.title().strip());
            if (r.kind() != null) {
                sb.append(" [").append(r.kind().wire()).append("]");
            }
            sb.append("\n\n");
            // Body — strip Markdown headers we don't need to repeat; keep
            // the first 1500 chars to bound the system-prompt size.
            String body = r.body() == null ? "" : r.body();
            if (body.length() > 1500) body = body.substring(0, 1499) + "…";
            sb.append(body);
            if (!body.endsWith("\n")) sb.append("\n");
        }
        return sb.toString();
    }

    // ----------------------------------------------------------------
    // SESSION k/v recall
    // ----------------------------------------------------------------

    /**
     * recall k/v entries from the current session's session_store
     * (the {@link SessionMemoryStore} SQLite table). Cheap — the
     * session table is small (one row per session). Filters by token
     * overlap with the user input; falls back to "all" if no overlap
     * (the user might be asking about something they just told us).
     *
     * <p>Designed for the R232 SESSION-into-recall gap: the previous
     * flow only scanned USER/PROJECT file-backed memory, so anything
     * a user said earlier in the same session ("use junit 5", "the
     * build command is mvn -B install", ...) wasn't surfaced into
     * later turns' system prompts.
     */
    public List<SessionMemoryStore.MemoryEntry> recallSessionKv(String sessionId, String userInput, int k) {
        if (!config.enabled) return List.of();
        if (store == null) return List.of();
        if (sessionId == null || sessionId.isBlank()) return List.of();
        SessionMemoryStore ss = store.sessionStore();
        if (ss == null) return List.of();
        List<SessionMemoryStore.MemoryEntry> all;
        try {
            all = ss.listMemory(sessionId);
        } catch (Exception e) {
            LOG.warn("session k/v list failed: {}", e.getMessage());
            return List.of();
        }
        if (all == null || all.isEmpty()) return List.of();
        if (k <= 0) k = 16;
        // Token overlap filter
        java.util.Set<String> qTokens = tokenize(userInput == null ? "" : userInput.toLowerCase());
        List<SessionMemoryStore.MemoryEntry> matched = new ArrayList<>();
        for (SessionMemoryStore.MemoryEntry e : all) {
            if (qTokens.isEmpty()) {
                matched.add(e);
                if (matched.size() >= k) break;
                continue;
            }
            String bag = (e.key() + " " + e.value()).toLowerCase();
            java.util.Set<String> eTokens = tokenize(bag);
            for (String t : eTokens) {
                if (qTokens.contains(t)) {
                    matched.add(e);
                    break;
                }
            }
            if (matched.size() >= k) break;
        }
        // If no overlap, surface the most recent (assumes the user
        // is asking about something they just told us).
        if (matched.isEmpty()) {
            int n = Math.min(k, all.size());
            for (int i = 0; i < n; i++) matched.add(all.get(i));
        }
        if (audit != null && !matched.isEmpty()) {
            audit.record(null, MemoryAudit.Action.RECALL, MemoryScope.SESSION,
                    null, "kv", sessionId, MemoryAudit.Decision.ALLOW,
                    java.util.Map.of("count", Integer.toString(matched.size())));
        }
        return matched;
    }

    /**
     * render a list of session k/v entries as a small system-prompt
     * section. Best-effort: empty when the list is null/empty.
     */
    public static String renderSessionKvSection(List<SessionMemoryStore.MemoryEntry> entries) {
        if (entries == null || entries.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        sb.append("## Session facts (auto-recalled)\n");
        for (SessionMemoryStore.MemoryEntry e : entries) {
            sb.append("- **").append(e.key()).append("**: ");
            String v = e.value() == null ? "" : e.value();
            if (v.length() > 500) v = v.substring(0, 499) + "…";
            sb.append(v).append("\n");
        }
        return sb.toString();
    }

    // ----------------------------------------------------------------
    // Tier 4: periodic decay
    // ----------------------------------------------------------------

    /**
     * Run a single decay pass on the user-scope store + the current
     * project-scope store (if set). Throttled by item count.
     *
     * <p>Safe to call from any thread.
     */
    public void runPeriodicDecay() {
        if (!config.enabled) return;
        lastDecayAt = Instant.now();
        decayPasses.incrementAndGet();
        try {
            if (store == null) return;
            int total = 0;
            // user scope
            var userStore = store.userStore();
            var userReport = forgettingPolicy.runDecayPass(userStore, false);
            total += userReport.tombstoned() + userReport.decayed();
            decayItemsRemoved.addAndGet(userReport.tombstoned());
            if (currentProjectCwd != null) {
                var projectStore = store.publicProjectStore(currentProjectCwd);
                if (projectStore != null) {
                    var projReport = forgettingPolicy.runDecayPass(projectStore, false);
                    total += projReport.tombstoned() + projReport.decayed();
                    decayItemsRemoved.addAndGet(projReport.tombstoned());
                }
            }
            if (audit != null && total > 0) {
                audit.record(null, MemoryAudit.Action.DECAY, null, null, "decay",
                        sessionId,
                        total > 0 ? MemoryAudit.Decision.TOMBSTONED : MemoryAudit.Decision.ALLOW,
                        java.util.Map.of("total", Integer.toString(total)));
            }
        } catch (Exception e) {
            LOG.warn("periodic decay failed: {}", e.getMessage());
        }
    }

    /** Start the periodic decay background task. Idempotent. */
    public void start() {
        if (!config.enabled) return;
        if (!schedulerRunning.compareAndSet(false, true)) return;
        scheduler.scheduleAtFixedRate(this::runPeriodicDecay,
                config.decayIntervalMs, config.decayIntervalMs, TimeUnit.MILLISECONDS);
    }

    /** Stop the periodic decay background task. Safe to call from any thread. */
    public void stop() {
        schedulerRunning.set(false);
        scheduler.shutdownNow();
    }

    // ---------- helpers ----------

    private static String truncate(String s, int max) {
        if (s == null) return "";
        if (s.length() <= max) return s;
        return s.substring(0, max - 1) + "…";
    }
}
