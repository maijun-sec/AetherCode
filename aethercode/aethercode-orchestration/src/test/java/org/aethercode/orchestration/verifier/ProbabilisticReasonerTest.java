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
