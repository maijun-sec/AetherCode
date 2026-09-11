package org.aethercode.deepagents.selfimprove;

import org.aethercode.core.runtime.AgentState;
import org.aethercode.core.runtime.ContentBlock;
import org.aethercode.core.runtime.Message;
import org.aethercode.deepagents.middleware.Middleware;
import org.aethercode.deepagents.middleware.WrapModelCallResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.function.BiFunction;
import org.aethercode.core.runtime.Message.AIMessage;
import org.aethercode.core.runtime.Message.SystemMessage;

/**
 * R241.2 (O-3): the read-side counterpart to
 * {@link SelfReflectMiddleware}. While {@code SelfReflectMiddleware}
 * writes new {@link ReasoningUnit}s into the {@link ReasoningBank}
 * after a tool failure, this middleware reads them back out and
 * injects them into the system prompt on the next model call.
 *
 * <h2>What it does</h2>
 *
 * <p>On {@link #beforeModel} it asks the bank for its top units
 * (default {@code topK=3}, one per task kind by default, ranked by
 * utility), caches the result on the agent state under
 * {@link #RECALL_KEY}, and on {@link #wrapModelCall} formats the
 * units as a small {@code <prior_reflections>} block appended to
 * the existing {@link SystemMessage}.</p>
 *
 * <h2>Why a separate middleware</h2>
 *
 * <p>Reading the bank and writing to it are two orthogonal
 * concerns with different triggers (model-call time vs. tool-failure
 * time). A separate middleware also means a host application can
 * enable recall without enabling reflection, and vice versa, just
 * by which middleware it wires into the chain.</p>
 *
 * <h2>Caching</h2>
 *
 * <p>The recall result is cached on the agent state via
 * {@link AgentState#withExtension} so subsequent turns of the same
 * agent loop do not re-run the recall query. Callers that want a
 * fresh recall every turn can simply pass a fresh
 * {@link AgentState} on each invocation.</p>
 *
 * <h2>Recall mode</h2>
 *
 * <p>The default mode takes the top-1 unit of every kind in the
 * bank, sorts by {@code utility desc, uses desc, createdAt desc},
 * and keeps the first {@code topK} entries. This keeps the
 * injected prompt small while still covering the most useful
 * patterns. The mode is intentionally simpler than
 * {@link ReasoningBank#recallFor(String, int)} (which is exact-match
 * on a kind) because the middleware has no clean way to know the
 * current task kind from outside.</p>
 */
public class BankRecallMiddleware implements Middleware {

    private static final Logger LOG = LoggerFactory.getLogger(BankRecallMiddleware.class);

    /** State-extension key holding the cached recall result. */
    public static final String RECALL_KEY = "__reasoning_bank_recall__";
    /** Default top-K units to inject. */
    public static final int DEFAULT_TOP_K = 3;

    /** Default prompt fragment; mirrors the {@code <prior_reflections>}
     *  block the Python ReasoningBank port emits. The {@code {recall}}
     *  slot is the substitution point for the formatted units. */
    public static final String DEFAULT_SYSTEM_PROMPT = """
            <prior_reflections>
            The following are abstracted reflections on past failures
            of the same kind. Read them before deciding which tool to
            call, and prefer strategies that worked previously.
            {recall}
            </prior_reflections>
            """;

    private final ReasoningBank bank;
    private final int topK;
    private final String systemPrompt;

    public BankRecallMiddleware(ReasoningBank bank) {
        this(bank, DEFAULT_TOP_K, DEFAULT_SYSTEM_PROMPT);
    }

    public BankRecallMiddleware(ReasoningBank bank, int topK, String systemPrompt) {
        this.bank = Objects.requireNonNull(bank, "bank");
        if (topK <= 0) {
            throw new IllegalArgumentException("topK must be > 0, got " + topK);
        }
        this.topK = topK;
        if (systemPrompt != null && !systemPrompt.contains("{recall}")) {
            throw new IllegalArgumentException(
                    "systemPrompt must contain the `{recall}` format slot");
        }
        this.systemPrompt = systemPrompt;
    }

    public ReasoningBank bank() { return bank; }
    public int topK() { return topK; }
    public String systemPrompt() { return systemPrompt; }

    @Override
    public String name() { return "BankRecallMiddleware"; }

    @Override
    public int priority() { return 5; } // run after MemoryMiddleware (priority 0)

    @Override
    public AgentState beforeModel(AgentState state, Runtime runtime) {
        if (state.extensions().get(RECALL_KEY) != null) return state; // cached
        List<ReasoningUnit> recalled = recallAllKinds();
        if (recalled.isEmpty()) return state;
        return state.withExtension(RECALL_KEY, recalled);
    }

    @Override
    public AIMessage wrapModelCall(BiFunction<List<Message>, Runtime, AIMessage> modelCall,
                                    List<Message> messages,
                                    AgentState state,
                                    Runtime runtime) {
        List<Message> out = injectRecallIntoMessages(messages, state);
        return modelCall.apply(out, runtime);
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

    /**
     * Pull the top-1 unit of every kind in the bank, sort by
     * {@code confidence * effectiveUtility desc, uses desc,
     * createdAt desc}, and return the first {@link #topK}
     * entries.
     *
     * <p>R244.1 (O-6): the ranking now multiplies effective
     * utility by {@link ReasoningUnit#confidence()} so a
     * unit that the agent has actually followed-and-tested
     * outranks a unit of the same utility that has never
     * been observed. The two-factor ranking is
     * "importance × trust", which avoids the failure mode
     * where a high-utility but wrong unit keeps getting
     * recalled because utility never goes down on its
     * own.</p>
     *
     * <p>Visible for tests.</p>
     */
    public List<ReasoningUnit> recallAllKinds() {
        List<ReasoningUnit> merged = new ArrayList<>();
        for (String kind : bank.kinds()) {
            List<ReasoningUnit> top = bank.recallFor(kind, 1);
            merged.addAll(top);
        }
        merged.sort(Comparator
                .comparingDouble((ReasoningUnit u) -> u.confidence() * u.utility()).reversed()
                .thenComparing(Comparator.comparingLong(ReasoningUnit::uses).reversed())
                .thenComparing(Comparator.comparing(ReasoningUnit::createdAt).reversed()));
        if (merged.size() > topK) {
            merged = new ArrayList<>(merged.subList(0, topK));
        }
        return merged;
    }

    /**
     * Format the recalled units as a numbered list ready to be
     * dropped into the {@code {recall}} slot of
     * {@link #DEFAULT_SYSTEM_PROMPT}. Visible for tests.
     */
    public static String formatRecall(List<ReasoningUnit> units) {
        if (units == null || units.isEmpty()) return "(no reflections yet)";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < units.size(); i++) {
            ReasoningUnit u = units.get(i);
            sb.append(i + 1).append(". task_kind: ").append(u.taskKind()).append('\n');
            sb.append("   error_pattern: ").append(u.errorPattern()).append('\n');
            sb.append("   fix_strategy: ").append(u.fixStrategy()).append('\n');
            if (u.example() != null && !u.example().isEmpty()) {
                sb.append("   example: ").append(u.example()).append('\n');
            }
            sb.append("   (uses=").append(u.uses())
                    .append(", utility=").append(String.format("%.2f", u.utility()))
                    .append(", confidence=").append(String.format("%.2f", u.confidence()))
                    .append(')').append('\n');
        }
        // Trim trailing newline.
        if (sb.length() > 0 && sb.charAt(sb.length() - 1) == '\n') {
            sb.setLength(sb.length() - 1);
        }
        return sb.toString();
    }

    /**
     * Walk {@code messages}, find the existing {@link SystemMessage}
     * (or default to a fresh one), and append the formatted recall
     * text. If the state has no recall result or the system-prompt
     * template is {@code null}, the messages list is returned
     * unchanged. Visible for tests and for the {@code createDeepAgent}
     * adapter that needs to merge recall into its own system-prompt
     * build.
     */
    public List<Message> injectRecallIntoMessages(List<Message> messages, AgentState state) {
        if (systemPrompt == null) return messages;
        Object raw = state == null ? null : state.extensions().get(RECALL_KEY);
        if (!(raw instanceof List<?> list)) return messages;
        @SuppressWarnings("unchecked")
        List<ReasoningUnit> units = (List<ReasoningUnit>) list;
        if (units.isEmpty()) return messages;
        String recallBlock = formatRecall(units);
        String fragment = systemPrompt.replace("{recall}", recallBlock);

        int sysIdx = -1;
        for (int i = 0; i < messages.size(); i++) {
            if (messages.get(i) instanceof SystemMessage) { sysIdx = i; break; }
        }
        List<Message> out = new ArrayList<>(messages.size() + 1);
        if (sysIdx >= 0) {
            for (int i = 0; i < messages.size(); i++) {
                Message m = messages.get(i);
                if (i == sysIdx && m instanceof SystemMessage sm) {
                    out.add(appendToSystemMessage(sm, fragment));
                } else {
                    out.add(m);
                }
            }
        } else {
            out.add(new SystemMessage(
                    "recall-sys-" + System.nanoTime(),
                    List.of(ContentBlock.text(fragment))));
            out.addAll(messages);
        }
        return out;
    }

    private static SystemMessage appendToSystemMessage(SystemMessage existing, String text) {
        java.util.List<ContentBlock> blocks = new java.util.ArrayList<>(existing.content());
        String prefix = blocks.isEmpty() ? text : "\n\n" + text;
        blocks.add(ContentBlock.text(prefix));
        return new SystemMessage(existing.id(), blocks);
    }
}
