/*
 * Copyright (c) 2026 maijun
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package org.aethercode.core.agent;

import org.aethercode.core.stream.StreamEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * R746 (2026-10-21) — {@link SubagentOrchestrator} runs an
 * <b>unattended child engine</b>, which makes its consumer contract the
 * strictest of the five R746 sites.
 *
 * <p>Why this site is different from the other four: a spawned subagent
 * has nobody driving it. If the child's producer wedges, nothing throws
 * back into the parent thread — the parent's {@code delegate(...)} call
 * simply never returns, and the parent agent sits there waiting on
 * {@code subagent_status} with no error path and no timeout. Before
 * R746 the loop was a bare {@code child.query(task).forEach(...)},
 * whose {@code hasNext()} is an unbounded blocking read.
 *
 * <p>So the pinned contract is: <b>a wedged child must become an error
 * {@link Subagent.Result} the parent can act on</b>, not an infinite
 * wait. That is the single case the rest of this class exists for.
 */
class SubagentOrchestratorR746Test {

    /** 2s instead of the production 180s so the stall path finishes
     *  fast. Surefire forks one JVM per module, so the real env var
     *  cannot be set per-test — hence the explicit constructor arg. */
    private static final long TEST_BUDGET_MS = 2_000L;

    private static SubagentOrchestrator orchestratorWith(Subagent.EngineFactory f) {
        Subagent.Registry r = new Subagent.Registry();
        r.register(new Subagent("worker", "does work", "You work.", List.of(), null));
        return new SubagentOrchestrator(r, f, TEST_BUDGET_MS);
    }

    // ------------------------------------------------------------------
    //  1. Happy path — the child finished, nothing is synthesised
    // ------------------------------------------------------------------

    @Test
    @DisplayName("R746: a child that completes normally returns its text and ok=true (no synthetic failure)")
    void healthyChildReturnsText() {
        SubagentOrchestrator o = orchestratorWith((spec, parent) -> engineOf(
                new StreamEvent.TextDelta("found it"),
                new StreamEvent.RunEnd("end_turn", List.of())));

        Subagent.Result res = o.delegate(new Object(), "worker", "look up X");

        assertThat(res.ok()).isTrue();
        assertThat(res.text()).isEqualTo("found it");
        assertThat(res.error()).isNull();
        assertThat(res.toolCalls()).isZero();
    }

    @Test
    @DisplayName("R746: tool calls are still counted on the healthy path")
    void healthyChildStillCountsToolCalls() {
        SubagentOrchestrator o = orchestratorWith((spec, parent) -> engineOf(
                new StreamEvent.ToolUseStart("t1", "bash", Map.of()),
                new StreamEvent.ToolUseStart("t2", "file_read", Map.of()),
                new StreamEvent.TextDelta("done"),
                new StreamEvent.RunEnd("end_turn", List.of())));

        Subagent.Result res = o.delegate(new Object(), "worker", "x");

        assertThat(res.ok()).isTrue();
        assertThat(res.toolCalls()).isEqualTo(2);
        assertThat(res.text()).isEqualTo("done");
    }

    @Test
    @DisplayName("R746: a child that ends without a RunEnd still returns ok (guard mints the terminal event)")
    void childWithoutRunEndIsNotReportedAsFailure() {
        // The child produced real output but the stream died before
        // the terminal event. That is the EXHAUSTED_WITHOUT_RUN_END
        // shape — a protocol violation, not a task failure. The parent
        // gets the text it paid for; the LOG marker is how an operator
        // learns about it.
        SubagentOrchestrator o = orchestratorWith((spec, parent) -> engineOf(
                new StreamEvent.TextDelta("partial but useful")));

        Subagent.Result res = o.delegate(new Object(), "worker", "x");

        assertThat(res.ok()).isTrue();
        assertThat(res.text()).isEqualTo("partial but useful");
    }

    // ------------------------------------------------------------------
    //  2. THE key case — a wedged child must not hang the parent
    // ------------------------------------------------------------------

    @Test
    @DisplayName("R746: a wedged child returns a consumer_timeout error Result — the parent is NOT left waiting forever")
    void wedgedChildSurfacesAsError() {
        // The child engine streams one event and then the producer
        // wedges forever. Pre-fix this parked inside hasNext() and the
        // parent's delegate() never returned.
        SubagentOrchestrator o = orchestratorWith((spec, parent) -> wedgedEngineAfter(
                new StreamEvent[] { new StreamEvent.TextDelta("starting") },
                new CountDownLatch(1)));

        long started = System.nanoTime();
        Subagent.Result res = o.delegate(new Object(), "worker", "long task");
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);

        assertThat(res.ok())
                .as("R746: a subagent whose stream wedged MUST come back as a failure — the "
                        + "parent agent is otherwise stuck on subagent_status with no error and "
                        + "no timeout, which is the whole reason R746 exists here")
                .isFalse();
        assertThat(res.error())
                .as("R746: the error must name the stall so the parent (and the operator) can "
                        + "tell 'the child wedged' from 'the child returned nothing'")
                .contains("stalled")
                .contains("consumer_timeout");
        assertThat(res.error())
                .as("R746: the error must say how far the child got, otherwise the parent "
                        + "cannot tell an immediate crash from a long-then-wedge")
                .contains("1 event");
        assertThat(res.elapsedMs()).isGreaterThanOrEqualTo(0);
        assertThat(elapsedMs)
                .as("R746: delegate() must return within the bounded budget (%dms), not hang — "
                        + "took %dms", TEST_BUDGET_MS, elapsedMs)
                .isLessThan(TEST_BUDGET_MS * 5);
    }

    @Test
    @DisplayName("R746: a child that wedges immediately is also caught")
    void immediatelyWedgedChildSurfacesAsError() {
        // Zero events before the wedge — the child engine never even
        // produced a first token. Same contract: error, not a hang.
        SubagentOrchestrator o = orchestratorWith((spec, parent) ->
                wedgedEngineAfter(new StreamEvent[]{}, new CountDownLatch(1)));

        Subagent.Result res = o.delegate(new Object(), "worker", "x");

        assertThat(res.ok()).isFalse();
        assertThat(res.error()).contains("consumer_timeout");
        assertThat(res.error()).contains("0 event");
    }

    // ------------------------------------------------------------------
    //  Helpers — stub child engines
    // ------------------------------------------------------------------

    /** a child engine that replays the given events and then ends
     *  cleanly (hasNext() == false). */
    private static Subagent.SubagentEngine engineOf(StreamEvent... events) {
        List<StreamEvent> canned = List.of(events);
        return engineOf(canned.iterator());
    }

    private static Subagent.SubagentEngine engineOf(java.util.Iterator<StreamEvent> it) {
        return new Subagent.SubagentEngine() {
            @Override
            public java.util.stream.Stream<StreamEvent> query(String task,
                    org.aethercode.core.llm.ChatClient chatClientOverride,
                    List<org.aethercode.core.tool.Tool> toolPoolOverride) {
                return streamOf(it);
            }
            @Override public String sessionId() { return "test-session"; }
            @Override public List<org.aethercode.core.tool.Tool> tools() { return List.of(); }
        };
    }

    /**
     * A child engine that emits {@code prefix} and then wedges: the
     * iterator's {@code hasNext()} blocks forever once the prefix is
     * drained, exactly like a producer whose backing queue never
     * receives another element.
     */
    private static Subagent.SubagentEngine wedgedEngineAfter(StreamEvent[] prefix,
                                                            CountDownLatch neverReleased) {
        List<StreamEvent> canned = new ArrayList<>(List.of(prefix));
        java.util.Iterator<StreamEvent> wedged = new java.util.Iterator<>() {
            @Override public boolean hasNext() {
                if (!canned.isEmpty()) return true;
                try { neverReleased.await(); } catch (InterruptedException ignored) { }
                return false;
            }
            @Override public StreamEvent next() { return canned.remove(0); }
        };
        return engineOf(wedged);
    }

    private static java.util.stream.Stream<StreamEvent> streamOf(
            java.util.Iterator<StreamEvent> it) {
        java.util.Spliterator<StreamEvent> spliterator =
                java.util.Spliterators.spliteratorUnknownSize(it,
                        java.util.Spliterator.ORDERED | java.util.Spliterator.NONNULL);
        return java.util.stream.StreamSupport.stream(spliterator, false);
    }
}