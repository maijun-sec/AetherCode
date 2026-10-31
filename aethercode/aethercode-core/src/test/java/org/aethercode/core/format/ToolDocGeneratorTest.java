/*
 * Copyright (c) 2026 maijun
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package org.aethercode.core.format;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.aethercode.core.permission.PermissionResult;
import org.aethercode.core.tool.Tool;
import org.aethercode.core.tool.Tool.ToolResult;
import org.aethercode.core.tool.ToolCatalog;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolDocGeneratorTest {

    static class FakeTool implements Tool {
        final String name;
        FakeTool(String n) { this.name = n; }
        @Override public String name() { return name; }
        @Override public String description() { return "does " + name; }
        @Override public Map<String, Object> inputSchema() { return Map.of("type", "object"); }
        @Override public CompletableFuture<PermissionResult> checkPermissions(Map<String, Object> input, Tool.CallContext ctx) {
            return CompletableFuture.completedFuture(new PermissionResult.Allow(Map.of()));
        }
        @Override public CompletableFuture<ToolResult> call(Map<String, Object> input, Tool.CallContext ctx) {
            return CompletableFuture.completedFuture(new ToolResult("ok", List.of(), false));
        }
    }

    @Test
    void generate_includesAllTools() {
        ToolCatalog c = new ToolCatalog();
        c.register(new FakeTool("Bash"));
        c.register(new FakeTool("Read"));
        String md = ToolDocGenerator.generate(c);
        assertTrue(md.contains("`Bash`"));
        assertTrue(md.contains("`Read`"));
    }

    @Test
    void generate_includesDescription() {
        ToolCatalog c = new ToolCatalog();
        c.register(new FakeTool("Bash"));
        String md = ToolDocGenerator.generate(c);
        assertTrue(md.contains("does Bash"));
    }

    @Test
    void generate_marksDeprecated() {
        ToolCatalog c = new ToolCatalog();
        c.register(new FakeTool("old"), "shell", true);
        String md = ToolDocGenerator.generate(c);
        assertTrue(md.contains("[deprecated]"));
    }

    @Test
    void generate_excludesDeprecatedWhenConfigured() {
        ToolCatalog c = new ToolCatalog();
        c.register(new FakeTool("new"));
        c.register(new FakeTool("old"), "shell", true);
        String md = ToolDocGenerator.generate(c, new ToolDocGenerator.Options(true, false, "##"));
        assertTrue(md.contains("`new`"));
        assertFalse(md.contains("`old`"));
    }

    @Test
    void generate_includesSchema() {
        ToolCatalog c = new ToolCatalog();
        c.register(new FakeTool("Bash"));
        String md = ToolDocGenerator.generate(c);
        assertTrue(md.contains("Input schema"));
        assertTrue(md.contains("```json"));
    }

    @Test
    void generate_excludesSchemaWhenConfigured() {
        ToolCatalog c = new ToolCatalog();
        c.register(new FakeTool("Bash"));
        String md = ToolDocGenerator.generate(c, new ToolDocGenerator.Options(false, true, "##"));
        assertFalse(md.contains("```json"));
    }

    @Test
    void generate_includesCategory() {
        ToolCatalog c = new ToolCatalog();
        c.register(new FakeTool("Bash"), "shell");
        String md = ToolDocGenerator.generate(c);
        assertTrue(md.contains("shell"));
    }

    @Test
    void generate_handlesEmptyCatalog() {
        ToolCatalog c = new ToolCatalog();
        String md = ToolDocGenerator.generate(c);
        assertTrue(md.contains("No tools"));
    }

    @Test
    void generate_startsWithHeading() {
        ToolCatalog c = new ToolCatalog();
        c.register(new FakeTool("Bash"));
        String md = ToolDocGenerator.generate(c);
        assertTrue(md.startsWith("## Tools"));
    }

    @Test
    void generate_customHeadingLevel() {
        ToolCatalog c = new ToolCatalog();
        c.register(new FakeTool("Bash"));
        String md = ToolDocGenerator.generate(c, new ToolDocGenerator.Options(true, true, "#"));
        assertTrue(md.startsWith("# Tools"));
        assertTrue(md.contains("### `Bash`"));
    }
}
