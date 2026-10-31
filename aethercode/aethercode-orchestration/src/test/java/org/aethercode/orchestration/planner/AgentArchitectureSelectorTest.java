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

package org.aethercode.orchestration.planner;

import org.aethercode.orchestration.planner.AgentArchitectureSelector.Architecture;
import org.aethercode.orchestration.planner.AgentArchitectureSelector.Recommendation;
import org.aethercode.orchestration.planner.AgentArchitectureSelector.TaskFeatures;
import org.aethercode.orchestration.multiagent.HybridStrategy;
import org.aethercode.orchestration.multiagent.IndependentStrategy;
import org.aethercode.orchestration.multiagent.MultiAgentOrchestrator;
import org.aethercode.orchestration.multiagent.VoteStrategy;
import org.aethercode.orchestration.verifier.HeuristicVerifier;
import org.aethercode.orchestration.verifier.Verifier;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class AgentArchitectureSelectorTest {

    @Test
    void saturatedTaskReturnsSingle() {
        AgentArchitectureSelector sel = new AgentArchitectureSelector();
        TaskFeatures f = new TaskFeatures(false, true, false, false, 0.6);
        Recommendation r = sel.recommend(f);
        assertEquals(Architecture.SINGLE, r.architecture());
        assertTrue(r.rationale().contains("saturat"));
    }

    @Test
    void sequentialReasoningReturnsSingle() {
        AgentArchitectureSelector sel = new AgentArchitectureSelector();
        TaskFeatures f = new TaskFeatures(false, true, false, false, 0.3);
        Recommendation r = sel.recommend(f);
        assertEquals(Architecture.SINGLE, r.architecture());
    }

    @Test
    void dynamicWebNavReturnsIndependent() {
        AgentArchitectureSelector sel = new AgentArchitectureSelector();
        TaskFeatures f = new TaskFeatures(false, false, false, true, 0.3);
        Recommendation r = sel.recommend(f);
        assertEquals(Architecture.INDEPENDENT, r.architecture());
        assertEquals(9.2, r.expectedGainPct());
    }

    @Test
    void toolHeavyParallelizableReturnsHybrid() {
        AgentArchitectureSelector sel = new AgentArchitectureSelector();
        TaskFeatures f = new TaskFeatures(true, false, true, false, 0.3);
        Recommendation r = sel.recommend(f);
        assertEquals(Architecture.HYBRID, r.architecture());
    }

    @Test
    void parallelizableLowBaselineReturnsCentralized() {
        AgentArchitectureSelector sel = new AgentArchitectureSelector();
        TaskFeatures f = new TaskFeatures(true, false, false, false, 0.2);
        Recommendation r = sel.recommend(f);
        assertEquals(Architecture.CENTRALIZED, r.architecture());
        assertEquals(80.8, r.expectedGainPct());
    }

    @Test
    void defaultFallsBackToSingle() {
        AgentArchitectureSelector sel = new AgentArchitectureSelector();
        TaskFeatures f = new TaskFeatures(false, false, false, false, 0.2);
        Recommendation r = sel.recommend(f);
        assertEquals(Architecture.SINGLE, r.architecture());
    }

    @Test
    void customSaturationThreshold() {
        AgentArchitectureSelector sel = new AgentArchitectureSelector(0.7);
        TaskFeatures f = new TaskFeatures(true, false, true, false, 0.5);
        Recommendation r = sel.recommend(f);
        // With threshold 0.7, 0.5 is below saturation, so HYBRID rule applies
        assertEquals(Architecture.HYBRID, r.architecture());
    }

    @Test
    void invalidBaselineRejected() {
        assertThrows(IllegalArgumentException.class,
            () -> new TaskFeatures(false, false, false, false, 1.5));
    }

    @Test
    void buildStrategyReturnsCorrectImpl() {
        AgentArchitectureSelector sel = new AgentArchitectureSelector();
        MultiAgentOrchestrator.EnsembleStrategy<String> indep = sel.buildStrategy(
            new Recommendation(Architecture.INDEPENDENT, "x", 0.0), null);
        assertInstanceOf(IndependentStrategy.class, indep);

        MultiAgentOrchestrator.EnsembleStrategy<String> cen = sel.buildStrategy(
            new Recommendation(Architecture.CENTRALIZED, "x", 0.0), null);
        assertInstanceOf(VoteStrategy.class, cen);

        MultiAgentOrchestrator.EnsembleStrategy<String> hyb = sel.buildStrategy(
            new Recommendation(Architecture.HYBRID, "x", 0.0),
            new AgentArchitectureSelector.VerifierSupplier() {
                @Override
                @SuppressWarnings("unchecked")
                public <T> Verifier<T> get() {
                    return (Verifier<T>) new HeuristicVerifier("test", java.util.List.of(
                        new HeuristicVerifier.NonEmptyCheck()));
                }
            });
        assertInstanceOf(HybridStrategy.class, hyb);
    }

    @Test
    void buildStrategySingleThrows() {
        AgentArchitectureSelector sel = new AgentArchitectureSelector();
        assertThrows(IllegalStateException.class, () -> sel.buildStrategy(
            new Recommendation(Architecture.SINGLE, "x", 0.0), null));
    }

    @Test
    void buildStrategyHybridRequiresVerifier() {
        AgentArchitectureSelector sel = new AgentArchitectureSelector();
        assertThrows(IllegalArgumentException.class, () -> sel.buildStrategy(
            new Recommendation(Architecture.HYBRID, "x", 0.0), null));
    }

    @Test
    void buildStrategyDecentralizedThrows() {
        AgentArchitectureSelector sel = new AgentArchitectureSelector();
        assertThrows(UnsupportedOperationException.class, () -> sel.buildStrategy(
            new Recommendation(Architecture.DECENTRALIZED, "x", 0.0), null));
    }

    @Test
    void confidenceBetween0And1() {
        AgentArchitectureSelector sel = new AgentArchitectureSelector();
        TaskFeatures f = new TaskFeatures(true, false, true, false, 0.3);
        double conf = sel.confidence(sel.recommend(f), f);
        assertTrue(conf > 0.0 && conf <= 1.0);
    }
}
