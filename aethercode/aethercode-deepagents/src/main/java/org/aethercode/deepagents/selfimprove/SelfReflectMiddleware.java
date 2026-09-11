package org.aethercode.deepagents.selfimprove;

import org.aethercode.core.runtime.AgentState;
import org.aethercode.core.runtime.ContentBlock;
import org.aethercode.core.runtime.Message;
import org.aethercode.deepagents.middleware.Middleware;
import org.aethercode.deepagents.middleware.WrapModelCallResult;
import org.aethercode.deepagents.tools.Tool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiFunction;
import org.aethercode.core.runtime.Message.AIMessage;

/**
 * R241.2 (O-3): the <em>Reflexion</em>-style self-reflection
 * middleware (paper 4 §11.1). On every tool-call failure the
 * middleware asks a {@link Reflector} to verbalise what went
 * wrong, parses the response into a {@link ReasoningUnit},
 * and stores it in the {@link ReasoningBank}.
 *
 * <h2>Why wrapToolCall, not beforeModel?</h2>
 *
 * <p>The most reliable failure signal in a deep-agent loop
 * is the tool-call {@code wrapToolCall} hook — it is invoked
 * with the full arguments and the actual exception (or
 * tool result) so we know both the action and the failure.
 * Hooking {@code beforeModel} would require a separate
 * "did the last turn fail" check; the wrapToolCall site
 * already has all the information in one place.
 *
 * <h2>Failure routing</h2>
 *
 * <p>The {@link FailureClassifier} decides whether a given
 * error is worth reflecting on and assigns the resulting
 * unit a {@code taskKind}. The default
 * {@link FailureClassifier#always()} classifier treats
 * any thrown exception as a reflection candidate with
 * kind {@code "tool_error"}.
 *
 * <h2>Idempotency</h2>
 *
 * <p>The middleware de-duplicates reflections within a
 * single session via {@link #RECENTLY_REFLECTED_KEY} on the
 * agent state. If the same failure description is
 * reflected twice in a row, the second call is a no-op.
 * This is important because a tool call can be retried
 * (see {@code ToolRetryMiddleware}); we don't want to
 * store 5 copies of the same reflection.
 */
public class SelfReflectMiddleware implements Middleware {

    private static final Logger LOG = LoggerFactory.getLogger(SelfReflectMiddleware.class);

    /** Agent-state key for the most recent failure descriptions. */
    public static final String RECENTLY_REFLECTED_KEY = "__self_recent_failures__";
    /** Maximum reflection history kept on state. */
    public static final int MAX_RECENT = 8;

    private final Reflector reflector;
    private final ReasoningBank bank;
    private final FailureClassifier classifier;
    private final String systemPrompt;
    private final int maxReflectionsPerTurn;

    private final AtomicLong totalReflections = new AtomicLong(0L);
    private final AtomicLong skippedDedup = new AtomicLong(0L);
    private final AtomicLong skippedReflectorError = new AtomicLong(0L);
    /** Per-middleware ring buffer of recent failure descriptions
     *  for de-duplication. We keep this in memory (not in
     *  {@link AgentState}) because {@code wrapToolCall}'s
     *  contract does not let the middleware hand a new state
     *  back to the runtime; an immutable record passed in is
     *  useless for cross-call dedup. The buffer is bounded at
     *  {@link #MAX_RECENT} so it cannot grow without bound
     *  during a long session. */
    private final java.util.Deque<String> recentFailures =
            new java.util.concurrent.ConcurrentLinkedDeque<>();

    public SelfReflectMiddleware(Reflector reflector, ReasoningBank bank) {
        this(reflector, bank, FailureClassifier.always(),
                SelfReflectPrompts.DEFAULT_SYSTEM_PROMPT, 1);
    }

    public SelfReflectMiddleware(Reflector reflector, ReasoningBank bank,
                                  FailureClassifier classifier,
                                  String systemPrompt,
                                  int maxReflectionsPerTurn) {
        this.reflector = Objects.requireNonNull(reflector, "reflector");
        this.bank = Objects.requireNonNull(bank, "bank");
        this.classifier = classifier == null ? FailureClassifier.always() : classifier;
        this.systemPrompt = systemPrompt == null
                ? SelfReflectPrompts.DEFAULT_SYSTEM_PROMPT : systemPrompt;
        if (maxReflectionsPerTurn < 0) {
            throw new IllegalArgumentException(
                    "maxReflectionsPerTurn must be >= 0, got " + maxReflectionsPerTurn);
        }
        this.maxReflectionsPerTurn = maxReflectionsPerTurn;
    }

    public Reflector reflector() { return reflector; }
    public ReasoningBank bank() { return bank; }
    public FailureClassifier classifier() { return classifier; }
    public long totalReflections() { return totalReflections.get(); }
    public long skippedDedup() { return skippedDedup.get(); }
    public long skippedReflectorError() { return skippedReflectorError.get(); }
    public int maxReflectionsPerTurn() { return maxReflectionsPerTurn; }
    /** Snapshot of the in-memory dedup ring buffer. Bounded at {@link #MAX_RECENT}.
     *  Returned list is a defensive copy; mutating it does not affect the middleware. */
    public List<String> recentFailures() { return new java.util.ArrayList<>(recentFailures); }

    @Override
    public String name() { return "SelfReflectMiddleware"; }

    @Override
    public Object wrapToolCall(Tool tool,
                                 Map<String, Object> arguments,
                                 AgentState state,
                                 Runtime runtime) throws Exception {
        Objects.requireNonNull(tool, "tool");
        try {
            return tool.invoke(arguments);
        } catch (Exception e) {
            reflect(tool.name(), arguments, e, state, runtime);
            throw e;
        }
    }

    /** Public for tests and for the rare "I want to reflect
     *  on a non-tool failure" caller. */
    public void reflect(String toolName, Map<String, Object> arguments,
                         Throwable error, AgentState state, Runtime runtime) {
        FailureClassifier.Classification c = classifier.classify(toolName, arguments, error);
        if (isDuplicate(c.description())) {
            skippedDedup.incrementAndGet();
            LOG.debug("self-reflect: skip duplicate failure ({})", c.description());
            return;
        }
        if (tooManyThisTurn()) {
            skippedDedup.incrementAndGet();
            LOG.debug("self-reflect: skip — already at maxReflectionsPerTurn");
            return;
        }
        String userPrompt = buildUserPrompt(toolName, arguments, c.description(), error, runtime);
        String response;
        try {
            response = reflector.reflect(systemPrompt, userPrompt);
        } catch (Exception re) {
            skippedReflectorError.incrementAndGet();
            LOG.warn("self-reflect: reflector call failed: {}", re.getMessage());
            return;
        }
        if (response == null || response.isBlank()) {
            skippedReflectorError.incrementAndGet();
            LOG.debug("self-reflect: empty reflector response");
            return;
        }
        bank.parse(c.taskKind(), response);
        rememberFailure(c.description());
        totalReflections.incrementAndGet();
        LOG.info("self-reflect: stored unit for kind={} desc='{}'",
                c.taskKind(), abbreviate(c.description(), 80));
    }

    // -----------------------------------------------------------------
    //  Helpers
    // -----------------------------------------------------------------

    private static String buildUserPrompt(String toolName,
                                           Map<String, Object> arguments,
                                           String description,
                                           Throwable error,
                                           Runtime runtime) {
        StringBuilder sb = new StringBuilder();
        sb.append("A tool call failed. Reflect on what went wrong and how to avoid it next time.\n\n");
        if (toolName != null) sb.append("Tool: ").append(toolName).append('\n');
        if (arguments != null && !arguments.isEmpty()) {
            sb.append("Arguments: ").append(summarise(arguments)).append('\n');
        }
        sb.append("Description: ").append(description).append('\n');
        if (error != null) {
            String msg = error.getMessage();
            if (msg != null && !msg.isBlank()) {
                if (msg.length() > 400) msg = msg.substring(0, 397) + "...";
                sb.append("Error: ").append(msg).append('\n');
            }
        }
        sb.append("\nRespond in this exact format (one line per key):\n");
        sb.append("error_pattern: <one line describing the failure pattern>\n");
        sb.append("fix_strategy: <one line describing what to do next time>\n");
        sb.append("example: <short transcript excerpt, may be empty>\n");
        return sb.toString();
    }

    private boolean isDuplicate(String description) {
        return recentFailures.contains(description);
    }

    private boolean tooManyThisTurn() {
        if (maxReflectionsPerTurn <= 0) return false;
        return recentFailures.size() >= maxReflectionsPerTurn;
    }

    private void rememberFailure(String description) {
        recentFailures.addLast(description);
        // Cap to MAX_RECENT so the ring buffer doesn't grow
        // without bound across a long session.
        while (recentFailures.size() > MAX_RECENT) {
            recentFailures.pollFirst();
        }
    }

    private static String summarise(Map<String, Object> args) {
        // Truncate values aggressively — a 5KB bash command
        // shouldn't pollute the reflection prompt.
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, Object> e : args.entrySet()) {
            if (!first) sb.append(", ");
            first = false;
            sb.append(e.getKey()).append('=');
            String v = String.valueOf(e.getValue());
            if (v.length() > 80) v = v.substring(0, 77) + "...";
            sb.append(v);
        }
        sb.append('}');
        return sb.toString();
    }

    private static String abbreviate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }

    @Override
    public AgentState beforeModel(AgentState state, Runtime runtime) {
        return state; // no-op
    }

    @Override
    public AgentState afterModel(AgentState state, AIMessage ai, Runtime runtime) {
        return state; // no-op
    }

    @Override
    public AIMessage wrapModelCall(BiFunction<List<Message>, Runtime, AIMessage> modelCall,
                                    List<Message> messages, AgentState state, Runtime runtime) {
        return modelCall.apply(messages, runtime);
    }

    @Override
    public WrapModelCallResult wrapModelCallWithEvents(
            BiFunction<List<Message>, Runtime, AIMessage> modelCall,
            List<Message> messages,
            AgentState state,
            Runtime runtime) {
        return WrapModelCallResult.passthrough(
                wrapModelCall(modelCall, messages, state, runtime));
    }
}
