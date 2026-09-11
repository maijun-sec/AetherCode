package org.aethercode.deepagents.selfimprove;

import org.aethercode.core.runtime.AgentState;
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
 * R244.1 (O-6): the <em>success-loop</em> counterpart
 * of {@link SelfReflectMiddleware} and
 * {@link SuccessReflectMiddleware}. After every tool
 * call, this middleware:
 *
 * <ol>
 *   <li>Asks the {@link SelfEvalClassifier} whether the
 *       outcome was ok (default: "no exception = ok").</li>
 *   <li>Asks the bank which units are currently
 *       recalled (the same set {@link BankRecallMiddleware}
 *       would inject into the next system prompt).</li>
 *   <li>Calls
 *       {@link ReasoningBank#recordOutcome(String, boolean)}
 *       for every recalled unit, bumping its
 *       {@link ReasoningUnit#okCount()} or
 *       {@link ReasoningUnit#notOkCount()}.</li>
 * </ol>
 *
 * <p>The result is that a unit the agent followed and
 * which kept working will rank higher on subsequent
 * recalls (higher {@link ReasoningUnit#confidence()});
 * a unit the agent followed and which kept failing will
 * rank lower and eventually drop out of the
 * confidence-weighted top-K.</p>
 *
 * <h2>Why a separate middleware (not a flag on
 * {@code BankRecallMiddleware})</h2>
 *
 * <p>The eval side-effect (writing) and the recall
 * side-effect (reading) happen on different hooks
 * ({@code wrapToolCall} vs {@code beforeModel}) and
 * are independently useful. A host that wants recall
 * but no self-eval, or vice versa, can wire only the
 * piece it needs.</p>
 *
 * <h2>Failure isolation</h2>
 *
 * <p>Like the other reflection middlewares, this one
 * never breaks the call site: a recordOutcome error is
 * logged and counted, the tool result is still
 * returned to the caller.</p>
 */
public class SelfEvalMiddleware implements Middleware {

    private static final Logger LOG = LoggerFactory.getLogger(SelfEvalMiddleware.class);

    private final ReasoningBank bank;
    private final BankRecallMiddleware recall;
    private final SelfEvalClassifier classifier;
    private final boolean enabled;

    private final AtomicLong totalEvaluations = new AtomicLong(0L);
    private final AtomicLong totalOk = new AtomicLong(0L);
    private final AtomicLong totalNotOk = new AtomicLong(0L);
    private final AtomicLong skippedClassifier = new AtomicLong(0L);

    public SelfEvalMiddleware(ReasoningBank bank,
                               BankRecallMiddleware recall) {
        this(bank, recall, SelfEvalClassifier.heuristic(), true);
    }

    public SelfEvalMiddleware(ReasoningBank bank,
                               BankRecallMiddleware recall,
                               SelfEvalClassifier classifier,
                               boolean enabled) {
        this.bank = Objects.requireNonNull(bank, "bank");
        this.recall = Objects.requireNonNull(recall, "recall");
        this.classifier = classifier == null
                ? SelfEvalClassifier.heuristic() : classifier;
        this.enabled = enabled;
    }

    public ReasoningBank bank() { return bank; }
    public BankRecallMiddleware recall() { return recall; }
    public SelfEvalClassifier classifier() { return classifier; }
    public boolean enabled() { return enabled; }
    public long totalEvaluations() { return totalEvaluations.get(); }
    public long totalOk() { return totalOk.get(); }
    public long totalNotOk() { return totalNotOk.get(); }
    public long skippedClassifier() { return skippedClassifier.get(); }

    @Override
    public String name() { return "SelfEvalMiddleware"; }

    @Override
    public Object wrapToolCall(Tool tool, Map<String, Object> arguments,
                                 AgentState state, Runtime runtime) throws Exception {
        Object result = null;
        Throwable error = null;
        try {
            result = tool.invoke(arguments);
            return result;
        } catch (Exception e) {
            error = e;
            throw e;
        } finally {
            if (enabled) {
                try {
                    SelfEvalClassifier.Evaluation ev = classifier.classify(
                            tool.name(), arguments, result, error);
                    if (ev != null) {
                        totalEvaluations.incrementAndGet();
                        boolean ok = ev.ok();
                        if (ok) totalOk.incrementAndGet();
                        else totalNotOk.incrementAndGet();
                        applyToRecalledUnits(ok);
                    } else {
                        skippedClassifier.incrementAndGet();
                    }
                } catch (RuntimeException re) {
                    LOG.warn("self-eval: classifier error: {}", re.getMessage());
                }
            }
        }
    }

    /**
     * Bump the outcome counter for every unit that
     * {@link BankRecallMiddleware#recallAllKinds()} would
     * have surfaced for the next prompt. A unit that the
     * agent never sees cannot be evaluated, so we restrict
     * the feedback loop to the visible set.
     */
    private void applyToRecalledUnits(boolean ok) {
        List<ReasoningUnit> recalled = recall.recallAllKinds();
        for (ReasoningUnit u : recalled) {
            try {
                bank.recordOutcome(u.id(), ok);
            } catch (RuntimeException re) {
                LOG.warn("self-eval: recordOutcome({}) failed: {}",
                        u.id(), re.getMessage());
            }
        }
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
