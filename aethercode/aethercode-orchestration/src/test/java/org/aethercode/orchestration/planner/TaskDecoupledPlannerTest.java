package org.aethercode.orchestration.planner;

import org.aethercode.orchestration.planner.TaskDecoupledPlanner.Planner;
import org.aethercode.orchestration.planner.TaskDecoupledPlanner.RevisionPolicy;
import org.aethercode.orchestration.planner.TaskDecoupledPlanner.SubTask;
import org.aethercode.orchestration.planner.TaskDecoupledPlanner.SubTaskExecution;
import org.aethercode.orchestration.planner.TaskDecoupledPlanner.Supervisor;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class TaskDecoupledPlannerTest {

    @Test
    void singleSubTaskExecutes() {
        TaskDecoupledPlanner p = TaskDecoupledPlanner.builder()
            .supervisor((goal, ctx) -> List.of(new SubTask(null, goal, List.of())))
            .planner((task, prereq, goal) -> "plan:" + task.description())
            .executor((task, plan) -> "result:" + plan)
            .build();
        List<SubTaskExecution> log = p.run("hello", Map.of());
        assertEquals(1, log.size());
        assertTrue(log.get(0).success());
        assertEquals("plan:hello", log.get(0).plan());
    }

    @Test
    void multiSubTaskTopologically() {
        Supervisor sup = (goal, ctx) -> List.of(
            new SubTask("a", "first", List.of()),
            new SubTask("b", "second", List.of("a")),
            new SubTask("c", "third", List.of("b"))
        );
        TaskDecoupledPlanner p = TaskDecoupledPlanner.builder()
            .supervisor(sup)
            .planner((task, prereq, goal) -> {
                StringBuilder sb = new StringBuilder("plan:").append(task.id());
                for (var e : prereq.entrySet()) sb.append("|").append(e.getKey()).append("=").append(e.getValue());
                return sb.toString();
            })
            .executor((task, plan) -> "exec:" + task.id())
            .build();
        List<SubTaskExecution> log = p.run("test", Map.of());
        assertEquals(3, log.size());
        // Order: a, b, c
        assertEquals("a", log.get(0).subTaskId());
        assertEquals("b", log.get(1).subTaskId());
        assertEquals("c", log.get(2).subTaskId());
    }

    @Test
    void parallelReadySubTasksExecuteInOrder() {
        Supervisor sup = (goal, ctx) -> List.of(
            new SubTask("a", "first", List.of()),
            new SubTask("b", "parallel", List.of())
        );
        TaskDecoupledPlanner p = TaskDecoupledPlanner.builder()
            .supervisor(sup)
            .planner((task, prereq, goal) -> "p:" + task.id())
            .executor((task, plan) -> "e:" + task.id())
            .build();
        List<SubTaskExecution> log = p.run("x", Map.of());
        assertEquals(2, log.size());
    }

    @Test
    void emptyDagRejected() {
        TaskDecoupledPlanner p = TaskDecoupledPlanner.builder()
            .supervisor((goal, ctx) -> List.of())
            .planner((task, prereq, goal) -> "")
            .executor((task, plan) -> "")
            .build();
        assertThrows(IllegalStateException.class, () -> p.run("x", Map.of()));
    }

    @Test
    void nullGoalRejected() {
        TaskDecoupledPlanner p = TaskDecoupledPlanner.builder()
            .planner((t, p2, g) -> "")
            .executor((t, p2) -> "")
            .build();
        assertThrows(NullPointerException.class, () -> p.run(null, Map.of()));
    }

    @Test
    void scopedContextOnlyIncludesPrerequisites() {
        Supervisor sup = (goal, ctx) -> List.of(
            new SubTask("a", "first", List.of()),
            new SubTask("b", "second", List.of("a"))
        );
        Map<String, Integer> callCounts = new HashMap<>();
        Planner scopedPlanner = (task, prereq, goal) -> {
            callCounts.merge(task.id(), 1, Integer::sum);
            // 'b' should only see 'a' in prereq, not other tasks
            if ("b".equals(task.id())) {
                assertEquals(1, prereq.size(), "b should see only 1 prereq");
                assertNotNull(prereq.get("a"));
            }
            return "plan:" + task.id();
        };
        TaskDecoupledPlanner p = TaskDecoupledPlanner.builder()
            .supervisor(sup)
            .planner(scopedPlanner)
            .executor((task, plan) -> "result:" + task.id())
            .build();
        p.run("test", Map.of());
        assertEquals(1, callCounts.get("a"));
        assertEquals(1, callCounts.get("b"));
    }

    @Test
    void revisionPolicyCanAddSubTask() {
        Supervisor sup = (goal, ctx) -> List.of(
            new SubTask("a", "first", List.of())
        );
        RevisionPolicy addB = (dag, last) -> {
            if (last.subTaskId().equals("a") && last.result().contains("trigger")) {
                List<SubTask> revised = new ArrayList<>(dag);
                revised.add(new SubTask("b", "added", List.of("a")));
                return revised;
            }
            return dag;
        };
        TaskDecoupledPlanner p = TaskDecoupledPlanner.builder()
            .supervisor(sup)
            .planner((task, prereq, goal) -> "plan:" + task.id())
            .executor((task, plan) -> "trigger") // triggers b addition
            .revisionPolicy(addB)
            .build();
        List<SubTaskExecution> log = p.run("x", Map.of());
        assertEquals(2, log.size());
        assertEquals("a", log.get(0).subTaskId());
        assertEquals("b", log.get(1).subTaskId());
    }

    @Test
    void noRevisionPolicyKeepsDagUnchanged() {
        RevisionPolicy identity = (dag, last) -> dag;
        assertNotNull(identity);
        assertEquals(0, identity.revise(List.of(), null).size());
    }

    @Test
    void subTaskAutoIdWhenBlank() {
        SubTask s = new SubTask(null, "x", List.of());
        assertNotNull(s.id());
        SubTask s2 = new SubTask("explicit", "x", List.of());
        assertEquals("explicit", s2.id());
    }

    @Test
    void subTaskDependsOnCopied() {
        List<String> deps = new ArrayList<>();
        deps.add("a");
        SubTask s = new SubTask("t", "x", deps);
        deps.add("b");
        assertEquals(1, s.dependsOn().size(), "dependsOn should be immutable copy");
    }

    @Test
    void executeWithPrereqOutputInPlan() {
        Supervisor sup = (goal, ctx) -> List.of(
            new SubTask("a", "first", List.of()),
            new SubTask("b", "second", List.of("a"))
        );
        TaskDecoupledPlanner p = TaskDecoupledPlanner.builder()
            .supervisor(sup)
            .planner((task, prereq, goal) -> {
                if ("b".equals(task.id())) {
                    assertTrue(prereq.containsKey("a"));
                    assertTrue(prereq.get("a").contains("result-of-a"));
                }
                return "plan:" + task.id();
            })
            .executor((task, plan) -> "result-of-" + task.id())
            .build();
        List<SubTaskExecution> log = p.run("x", Map.of());
        assertEquals(2, log.size());
    }
}
