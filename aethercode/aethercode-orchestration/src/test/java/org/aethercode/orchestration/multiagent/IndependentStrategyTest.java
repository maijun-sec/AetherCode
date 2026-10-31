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

package org.aethercode.orchestration.multiagent;

import org.aethercode.orchestration.multiagent.IndependentStrategy.PickMode;
import org.aethercode.orchestration.multiagent.MultiAgentOrchestrator.EnsembleResult;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * R-orchestration: Independent strategy (paper 2512.08296
 * "Independent" architecture).
 *
 * <p>Verified contracts:</p>
 * <ul>
 *   <li>N agents run in parallel with no communication.</li>
 *   <li>Pick mode selects which agent's output is the winner.</li>
 *   <li>Per-agent outputs are all recorded in the audit log.</li>
 *   <li>Bad construction rejected.</li>
 * </ul>
 */
class IndependentStrategyTest {

    /** Two-arg body: (prompt, peers). */
    @FunctionalInterface
    interface Body {
        String apply(String prompt, List<String> peers);
    }

    /** Convenience: build a stub AgentFn with a given name. */
    private static AgentFn<String> stub(String name, Body body) {
        return new AgentFn<>() {
            @Override public String name() { return name; }
            @Override public String respond(String prompt, List<String> peers) {
                return body.apply(prompt, peers);
            }
        };
    }

    @Test
    void firstModePicksFirstAgent() {
        IndependentStrategy<String> s = new IndependentStrategy<>(PickMode.FIRST);
        List<AgentFn<String>> agents = List.of(
                stub("a", (p, peers) -> "A:" + p),
                stub("b", (p, peers) -> "B:" + p),
                stub("c", (p, peers) -> "C:" + p)
        );
        EnsembleResult<String> r = s.run("hello", agents);
        assertEquals("A:hello", r.winner());
        assertEquals(3, r.perAgent().size());
        assertEquals("FIRST", r.metadata().get("pick"));
    }

    @Test
    void lastModePicksLastAgent() {
        IndependentStrategy<String> s = new IndependentStrategy<>(PickMode.LAST);
        List<AgentFn<String>> agents = List.of(
                stub("a", (p, peers) -> "A"),
                stub("b", (p, peers) -> "B"),
                stub("c", (p, peers) -> "C")
        );
        EnsembleResult<String> r = s.run("p", agents);
        assertEquals("C", r.winner());
        assertEquals(2, r.metadata().get("winnerIndex"));
    }

    @Test
    void randomModeProducesValidIndex() {
        IndependentStrategy<String> s = new IndependentStrategy<>(PickMode.RANDOM);
        List<AgentFn<String>> agents = List.of(
                stub("a", (p, peers) -> "A"),
                stub("b", (p, peers) -> "B"),
                stub("c", (p, peers) -> "C")
        );
        // Run a few times; the winner index must always be in [0, 3).
        for (int i = 0; i < 20; i++) {
            EnsembleResult<String> r = s.run("p", agents);
            int idx = (int) r.metadata().get("winnerIndex");
            assertTrue(idx >= 0 && idx < 3,
                    "random winner idx out of range: " + idx);
            assertNotNull(r.winner());
        }
    }

    @Test
    void noCommunicationBetweenAgents() {
        // Each agent must receive an empty peers list — that's
        // the "no inter-agent communication" guarantee from
        // arXiv:2512.08296 §3.3 (Independent topology).
        AtomicInteger sawPeers = new AtomicInteger();
        IndependentStrategy<String> s = new IndependentStrategy<>();
        List<AgentFn<String>> agents = List.of(
                stub("a", (p, peers) -> {
                    if (peers != null && !peers.isEmpty()) sawPeers.incrementAndGet();
                    return "A";
                }),
                stub("b", (p, peers) -> {
                    if (peers != null && !peers.isEmpty()) sawPeers.incrementAndGet();
                    return "B";
                })
        );
        s.run("p", agents);
        assertEquals(0, sawPeers.get(), "no agent should see peers");
    }

    @Test
    void perAgentOutputsAreAllRecorded() {
        // The audit log carries every output even though we
        // only pick one as the winner.
        IndependentStrategy<String> s = new IndependentStrategy<>();
        List<AgentFn<String>> agents = List.of(
                stub("a", (p, peers) -> "A:" + p),
                stub("b", (p, peers) -> "B:" + p)
        );
        EnsembleResult<String> r = s.run("p", agents);
        assertEquals(2, r.perAgent().size());
        assertEquals("A:p", r.perAgent().get(0).output());
        assertEquals("B:p", r.perAgent().get(1).output());
        // Names are preserved for audit.
        assertEquals("a", r.perAgent().get(0).agentName());
        assertEquals("b", r.perAgent().get(1).agentName());
    }

    @Test
    void rejectsNullPickMode() {
        assertThrows(IllegalArgumentException.class,
                () -> new IndependentStrategy<>(null));
    }

    @Test
    void rejectsEmptyAgents() {
        IndependentStrategy<String> s = new IndependentStrategy<>();
        assertThrows(IllegalArgumentException.class,
                () -> s.run("p", List.of()));
    }
}
