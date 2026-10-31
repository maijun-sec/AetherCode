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
