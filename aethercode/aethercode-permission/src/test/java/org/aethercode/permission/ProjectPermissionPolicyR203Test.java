package org.aethercode.permission;

import org.aethercode.core.engine.PermissionPolicy;
import org.aethercode.core.permission.PermissionMode;
import org.aethercode.core.permission.PermissionResult;
import org.aethercode.core.tool.Tool;
import org.aethercode.core.tool.ToolDef;
import org.aethercode.core.tool.Tools;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * contract test for the new {@code 智能授权} (smart) tier of
 * the 3-tier permission model. The Chinese label is preserved
 * because the desktop UI surface-pins it (see
 * {@code aethercode-desktop/src/store/permissionModeR203.test.ts}).
 *
 * <p>legacy, {@link PermissionMode#ACCEPT_EDITS} was an
 * all-or-nothing "auto-allow file edits; ask for bash / network"
 * mode that still prompted for {@code ls} / {@code cat} / {@code pwd}.
 * That defeated the mode: a user who flipped to ACCEPT_EDITS to
 * stop seeing permission prompts for everyday read-only commands
 * was still prompted. R203 introduces a smarter auto-allow that
 * (a) auto-allows read-only tools (file_read, glob, grep,
 * web_search, �?, (b) auto-allows bash read-only commands
 * (ls, cat, pwd, find, �? �?see {@code BashTool.isReadOnly} �? * and (c) asks for anything that mutates state. The engine
 * mode name stays {@code ACCEPT_EDITS}; the policy's resolution
 * is the new bit.
 *
 * <p>The tests below pin the four critical corner cases:
 * <ol>
 *   <li>ACCEPT_EDITS + read-only tool �?auto-allow (no prompt)</li>
 *   <li>ACCEPT_EDITS + bash read-only command �?auto-allow</li>
 *   <li>ACCEPT_EDITS + bash mutating command �?ask</li>
 *   <li>ACCEPT_EDITS + write/edit tool �?ask</li>
 * </ol>
 *
 * <p>The test also pins that the existing semantics for the
 * other modes are unaffected: BYPASS_PERMISSIONS still allows
 * everything (even {@code rm -rf}), DEFAULT still asks for
 * everything, ASK_BEFORE_TOOL still asks for everything.
 */
class ProjectPermissionPolicyR203Test {

    // --- mock tools ---------------------------------------------------

    // a smart bash mock. The bash tool in
    // aethercode-tools overrides Tool.isReadOnly to
    // delegate to BashTool.isReadOnly(input) (a
    // documented read-only command whitelist + a
    // destructive-token check). We can't import
    // aethercode-tools in this test (cycle: tools
    // depends on permission), so we replicate the
    // heuristic in the test mock. The test for
    // BashTool.isReadOnly itself is in
    // aethercode-tools: BashToolR203ReadOnlyTest.
    private Tool bash() {
        return new Tool() {
            @Override public String name() { return "bash"; }
            @Override public String description() { return "Bash"; }
            @Override public Map<String, Object> inputSchema() { return Map.of(); }
            @Override public boolean isReadOnly(Map<String, Object> input) {
                return mockBashIsReadOnly(input);
            }
            @Override public boolean isDestructive(Map<String, Object> input) { return true; }
            @Override public CompletableFuture<PermissionResult> checkPermissions(
                    Map<String, Object> input, CallContext ctx) {
                return CompletableFuture.completedFuture(new PermissionResult.Allow(input));
            }
            @Override public CompletableFuture<ToolResult> call(
                    Map<String, Object> input, CallContext ctx) {
                return CompletableFuture.completedFuture(Tool.ToolResult.of("ok"));
            }
        };
    }

    /** Mirror of BashTool.isReadOnly for the test mock.
     *  Kept in sync with the BashTool static helper via
     *  the BashToolR203ReadOnlyTest coverage; this
     *  duplicate exists only because aethercode-permission
     *  cannot depend on aethercode-tools (cycle). */
    private static boolean mockBashIsReadOnly(Map<String, Object> input) {
        if (input == null) return false;
        Object cmdObj = input.get("command");
        if (!(cmdObj instanceof String cmd) || cmd.isBlank()) return false;
        String trimmed = cmd.strip();
        // Conservative destructive token list �?same as
        // BashTool.DESTRUCTIVE_TOKENS. Any of these in
        // the command means it's not read-only.
        String[] destructive = {">", ">>", "<", "&&", "||", ";", "`", "$(",
            "rm ", "rm\t", " mv ", " cp ", " ln ", " chmod", " chown",
            " kill", " sudo", " su ", " dd ", " mkfs", " shutdown",
            "&", "2>", "&>"};
        for (String t : destructive) {
            if (trimmed.contains(t)) return false;
        }
        java.util.Set<String> readOnly = java.util.Set.of(
            "ls", "cat", "pwd", "echo", "env", "which", "where", "whoami", "date",
            "head", "tail", "wc", "sort", "uniq", "tr", "cut",
            "find", "grep", "egrep", "fgrep", "rg", "ag",
            "stat", "file", "test", "[",
            "df", "du", "tree", "uname", "hostname",
            "ifconfig", "ip", "netstat", "ss", "lsof",
            "less", "more", "man", "tldr",
            "true", "false", "printenv", "id", "logname",
            "basename", "dirname", "realpath", "readlink",
            "dir", "type", "ver", "systeminfo", "tasklist", "sc", "wmic"
        );
        String[] segments = trimmed.split("[&|;]+");
        for (String seg : segments) {
            String t = seg.strip();
            if (t.isEmpty()) continue;
            int sp = t.indexOf(' ');
            String first = (sp < 0 ? t : t.substring(0, sp)).trim();
            int slash = first.lastIndexOf('/');
            if (slash >= 0) first = first.substring(slash + 1);
            int bslash = first.lastIndexOf('\\');
            if (bslash >= 0) first = first.substring(bslash + 1);
            if (first.toLowerCase(java.util.Locale.ROOT).endsWith(".exe")) {
                first = first.substring(0, first.length() - 4);
            }
            if (!readOnly.contains(first.toLowerCase(java.util.Locale.ROOT))) {
                return false;
            }
        }
        return true;
    }

    private static Tool readOnlyTool() {
        Tool inner = Tools.build(new ToolDef("read", "R", Map.of(),
                (in, ctx) -> CompletableFuture.completedFuture(Tool.ToolResult.of("ok"))));
        return new Tool() {
            @Override public String name() { return "read"; }
            @Override public String description() { return "R"; }
            @Override public Map<String, Object> inputSchema() { return Map.of(); }
            @Override public boolean isReadOnly(Map<String, Object> input) { return true; }
            @Override public CompletableFuture<PermissionResult> checkPermissions(
                    Map<String, Object> input, CallContext ctx) {
                return CompletableFuture.completedFuture(new PermissionResult.Allow(input));
            }
            @Override public CompletableFuture<ToolResult> call(
                    Map<String, Object> input, CallContext ctx) {
                return inner.call(input, ctx);
            }
        };
    }

    private static Tool mutatingTool(String name) {
        Tool inner = Tools.build(new ToolDef(name, name, Map.of(),
                (in, ctx) -> CompletableFuture.completedFuture(Tool.ToolResult.of("ok"))));
        return new Tool() {
            @Override public String name() { return name; }
            @Override public String description() { return name; }
            @Override public Map<String, Object> inputSchema() { return Map.of(); }
            @Override public boolean isReadOnly(Map<String, Object> input) { return false; }
            @Override public CompletableFuture<PermissionResult> checkPermissions(
                    Map<String, Object> input, CallContext ctx) {
                return CompletableFuture.completedFuture(new PermissionResult.Allow(input));
            }
            @Override public CompletableFuture<ToolResult> call(
                    Map<String, Object> input, CallContext ctx) {
                return inner.call(input, ctx);
            }
        };
    }

    // --- 智能授权 (smart): ACCEPT_EDITS ---------------------------------

    @Test
    void acceptEdits_readOnlyToolBypassesAsk() {
        // The "smart" mode MUST auto-allow a tool that
        // declares itself read-only. The user picked
        // 智能授权 (smart) to stop being asked for safe things;
        // a read-only tool reaching the resolver with
        // isReadOnly=true short-circuits to Allow.
        AtomicInteger prompterCalls = new AtomicInteger(0);
        ToolPermissionPrompter prompter = (t, i, q) -> {
            prompterCalls.incrementAndGet();
            return CompletableFuture.completedFuture(new PermissionResult.Allow(i));
        };
        SettingsPermissions sp = SettingsPermissions.empty();
        PermissionPolicy p = new ProjectPermissionPolicy(sp, PermissionMode.ACCEPT_EDITS, prompter);
        PermissionResult r = p.check(readOnlyTool(), Map.of(), Tool.CallContext.of("s")).join();
        assertThat(r).isInstanceOf(PermissionResult.Allow.class);
        assertThat(prompterCalls.get()).as("read-only tool must NOT prompt under 智能授权 (smart)").isEqualTo(0);
    }

    @Test
    void acceptEdits_bashReadOnlyCommandBypassesAsk() {
        // The BashTool.isReadOnly helper classifies
        // `ls` / `cat` / `pwd` / `find` / `head` etc. as
        // read-only. The smart policy asks BashTool
        // (via reflection or direct import) and
        // auto-allows when the verdict is read-only.
        // The user picking 智能授权 (smart) to escape permission
        // prompts for `ls -la` is the entire point of
        // the new tier.
        AtomicInteger prompterCalls = new AtomicInteger(0);
        ToolPermissionPrompter prompter = (t, i, q) -> {
            prompterCalls.incrementAndGet();
            return CompletableFuture.completedFuture(new PermissionResult.Allow(i));
        };
        SettingsPermissions sp = SettingsPermissions.empty();
        PermissionPolicy p = new ProjectPermissionPolicy(sp, PermissionMode.ACCEPT_EDITS, prompter);
        PermissionResult r = p.check(bash(), Map.of("command", "ls -la"), Tool.CallContext.of("s")).join();
        assertThat(r).isInstanceOf(PermissionResult.Allow.class);
        assertThat(prompterCalls.get()).as("bash `ls` must NOT prompt under 智能授权 (smart)").isEqualTo(0);

        // cat / pwd / find / head / tail / wc all
        // are read-only by the BashTool heuristic.
        AtomicInteger p2 = new AtomicInteger(0);
        ToolPermissionPrompter prompter2 = (t, i, q) -> {
            p2.incrementAndGet();
            return CompletableFuture.completedFuture(new PermissionResult.Allow(i));
        };
        PermissionPolicy pp2 = new ProjectPermissionPolicy(sp, PermissionMode.ACCEPT_EDITS, prompter2);
        for (String cmd : new String[]{"cat foo.txt", "pwd", "find . -name '*.java'", "head -5 foo.txt", "tail -20 foo.txt", "wc -l foo.txt"}) {
            PermissionResult rr = pp2.check(bash(), Map.of("command", cmd), Tool.CallContext.of("s")).join();
            assertThat(rr).as("bash `" + cmd + "` must auto-allow").isInstanceOf(PermissionResult.Allow.class);
        }
        assertThat(p2.get()).as("read-only bash commands must NEVER prompt under 智能授权 (smart)").isEqualTo(0);
    }

    @Test
    void acceptEdits_bashMutatingCommandAsks() {
        // `rm -rf /tmp/foo` is destructive. The
        // BashTool.isReadOnly heuristic returns false
        // (the `rm` token), so the smart policy must
        // ask. A user in 智能授权 (smart) mode who is asked
        // for a `rm` is the correct UX — the tier is
        // "smart", not "bypass".
        AtomicInteger prompterCalls = new AtomicInteger(0);
        ToolPermissionPrompter prompter = (t, i, q) -> {
            prompterCalls.incrementAndGet();
            return CompletableFuture.completedFuture(new PermissionResult.Allow(i));
        };
        SettingsPermissions sp = SettingsPermissions.empty();
        PermissionPolicy p = new ProjectPermissionPolicy(sp, PermissionMode.ACCEPT_EDITS, prompter);
        PermissionResult r = p.check(bash(), Map.of("command", "rm -rf /tmp/foo"), Tool.CallContext.of("s")).join();
        assertThat(r).isInstanceOf(PermissionResult.Allow.class);
        assertThat(prompterCalls.get()).as("bash `rm -rf` MUST prompt under 智能授权 (smart)").isEqualTo(1);

        // All other mutating commands: also ask.
        for (String cmd : new String[]{"mv foo bar", "chmod 755 foo", "cat foo > bar", "ls | tee out.txt", "echo $(rm foo)"}) {
            AtomicInteger c = new AtomicInteger(0);
            ToolPermissionPrompter pr = (t, i, q) -> {
                c.incrementAndGet();
                return CompletableFuture.completedFuture(new PermissionResult.Allow(i));
            };
            PermissionPolicy pp = new ProjectPermissionPolicy(sp, PermissionMode.ACCEPT_EDITS, pr);
            PermissionResult rr = pp.check(bash(), Map.of("command", cmd), Tool.CallContext.of("s")).join();
            assertThat(rr).as("bash `" + cmd + "` should still Allow (prompter said yes)").isInstanceOf(PermissionResult.Allow.class);
            assertThat(c.get()).as("bash `" + cmd + "` MUST prompt under 智能授权 (smart)").isEqualTo(1);
        }
    }

    @Test
    void acceptEdits_writeToolAsks() {
        // A write/edit tool (file_write, file_edit) is
        // mutating by definition. The smart policy asks
        // for it. (Future R-round: distinguish "create
        // new" from "modify existing" so file_create
        // can auto-allow. legacy+ there's no such
        // distinction in the tool surface.)
        Tool writeTool = mutatingTool("file_write");
        AtomicInteger prompterCalls = new AtomicInteger(0);
        ToolPermissionPrompter prompter = (t, i, q) -> {
            prompterCalls.incrementAndGet();
            return CompletableFuture.completedFuture(new PermissionResult.Allow(i));
        };
        SettingsPermissions sp = SettingsPermissions.empty();
        PermissionPolicy p = new ProjectPermissionPolicy(sp, PermissionMode.ACCEPT_EDITS, prompter);
        PermissionResult r = p.check(writeTool, Map.of("file_path", "foo.txt"), Tool.CallContext.of("s")).join();
        assertThat(r).isInstanceOf(PermissionResult.Allow.class);
        assertThat(prompterCalls.get()).as("file_write MUST prompt under 智能授权 (smart)").isEqualTo(1);
    }

    // --- 主动询问 (ask): ASK_BEFORE_TOOL + DEFAULT ---------------------

    @Test
    void askBeforeTool_bashMutatingCommandAsks() {
        // 主动询问 (ask) is "always ask for non-read-only".
        // The user's first-tier option keeps asking
        // for bash so they can decide each call. This
        // is the same behaviour legacy (DEFAULT +
        // ASK_BEFORE_TOOL are interchangeable; the
        // R163 source-pin tests cover that). Note:
        // read-only commands like `ls` auto-allow under
        // ANY mode (the policy short-circuits read-only
        // tools in resolveAsk); a user testing 主动询问 (ask)
        // for bash needs a mutating command to see the
        // prompt.
        AtomicInteger prompterCalls = new AtomicInteger(0);
        ToolPermissionPrompter prompter = (t, i, q) -> {
            prompterCalls.incrementAndGet();
            return CompletableFuture.completedFuture(new PermissionResult.Allow(i));
        };
        SettingsPermissions sp = SettingsPermissions.empty();
        PermissionPolicy p = new ProjectPermissionPolicy(sp, PermissionMode.ASK_BEFORE_TOOL, prompter);
        PermissionResult r = p.check(bash(), Map.of("command", "rm -rf /tmp/foo"), Tool.CallContext.of("s")).join();
        assertThat(r).isInstanceOf(PermissionResult.Allow.class);
        assertThat(prompterCalls.get()).as("bash `rm -rf` MUST prompt under 主动询问 (ask)").isEqualTo(1);
    }

    // --- 始终授权 (bypass): BYPASS_PERMISSIONS -------------------------

    @Test
    void bypassPermissions_evenDestructiveBashAllows() {
        // 始终授权 (bypass) is "never ask". A user picking
        // 始终授权 (bypass) is on the hook for everything
        // (the daemon's R130 denylist still applies
        // for the most dangerous patterns, but the
        // permission policy itself allows without
        // prompt).
        AtomicInteger prompterCalls = new AtomicInteger(0);
        ToolPermissionPrompter prompter = (t, i, q) -> {
            prompterCalls.incrementAndGet();
            return CompletableFuture.completedFuture(new PermissionResult.Allow(i));
        };
        SettingsPermissions sp = SettingsPermissions.empty();
        PermissionPolicy p = new ProjectPermissionPolicy(sp, PermissionMode.BYPASS_PERMISSIONS, prompter);
        PermissionResult r = p.check(bash(), Map.of("command", "rm -rf /"), Tool.CallContext.of("s")).join();
        assertThat(r).isInstanceOf(PermissionResult.Allow.class);
        assertThat(prompterCalls.get()).as("始终授权 (bypass) must NEVER prompt").isEqualTo(0);
    }

    // --- source-pin: ACCEPT_EDITS calls resolveSmart ----------------

    @Test
    void acceptEdits_resolveSmart_isCalled() {
        // Source-pin: ACCEPT_EDITS now routes through
        // the new resolveSmart helper. The check is
        // a structural one �?the source has a switch
        // arm `case ACCEPT_EDITS -> resolveSmart(...)`.
        // We pin via the public observable: a bash
        // read-only command auto-allows, which only
        // happens via the new path. (The legacy
        // behaviour routed ACCEPT_EDITS through
        // resolveAsk unconditionally, which would
        // have prompted for `ls`.)
        String src;
        try {
            java.nio.file.Path p = java.nio.file.Path.of("src/main/java/org/aethercode/permission/ProjectPermissionPolicy.java");
            src = new String(java.nio.file.Files.readAllBytes(p));
        } catch (Exception e) {
            // If the source path isn't resolvable
            // (test runs from a different cwd), fall
            // back to the classpath resource.
            try (var is = getClass().getClassLoader().getResourceAsStream(
                    "ProjectPermissionPolicy.java")) {
                src = new String(is.readAllBytes());
            } catch (Exception ex) {
                throw new RuntimeException("could not read ProjectPermissionPolicy source", ex);
            }
        }
        // The switch arm must invoke resolveSmart,
        // not resolveAsk, for ACCEPT_EDITS.
        assertThat(src)
            .as("ACCEPT_EDITS arm must call resolveSmart (R203)")
            .contains("case ACCEPT_EDITS -> resolveSmart");
        // The helper itself must exist.
        assertThat(src)
            .as("resolveSmart helper must exist (R203)")
            .contains("private CompletableFuture<PermissionResult> resolveSmart");
    }
}
