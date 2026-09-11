package org.aethercode.permission;

import org.aethercode.permission.CommandAllowlist.Decision;
import org.aethercode.permission.CommandAllowlist.Verdict;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CommandAllowlistTest {

    @Test
    void check_emptyCommandDenied() {
        CommandAllowlist c = new CommandAllowlist();
        assertFalse(c.check("").allowed());
        assertFalse(c.check(null).allowed());
    }

    @Test
    void check_exactMatch() {
        CommandAllowlist c = new CommandAllowlist();
        c.allowExact("ls");
        assertTrue(c.check("ls").allowed());
        assertFalse(c.check("ls -la").allowed()); // exact, no prefix
    }

    @Test
    void check_prefixMatch() {
        CommandAllowlist c = new CommandAllowlist();
        c.allowPrefix("git");
        assertTrue(c.check("git status").allowed());
        assertTrue(c.check("git log").allowed());
        assertFalse(c.check("rm -rf /").allowed());
    }

    @Test
    void check_regexMatch() {
        CommandAllowlist c = new CommandAllowlist();
        c.allowRegex("^echo .*");
        assertTrue(c.check("echo hello").allowed());
        assertFalse(c.check("rm echo").allowed());
    }

    @Test
    void check_denyWinsOverAllow() {
        CommandAllowlist c = new CommandAllowlist();
        c.allowPrefix("rm").denyExact("rm -rf /");
        assertTrue(c.check("rm file.txt").allowed());
        assertFalse(c.check("rm -rf /").allowed());
    }

    @Test
    void check_defaultDenyWhenNoMatch() {
        CommandAllowlist c = new CommandAllowlist();
        c.allowExact("ls");
        assertFalse(c.check("unknown").allowed());
    }

    @Test
    void check_defaultAllowWhenConfigured() {
        CommandAllowlist c = new CommandAllowlist().defaultVerdict(Verdict.ALLOW);
        assertTrue(c.check("anything").allowed());
    }

    @Test
    void check_returnsReason() {
        CommandAllowlist c = new CommandAllowlist();
        c.allowExact("ls");
        Decision d = c.check("ls");
        assertNotNull(d.reason());
    }

    @Test
    void check_nullCommandDenied() {
        CommandAllowlist c = new CommandAllowlist();
        assertFalse(c.check(null).allowed());
    }

    @Test
    void check_invalidRegexFailsClosed() {
        CommandAllowlist c = new CommandAllowlist();
        c.allowRegex("[unclosed");
        // PatternSyntaxException is caught and the rule doesn't match
        assertFalse(c.check("anything").allowed()); // falls back to default deny
    }

    @Test
    void deny_specificRuleBlocks() {
        CommandAllowlist c = new CommandAllowlist();
        c.allowPrefix("git").denyExact("git push --force");
        assertTrue(c.check("git status").allowed());
        assertFalse(c.check("git push --force").allowed());
    }

    @Test
    void safeDefaults_allowsCommonCommands() {
        CommandAllowlist c = CommandAllowlist.safeDefaults();
        assertTrue(c.check("ls").allowed());
        assertTrue(c.check("pwd").allowed());
        assertTrue(c.check("git status").allowed());
    }

    @Test
    void safeDefaults_blocksDangerousCommands() {
        CommandAllowlist c = CommandAllowlist.safeDefaults();
        assertFalse(c.check("rm -rf /").allowed());
        assertFalse(c.check("shutdown").allowed());
    }

    @Test
    void size_returnsRuleCount() {
        CommandAllowlist c = new CommandAllowlist();
        c.allowExact("a").allowExact("b").denyExact("c");
        assertEquals(3, c.size());
    }

    @Test
    void clear_removesAll() {
        CommandAllowlist c = new CommandAllowlist();
        c.allowExact("a");
        c.clear();
        assertEquals(0, c.size());
    }

    @Test
    void isAllowed_shorthand() {
        CommandAllowlist c = new CommandAllowlist();
        c.allowExact("ls");
        assertTrue(c.isAllowed("ls"));
        assertFalse(c.isAllowed("rm"));
    }

    @Test
    void ruleFactory_denyExact() {
        CommandAllowlist c = new CommandAllowlist();
        c.deny(CommandAllowlist.Rule.denyExact("rm"));
        assertFalse(c.check("rm").allowed());
    }

    @Test
    void defaultVerdict_returnsConstructorValue() {
        CommandAllowlist c = new CommandAllowlist();
        assertEquals(Verdict.DENY, c.defaultVerdict());
        c.defaultVerdict(Verdict.ALLOW);
        assertEquals(Verdict.ALLOW, c.defaultVerdict());
    }

    @Test
    void rules_returnsImmutableSnapshot() {
        CommandAllowlist c = new CommandAllowlist();
        c.allowExact("x");
        assertEquals(1, c.rules().size());
        assertThrows(UnsupportedOperationException.class, () -> c.rules().add(CommandAllowlist.Rule.exact("y")));
    }

    @Test
    void add_nullRuleIgnored() {
        CommandAllowlist c = new CommandAllowlist();
        c.allow(null);
        c.deny(null);
        assertEquals(0, c.size());
    }

    private static org.junit.jupiter.api.function.Executable run(Runnable r) { return r::run; }
}
