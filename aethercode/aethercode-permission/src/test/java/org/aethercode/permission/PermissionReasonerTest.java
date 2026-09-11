package org.aethercode.permission;

import org.aethercode.core.permission.PermissionMode;
import org.aethercode.core.permission.PermissionResult;
import org.aethercode.core.tool.Tool;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;

class PermissionReasonerTest {

    private final SettingsPermissions empty = SettingsPermissions.empty();

    @Test
    void denyRuleWins() {
        SettingsPermissions rules = new SettingsPermissions();
        rules.deny = List.of(new Rule("bash", "rm.*", "rm is destructive"));
        PermissionReasoner r = new PermissionReasoner(rules, PermissionMode.DEFAULT);
        PermissionReasoner.Reason reason = r.evaluate(bashTool(), Map.of("command", "rm -rf /"));
        assertThat(reason.verdict()).isEqualTo(PermissionReasoner.Verdict.DENY);
        assertThat(reason.source()).isEqualTo("deny-rule:bash");
        assertThat(reason.explanation()).contains("rm is destructive");
    }

    @Test
    void allowRuleBeatsDefaultMode() {
        SettingsPermissions rules = new SettingsPermissions();
        rules.allow = List.of(new Rule("file_read", null, "always allow reads"));
        PermissionReasoner r = new PermissionReasoner(rules, PermissionMode.PLAN);
        PermissionReasoner.Reason reason = r.evaluate(fileReadTool(), Map.of("file_path", "/x"));
        assertThat(reason.verdict()).isEqualTo(PermissionReasoner.Verdict.ALLOW);
        assertThat(reason.source()).isEqualTo("allow-rule:file_read");
    }

    @Test
    void askRuleProducesAsk() {
        SettingsPermissions rules = new SettingsPermissions();
        rules.ask = List.of(new Rule("bash", null, "ask first"));
        PermissionReasoner r = new PermissionReasoner(rules, PermissionMode.BYPASS_PERMISSIONS);
        PermissionReasoner.Reason reason = r.evaluate(bashTool(), Map.of("command", "ls"));
        // ask rule should fire even when mode would auto-allow
        assertThat(reason.verdict()).isEqualTo(PermissionReasoner.Verdict.ASK);
    }

    @Test
    void defaultModeWithoutRulesAsks() {
        PermissionReasoner r = new PermissionReasoner(empty, PermissionMode.DEFAULT);
        PermissionReasoner.Reason reason = r.evaluate(bashTool(), Map.of("command", "ls"));
        assertThat(reason.verdict()).isEqualTo(PermissionReasoner.Verdict.ASK);
        assertThat(reason.source()).startsWith("mode:");
    }

    @Test
    void acceptEditsAutoAllows() {
        PermissionReasoner r = new PermissionReasoner(empty, PermissionMode.ACCEPT_EDITS);
        PermissionReasoner.Reason reason = r.evaluate(bashTool(), Map.of("command", "ls"));
        assertThat(reason.verdict()).isEqualTo(PermissionReasoner.Verdict.ALLOW);
    }

    @Test
    void bypassPermissionsAutoAllows() {
        PermissionReasoner r = new PermissionReasoner(empty, PermissionMode.BYPASS_PERMISSIONS);
        PermissionReasoner.Reason reason = r.evaluate(bashTool(), Map.of("command", "ls"));
        assertThat(reason.verdict()).isEqualTo(PermissionReasoner.Verdict.ALLOW);
    }

    @Test
    void autoReadOnlyAllowsReadOnlyTool() {
        PermissionReasoner r = new PermissionReasoner(empty, PermissionMode.AUTO_READ_ONLY);
        PermissionReasoner.Reason reason = r.evaluate(fileReadTool(), Map.of("file_path", "/x"));
        assertThat(reason.verdict()).isEqualTo(PermissionReasoner.Verdict.ALLOW);
        assertThat(reason.source()).isEqualTo("read-only-auto");
    }

    @Test
    void autoReadOnlyAsksForWrite() {
        PermissionReasoner r = new PermissionReasoner(empty, PermissionMode.AUTO_READ_ONLY);
        PermissionReasoner.Reason reason = r.evaluate(bashTool(), Map.of("command", "ls"));
        assertThat(reason.verdict()).isEqualTo(PermissionReasoner.Verdict.ASK);
    }

    @Test
    void denyRuleWithPromptRegex() {
        SettingsPermissions rules = new SettingsPermissions();
        rules.deny = List.of(new Rule("bash", "sudo.*", null));
        PermissionReasoner r = new PermissionReasoner(rules, PermissionMode.ACCEPT_EDITS);
        // matched — even though mode would auto-allow
        PermissionReasoner.Reason re1 = r.evaluate(bashTool(), Map.of("command", "sudo rm /"));
        assertThat(re1.verdict()).isEqualTo(PermissionReasoner.Verdict.DENY);
        // not matched — mode wins
        PermissionReasoner.Reason re2 = r.evaluate(bashTool(), Map.of("command", "ls"));
        assertThat(re2.verdict()).isEqualTo(PermissionReasoner.Verdict.ALLOW);
    }

    @Test
    void proceedsIsTrueForAllow() {
        PermissionReasoner.Reason r = new PermissionReasoner(empty, PermissionMode.BYPASS_PERMISSIONS)
                .evaluate(bashTool(), Map.of("command", "ls"));
        assertThat(r.proceeds()).isTrue();
    }

    @Test
    void proceedsIsFalseForAsk() {
        PermissionReasoner.Reason r = new PermissionReasoner(empty, PermissionMode.DEFAULT)
                .evaluate(bashTool(), Map.of("command", "ls"));
        assertThat(r.proceeds()).isFalse();
    }

    private static Tool bashTool() {
        return new Tool() {
            @Override public String name() { return "bash"; }
            @Override public String description() { return ""; }
            @Override public java.util.Map<String, Object> inputSchema() { return Map.of(); }
            @Override public boolean isReadOnly(java.util.Map<String, Object> input) { return false; }
            @Override public CompletableFuture<PermissionResult> checkPermissions(java.util.Map<String, Object> input, CallContext ctx) {
                return CompletableFuture.completedFuture(new PermissionResult.Allow(input));
            }
            @Override public CompletableFuture<ToolResult> call(java.util.Map<String, Object> input, CallContext ctx) {
                return CompletableFuture.completedFuture(new ToolResult(""));
            }
        };
    }

    private static Tool fileReadTool() {
        return new Tool() {
            @Override public String name() { return "file_read"; }
            @Override public String description() { return ""; }
            @Override public java.util.Map<String, Object> inputSchema() { return Map.of(); }
            @Override public boolean isReadOnly(java.util.Map<String, Object> input) { return true; }
            @Override public CompletableFuture<PermissionResult> checkPermissions(java.util.Map<String, Object> input, CallContext ctx) {
                return CompletableFuture.completedFuture(new PermissionResult.Allow(input));
            }
            @Override public CompletableFuture<ToolResult> call(java.util.Map<String, Object> input, CallContext ctx) {
                return CompletableFuture.completedFuture(new ToolResult(""));
            }
        };
    }
}
