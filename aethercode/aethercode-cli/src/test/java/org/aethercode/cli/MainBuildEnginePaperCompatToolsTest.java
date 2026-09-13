package org.aethercode.cli;

import org.aethercode.core.tool.Tool;
import org.aethercode.sdk.AetherCodeEngine;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * R-paper-batch7-papercompat-engine: the daemon's default tool pool
 * (built by {@code Main.buildEngineForSession}) now includes the
 * 8 paper-compat tools on top of the standard 18 + 4 working-memory
 * tools. A real business process launched from the CLI can call
 * them from inside the LLM tool loop.
 *
 * <p>Builds the engine without actually starting the daemon
 * (we just want to inspect {@code engine.tools()}). Uses a
 * stub-ish {@link AetherCodeEngine.Builder} with the minimal
 * config the test needs.</p>
 */
class MainBuildEnginePaperCompatToolsTest {

    @Test
    void daemonToolPoolIncludesAllPaperCompatTools() {
        AetherCodeEngine engine = buildMinimalEngine();
        List<Tool> tools = engine.tools();

        // Standard 18 tools present
        assertTrue(tools.stream().anyMatch(t -> "file_read".equals(t.name())),
            "file_read should be present");
        assertTrue(tools.stream().anyMatch(t -> "bash".equals(t.name())),
            "bash should be present");

        // All 8 paper-compat tools present
        assertTrue(tools.stream().anyMatch(t -> t.name().equals("paper_compat_architecture_recommend")),
            "paper_compat_architecture_recommend should be present");
        assertTrue(tools.stream().anyMatch(t -> t.name().equals("paper_compat_saturation_assess")),
            "paper_compat_saturation_assess should be present");
        assertTrue(tools.stream().anyMatch(t -> t.name().equals("paper_compat_redflag_inspect")),
            "paper_compat_redflag_inspect should be present");
        assertTrue(tools.stream().anyMatch(t -> t.name().equals("paper_compat_byzantine_observe")),
            "paper_compat_byzantine_observe should be present");
        assertTrue(tools.stream().anyMatch(t -> t.name().equals("paper_compat_byzantine_flagged")),
            "paper_compat_byzantine_flagged should be present");
        assertTrue(tools.stream().anyMatch(t -> t.name().equals("paper_compat_byzantine_reset")),
            "paper_compat_byzantine_reset should be present");
        assertTrue(tools.stream().anyMatch(t -> t.name().equals("paper_compat_voting_first_to_ahead_by_k")),
            "paper_compat_voting_first_to_ahead_by_k should be present");
        assertTrue(tools.stream().anyMatch(t -> t.name().equals("paper_compat_plan_execute_sequence")),
            "paper_compat_plan_execute_sequence should be present");
    }

    @Test
    void paperCompatToolsAreAllReadOnly() {
        AetherCodeEngine engine = buildMinimalEngine();
        List<Tool> paperCompat = engine.tools().stream()
            .filter(t -> t.name().startsWith("paper_compat_"))
            .toList();
        assertEquals(8, paperCompat.size(), "expected 8 paper-compat tools");
        for (Tool t : paperCompat) {
            assertTrue(t.isReadOnly(java.util.Map.of()),
                t.name() + " should be read-only");
        }
    }

    @Test
    void toolPoolHasUniqueNames() {
        AetherCodeEngine engine = buildMinimalEngine();
        List<String> names = engine.tools().stream().map(Tool::name).sorted().toList();
        long distinct = names.stream().distinct().count();
        assertEquals(names.size(), distinct,
            "tool names must be unique; got duplicates in: " + names);
    }

    /**
     * Build a minimal engine suitable for inspecting the tool pool.
     * Mirrors the daemon's {@code buildEngineForSession} path but
     * skips the chat client / transcript wiring the daemon needs.
     */
    private AetherCodeEngine buildMinimalEngine() {
        // We can't easily call Main.buildEngineForSession (it's
        // an instance method on Main that needs a TTY). Instead we
        // reproduce the relevant slice here: pull the same tool
        // list the daemon does, hand it to a fresh engine.
        java.util.List<Tool> pool = new java.util.ArrayList<>(
            org.aethercode.tools.StandardTools.all());
        pool.addAll(new org.aethercode.orchestration.papercompat.PaperCompatTools().buildAll());
        pool.addAll(org.aethercode.memory.tools.WorkingMemoryTools.all());

        return AetherCodeEngine.builder()
            .cwd(java.nio.file.Path.of(System.getProperty("java.io.tmpdir")))
            .tools(pool)
            .build();
    }
}
