package org.aethercode.orchestration.planner;

import org.aethercode.orchestration.planner.ModelSizeRouter.Decision;
import org.aethercode.orchestration.planner.ModelSizeRouter.TaskSignal;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ModelSizeRouterTest {

    @Test
    void longContextRoutesToBigSingle() {
        var r = new ModelSizeRouter();
        var d = r.route(new TaskSignal(true, true, false, 3, 0.2));
        assertEquals(Decision.BIG_SINGLE, d.decision());
    }

    @Test
    void crossStepReasoningRoutesToBigSingle() {
        var r = new ModelSizeRouter();
        var d = r.route(new TaskSignal(true, false, true, 3, 0.2));
        assertEquals(Decision.BIG_SINGLE, d.decision());
    }

    @Test
    void decomposableSmallTaskRoutesToSmallMulti() {
        var r = new ModelSizeRouter();
        var d = r.route(new TaskSignal(true, false, false, 3, 0.3));
        assertEquals(Decision.SMALL_MULTI, d.decision());
    }

    @Test
    void tooManySubtasksRoutesToHybrid() {
        var r = new ModelSizeRouter();
        var d = r.route(new TaskSignal(true, false, false, 10, 0.3));
        assertEquals(Decision.HYBRID, d.decision());
    }

    @Test
    void nonDecomposableRoutesToBigSingle() {
        var r = new ModelSizeRouter();
        var d = r.route(new TaskSignal(false, false, false, 1, 0.1));
        assertEquals(Decision.BIG_SINGLE, d.decision());
    }

    @Test
    void budgetRatioGatePreventsSmallMulti() {
        // decomposable but expensive to fan out
        var r = new ModelSizeRouter();
        var d = r.route(new TaskSignal(true, false, false, 3, 0.9));
        // budget ratio 0.9 > threshold 0.5, so neither small-multi nor hybrid:
        // not decomposable enough — actually it IS decomposable but budget blocks.
        // Our rule says: decomposable && subtasks <= 5 && budgetRatio <= 0.5.
        // Here budgetRatio=0.9 violates the third condition; falls through to
        // the "decomposable but too many subtasks" branch (which it isn't),
        // then to BIG_SINGLE. Confirm BIG_SINGLE.
        assertEquals(Decision.BIG_SINGLE, d.decision());
    }
}
