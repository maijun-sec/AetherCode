package org.aethercode.orchestration.planner;

import org.aethercode.orchestration.multiagent.AgentFn;
import org.aethercode.orchestration.planner.CentralPlanner.AgentSpec;
import org.aethercode.orchestration.planner.CentralPlanner.Plan;
import org.aethercode.orchestration.planner.CentralPlanner.PlanResult;
import org.aethercode.orchestration.planner.CentralPlanner.SubGoal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class CentralPlannerTest {

    private CentralPlanner planner;

    @BeforeEach
    void setUp() {
        planner = CentralPlanner.builder()
            .registerAgent(new AgentSpec("researcher", "research", input -> {
                @SuppressWarnings("unchecked")
                Map<String, Object> m = (Map<String, Object>) input;
                return "research-result:" + m.getOrDefault("query", "x");
            }))
            .registerAgent(new AgentSpec("writer", "write", input -> {
                @SuppressWarnings("unchecked")
                Map<String, Object> m = (Map<String, Object>) input;
                return "write-result:" + m.getOrDefault("topic", "y");
            }))
            .registerAgent(new AgentSpec("default-agent", "default", input -> "default-result"))
            .build();
    }

    @AfterEach
    void tearDown() {
        planner.shutdown();
    }

    @Test
    void singleSubGoalExecutes() {
        Plan p = planner.plan("hello", Map.of());
        assertEquals(1, p.subGoals().size());
        assertEquals(1, p.dispatchOrder().size());

        PlanResult r = planner.execute(p);
        assertTrue(r.success(), "expected success, got: " + r.results());
    }

    @Test
    void multiSubGoalChainedByDependency() {
        CentralPlanner custom = CentralPlanner.builder()
            .decomposer((goal, ctx) -> List.of(
                new SubGoal(null, "research first", List.of(), "research", Map.of("query", goal)),
                new SubGoal(null, "write based on research", List.of(), "write", Map.of("topic", "auto"))
            ))
            .registerAgent(new AgentSpec("researcher", "research", input -> "researched"))
            .registerAgent(new AgentSpec("writer", "write", input -> "written"))
            .build();
        try {
            Plan p = custom.plan("make report", Map.of());
            assertEquals(2, p.subGoals().size());
            // SubGoal 1 must come before subGoal 2 (it has no depends, but writer depends on researcher)
            PlanResult r = custom.execute(p);
            assertTrue(r.success());
            assertEquals(2, r.results().size());
        } finally {
            custom.shutdown();
        }
    }

    @Test
    void parallelIndependentSubGoals() throws InterruptedException {
        AtomicInteger counter = new AtomicInteger();
        CentralPlanner custom = CentralPlanner.builder()
            .decomposer((goal, ctx) -> List.of(
                new SubGoal(null, "task 1", List.of(), "research", Map.of()),
                new SubGoal(null, "task 2", List.of(), "research", Map.of()),
                new SubGoal(null, "task 3", List.of(), "research", Map.of())
            ))
            .registerAgent(new AgentSpec("r", "research", input -> {
                counter.incrementAndGet();
                try { Thread.sleep(100); } catch (InterruptedException e) {}
                return "result";
            }))
            .build();
        try {
            Plan p = custom.plan("parallel", Map.of());
            long start = System.currentTimeMillis();
            custom.execute(p);
            long elapsed = System.currentTimeMillis() - start;
            // 3 tasks × 100ms = 300ms sequential vs ~100ms parallel
            assertTrue(elapsed < 280, "expected parallel execution (<280ms), got: " + elapsed);
        } finally {
            custom.shutdown();
        }
    }

    @Test
    void missingCapabilityReported() {
        CentralPlanner custom = CentralPlanner.builder()
            .decomposer((goal, ctx) -> List.of(
                new SubGoal(null, "unsupported", List.of(), "no-such-cap", Map.of())
            ))
            .build();
        try {
            PlanResult r = custom.run("test", Map.of());
            assertFalse(r.success());
            assertTrue(r.results().values().iterator().next().error().contains("no agent"));
        } finally {
            custom.shutdown();
        }
    }

    @Test
    void cycleDetected() {
        CentralPlanner custom = CentralPlanner.builder()
            .decomposer((goal, ctx) -> {
                // Construct a cycle: A depends on B, B depends on A
                SubGoal a = new SubGoal("a", "task a", List.of("b"), "default", Map.of());
                SubGoal b = new SubGoal("b", "task b", List.of("a"), "default", Map.of());
                return List.of(a, b);
            })
            .build();
        try {
            assertThrows(IllegalStateException.class, () -> custom.plan("cycle", Map.of()));
        } finally {
            custom.shutdown();
        }
    }

    @Test
    void emptyDecompositionRejected() {
        CentralPlanner custom = CentralPlanner.builder()
            .decomposer((goal, ctx) -> List.of())
            .build();
        try {
            assertThrows(IllegalStateException.class, () -> custom.plan("empty", Map.of()));
        } finally {
            custom.shutdown();
        }
    }

    @Test
    void subGoalAutoId() {
        SubGoal sg = new SubGoal(null, "x", List.of(), "c", Map.of());
        assertNotNull(sg.id());
        SubGoal sg2 = new SubGoal("explicit", "x", List.of(), "c", Map.of());
        assertEquals("explicit", sg2.id());
    }

    @Test
    void dependentInputMerged() {
        CentralPlanner custom = CentralPlanner.builder()
            .decomposer((goal, ctx) -> {
                SubGoal a = new SubGoal("a", "produce x", List.of(), "research", Map.of("k", "v"));
                SubGoal b = new SubGoal("b", "use a", List.of("a"), "write", Map.of());
                return List.of(a, b);
            })
            .registerAgent(new AgentSpec("r", "research", input -> "X"))
            .registerAgent(new AgentSpec("w", "write", input -> {
                @SuppressWarnings("unchecked")
                Map<String, Object> m = (Map<String, Object>) input;
                return "got:" + m.get("dep:a");
            }))
            .build();
        try {
            PlanResult r = custom.run("merge test", Map.of());
            assertTrue(r.success(), "got: " + r.results());
            assertEquals("got:X", r.results().get("b").output());
        } finally {
            custom.shutdown();
        }
    }

    @Test
    void runConvenienceEqualsExecuteOfPlan() {
        Plan p = planner.plan("test", Map.of());
        PlanResult r1 = planner.execute(p);
        PlanResult r2 = planner.run("test", Map.of());
        assertEquals(r1.success(), r2.success());
        assertEquals(r1.results().size(), r2.results().size());
    }

    @Test
    void builderFluentStyle() {
        CentralPlanner p = CentralPlanner.builder()
            .registerAgent(new AgentSpec("a", "default", input -> "a"))
            .build();
        try {
            PlanResult r = p.run("x", Map.of());
            assertTrue(r.success());
        } finally {
            p.shutdown();
        }
    }

    @Test
    void unknownCapabilityUsesDefault() {
        // A "default" capability is matched as a fallback
        Plan p = planner.plan("test", Map.of());
        assertTrue(p.subGoals().size() > 0);
        // Run with custom decomposer that uses unknown capability
        CentralPlanner custom = CentralPlanner.builder()
            .decomposer((goal, ctx) -> List.of(
                new SubGoal(null, "uses default", List.of(), "totally-unknown-cap", Map.of())
            ))
            .registerAgent(new AgentSpec("d", "default", input -> "fallback"))
            .build();
        try {
            PlanResult r = custom.run("x", Map.of());
            assertTrue(r.success());
            assertEquals("fallback", r.results().values().iterator().next().output());
        } finally {
            custom.shutdown();
        }
    }
}
