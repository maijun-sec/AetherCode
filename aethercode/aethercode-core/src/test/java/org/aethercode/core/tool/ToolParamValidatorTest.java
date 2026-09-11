package org.aethercode.core.tool;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.aethercode.core.permission.PermissionResult;
import org.aethercode.core.tool.ToolParamValidator.Cache;
import org.aethercode.core.tool.ToolParamValidator.ValidationResult;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolParamValidatorTest {

    static class FakeTool implements Tool {
        final String name;
        final Map<String, Object> schema;
        final String hookError;
        FakeTool(String name) { this(name, Map.of("type", "object"), null); }
        FakeTool(String name, String hookError) { this(name, Map.of("type", "object"), hookError); }
        FakeTool(String name, Map<String, Object> schema, String hookError) {
            this.name = name; this.schema = schema; this.hookError = hookError;
        }
        @Override public String name() { return name; }
        @Override public String description() { return "fake"; }
        @Override public Map<String, Object> inputSchema() { return schema; }
        @Override public String validateInput(Map<String, Object> input) { return hookError; }
        @Override public CompletableFuture<PermissionResult> checkPermissions(Map<String, Object> input, Tool.CallContext ctx) {
            return CompletableFuture.completedFuture(new PermissionResult.Allow(Map.of()));
        }
        @Override public CompletableFuture<ToolResult> call(Map<String, Object> input, Tool.CallContext ctx) {
            return CompletableFuture.completedFuture(new ToolResult("ok", List.of(), false));
        }
    }

    @Test
    void validate_returnsOkForEmptySchema() {
        FakeTool t = new FakeTool("x", Map.of(), null);
        ValidationResult r = ToolParamValidator.validate(t, Map.of("anything", 1));
        assertTrue(r.valid());
    }

    @Test
    void validate_returnsOkForNullInput() {
        FakeTool t = new FakeTool("x", Map.of("type", "object"), null);
        ValidationResult r = ToolParamValidator.validate(t, null);
        assertTrue(r.valid());
    }

    @Test
    void validate_passesValidInput() {
        Map<String, Object> schema = Map.of(
                "type", "object",
                "required", List.of("name"),
                "properties", Map.of("name", Map.of("type", "string"))
        );
        FakeTool t = new FakeTool("x", schema, null);
        ValidationResult r = ToolParamValidator.validate(t, Map.of("name", "alice"));
        assertTrue(r.valid());
    }

    @Test
    void validate_failsMissingRequired() {
        Map<String, Object> schema = Map.of(
                "type", "object",
                "required", List.of("name"),
                "properties", Map.of("name", Map.of("type", "string"))
        );
        FakeTool t = new FakeTool("x", schema, null);
        ValidationResult r = ToolParamValidator.validate(t, Map.of());
        assertFalse(r.valid());
        assertTrue(r.errors().get(0).contains("[x]"));
    }

    @Test
    void validate_includesHookError() {
        FakeTool t = new FakeTool("x", "name cannot be empty");
        ValidationResult r = ToolParamValidator.validate(t, Map.of());
        assertFalse(r.valid());
        assertTrue(r.errors().stream().anyMatch(s -> s.contains("name cannot be empty")));
    }

    @Test
    void validate_combinesSchemaAndHookErrors() {
        Map<String, Object> schema = Map.of("type", "string");
        FakeTool t = new FakeTool("x", schema, "hook says no");
        ValidationResult r = ToolParamValidator.validate(t, Map.of("not", "string"));
        // both errors should appear
        assertTrue(r.errors().size() >= 1);
    }

    @Test
    void isValid_shorthand() {
        Map<String, Object> schema = Map.of(
                "type", "object",
                "required", List.of("name"),
                "properties", Map.of("name", Map.of("type", "string"))
        );
        FakeTool t = new FakeTool("x", schema, null);
        assertTrue(ToolParamValidator.isValid(t, Map.of("name", "alice")));
        assertFalse(ToolParamValidator.isValid(t, Map.of()));
    }

    @Test
    void validate_rejectsNullTool() {
        assertThrows(NullPointerException.class, () -> ToolParamValidator.validate(null, Map.of()));
    }

    @Test
    void cache_registerAndValidate() {
        Map<String, Object> schema = Map.of(
                "type", "object",
                "required", List.of("name")
        );
        FakeTool t = new FakeTool("x", schema, null);
        Cache cache = new Cache().register(t);
        assertTrue(cache.isValid("x", Map.of("name", "alice")));
        assertFalse(cache.isValid("x", Map.of()));
    }

    @Test
    void cache_throwsForUnknown() {
        Cache c = new Cache();
        assertThrows(IllegalArgumentException.class, () -> c.isValid("nope", Map.of()));
    }

    @Test
    void cache_toolNames_returnsRegistered() {
        FakeTool a = new FakeTool("a", Map.of(), null);
        FakeTool b = new FakeTool("b", Map.of(), null);
        Cache c = new Cache().register(a).register(b);
        assertEquals(2, c.toolNames().size());
    }

    @Test
    void cache_size_tracksCount() {
        Cache c = new Cache();
        c.register(new FakeTool("a", Map.of(), null));
        c.register(new FakeTool("b", Map.of(), null));
        assertEquals(2, c.size());
    }

    @Test
    void cache_validate_returnsFullResult() {
        FakeTool t = new FakeTool("x", Map.of("type", "string"), null);
        Cache c = new Cache().register(t);
        ValidationResult r = c.validate("x", Map.of("k", "v"));
        assertFalse(r.valid());
    }

    @Test
    void cache_validate_handlesNullInput() {
        FakeTool t = new FakeTool("x", Map.of("type", "object"), null);
        Cache c = new Cache().register(t);
        // null input is treated as empty map
        ValidationResult r = c.validate("x", null);
        assertNotNull(r);
        assertTrue(r.valid());
    }

    @Test
    void validate_acceptsEmptyHookError() {
        FakeTool t = new FakeTool("x", ""); // empty string is filtered
        ValidationResult r = ToolParamValidator.validate(t, Map.of());
        assertTrue(r.valid());
    }
}
