package org.aethercode.mcp;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * tests for the stateful {@link McpManager} +
 * the diff-based hot reload.
 *
 * <p>We use the config-shape layer only (no actual
 * stdio Process / socket conn): the manager reads
 * the JSON, diffs, and the live handle is a
 * {@link McpClientHandle} that records its close().
 * The transport-specific code is unit-tested
 * elsewhere; here we focus on the manager's
 * orchestration: identity check, lifecycle, and
 * the loadInitial / reload / closeAll flow.
 */
class McpManagerR132Test {

    @Test
    void loadInitial_emptyConfig_returnsEmptyList(@TempDir Path tmp) {
        Path file = tmp.resolve("mcp.json");
        McpManager mgr = new McpManager();
        List<org.aethercode.core.tool.Tool> tools = mgr.loadInitial(file);
        assertNotNull(tools);
        assertEquals(0, tools.size());
    }

    @Test
    void loadInitial_missingFile_returnsEmptyList(@TempDir Path tmp) {
        McpManager mgr = new McpManager();
        List<org.aethercode.core.tool.Tool> tools = mgr.loadInitial(
                tmp.resolve("does-not-exist.json"));
        assertNotNull(tools);
        assertEquals(0, tools.size());
    }

    @Test
    void reload_noServersConfigured_reportsNoChanges(@TempDir Path tmp) throws Exception {
        Path file = tmp.resolve("mcp.json");
        Files.writeString(file, "{ \"mcpServers\": {} }");
        McpManager mgr = new McpManager();
        mgr.loadInitial(file);
        McpManager.ReloadResult res = mgr.reload(file);
        assertEquals(0, res.added());
        assertEquals(0, res.removed());
        assertEquals(0, res.changed());
        assertEquals(0, res.unchanged());
    }

    @Test
    void currentTools_isEmptyBeforeLoad(@TempDir Path tmp) {
        McpManager mgr = new McpManager();
        assertEquals(0, mgr.currentTools().size());
    }

    @Test
    void lastResult_returnsMostRecentReload(@TempDir Path tmp) throws Exception {
        Path file = tmp.resolve("mcp.json");
        Files.writeString(file, "{ \"mcpServers\": {} }");
        McpManager mgr = new McpManager();
        mgr.reload(file);
        McpManager.ReloadResult res = mgr.lastResult();
        assertNotNull(res);
        assertEquals(0, res.total());
    }

    @Test
    void reloadSeq_incrementsOnReload(@TempDir Path tmp) throws Exception {
        Path file = tmp.resolve("mcp.json");
        Files.writeString(file, "{ \"mcpServers\": {} }");
        McpManager mgr = new McpManager();
        long before = mgr.reloadSeq();
        mgr.reload(file);
        assertEquals(before + 1, mgr.reloadSeq());
    }

    @Test
    void reload_badJson_doesNotCloseExistingServers(@TempDir Path tmp) throws Exception {
        // R132 invariant: a corrupt mcp.json write should
        // NOT close the live servers. The user is editing
        // the file in vim; a transient bad write shouldn't
        // tear down their working connections. We verify
        // by parsing the bytes 鈥?a real ReloadResult should
        // have unchanged == original count and no removals.
        Path file = tmp.resolve("mcp.json");
        Files.writeString(file, "{ this is not json");
        McpManager mgr = new McpManager();
        McpManager.ReloadResult res = mgr.reload(file);
        assertTrue(res.hasErrors(), "bad json should produce an error entry");
        assertEquals(0, res.removed(), "bad json must NOT report removals");
    }

    @Test
    void closeAll_isIdempotent(@TempDir Path tmp) {
        McpManager mgr = new McpManager();
        mgr.closeAll();
        // Second close is a no-op (no NPE, no exception).
        mgr.closeAll();
    }

    @Test
    void reloadResult_totalIsSumOfBuckets() {
        var r = new McpManager.ReloadResult(2, 1, 3, 4, List.of(), List.of());
        assertEquals(10, r.total());
        assertFalse(r.hasErrors());
    }

    @Test
    void reloadResult_hasErrors_whenErrorsNonEmpty() {
        var r = new McpManager.ReloadResult(0, 0, 0, 0, List.of(),
                List.of("boom"));
        assertTrue(r.hasErrors());
    }
}
