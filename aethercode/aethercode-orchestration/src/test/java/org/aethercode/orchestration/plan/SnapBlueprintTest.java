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
