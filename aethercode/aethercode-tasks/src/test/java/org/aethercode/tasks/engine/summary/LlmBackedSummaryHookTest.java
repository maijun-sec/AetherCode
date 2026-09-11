package org.aethercode.tasks.engine.summary;

import org.aethercode.tasks.engine.core.SessionRegistry;
import org.aethercode.tasks.engine.core.SessionStream;
import org.aethercode.tasks.engine.core.SupervisorService;
import org.aethercode.tasks.engine.core.SupervisorStore;
import org.aethercode.tasks.engine.core.TaskEvent;
import org.aethercode.tasks.engine.summary.PostTurnSummaryHook.Kind;
import org.aethercode.tasks.engine.summary.PostTurnSummaryHook.SummaryResult;
import org.aethercode.tasks.engine.summary.PostTurnSummaryHook.TurnContext;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T-1-23 (impl) + T-1-24 (wiring) — 5 tests. Covers the
 * {@link LlmBackedSummaryHook} end-to-end (skips / injects /
 * falls-back) and the {@link SupervisorService#onAssistantTurnEnd}
 * wiring (injection vs fallback, stream + event emission).
 */
class LlmBackedSummaryHookTest {

    @Test
    void skipsLlmCallWhenSummaryBlockPresent() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        LlmCaller llm = (sys, usr) -> { calls.incrementAndGet(); return "## Summary\nx\n"; };
        LlmBackedSummaryHook hook = new LlmBackedSummaryHook(llm);
        SummaryResult r = hook.onAssistantTurnEnd(ctx(
                "the answer\n\n## Summary\nDid X. Will do Y.\n")).get();
        assertThat(r.kind()).isEqualTo(Kind.ALREADY_PRESENT);
        assertThat(calls.get()).isZero();
    }

    @Test
    void injectsSummaryWhenMissing() throws Exception {
        LlmCaller llm = (sys, usr) -> "## Summary\nDid A, B, C. Will do D next.\n";
        LlmBackedSummaryHook hook = new LlmBackedSummaryHook(llm);
        SummaryResult r = hook.onAssistantTurnEnd(ctx("no summary here")).get();
        assertThat(r.kind()).isEqualTo(Kind.INJECTED);
        assertThat(r.text()).contains("Did A, B, C");
    }

    @Test
    void fallsBackWhenLlmThrows() throws Exception {
        LlmCaller llm = (sys, usr) -> { throw new RuntimeException("rate limit"); };
        LlmBackedSummaryHook hook = new LlmBackedSummaryHook(llm);
        SummaryResult r = hook.onAssistantTurnEnd(ctx("answer")).get();
        assertThat(r.kind()).isEqualTo(Kind.FALLBACK);
        assertThat(r.reason()).contains("rate limit");
    }

    @Test
    void supervisorServiceInjectsAndAppendsToStream() throws Exception {
        // T-1-24: wiring. The supervisor's onAssistantTurnEnd must
        // (a) inject the summary when the LLM succeeds, (b) append
        // the injected footer to the session stream, (c) emit a
        // task/summary event.
        SupervisorStore store = new SupervisorStore();
        SessionRegistry registry = new SessionRegistry();
        SessionStream stream = new SessionStream();
        List<TaskEvent> events = new ArrayList<>();
        LlmCaller llm = (sys, usr) -> "## Summary\nDid A, B. Will do C next.\n";
        SupervisorService svc = SupervisorService.withLlmBackedHook(
                store, registry, stream, events::add, llm,
                Executors.newSingleThreadExecutor());

        SupervisorService.OnTurnResult res = svc.onAssistantTurnEnd(
                "s1",
                "the answer without a summary",
                List.of(Map.of("role", "user", "content", "hi"),
                        Map.of("role", "assistant", "content", "the answer without a summary"))
        ).get();

        assertThat(res.summaryInjected()).isTrue();
        assertThat(res.summaryText()).contains("Did A, B");
        // The injected footer should be in the session stream.
        List<Map<String, Object>> msgs = stream.messages("s1");
        assertThat(msgs).isNotEmpty();
        String last = (String) msgs.get(msgs.size() - 1).get("content");
        assertThat(last).contains("## Summary");
        // The task/summary event should be emitted with source=llm.
        TaskEvent ev = events.stream().filter(e -> "task/summary".equals(e.type()))
                .findFirst().orElseThrow();
        assertThat(ev.payload()).containsEntry("source", "llm");
        assertThat(ev.payload()).containsEntry("injected", true);
    }

    @Test
    void supervisorServiceFallsBackAndAppendsLastMessageLine() throws Exception {
        // T-1-24: fallback path. The supervisor appends the spec
        // wording ("Summary: (LLM auto-summary failed — see last
        // assistant message)") and emits a task/summary event
        // with source=fallback.
        SupervisorStore store = new SupervisorStore();
        SessionRegistry registry = new SessionRegistry();
        SessionStream stream = new SessionStream();
        List<TaskEvent> events = new ArrayList<>();
        LlmCaller llm = (sys, usr) -> { throw new RuntimeException("boom"); };
        SupervisorService svc = SupervisorService.withLlmBackedHook(
                store, registry, stream, events::add, llm,
                Executors.newSingleThreadExecutor());

        SupervisorService.OnTurnResult res = svc.onAssistantTurnEnd(
                "s1", "the answer", List.of()).get();
        assertThat(res.summaryInjected()).isFalse();
        assertThat(res.fallbackReason()).contains("boom");

        // The stream now contains the fallback footer.
        String content = String.valueOf(stream.messages("s1").get(0).get("content"));
        assertThat(content).contains("LLM auto-summary failed");
        // The event payload marks the failure.
        TaskEvent ev = events.stream().filter(e -> "task/summary".equals(e.type()))
                .findFirst().orElseThrow();
        assertThat(ev.payload()).containsEntry("source", "fallback");
        assertThat(ev.payload()).containsEntry("injected", false);
    }

    private static TurnContext ctx(String last) {
        return new TurnContext("s1", last, List.of(
                Map.of("role", "user", "content", "hi"),
                Map.of("role", "assistant", "content", last)
        ), Map.of());
    }
}
