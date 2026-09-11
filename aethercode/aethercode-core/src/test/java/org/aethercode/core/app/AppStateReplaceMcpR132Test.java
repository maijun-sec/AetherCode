package org.aethercode.core.app;

import org.aethercode.core.tool.Tool;
import org.aethercode.core.permission.PermissionResult;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * tests for {@link AppState#replaceMcpTools(List)}.
 *
 * <p>The contract is: remove the previously-installed MCP
 * tools (by {@code "mcp:"} name prefix), install the new
 * ones, leave the engine's built-in tools untouched. The
 * swap is atomic 鈥?readers see either the old or the new
 * set, never a half-state.
 */
class AppStateReplaceMcpR132Test {

    /** Minimal Tool stub 鈥?only {@code name()} is read by
     *  the swap. We don't bother wiring call() because
     *  none of the tests invoke the tool. */
    private static Tool stubTool(String name) {
        return new Tool() {
            @Override public String name() { return name; }
            @Override public String description() { return "stub:" + name; }
            @Override public Map<String, Object> inputSchema() { return Map.of(); }
            @Override public CompletableFuture<PermissionResult> checkPermissions(
                    Map<String, Object> input, CallContext ctx) {
                return CompletableFuture.completedFuture(new PermissionResult.Allow(input));
            }
            @Override public CompletableFuture<ToolResult> call(
                    Map<String, Object> input, CallContext ctx) {
                return CompletableFuture.completedFuture(ToolResult.of(""));
            }
        };
    }

    @Test
    void replaceMcpTools_removesOldMcpAndInstallsNew() {
        AppState st = new AppState("s1", Path.of("/tmp"));
        st.toolPool().add(stubTool("mcp:filesystem__read"));
        st.toolPool().add(stubTool("mcp:filesystem__list"));
        assertEquals(2, st.toolPool().size());

        List<Tool> fresh = List.of(
                stubTool("mcp:filesystem__read"),  // unchanged
                stubTool("mcp:git__status")        // added
        );
        st.replaceMcpTools(fresh);

        // After the swap, the two old MCP tools are gone,
        // the new set is in. Built-in tools are untouched
        // (we had no built-ins in this test, so the
        // post-swap list == the new list).
        assertEquals(2, st.toolPool().size());
        java.util.Set<String> names = new java.util.HashSet<>();
        for (Tool t : st.toolPool()) names.add(t.name());
        assertTrue(names.contains("mcp:filesystem__read"));
        assertTrue(names.contains("mcp:git__status"));
        assertFalse(names.contains("mcp:filesystem__list"),
                "removed MCP tool should be gone after swap");
    }

    @Test
    void replaceMcpTools_keepsNonMcpToolsUntouched() {
        AppState st = new AppState("s1", Path.of("/tmp"));
        Tool builtIn = stubTool("file_read");
        Tool mcpOld = stubTool("mcp:foo");
        st.toolPool().add(builtIn);
        st.toolPool().add(mcpOld);
        assertEquals(2, st.toolPool().size());

        List<Tool> fresh = List.of(stubTool("mcp:bar"));
        st.replaceMcpTools(fresh);

        // The built-in tool is untouched; the old MCP
        // tool is gone; the new MCP tool is in.
        assertEquals(2, st.toolPool().size());
        assertTrue(st.toolPool().contains(builtIn),
                "built-in tool must survive the MCP swap");
        assertFalse(st.toolPool().contains(mcpOld),
                "old MCP tool must be removed");
        assertTrue(st.toolPool().stream()
                .anyMatch(t -> "mcp:bar".equals(t.name())));
    }

    @Test
    void replaceMcpTools_emptyList_removesAllMcp() {
        AppState st = new AppState("s1", Path.of("/tmp"));
        st.toolPool().add(stubTool("file_read"));
        st.toolPool().add(stubTool("mcp:foo"));
        st.toolPool().add(stubTool("mcp:bar"));
        assertEquals(3, st.toolPool().size());

        st.replaceMcpTools(List.of());

        // Only the built-in tool is left; both MCP tools
        // are gone.
        assertEquals(1, st.toolPool().size());
        assertEquals("file_read", st.toolPool().get(0).name());
    }

    @Test
    void replaceMcpTools_nullList_removesAllMcp() {
        // a null fresh list is treated as "empty" 鈥?        // the user wants to clear MCP tools without
        // having to re-build the engine.
        AppState st = new AppState("s1", Path.of("/tmp"));
        st.toolPool().add(stubTool("file_read"));
        st.toolPool().add(stubTool("mcp:foo"));
        st.replaceMcpTools(null);
        assertEquals(1, st.toolPool().size());
    }

    @Test
    void replaceMcpTools_filtersNonMcpEntriesFromFresh() {
        // Defensive: a caller may accidentally pass a
        // list that includes non-mcp: tools. The swap
        // must ignore them (don't accidentally install
        // a tool that isn't really from MCP).
        AppState st = new AppState("s1", Path.of("/tmp"));
        st.toolPool().add(stubTool("file_read"));
        st.replaceMcpTools(List.of(
                stubTool("mcp:bar"),
                stubTool("not_mcp_at_all")  // should be filtered out
        ));
        // 2 tools: the original built-in + the one mcp: entry.
        assertEquals(2, st.toolPool().size());
        assertTrue(st.toolPool().stream()
                .anyMatch(t -> "mcp:bar".equals(t.name())));
        assertTrue(st.toolPool().stream()
                .noneMatch(t -> "not_mcp_at_all".equals(t.name())));
    }
}
