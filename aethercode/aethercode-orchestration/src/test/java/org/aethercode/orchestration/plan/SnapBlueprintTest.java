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

package org.aethercode.orchestration.plan;

import org.aethercode.orchestration.plan.SnapBlueprint.Assertion;
import org.aethercode.orchestration.plan.SnapBlueprint.Blueprint;
import org.aethercode.orchestration.plan.SnapBlueprint.Step;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class SnapBlueprintTest {

    @Test
    void consistentStateVerifies() {
        var b = new Blueprint(List.of(
            new Step("s1", "load", List.of(), List.of(new Assertion("loaded", "true"))),
            new Step("s2", "compute", List.of(new Assertion("loaded", "true")), List.of(new Assertion("computed", "true"))),
            new Step("s3", "save", List.of(new Assertion("computed", "true")), List.of())
        ));
        Map<String, String> state = new HashMap<>(Map.of("loaded", "true", "computed", "true"));
        var r = new SnapBlueprint().verify(b, state);
        assertTrue(r.ok(), "expected ok; failed=" + r.failedAssertions());
    }

    @Test
    void missingPreconditionFails() {
        var b = new Blueprint(List.of(
            new Step("s1", "load", List.of(), List.of(new Assertion("loaded", "true"))),
            new Step("s2", "compute", List.of(new Assertion("loaded", "true")), List.of())
        ));
        // s1 has no preconditions; s2 expects "loaded"=true but state is empty
        var r = new SnapBlueprint().verify(b, new HashMap<>());
        assertFalse(r.ok());
        assertTrue(r.failedAssertions().stream().anyMatch(s -> s.contains("s2")));
    }

    @Test
    void stepIdsReturnsAllSteps() {
        var b = new Blueprint(List.of(
            new Step("a", "A", List.of(), List.of()),
            new Step("b", "B", List.of(), List.of()),
            new Step("c", "C", List.of(), List.of())
        ));
        var ids = new SnapBlueprint().stepIds(b);
        assertEquals(List.of("a", "b", "c"), ids);
    }

    @Test
    void nullBlueprintReturnsEmpty() {
        assertTrue(new SnapBlueprint().stepIds(null).isEmpty());
    }
}
