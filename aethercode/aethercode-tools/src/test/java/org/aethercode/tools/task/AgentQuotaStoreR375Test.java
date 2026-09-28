package org.aethercode.tools.task;

import org.aethercode.tasks.supervisor.SupervisorHome;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * R375.2: tests for {@link AgentQuotaStore} —
 * the persistent store that backs
 * {@code subagentSetQuota} so the per-role concurrency
 * override survives daemon restarts.
 *
 * <p>Scope:
 * <ul>
 *   <li>save + load round-trip: every override survives.</li>
 *   <li>default-quota entries are NOT written to the
 *       file (1 = implicit, the file stays clean).</li>
 *   <li>missing file → empty map (no crash).</li>
 *   <li>malformed file → empty map + the file can be
 *       overwritten by the next save.</li>
 *   <li>atomic write: the .tmp file is cleaned up after
 *       a successful save.</li>
 * </ul>
 *
 * <p>All tests use a {@link TempDir} redirected to
 * {@link SubagentHome} via the test-only {@code override()}
 * hook so the production {@code ~/.aethercode/agents.yaml}
 * is never touched.
 */
class AgentQuotaStoreR375Test {

    @TempDir Path tempHome;

    private Path yamlPath;
    private SubagentRegistry reg;
    private AgentQuotaStore store;

    @BeforeEach
    void wire() {
        // Redirect the global "aethercode home" to the
        // temp dir. Every test gets a fresh, empty home.
        SupervisorHome.override(tempHome);
        yamlPath = tempHome.resolve("agents.yaml");
        // Reset the registry's quota table so a stale
        // entry from a prior test doesn't leak in.
        SubagentRegistry.instance().resetConcurrencyLimiter();
        reg = SubagentRegistry.instance();
        store = new AgentQuotaStore(yamlPath, reg);
    }

    @AfterEach
    void cleanup() {
        SubagentRegistry.instance().resetConcurrencyLimiter();
        SupervisorHome.clearOverride();
    }

    @Test
    void save_thenLoad_roundTripsOverrides() {
        Map<String, Integer> overrides = new LinkedHashMap<>();
        overrides.put("pm", 3);
        overrides.put("coder", 2);
        store.save(overrides);

        // The file should exist now.
        assertTrue(Files.exists(yamlPath), "save should create the file");

        // Load and check.
        Map<String, Integer> loaded = store.load();
        assertEquals(2, loaded.size());
        assertEquals(3, loaded.get("pm"));
        assertEquals(2, loaded.get("coder"));
        // And the in-memory registry has them too.
        assertEquals(3, reg.quotaFor("pm"));
        assertEquals(2, reg.quotaFor("coder"));
    }

    @Test
    void save_omitsDefaultQuotaEntries() {
        // pm = 3 (override), coder = 1 (default — should
        // NOT be in the file), tester = 5 (override).
        Map<String, Integer> overrides = new LinkedHashMap<>();
        overrides.put("pm", 3);
        overrides.put("coder", SubagentConcurrencyLimiter.DEFAULT_QUOTA);
        overrides.put("tester", 5);
        store.save(overrides);

        // Read the raw file content. The "coder" entry
        // must NOT appear.
        String content = readFile(yamlPath);
        assertTrue(content.contains("pm"), "pm should be in the file");
        assertTrue(content.contains("tester"), "tester should be in the file");
        assertFalse(content.contains("coder"),
                "default-quota entries must be omitted from the file");
    }

    @Test
    void load_missingFile_returnsEmpty() {
        // yamlPath doesn't exist yet. load() must return
        // empty without throwing.
        assertFalse(Files.exists(yamlPath));
        Map<String, Integer> loaded = store.load();
        assertTrue(loaded.isEmpty(),
                "missing file should yield empty map (all defaults)");
    }

    @Test
    void load_malformedFile_returnsEmptyAndLogsWarning() {
        // Write garbage.
        writeFile(yamlPath, "this is :: not :: valid: yaml: [\n");

        // Should not throw — bad input is a warning, not
        // an error. The empty map lets the next save()
        // rewrite the file cleanly.
        Map<String, Integer> loaded = store.load();
        assertTrue(loaded.isEmpty(),
                "malformed file should yield empty map");
    }

    @Test
    void load_emptyRolesSection_returnsEmpty() {
        // File has the right shape but no agents block.
        writeFile(yamlPath, "agents: {}\n");

        Map<String, Integer> loaded = store.load();
        assertTrue(loaded.isEmpty());
    }

    @Test
    void load_appliedViaSetQuotaOnRegistry() {
        // Write the file directly, then load. The
        // registry must end up with the right quotas
        // applied (not just returned from load()).
        writeFile(yamlPath, "agents:\n  pm:\n    quota: 7\n  coder:\n    quota: 2\n");

        Map<String, Integer> loaded = store.load();
        assertEquals(2, loaded.size());
        assertEquals(7, reg.quotaFor("pm"),
                "registry should pick up the persisted quota");
        assertEquals(2, reg.quotaFor("coder"));
    }

    @Test
    void save_overwritesPreviousFileCleanly() {
        // First save has 2 entries. Second save has 1
        // different entry. The file should now contain
        // only the second's contents (no leftover
        // entries from the first save).
        Map<String, Integer> first = new LinkedHashMap<>();
        first.put("pm", 3);
        first.put("coder", 2);
        store.save(first);

        Map<String, Integer> second = new LinkedHashMap<>();
        second.put("tester", 5);
        store.save(second);

        // Read raw file — pm/coder must NOT be present.
        String content = readFile(yamlPath);
        assertFalse(content.contains("pm:"),
                "second save should remove pm from the file");
        assertFalse(content.contains("coder:"),
                "second save should remove coder from the file");
        assertTrue(content.contains("tester"));

        // And load reflects the new state.
        Map<String, Integer> loaded = store.load();
        assertEquals(1, loaded.size());
        assertEquals(5, loaded.get("tester"));
    }

    @Test
    void save_emptyMap_clearsTheFile() {
        // First put something in.
        store.save(Map.of("pm", 5));
        assertTrue(Files.exists(yamlPath));

        // Now save empty — the file should be rewritten
        // without the "agents" key, effectively clearing
        // all overrides. Subsequent load returns empty.
        store.save(Map.of());

        Map<String, Integer> loaded = store.load();
        assertTrue(loaded.isEmpty(),
                "saving empty map should clear all overrides");
    }

    @Test
    void save_doesNotLeaveTempFileOnSuccess() {
        store.save(Map.of("pm", 3));

        // The .tmp sibling must NOT remain after a
        // successful save. A leftover .tmp is a sign
        // of a bug in the atomic-rename sequence.
        Path tmp = yamlPath.resolveSibling(
                yamlPath.getFileName().toString() + ".tmp");
        assertFalse(Files.exists(tmp),
                "no .tmp file should remain after a successful save");
    }

    @Test
    void save_createsParentDirIfMissing() {
        // yamlPath points at a path whose parent doesn't
        // exist yet. save() must create it.
        Path nested = tempHome.resolve("aethercode").resolve("agents.yaml");
        AgentQuotaStore nestedStore = new AgentQuotaStore(nested, reg);
        nestedStore.save(Map.of("pm", 4));

        assertTrue(Files.exists(nested));
        Map<String, Integer> loaded = nestedStore.load();
        assertEquals(4, loaded.get("pm"));
    }

    // ----- helpers -----

    private static void writeFile(Path p, String content) {
        try {
            Files.createDirectories(p.getParent());
            Files.writeString(p, content, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private static String readFile(Path p) {
        try {
            return Files.readString(p, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}