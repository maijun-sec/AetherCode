package org.aethercode.permission.categorize;

import org.aethercode.permission.categorize.Rule.ArgRegexRule;
import org.aethercode.permission.categorize.Rule.ToolNameRule;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T-233 / T-234 / design.md §3.2: every default rule fires for
 * its representative call. Also tests the explainability hook
 * ({@code matchedRules}) and the pluggable rule table
 * (T-232).
 */
class RiskCategorizerTest {

    // ------------------------------------------------------------------
    //  Default rules — one test per rule
    // ------------------------------------------------------------------

    @Test
    void readOnlyRules_classifyReadToolsAsLow() {
        RiskCategorizer c = new RiskCategorizer();
        for (String tool : List.of("read_file", "glob_files", "grep_files", "ls",
                "file_read", "list_files")) {
            CategoryResult r = c.categorize(new ToolCall(tool, Map.of()));
            assertEquals(Risk.LOW, r.risk(), "tool " + tool + " should be low risk");
        }
    }

    @Test
    void readFileDestructiveCommand_isHigh() {
        RiskCategorizer c = new RiskCategorizer();
        CategoryResult r = c.categorize(new ToolCall("bash",
                Map.of("command", "rm -rf node_modules")));
        assertEquals(Risk.HIGH, r.risk());
        assertTrue(r.categories().contains("shell.destructive"));
        assertTrue(r.matchedRules().contains("shell.destructive"));
    }

    @Test
    void bashMv_isHigh() {
        RiskCategorizer c = new RiskCategorizer();
        CategoryResult r = c.categorize(new ToolCall("bash",
                Map.of("command", "mv a b")));
        assertEquals(Risk.HIGH, r.risk());
        assertTrue(r.categories().contains("shell.destructive"));
    }

    @Test
    void bashKill_isHigh() {
        RiskCategorizer c = new RiskCategorizer();
        CategoryResult r = c.categorize(new ToolCall("bash",
                Map.of("command", "kill -9 1234")));
        assertEquals(Risk.HIGH, r.risk());
    }

    @Test
    void bashNpmInstall_isHigh() {
        RiskCategorizer c = new RiskCategorizer();
        CategoryResult r = c.categorize(new ToolCall("bash",
                Map.of("command", "npm install react")));
        assertEquals(Risk.HIGH, r.risk());
        assertTrue(r.categories().contains("shell.package_install"));
    }

    @Test
    void bashPipInstall_isHigh() {
        RiskCategorizer c = new RiskCategorizer();
        CategoryResult r = c.categorize(new ToolCall("bash",
                Map.of("command", "pip install flask")));
        assertEquals(Risk.HIGH, r.risk());
        assertTrue(r.categories().contains("shell.package_install"));
    }

    @Test
    void bashCargoAdd_isHigh() {
        RiskCategorizer c = new RiskCategorizer();
        CategoryResult r = c.categorize(new ToolCall("bash",
                Map.of("command", "cargo add serde")));
        assertEquals(Risk.HIGH, r.risk());
    }

    @Test
    void gitPushForce_isHigh() {
        RiskCategorizer c = new RiskCategorizer();
        CategoryResult r = c.categorize(new ToolCall("bash",
                Map.of("command", "git push --force origin main")));
        assertEquals(Risk.HIGH, r.risk());
        assertTrue(r.categories().contains("shell.git_mutation"));
    }

    @Test
    void gitResetHard_isHigh() {
        RiskCategorizer c = new RiskCategorizer();
        CategoryResult r = c.categorize(new ToolCall("bash",
                Map.of("command", "git reset --hard HEAD~3")));
        assertEquals(Risk.HIGH, r.risk());
    }

    @Test
    void gitPushForceShortFlag_isHigh() {
        RiskCategorizer c = new RiskCategorizer();
        CategoryResult r = c.categorize(new ToolCall("bash",
                Map.of("command", "git push -f origin main")));
        assertEquals(Risk.HIGH, r.risk());
    }

    @Test
    void bashCurl_isMedium() {
        RiskCategorizer c = new RiskCategorizer();
        CategoryResult r = c.categorize(new ToolCall("bash",
                Map.of("command", "curl https://example.com")));
        assertEquals(Risk.MEDIUM, r.risk());
        assertTrue(r.categories().contains("shell.network"));
    }

    @Test
    void bashWget_isMedium() {
        RiskCategorizer c = new RiskCategorizer();
        CategoryResult r = c.categorize(new ToolCall("bash",
                Map.of("command", "wget -O /tmp/x https://example.com")));
        assertEquals(Risk.MEDIUM, r.risk());
    }

    @Test
    void bashLs_isMedium() {
        RiskCategorizer c = new RiskCategorizer();
        CategoryResult r = c.categorize(new ToolCall("bash",
                Map.of("command", "ls -la")));
        assertEquals(Risk.MEDIUM, r.risk());
    }

    @Test
    void bashCat_isMedium() {
        RiskCategorizer c = new RiskCategorizer();
        CategoryResult r = c.categorize(new ToolCall("bash",
                Map.of("command", "cat foo.txt")));
        assertEquals(Risk.MEDIUM, r.risk());
    }

    @Test
    void writeFileToNewFile_isMedium() {
        RiskCategorizer c = new RiskCategorizer();
        CategoryResult r = c.categorize(new ToolCall("write_file",
                Map.of("file_path", "/tmp/new.txt")));
        assertEquals(Risk.MEDIUM, r.risk());
        assertTrue(r.categories().contains("file.write"));
    }

    @Test
    void deleteFile_isHigh() {
        RiskCategorizer c = new RiskCategorizer();
        CategoryResult r = c.categorize(new ToolCall("delete_file",
                Map.of("file_path", "/tmp/x")));
        assertEquals(Risk.HIGH, r.risk());
        assertTrue(r.categories().contains("file.delete"));
    }

    @Test
    void fileEdit_isMediumWithOverwriteCategory() {
        RiskCategorizer c = new RiskCategorizer();
        CategoryResult r = c.categorize(new ToolCall("file_edit",
                Map.of("file_path", "/tmp/x")));
        assertEquals(Risk.MEDIUM, r.risk());
        assertTrue(r.categories().contains("file.write"));
        assertTrue(r.categories().contains("file.overwrite_existing"));
    }

    @Test
    void pythonRunWithOsSystem_isHigh() {
        RiskCategorizer c = new RiskCategorizer();
        CategoryResult r = c.categorize(new ToolCall("python_run",
                Map.of("code", "import os; os.system('rm -rf /')")));
        assertEquals(Risk.HIGH, r.risk());
        assertTrue(r.categories().contains("code.python_run"));
        assertTrue(r.categories().contains("shell.command"));
    }

    @Test
    void mcpTool_isHigh() {
        RiskCategorizer c = new RiskCategorizer();
        CategoryResult r = c.categorize(new ToolCall("mcp_github_create_pr",
                Map.of("repo", "aethercode/permission")));
        assertEquals(Risk.HIGH, r.risk());
        assertTrue(r.categories().contains("mcp.tool_invocation"));
    }

    @Test
    void webFetch_isMedium() {
        RiskCategorizer c = new RiskCategorizer();
        CategoryResult r = c.categorize(new ToolCall("web_fetch",
                Map.of("url", "https://example.com")));
        assertEquals(Risk.MEDIUM, r.risk());
        assertTrue(r.categories().contains("network.external_request"));
    }

    // ------------------------------------------------------------------
    //  Multi-rule / union behavior
    // ------------------------------------------------------------------

    @Test
    void multipleMatchingRules_accumulateCategories() {
        RiskCategorizer c = new RiskCategorizer();
        // bash "rm -rf" matches BOTH shell.destructive and
        // shell.read_only.ls? No — it doesn't start with "ls".
        // It should match shell.destructive only. But
        // "bash npm install" matches shell.package_install.
        CategoryResult r = c.categorize(new ToolCall("bash",
                Map.of("command", "npm install")));
        // categories should include shell.command (general bash)
        // and shell.package_install (specific). Multiple rule
        // matches → multiple categories.
        assertTrue(r.categories().contains("shell.command"));
        assertTrue(r.categories().contains("shell.package_install"));
        // Max risk wins.
        assertEquals(Risk.HIGH, r.risk());
    }

    @Test
    void deduplicatedCategories() {
        // A "git push --force" matches BOTH the general bash
        // network? No. But it should match shell.git_mutation.
        // To verify dedup we construct a custom rule that
        // always matches.
        RiskCategorizer c = new RiskCategorizer(List.of(
                new ToolNameRule("dup", "bash", Risk.MEDIUM, List.of("shell.command")),
                new ToolNameRule("dup2", "bash", Risk.MEDIUM, List.of("shell.command"))
        ));
        CategoryResult r = c.categorize(new ToolCall("bash", Map.of("command", "echo hi")));
        assertEquals(1, r.categories().size(),
                "duplicate categories from multiple rules should be collapsed");
    }

    @Test
    void maxRiskWins() {
        // High + medium matches → high
        RiskCategorizer c = new RiskCategorizer(List.of(
                new ToolNameRule("medium", "bash", Risk.MEDIUM, List.of("shell.command")),
                new ToolNameRule("high", "bash", Risk.HIGH, List.of("shell.dangerous"))
        ));
        CategoryResult r = c.categorize(new ToolCall("bash", Map.of("command", "x")));
        assertEquals(Risk.HIGH, r.risk());
    }

    @Test
    void explainability_matchedRulesAreListedInEvaluationOrder() {
        RiskCategorizer c = new RiskCategorizer(List.of(
                new ToolNameRule("first", "bash", Risk.MEDIUM, List.of("shell.command")),
                new ToolNameRule("second", "bash", Risk.HIGH, List.of("shell.dangerous"))
        ));
        CategoryResult r = c.categorize(new ToolCall("bash", Map.of("command", "x")));
        assertEquals(List.of("first", "second"), r.matchedRules());
        assertTrue(r.explain().contains("first"));
        assertTrue(r.explain().contains("second"));
    }

    // ------------------------------------------------------------------
    //  T-232: pluggable rule table
    // ------------------------------------------------------------------

    @Test
    void withRule_appendsUserRule() {
        RiskCategorizer base = new RiskCategorizer();
        // Default rule table classifies "bash" with no args as
        // nothing → LOW. Add a user rule to flag any bash with
        // "deploy" as HIGH.
        RiskCategorizer extended = new RiskCategorizer().withRule(
                new ArgRegexRule("user.deploy",
                        "command",
                        "\\bdeploy\\b",
                        Risk.HIGH,
                        List.of("user.deploy")));
        CategoryResult r1 = base.categorize(new ToolCall("bash",
                Map.of("command", "deploy production")));
        CategoryResult r2 = extended.categorize(new ToolCall("bash",
                Map.of("command", "deploy production")));
        // The user rule must fire on the extended categorizer
        // and not on the base.
        assertEquals(Risk.LOW, r1.risk());
        assertEquals(Risk.HIGH, r2.risk());
        assertTrue(r2.matchedRules().contains("user.deploy"));
    }

    @Test
    void withRules_bulkAdd() {
        RiskCategorizer c = new RiskCategorizer().withRules(List.of(
                new ToolNameRule("user.a", "foo", Risk.HIGH, List.of("user.a")),
                new ToolNameRule("user.b", "bar", Risk.MEDIUM, List.of("user.b"))
        ));
        assertEquals(Risk.HIGH, c.categorize(new ToolCall("foo", Map.of())).risk());
        assertEquals(Risk.MEDIUM, c.categorize(new ToolCall("bar", Map.of())).risk());
    }

    @Test
    void withRule_returnsSelfForChaining() {
        RiskCategorizer c = new RiskCategorizer();
        assertTrue(c.withRule(new ToolNameRule("x", "x", Risk.LOW, List.of())) == c);
    }

    @Test
    void rules_returnsImmutableSnapshot() {
        RiskCategorizer c = new RiskCategorizer();
        var snap = c.rules();
        assertNotNull(snap);
        assertFalse(snap.isEmpty());
        // The snapshot must not reflect later mutations.
        c.withRule(new ToolNameRule("x", "x", Risk.LOW, List.of()));
        assertFalse(snap.contains(new ToolNameRule("x", "x", Risk.LOW, List.of())),
                "the snapshot taken before withRule must not see the new rule");
    }

    // ------------------------------------------------------------------
    //  Edge cases
    // ------------------------------------------------------------------

    @Test
    void unknownTool_returnsLowRiskAndNoMatchedRules() {
        RiskCategorizer c = new RiskCategorizer();
        CategoryResult r = c.categorize(new ToolCall("made_up_tool", Map.of()));
        assertEquals(Risk.LOW, r.risk());
        assertTrue(r.matchedRules().isEmpty());
    }

    @Test
    void wildcardToolNamePrefix_matchesAnyTool() {
        RiskCategorizer c = new RiskCategorizer(List.of(
                new ToolNameRule("wild", "mcp_*", Risk.HIGH, List.of("mcp.tool_invocation"))));
        assertEquals(Risk.HIGH,
                c.categorize(new ToolCall("mcp_github_list_repos", Map.of())).risk());
        assertEquals(Risk.HIGH,
                c.categorize(new ToolCall("mcp_jira_create_issue", Map.of())).risk());
        // Non-mcp tool doesn't match.
        assertEquals(Risk.LOW,
                c.categorize(new ToolCall("read_file", Map.of())).risk());
    }

    @Test
    void toolNameRule_ignoresNullArg() {
        // A bash with no "command" arg should still match the
        // tool name rule but not the arg regex rules.
        RiskCategorizer c = new RiskCategorizer();
        CategoryResult r = c.categorize(new ToolCall("bash", Map.of()));
        // The arg rules all return null (no command), and no
        // tool name rule targets "bash" directly.
        assertEquals(Risk.LOW, r.risk());
    }

    @Test
    void nullArgMap_treatedAsEmpty() {
        RiskCategorizer c = new RiskCategorizer();
        CategoryResult r = c.categorize(new ToolCall("read_file", null));
        assertEquals(Risk.LOW, r.risk());
    }

    @Test
    void constructor_rejectsNullRuleList() {
        try {
            new RiskCategorizer(null);
            assertFalse(true);
        } catch (NullPointerException expected) { /* ok */ }
    }

    @Test
    void constructor_skipsNullRulesInList() {
        RiskCategorizer c = new RiskCategorizer(java.util.Arrays.asList(
                new ToolNameRule("ok", "x", Risk.LOW, List.of()),
                null
        ));
        // Only the non-null rule survives.
        assertEquals(1, c.rules().size());
    }

    @Test
    void categorize_nullCallRejected() {
        try {
            new RiskCategorizer().categorize(null);
            assertFalse(true);
        } catch (NullPointerException expected) { /* ok */ }
    }

    @Test
    void risk_severityOrderIsCorrect() {
        assertTrue(Risk.HIGH.severity() > Risk.MEDIUM.severity());
        assertTrue(Risk.MEDIUM.severity() > Risk.LOW.severity());
        assertEquals(Risk.HIGH, Risk.max(Risk.MEDIUM, Risk.HIGH));
        assertEquals(Risk.MEDIUM, Risk.max(Risk.MEDIUM, null));
        assertEquals(Risk.LOW, Risk.max(null, Risk.LOW));
    }

    @Test
    void toolCall_blankNameRejected() {
        try {
            new ToolCall("  ", Map.of());
            assertFalse(true);
        } catch (IllegalArgumentException expected) { /* ok */ }
    }

    @Test
    void toolCall_argMapIsDefensiveCopy() {
        java.util.HashMap<String, Object> src = new java.util.HashMap<>();
        src.put("k", "v");
        ToolCall call = new ToolCall("read_file", src);
        src.put("k2", "v2"); // mutate after construction
        assertFalse(call.args().containsKey("k2"),
                "ToolCall must take a defensive copy of the arg map");
    }

    @Test
    void toolCall_argStringReturnsToString() {
        ToolCall call = new ToolCall("bash",
                Map.of("command", "rm -rf /", "count", 42));
        assertEquals("rm -rf /", call.argString("command"));
        assertEquals("42", call.argString("count"));
        assertEquals(null, call.argString("missing"));
    }
}
