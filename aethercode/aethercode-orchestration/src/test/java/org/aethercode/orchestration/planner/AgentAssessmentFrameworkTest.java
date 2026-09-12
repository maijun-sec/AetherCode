package org.aethercode.orchestration.planner;

import org.aethercode.orchestration.planner.AgentAssessmentFramework.AgentRun;
import org.aethercode.orchestration.planner.AgentAssessmentFramework.AssessmentReport;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class AgentAssessmentFrameworkTest {

    @Test
    void assessBasic() {
        AgentAssessmentFramework fw = new AgentAssessmentFramework();
        List<AgentRun> runs = List.of(
            new AgentRun("r1", 0.8, 0.7, 0.9, 0.6),
            new AgentRun("r2", 0.6, 0.5, 0.7, 0.5)
        );
        AssessmentReport r = fw.assess(runs);
        assertEquals(0.7, r.llmScore(), 0.001);
        assertEquals(0.6, r.memoryScore(), 0.001);
        assertEquals(0.8, r.toolsScore(), 0.001);
        assertEquals(0.55, r.envScore(), 0.001);
        // overall = 0.3*0.7 + 0.25*0.6 + 0.25*0.8 + 0.2*0.55 = 0.21+0.15+0.2+0.11 = 0.67
        assertEquals(0.67, r.overall(), 0.001);
    }

    @Test
    void customWeights() {
        AgentAssessmentFramework fw = new AgentAssessmentFramework(0.5, 0.2, 0.2, 0.1);
        AssessmentReport r = fw.assess(List.of(
            new AgentRun("r1", 1.0, 0.0, 0.0, 0.0)
        ));
        assertEquals(0.5, r.overall(), 0.001);
    }

    @Test
    void invalidWeightsRejected() {
        assertThrows(IllegalArgumentException.class,
            () -> new AgentAssessmentFramework(0.5, 0.5, 0.5, 0.5)); // sum > 1
        assertThrows(IllegalArgumentException.class,
            () -> new AgentAssessmentFramework(0.2, 0.2, 0.2, 0.2)); // sum < 1
    }

    @Test
    void invalidScoresRejected() {
        assertThrows(IllegalArgumentException.class,
            () -> new AgentRun("x", -0.1, 0.5, 0.5, 0.5));
        assertThrows(IllegalArgumentException.class,
            () -> new AgentRun("x", 0.5, 0.5, 0.5, 1.1));
    }

    @Test
    void emptyRunsReport() {
        AgentAssessmentFramework fw = new AgentAssessmentFramework();
        AssessmentReport r = fw.assess(List.of());
        assertEquals(0.0, r.overall());
        assertNull(r.bestRun());
        assertNull(r.worstRun());
    }

    @Test
    void bestAndWorstRunIdentified() {
        AgentAssessmentFramework fw = new AgentAssessmentFramework();
        AssessmentReport r = fw.assess(List.of(
            new AgentRun("worst", 0.1, 0.1, 0.1, 0.1),
            new AgentRun("best", 0.9, 0.9, 0.9, 0.9),
            new AgentRun("mid", 0.5, 0.5, 0.5, 0.5)
        ));
        assertEquals("best", r.bestRun());
        assertEquals("worst", r.worstRun());
    }

    @Test
    void varianceIsZeroForIdenticalRuns() {
        AgentAssessmentFramework fw = new AgentAssessmentFramework();
        List<AgentRun> runs = List.of(
            new AgentRun("r1", 0.5, 0.5, 0.5, 0.5),
            new AgentRun("r2", 0.5, 0.5, 0.5, 0.5),
            new AgentRun("r3", 0.5, 0.5, 0.5, 0.5)
        );
        AssessmentReport r = fw.assess(runs);
        assertEquals(0.0, r.variance(), 0.001);
    }

    @Test
    void varianceIsHighForDivergentRuns() {
        AgentAssessmentFramework fw = new AgentAssessmentFramework();
        List<AgentRun> runs = List.of(
            new AgentRun("high", 1.0, 1.0, 1.0, 1.0),
            new AgentRun("low", 0.0, 0.0, 0.0, 0.0)
        );
        AssessmentReport r = fw.assess(runs);
        // pairwise agreement: |1-0| = 1, so 1-1 = 0; variance = 1 - 0 = 1
        assertEquals(1.0, r.variance(), 0.001);
    }

    @Test
    void varianceIsMediumForPartialDivergence() {
        AgentAssessmentFramework fw = new AgentAssessmentFramework();
        List<AgentRun> runs = List.of(
            new AgentRun("a", 1.0, 1.0, 1.0, 1.0),
            new AgentRun("b", 0.5, 0.5, 0.5, 0.5)
        );
        AssessmentReport r = fw.assess(runs);
        assertEquals(0.5, r.variance(), 0.001);
    }

    @Test
    void singleRunHasZeroVariance() {
        AgentAssessmentFramework fw = new AgentAssessmentFramework();
        AssessmentReport r = fw.assess(List.of(new AgentRun("only", 0.5, 0.5, 0.5, 0.5)));
        assertEquals(0.0, r.variance());
    }

    @Test
    void subMetricsContainsWeights() {
        AgentAssessmentFramework fw = new AgentAssessmentFramework(0.4, 0.3, 0.2, 0.1);
        AssessmentReport r = fw.assess(List.of(new AgentRun("r", 0.5, 0.5, 0.5, 0.5)));
        assertEquals(0.4, r.subMetrics().get("llmWeight"));
        assertEquals(0.1, r.subMetrics().get("envWeight"));
        assertEquals(1.0, r.subMetrics().get("numRuns"));
    }

    @Test
    void crossRunVarianceUtility() {
        AgentAssessmentFramework fw = new AgentAssessmentFramework();
        // Direct call to crossRunVariance
        double v = fw.crossRunVariance(List.of(
            new AgentRun("a", 0.5, 0.5, 0.5, 0.5),
            new AgentRun("b", 0.5, 0.5, 0.5, 0.5)
        ));
        assertEquals(0.0, v, 0.001);
    }
}
