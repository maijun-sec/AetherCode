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
