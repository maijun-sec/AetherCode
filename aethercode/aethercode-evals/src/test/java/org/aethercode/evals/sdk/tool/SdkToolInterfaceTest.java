package org.aethercode.evals.sdk.tool;

import org.aethercode.core.tool.Tool;
import org.aethercode.tools.StandardTools;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * R-sdk-5: AetherCode Tool Interface conformance.
 *
 * <p>Companion to {@code ToolUseCapabilityTest} (R-eval-3). The capability
 * suite proves the tool-use design (intent, function selection, param
 * mapping, multi-tool orchestration, sandbox) on a self-contained model.
 * This suite proves the actual {@code StandardTools} factory that the
 * AetherCode TUI tool palette, the agent call loop, and the IDE plugin
 * pull from.</p>
 *
 * <p>Scope:</p>
 * <ul>
 *   <li>Factory integrity — all 17 standard tools load, names unique,
 *       none null.</li>
 *   <li>Tool naming — matches the convention used by
 *       {@code aethercode.permission.PermissionReasoner.extractPrompt}
 *       ("bash" / "file_read" / "web_fetch" / etc.).</li>
 *   <li>Tool classification — file / network / shell / task buckets
 *       (the TUI tool palette groups by these).</li>
 * </ul>
 */
class SdkToolInterfaceTest {

    @Test
    void standardToolsAllLoadNonNull() {
        List<Tool> all = StandardTools.all();
        assertFalse(all.isEmpty(), "StandardTools.all() should not be empty");
        for (Tool t : all) {
            assertNotNull(t, "no null tool in factory");
            assertNotNull(t.name(), "tool name is non-null");
            assertFalse(t.name().isBlank(), "tool name is non-blank");
        }
    }

    @Test
    void standardToolsNamesAreUnique() {
        // Duplicate names would mean two tools claim the same wire
        // identifier, which the permission reasoner / classifier
        // can't disambiguate.
        List<Tool> all = StandardTools.all();
        Set<String> names = new HashSet<>();
        for (Tool t : all) {
            assertTrue(names.add(t.name()),
                    "duplicate tool name: " + t.name());
        }
    }

    @Test
    void standardToolsContainsExpectedCoreSet() {
        // The TUI tool palette expects the canonical names; if any
        // of these disappear, the front-end breaks.
        Set<String> names = toolNames();
        for (String expected : List.of(
                "file_read", "file_write", "file_edit",
                "glob", "grep", "bash",
                "todo_write", "sub_todo_write",
                "spawn_agent", "subagent_status", "subagent_list",
                "web_fetch", "web_search", "google_scholar", "arxiv_fetch",
                "notebook_edit", "ask_user_question"
        )) {
            assertTrue(names.contains(expected),
                    "missing canonical tool: " + expected);
        }
    }

    @Test
    void standardToolsFileToolsAreRecognizable() {
        // The permission reasoner's extractPrompt branches on these
        // tool names; we verify they're not accidentally renamed.
        Set<String> names = toolNames();
        assertTrue(names.contains("file_read"));
        assertTrue(names.contains("file_edit"));
        assertTrue(names.contains("file_write"));
    }

    @Test
    void standardToolsNetworkToolsAreRecognizable() {
        // web_fetch / web_search / google_scholar / arxiv_fetch are
        // the network tool surface; PermissionReasoner routes
        // "web_fetch" through URL guard.
        Set<String> names = toolNames();
        assertTrue(names.contains("web_fetch"));
        assertTrue(names.contains("web_search"));
        assertTrue(names.contains("google_scholar"));
        assertTrue(names.contains("arxiv_fetch"));
    }

    @Test
    void standardToolsShellToolIsPresent() {
        // The bash tool is the most dangerous surface; the permission
        // reasoner routes its input through CommandAllowlist.
        Set<String> names = toolNames();
        assertTrue(names.contains("bash"), "bash tool must be present");
    }

    @Test
    void standardToolsTaskSubagentToolsArePresent() {
        // The background subagent introspection trio is the
        // difference between "fire-and-forget" and "fire-and-poll"
        // — without these the model can't see if its backgrounded
        // jobs are still running.
        Set<String> names = toolNames();
        assertTrue(names.contains("spawn_agent"));
        assertTrue(names.contains("subagent_status"));
        assertTrue(names.contains("subagent_list"));
    }

    @Test
    void standardToolsSizeIsStable() {
        // If a new tool is added intentionally, this assertion is
        // the reminder to update the TUI palette + the permission
        // classifier. 17 is the historical count; bump on purpose,
        // not by accident.
        assertEquals(17, StandardTools.all().size(),
                "StandardTools count drifted — review TUI palette and classifiers");
    }

    /* ---------------- helpers ---------------- */

    private static Set<String> toolNames() {
        Set<String> out = new HashSet<>();
        for (Tool t : StandardTools.all()) out.add(t.name());
        return out;
    }
}
