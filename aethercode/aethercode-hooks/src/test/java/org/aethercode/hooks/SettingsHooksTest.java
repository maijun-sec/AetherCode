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
