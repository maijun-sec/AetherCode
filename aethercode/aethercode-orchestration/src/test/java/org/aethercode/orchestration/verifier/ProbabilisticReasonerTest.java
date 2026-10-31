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

package org.aethercode.orchestration.verifier;

import org.aethercode.orchestration.verifier.ProbabilisticReasoner.Observation;
import org.aethercode.orchestration.verifier.ProbabilisticReasoner.Posterior;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class ProbabilisticReasonerTest {

    @Test
    void unanimousObservationsHaveHighConfidence() {
        var r = new ProbabilisticReasoner();
        var p = r.posterior(List.of(
            new Observation("a", "yes"),
            new Observation("b", "yes"),
            new Observation("c", "yes")
        ));
        assertEquals("yes", p.bestAnswer());
        assertTrue(p.bestProb() > 0.5, "bestProb should be > 0.5 for unanimous; got " + p.bestProb());
        // credible interval should bracket the point estimate
        assertTrue(p.credibleLow() <= p.bestProb());
        assertTrue(p.credibleHigh() >= p.bestProb());
    }

    @Test
    void emptyObservationsProduceEmptyPosterior() {
        var r = new ProbabilisticReasoner();
        var p = r.posterior(List.of());
        assertNull(p.bestAnswer());
    }

    @Test
    void splitObservationsPickHighestPosterior() {
        var r = new ProbabilisticReasoner(1.0, 200, new Random(0));
        // 3 yes, 1 no -> yes wins but with less confidence
        var p = r.posterior(List.of(
            new Observation("a", "yes"),
            new Observation("b", "yes"),
            new Observation("c", "yes"),
            new Observation("d", "no")
        ));
        assertEquals("yes", p.bestAnswer());
    }

    @Test
    void sampleFromPosteriorReturnsDistribution() {
        var r = new ProbabilisticReasoner(1.0, 100, new Random(1));
        var observations = List.of(
            new Observation("a", "x"),
            new Observation("b", "x"),
            new Observation("c", "y")
        );
        var sample = r.sampleFromPosterior(observations, 1000);
        assertNotNull(sample);
        double total = sample.values().stream().mapToDouble(Double::doubleValue).sum();
        assertEquals(1.0, total, 1e-6);
        assertTrue(sample.getOrDefault("x", 0.0) > sample.getOrDefault("y", 0.0));
    }

    @Test
    void priorAlphaAffectsConfidence() {
        var low = new ProbabilisticReasoner(0.1, 200, new Random(2));
        var high = new ProbabilisticReasoner(10.0, 200, new Random(2));
        // 3:1 yes:no — asymmetric evidence; prior alpha should still affect
        // the posterior concentration: a stronger prior pulls the posterior
        // toward 0.5 (more uniform), so a weaker prior keeps the observed 3:1
        // signal sharper.
        var obs = List.of(
            new Observation("a", "yes"),
            new Observation("b", "yes"),
            new Observation("c", "yes"),
            new Observation("d", "no")
        );
        Posterior pLow = low.posterior(obs);
        Posterior pHigh = high.posterior(obs);
        assertTrue(pLow.bestProb() > pHigh.bestProb(),
            "low prior should give higher bestProb; got low=" + pLow.bestProb() + " high=" + pHigh.bestProb());
    }
}
