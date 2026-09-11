package org.aethercode.evals.evals;

import org.aethercode.evals.evals.Tau3Subset.SubsetTask;
import org.aethercode.evals.evals.Tau3Subset.Tier;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link Tau3Subset} — 30 curated tau3-bench tasks stratified by
 * Opus 4.8 pass rate.
 *
 * <p>R-radar-2: bring {@code aethercode-evals} test count from 0 to 50+.
 */
class Tau3SubsetTest {

    @Test
    void datasetConstantIsTau3Bench() {
        assertEquals("sierra-research/tau3-bench", Tau3Subset.DATASET);
    }

    @Test
    void totalTaskCountIsExactly30() {
        assertEquals(30, Tau3Subset.tasks().size(),
                "the curated subset must stay at 30 tasks (CI budget)");
    }

    @Test
    void allTaskIdsAreUnique() {
        Set<String> ids = new HashSet<>();
        for (SubsetTask t : Tau3Subset.tasks()) {
            assertTrue(ids.add(t.taskId()),
                    "duplicate task_id: " + t.taskId());
        }
        assertEquals(30, ids.size());
    }

    @Test
    void allTaskIdsStartWithTau3Prefix() {
        for (SubsetTask t : Tau3Subset.tasks()) {
            assertTrue(t.taskId().startsWith("tau3-"),
                    "task_id must start with 'tau3-': " + t.taskId());
        }
    }

    @Test
    void allJustificationsAreNonBlank() {
        for (SubsetTask t : Tau3Subset.tasks()) {
            assertNotNull(t.justification());
            assertFalse(t.justification().strip().isEmpty(),
                    "justification must be non-empty for " + t.taskId());
        }
    }

    @Test
    void difficultyDistributionEasyMediumHard() {
        // Hardcoded against the original paper. If you change TASKS, update
        // these numbers and the test will tell you where the distribution moved.
        // 30 tasks = 2 EASY + 7 MEDIUM + 21 HARD.
        assertEquals(2, Tau3Subset.tasksByTier(Tier.EASY).size());
        assertEquals(7, Tau3Subset.tasksByTier(Tier.MEDIUM).size());
        assertEquals(21, Tau3Subset.tasksByTier(Tier.HARD).size());
    }

    @Test
    void tasksByTierFiltersCorrectly() {
        for (SubsetTask t : Tau3Subset.tasksByTier(Tier.EASY)) {
            assertSame(Tier.EASY, t.tier());
        }
        for (SubsetTask t : Tau3Subset.tasksByTier(Tier.MEDIUM)) {
            assertSame(Tier.MEDIUM, t.tier());
        }
        for (SubsetTask t : Tau3Subset.tasksByTier(Tier.HARD)) {
            assertSame(Tier.HARD, t.tier());
        }
    }

    @Test
    void tierFromLabelRoundTrips() {
        for (Tier t : Tier.values()) {
            assertSame(t, Tier.fromLabel(t.label()));
        }
    }

    @Test
    void tierFromLabelRejectsUnknown() {
        assertThrows(IllegalArgumentException.class,
                () -> Tier.fromLabel("unknown"));
    }

    @Test
    void subsetTaskRejectsMissingPrefix() {
        assertThrows(IllegalArgumentException.class,
                () -> new SubsetTask("banking-task-001", Tier.EASY, "x"));
    }

    @Test
    void subsetTaskRejectsBlankJustification() {
        assertThrows(IllegalArgumentException.class,
                () -> new SubsetTask("tau3-banking-task-001", Tier.EASY, "  "));
    }

    @Test
    void subsetTaskRejectsNullJustification() {
        assertThrows(IllegalArgumentException.class,
                () -> new SubsetTask("tau3-banking-task-001", Tier.EASY, null));
    }

    @Test
    void includeTasksHas30SpaceSeparatedDatasetPrefixedEntries() {
        String include = Tau3Subset.includeTasks();
        assertNotNull(include);
        String[] tokens = include.split(" ");
        assertEquals(30, tokens.length, "include_tasks must enumerate all 30 tasks");
        for (String tok : tokens) {
            assertTrue(tok.startsWith(Tau3Subset.DATASET + "__"),
                    "each include_tasks entry must be prefixed with DATASET__: " + tok);
            assertTrue(tok.substring(Tau3Subset.DATASET.length() + 2).startsWith("tau3-"),
                    "task_id after prefix must start with 'tau3-': " + tok);
        }
    }

    @Test
    void includeTasksAndTasksAgreeOnIdSet() {
        Set<String> fromList = new HashSet<>();
        for (SubsetTask t : Tau3Subset.tasks()) {
            fromList.add(t.taskId());
        }
        Set<String> fromInclude = new HashSet<>();
        for (String tok : Tau3Subset.includeTasks().split(" ")) {
            fromInclude.add(tok.substring(Tau3Subset.DATASET.length() + 2));
        }
        assertEquals(fromList, fromInclude,
                "include_tasks() must enumerate exactly the same ids as tasks()");
    }

    @Test
    void bankingAndTelecomAreBothPresent() {
        // The 30-task mix is telecom + banking. A future re-tier that drops
        // either domain should fail this test loud.
        long banking = Tau3Subset.tasks().stream()
                .filter(t -> t.taskId().contains("banking"))
                .count();
        long telecom = Tau3Subset.tasks().stream()
                .filter(t -> t.taskId().contains("telecom"))
                .count();
        assertTrue(banking > 0, "banking subset dropped");
        assertTrue(telecom > 0, "telecom subset dropped");
    }

    @Test
    void tasksListIsImmutable() {
        List<SubsetTask> tasks = Tau3Subset.tasks();
        assertThrows(UnsupportedOperationException.class,
                () -> tasks.add(new SubsetTask("tau3-x", Tier.EASY, "x")));
    }
}
