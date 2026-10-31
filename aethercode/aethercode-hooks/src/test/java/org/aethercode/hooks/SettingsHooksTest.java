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

package org.aethercode.hooks;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SettingsHooksTest {

    @Test
    void denyRuleFromSettingsBlocksTool(@TempDir Path tmp) throws IOException {
        Path settings = tmp.resolve("settings.json");
        Files.writeString(settings, """
                {
                  "hooks": {
                    "PreToolUse": [
                      { "matcher": "bash", "action": "deny", "reason": "shell blocked" }
                    ]
                  }
                }
                """.trim());
        HookRegistry reg = new HookRegistry();
        SettingsHooks.loadFrom(settings, reg);
        var out = reg.runAll(Hook.Kind.PRE_TOOL_USE,
                Hook.HookContext.forPre("s", "bash", Map.of("command", "rm -rf /"))).join();
        assertThat(out).isInstanceOf(Hook.Outcome.Block.class);
        assertThat(((Hook.Outcome.Block) out).reason()).contains("shell blocked");
    }

    @Test
    void wildcardMatcherMatchesAnyTool(@TempDir Path tmp) throws IOException {
        Path settings = tmp.resolve("settings.json");
        Files.writeString(settings, """
                {
                  "hooks": {
                    "PreToolUse": [
                      { "matcher": "*", "action": "deny", "reason": "everything denied" }
                    ]
                  }
                }
                """.trim());
        HookRegistry reg = new HookRegistry();
        SettingsHooks.loadFrom(settings, reg);
        var out = reg.runAll(Hook.Kind.PRE_TOOL_USE,
                Hook.HookContext.forPre("s", "file_read", Map.of())).join();
        assertThat(out).isInstanceOf(Hook.Outcome.Block.class);
    }

    @Test
    void postHookLogAppendsLine(@TempDir Path tmp) throws IOException {
        Path log = tmp.resolve("audit.log");
        Path settings = tmp.resolve("settings.json");
        Files.writeString(settings, """
                {
                  "hooks": {
                    "PostToolUse": [
                      { "matcher": "*", "action": "log", "path": "%s" }
                    ]
                  }
                }
                """.formatted(log.toString().replace("\\", "\\\\")).trim());
        HookRegistry reg = new HookRegistry();
        SettingsHooks.loadFrom(settings, reg);
        reg.runAll(Hook.Kind.POST_TOOL_USE,
                Hook.HookContext.forPost("s", "bash", Map.of(),
                        org.aethercode.core.tool.Tool.ToolResult.of("ok"))).join();
        assertThat(Files.exists(log)).isTrue();
        String content = Files.readString(log);
        assertThat(content).contains("bash");
    }

    @Test
    void missingSettingsFileIsNoop(@TempDir Path tmp) {
        HookRegistry reg = new HookRegistry();
        SettingsHooks.loadFrom(tmp.resolve("missing.json"), reg);
        var out = reg.runAll(Hook.Kind.PRE_TOOL_USE,
                Hook.HookContext.forPre("s", "x", Map.of())).join();
        assertThat(out).isInstanceOf(Hook.Outcome.Continue.class);
    }
}
