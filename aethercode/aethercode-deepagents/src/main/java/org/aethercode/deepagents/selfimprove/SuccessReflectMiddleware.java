package org.aethercode.deepagents.selfimprove;

import org.aethercode.core.runtime.AgentState;
import org.aethercode.core.runtime.Message;
import org.aethercode.deepagents.middleware.Middleware;
import org.aethercode.deepagents.middleware.WrapModelCallResult;
import org.aethercode.deepagents.tools.Tool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiFunction;
import org.aethercode.core.runtime.Message.AIMessage;

/**
 * R243.1 (O-3): the success-path counterpart of
 * {@link SelfReflectMiddleware}. After a tool call returns
 * without throwing, the middleware asks a {@link Reflector}
 * to verbalise why the call worked, parses the response into
 * a {@link ReasoningUnit}, and stores it in the
 * {@link ReasoningBank}. Together with the failure-side
 * {@link SelfReflectMiddleware} this gives the bank a balanced
 * diet of "what went wrong" and "what went right", which is
 * the strategy-library pattern from paper 1 §4.2.2.
 *
 * <h2>Why a separate middleware (and not a flag on
 * {@code SelfReflectMiddleware})</h2>
 *
 * <p>The two paths are not symmetric at the lifecycle level:
 *
 * <ul>
 *   <li>Failure reflection is mandatory — by default every
 *       caught exception triggers a reflection. Closing the
 *       gap matters.</li>
 *   <li>Success reflection is opportunistic — the same tool
 *       call succeeds thousands of times in a long session
 *       and we do not want to chatter to the LLM on every
 *       one of them. A separate middleware lets the host
 *       choose whether to wire it in at all, and lets a host
 *       pick a different {@link Reflector} for success than
 *       for failure (e.g. a cheaper model).</li>
 * </ul>
 *
 * <h2>Dedup</h2>
 *
 * <p>The middleware keeps a bounded in-memory ring buffer of
 * recent success descriptions (mirroring
 * {@link SelfReflectMiddleware#recentFailures()}). Identical
 * consecutive successes are de-duplicated, so a long-running
 * tool that succeeds 100× in a row only stores one
 * reflection.
 *
 * <h2>Cap</h2>
 *
 * <p>{@code maxSuccessesPerTurn} caps how many reflections a
 * single turn can store. Set it to {@code 0} to disable
 * success reflection entirely (the
 * {@link #wrapToolCall} will then pass through unchanged).
 */
public class SuccessReflectMiddleware implements Middleware {

    private static final Logger LOG = LoggerFactory.getLogger(SuccessReflectMiddleware.class);

    /** Maximum success-description history kept on state. */
    public static final int MAX_RECENT = 8;

    private final Reflector reflector;
    private final ReasoningBank bank;
    private final SuccessClassifier classifier;
    private final String systemPrompt;
    private final int maxSuccessesPerTurn;

    private final AtomicLong totalReflections = new AtomicLong(0L);
    private final AtomicLong skippedDedup = new AtomicLong(0L);
    private final AtomicLong skippedReflectorError = new AtomicLong(0L);
    private final AtomicLong skippedDisabled = new AtomicLong(0L);

    private final Deque<String> recentSuccesses = new ConcurrentLinkedDeque<>();

    public SuccessReflectMiddleware(Reflector reflector, ReasoningBank bank) {
        this(reflector, bank, SuccessClassifier.always(),
                SuccessReflectPrompts.DEFAULT_SYSTEM_PROMPT, 1);
    }

    public SuccessReflectMiddleware(Reflector reflector, ReasoningBank bank,
                                     SuccessClassifier classifier,
                                     String systemPrompt,
                                     int maxSuccessesPerTurn) {
        this.reflector = Objects.requireNonNull(reflector, "reflector");
        this.bank = Objects.requireNonNull(bank, "bank");
        this.classifier = classifier == null ? SuccessClassifier.never() : classifier;
        this.systemPrompt = systemPrompt == null
                ? SuccessReflectPrompts.DEFAULT_SYSTEM_PROMPT : systemPrompt;
        if (maxSuccessesPerTurn < 0) {
            throw new IllegalArgumentException(
                    "maxSuccessesPerTurn must be >= 0, got " + maxSuccessesPerTurn);
        }
        this.maxSuccessesPerTurn = maxSuccessesPerTurn;
    }

    public Reflector reflector() { return reflector; }
    public ReasoningBank bank() { return bank; }
    public SuccessClassifier classifier() { return classifier; }
    public long totalReflections() { return totalReflections.get(); }
    public long skippedDedup() { return skippedDedup.get(); }
    public long skippedReflectorError() { return skippedReflectorError.get(); }
    public long skippedDisabled() { return skippedDisabled.get(); }
    public int maxSuccessesPerTurn() { return maxSuccessesPerTurn; }

    /** Snapshot of the in-memory dedup ring buffer. Bounded at
     *  {@link #MAX_RECENT}. */
    public List<String> recentSuccesses() { return new java.util.ArrayList<>(recentSuccesses); }

    @Override
    public String name() { return "SuccessReflectMiddleware"; }

    @Override
    public Object wrapToolCall(Tool tool, Map<String, Object> arguments,
                                 AgentState state, Runtime runtime) throws Exception {
        if (maxSuccessesPerTurn <= 0) {
            skippedDisabled.incrementAndGet();
            return tool.invoke(arguments);
        }
        Object result;
        try {
            result = tool.invoke(arguments);
        } catch (Exception failure) {
            // Symmetry with SelfReflectMiddleware: we do NOT
            // reflect on exceptions here. The failure path is
            // owned by SelfReflectMiddleware. Just rethrow.
            throw failure;
        }
        try {
            reflectOnSuccess(tool.name(), arguments, result, state, runtime);
        } catch (RuntimeException re) {
            // Reflection errors must never break the call site.
            LOG.warn("success-reflect: unexpected error: {}", re.getMessage());
        }
        return result;
    }

    /** Public for tests and for callers that want to reflect
     *  on a successful call from outside the tool path. */
    public void reflectOnSuccess(String toolName, Map<String, Object> arguments,
                                  Object result, AgentState state, Runtime runtime) {
        SuccessClassifier.Classification c = classifier.classify(toolName, arguments, result);
        if (c == null) {
            skippedDisabled.incrementAndGet();
            return;
        }
        if (isDuplicate(c.description())) {
            skippedDedup.incrementAndGet();
            LOG.debug("success-reflect: skip duplicate ({})", c.description());
            return;
        }
        if (tooManyThisTurn()) {
            skippedDedup.incrementAndGet();
            LOG.debug("success-reflect: skip — at maxSuccessesPerTurn");
            return;
        }
        String userPrompt = buildUserPrompt(toolName, arguments, result);
        String response;
        try {
            response = reflector.reflect(systemPrompt, userPrompt);
        } catch (Exception re) {
            skippedReflectorError.incrementAndGet();
            LOG.warn("success-reflect: reflector call failed: {}", re.getMessage());
            return;
        }
        if (response == null || response.isBlank()) {
            skippedReflectorError.incrementAndGet();
            LOG.debug("success-reflect: empty reflector response");
            return;
        }
        bank.parse(c.taskKind(), response);
        rememberSuccess(c.description());
        totalReflections.incrementAndGet();
        LOG.info("success-reflect: stored unit for kind={} desc='{}'",
                c.taskKind(), abbreviate(c.description(), 80));
    }

    // -----------------------------------------------------------------
    //  Helpers
    // -----------------------------------------------------------------

    private static String buildUserPrompt(String toolName, Map<String, Object> arguments, Object result) {
        StringBuilder sb = new StringBuilder();
        sb.append("A tool call succeeded. Reflect on the strategy that made it work.\n\n");
        if (toolName != null) sb.append("Tool: ").append(toolName).append('\n');
        if (arguments != null && !arguments.isEmpty()) {
            sb.append("Arguments: ").append(summarise(arguments)).append('\n');
        }
        if (result != null) {
            String r = String.valueOf(result);
            if (r.length() > 200) r = r.substring(0, 197) + "...";
            sb.append("Result: ").append(r).append('\n');
        }
        sb.append("\nRespond in this exact format (one line per key):\n");
        sb.append("error_pattern: <n/a if no pattern; otherwise the pattern that was avoided or exploited>\n");
        sb.append("fix_strategy: <one line describing the strategy that made the call work>\n");
        sb.append("example: <short transcript excerpt, may be empty>\n");
        return sb.toString();
    }

    private boolean isDuplicate(String description) {
        return recentSuccesses.contains(description);
    }

    private boolean tooManyThisTurn() {
        if (maxSuccessesPerTurn <= 0) return false;
        return recentSuccesses.size() >= maxSuccessesPerTurn;
    }

    private void rememberSuccess(String description) {
        recentSuccesses.addLast(description);
        while (recentSuccesses.size() > MAX_RECENT) {
            recentSuccesses.pollFirst();
        }
    }

    private static String summarise(Map<String, Object> args) {
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
    public AgentState beforeModel(AgentState state, Runtime runtime) { return state; }

    @Override
    public AgentState afterModel(AgentState state, AIMessage ai, Runtime runtime) { return state; }

    @Override
    public AIMessage wrapModelCall(BiFunction<List<Message>, Runtime, AIMessage> modelCall,
                                    List<Message> messages, AgentState state, Runtime runtime) {
        return modelCall.apply(messages, runtime);
    }

    @Override
    public WrapModelCallResult wrapModelCallWithEvents(
            BiFunction<List<Message>, Runtime, AIMessage> modelCall,
            List<Message> messages, AgentState state, Runtime runtime) {
        return WrapModelCallResult.passthrough(
                wrapModelCall(modelCall, messages, state, runtime));
    }
}
