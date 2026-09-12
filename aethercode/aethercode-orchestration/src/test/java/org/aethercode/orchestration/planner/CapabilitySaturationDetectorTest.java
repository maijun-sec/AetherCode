package org.aethercode.orchestration.planner;

import org.aethercode.orchestration.planner.CapabilitySaturationDetector.RunResult;
import org.aethercode.orchestration.planner.CapabilitySaturationDetector.SaturationReport;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CapabilitySaturationDetectorTest {

    @Test
    void highMeanIsSaturated() {
        CapabilitySaturationDetector det = new CapabilitySaturationDetector();
        List<RunResult> runs = List.of(
            new RunResult(0.50),
            new RunResult(0.55),
            new RunResult(0.48),
            new RunResult(0.52)
        );
        SaturationReport r = det.assess(runs);
        assertTrue(r.isSaturated(), "mean " + r.meanScore() + " should be saturated");
        assertTrue(r.meanScore() >= 0.45);
    }

    @Test
    void lowMeanIsNotSaturated() {
        CapabilitySaturationDetector det = new CapabilitySaturationDetector();
        List<RunResult> runs = List.of(
            new RunResult(0.1),
            new RunResult(0.2),
            new RunResult(0.3)
        );
        SaturationReport r = det.assess(runs);
        assertFalse(r.isSaturated());
        assertTrue(r.recommendation().contains("Below"));
    }

    @Test
    void varianceComputed() {
        CapabilitySaturationDetector det = new CapabilitySaturationDetector();
        List<RunResult> runs = List.of(
            new RunResult(0.1),
            new RunResult(0.5),
            new RunResult(0.9)
        );
        SaturationReport r = det.assess(runs);
        assertTrue(r.variance() > 0);
    }

    @Test
    void zeroVarianceForIdenticalRuns() {
        CapabilitySaturationDetector det = new CapabilitySaturationDetector();
        List<RunResult> runs = List.of(
            new RunResult(0.5),
            new RunResult(0.5),
            new RunResult(0.5)
        );
        SaturationReport r = det.assess(runs);
        assertEquals(0.0, r.variance(), 0.001);
        assertEquals(0.5, r.meanScore(), 0.001);
        assertTrue(r.isSaturated());
    }

    @Test
    void emptyRuns() {
        CapabilitySaturationDetector det = new CapabilitySaturationDetector();
        SaturationReport r = det.assess(List.of());
        assertEquals(0.0, r.meanScore());
        assertFalse(r.isSaturated());
    }

    @Test
    void singleRun() {
        CapabilitySaturationDetector det = new CapabilitySaturationDetector();
        SaturationReport r = det.assess(List.of(new RunResult(0.6)));
        assertEquals(0.6, r.meanScore(), 0.001);
        assertEquals(0.0, r.variance(), 0.001);
        assertTrue(r.isSaturated());
    }

    @Test
    void customThreshold() {
        CapabilitySaturationDetector det = new CapabilitySaturationDetector(0.7);
        List<RunResult> runs = List.of(new RunResult(0.6));
        assertFalse(det.assess(runs).isSaturated());
    }

    @Test
    void runResultValidatesScore() {
        assertThrows(IllegalArgumentException.class, () -> new RunResult(-0.1));
        assertThrows(IllegalArgumentException.class, () -> new RunResult(1.1));
    }

    @Test
    void saturationLevelBetween0And1() {
        CapabilitySaturationDetector det = new CapabilitySaturationDetector();
        SaturationReport r = det.assess(List.of(
            new RunResult(0.5),
            new RunResult(0.6),
            new RunResult(0.4)
        ));
        assertTrue(r.saturationLevel() >= 0.0 && r.saturationLevel() <= 1.0);
    }

    @Test
    void thresholdGetter() {
        CapabilitySaturationDetector det = new CapabilitySaturationDetector(0.55);
        assertEquals(0.55, det.getSaturationThreshold());
    }
}
