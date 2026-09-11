package org.aethercode.sdk;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * tests for {@link DagPlan} — topological order, ready
 * steps, and cycle detection.
 */
class DagPlanTest {

    @Test
    void emptyPlan_completesImmediately() {
        DagPlan p = new DagPlan(List.of());
        assertTrue(p.isComplete(null));
        assertTrue(p.readySteps(null).isEmpty());
    }

    @Test
    void linearDag_topoRespectsDependencies() {
        DagPlan p = DagPlan.of(
                new DagPlan.Step("a", "step a", List.of()),
                new DagPlan.Step("b", "step b", List.of("a")),
                new DagPlan.Step("c", "step c", List.of("b"))
        );
        assertEquals(List.of("a", "b", "c"), p.topoOrder());
    }

    @Test
    void readySteps_returnsOnlyUnblocked() {
        DagPlan p = DagPlan.of(
                new DagPlan.Step("a", "step a", List.of()),
                new DagPlan.Step("b", "step b", List.of("a")),
                new DagPlan.Step("c", "step c", List.of())
        );
        // Initially: a and c are ready, b is blocked on a.
        assertEquals(List.of("a", "c"), p.readySteps(Set.of()));
        // After a completes: b (depends on a) is now ready. c was
        // already ready (no deps) but not yet completed.
        assertEquals(List.of("c", "b"), p.readySteps(Set.of("a")));
        // After all complete: nothing left.
        assertTrue(p.readySteps(Set.of("a", "b", "c")).isEmpty());
        assertTrue(p.isComplete(Set.of("a", "b", "c")));
    }

    @Test
    void cycle_throwsAtConstruction() {
        assertThrows(IllegalStateException.class, () -> DagPlan.of(
                new DagPlan.Step("a", "step a", List.of("b")),
                new DagPlan.Step("b", "step b", List.of("a"))
        ));
    }

    @Test
    void unknownDependency_throwsAtConstruction() {
        assertThrows(IllegalArgumentException.class, () -> DagPlan.of(
                new DagPlan.Step("a", "step a", List.of("missing"))
        ));
    }
}
