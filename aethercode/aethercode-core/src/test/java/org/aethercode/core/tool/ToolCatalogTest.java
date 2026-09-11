package org.aethercode.core.tool;

import java.util.List;
import java.util.Map;
import org.aethercode.core.permission.PermissionResult;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolCatalogTest {

    static class FakeTool implements Tool {
        final String name;
        final String description;
        FakeTool(String name) { this(name, "desc " + name); }
        FakeTool(String name, String desc) { this.name = name; this.description = desc; }
        @Override public String name() { return name; }
        @Override public String description() { return description; }
        @Override public Map<String, Object> inputSchema() { return Map.of("type", "object"); }
        @Override public java.util.concurrent.CompletableFuture<PermissionResult> checkPermissions(
                Map<String, Object> input, Tool.CallContext ctx) {
            return java.util.concurrent.CompletableFuture.completedFuture(new PermissionResult.Allow(Map.of()));
        }
        @Override public java.util.concurrent.CompletableFuture<ToolResult> call(
                Map<String, Object> input, Tool.CallContext ctx) {
            return java.util.concurrent.CompletableFuture.completedFuture(new ToolResult("ok", List.of(), false));
        }
    }

    @Test
    void register_addsTool() {
        ToolCatalog c = new ToolCatalog();
        c.register(new FakeTool("Bash"));
        assertEquals(1, c.size());
    }

    @Test
    void register_rejectsNullTool() {
        ToolCatalog c = new ToolCatalog();
        assertThrows(NullPointerException.class, () -> c.register(null));
    }

    @Test
    void register_rejectsBlankName() {
        ToolCatalog c = new ToolCatalog();
        assertThrows(IllegalArgumentException.class, () -> c.register(new FakeTool("")));
    }

    @Test
    void get_returnsByName() {
        ToolCatalog c = new ToolCatalog();
        FakeTool t = new FakeTool("Bash");
        c.register(t);
        assertEquals(t, c.get("Bash").orElse(null));
    }

    @Test
    void get_returnsEmptyForUnknown() {
        ToolCatalog c = new ToolCatalog();
        assertFalse(c.get("missing").isPresent());
        assertFalse(c.get(null).isPresent());
    }

    @Test
    void contains_checksExistence() {
        ToolCatalog c = new ToolCatalog();
        c.register(new FakeTool("Bash"));
        assertTrue(c.contains("Bash"));
        assertFalse(c.contains("Read"));
    }

    @Test
    void unregister_removes() {
        ToolCatalog c = new ToolCatalog();
        c.register(new FakeTool("Bash"));
        c.unregister("Bash");
        assertEquals(0, c.size());
    }

    @Test
    void byCategory_filters() {
        ToolCatalog c = new ToolCatalog();
        c.register(new FakeTool("Bash"), "shell");
        c.register(new FakeTool("Read"), "file");
        c.register(new FakeTool("Write"), "file");
        assertEquals(1, c.byCategory("shell").size());
        assertEquals(2, c.byCategory("file").size());
    }

    @Test
    void categories_listsUnique() {
        ToolCatalog c = new ToolCatalog();
        c.register(new FakeTool("Bash"), "shell");
        c.register(new FakeTool("Read"), "file");
        c.register(new FakeTool("Write"), "file");
        List<String> cats = c.categories();
        assertEquals(2, cats.size());
        assertTrue(cats.contains("shell"));
        assertTrue(cats.contains("file"));
    }

    @Test
    void addFilter_appliesCorrectly() {
        ToolCatalog c = new ToolCatalog();
        c.register(new FakeTool("Bash", "Run shell"));
        c.register(new FakeTool("Read", "Read files"));
        c.addFilter(t -> t.description().contains("shell"));
        List<Tool> filtered = c.applyFilters();
        assertEquals(1, filtered.size());
        assertEquals("Bash", filtered.get(0).name());
    }

    @Test
    void addFilter_nullIgnored() {
        ToolCatalog c = new ToolCatalog();
        c.addFilter(null);
        assertEquals(0, c.applyFilters().size());
    }

    @Test
    void clear_empties() {
        ToolCatalog c = new ToolCatalog();
        c.register(new FakeTool("a"));
        c.register(new FakeTool("b"));
        c.clear();
        assertEquals(0, c.size());
    }

    @Test
    void registerAll_addsAll() {
        ToolCatalog c = new ToolCatalog();
        c.registerAll(List.of(new FakeTool("a"), new FakeTool("b")));
        assertEquals(2, c.size());
    }

    @Test
    void getAs_typedLookup() {
        ToolCatalog c = new ToolCatalog();
        c.register(new FakeTool("Bash"));
        assertTrue(c.getAs("Bash", FakeTool.class).isPresent());
        assertFalse(c.getAs("missing", FakeTool.class).isPresent());
    }

    @Test
    void allEntries_carriesMetadata() {
        ToolCatalog c = new ToolCatalog();
        c.register(new FakeTool("Bash"), "shell", true);
        var entry = c.getEntry("Bash").orElseThrow();
        assertEquals("shell", entry.category());
        assertTrue(entry.deprecated());
    }

    @Test
    void register_deprecated() {
        ToolCatalog c = new ToolCatalog();
        c.register(new FakeTool("old"), "shell", true);
        var e = c.getEntry("old").orElseThrow();
        assertTrue(e.deprecated());
    }
}
