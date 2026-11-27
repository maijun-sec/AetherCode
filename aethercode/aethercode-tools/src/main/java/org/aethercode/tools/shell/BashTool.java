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

package org.aethercode.tools.shell;

import org.aethercode.core.message.Message;
import org.aethercode.core.message.Role;
import org.aethercode.core.message.ContentBlock;
import org.aethercode.core.permission.PermissionResult;
import org.aethercode.core.tool.Tool;
import org.aethercode.core.tool.ToolDef;
import org.aethercode.core.tool.Tools;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Run a shell command. Mirrors the TS {@code BashTool}. Captures stdout, stderr, and exit
 * code, applies a per-call timeout, and refuses commands that are obviously dangerous
 * (configurable via {@code AETHERCODE_BASH_DENYLIST} env var, comma-separated regexes).
 *
 * <p>prior round: streaming + cancellation. The tool now emits each output
 * line as a progress message (via {@code ctx.emit(...)}) so the TUI /
 * --print can render output as the command runs, not just at the end.
 * Cancellation: the tool checks {@code ctx.isAborted()} between
 * lines; if set, the process is destroyed and the tool returns with
 * a "cancelled" error. The kill is best-effort — the process may take
 * a moment to die, and the tool waits up to 2s for the drain
 * threads to finish.
 *
 * <p>Background mode ({@code background: true}) returns immediately
 * with a job ID; the process runs in a daemon thread. The caller
 * can poll the job via {@link #BashJobRegistry}.
 */
public class BashTool {

    public static final String NAME = "bash";
    private static final Logger LOG = LoggerFactory.getLogger(BashTool.class);

    /**
     * ★ R417: THE ENVIRONMENT VARIABLE THAT NAMES THE SCRATCH AREA ★
     * the shell child is told where the session's own scratch directory
     * is, so a command that needs a temporary file can put it there
     * without the model having to invent a path (and without it landing
     * in the user's tree).
     *
     * <p>★ WHY NOT {@code TMPDIR} ★ the industry points the sandbox's
     * temporary directory at a per-user temp dir through {@code TMPDIR},
     * because most tools already honour it. Here the variable would have
     * to be {@code TMPDIR} / {@code TEMP} / {@code TMP} — and on Windows
     * that silently redirects EVERY tool's temporary directory on the
     * box, including the JDK's and the build tools'. A name that only
     * this product understands, plus one prompt line, says the same thing
     * without moving the whole machine's temp space.
     */
    public static final String SCRATCH_ENV = "AETHERCODE_SCRATCH";
    /** process-singleton registry of background bash jobs.
     *  Each entry maps a job id (assigned at spawn time) to a
     *  {@link BashJob} the caller can poll. */
    public static final BashJobRegistry JOBS = new BashJobRegistry();

    public static Tool build() {
        var props = new LinkedHashMap<String, Map<String, Object>>();
        props.put("command",    Tools.stringProp("Shell command to run."));
        // bumped default from 30s to 180s. The
        // previous 30s was too aggressive for build /
        // test commands — `mvn -q -B test` on a fresh
        // project takes 1-2 minutes for the dependency
        // download + first compile. With 30s the model
        // would hit timeout, see the truncated output,
        // re-run the same command, hit timeout again,
        // and loop until loop_detected. 180s gives a
        // typical maven build plenty of headroom. The
        // caller can still pass timeout_ms explicitly
        // to override.
        props.put("timeout_ms", Tools.intProp("Optional timeout in milliseconds. Default 180 000 (3 min)."));
        props.put("cwd",        Tools.stringProp("Optional working directory. Default: current directory."));
        Map<String, Object> boolSchema = new LinkedHashMap<>();
        boolSchema.put("type", "boolean");
        boolSchema.put("description",
                "Prior round: if true, stream each output line as a progress message " +
                "(default true). Set false to suppress progress emission and only " +
                "see the final captured output.");
        props.put("stream", boolSchema);
        boolSchema = new LinkedHashMap<>();
        boolSchema.put("type", "boolean");
        boolSchema.put("description",
                "Prior round: if true, run the command in the background. Returns a job ID " +
                "immediately; the process keeps running. Use /jobs to inspect or " +
                "/job-kill <id> to stop.");
        props.put("background", boolSchema);
        Map<String, Object> schema = Tools.objectSchema(props, "command");
        Tool built = Tools.build(new ToolDef(
                NAME,
                "Run a shell command. Returns stdout, stderr, and exit code. Default timeout 30s. " +
                "Prior round: streams output line-by-line and respects abort signals. Set " +
                "background=true to run detached (returns job id).",
                schema,
                (input, ctx) -> CompletableFuture.completedFuture(call(input, ctx))
        ));
        // wrap the BuiltTool so its isReadOnly(input)
        // delegates to BashTool.isReadOnly(input). The Tool
        // interface default returns false, but bash can
        // be a read-only operation (ls, cat, pwd, find, …)
        // — the 智能授权 tier of the 3-tier permission model
        // relies on this signal to auto-allow read-only
        // commands. We can't override isReadOnly in the
        // ToolDef itself (the BuiltTool's default
        // implementation reads from def.checkPermissions
        // only, not from a per-input predicate), so we
        // wrap with a thin anonymous Tool that delegates
        // everything except isReadOnly.
        return new Tool() {
            @Override public String name() { return built.name(); }
            @Override public String description() { return built.description(); }
            @Override public Map<String, Object> inputSchema() { return built.inputSchema(); }
            @Override public boolean isReadOnly(Map<String, Object> input) {
                return BashTool.isReadOnly(input);
            }
            @Override public boolean isDestructive(Map<String, Object> input) {
                return built.isDestructive(input);
            }
            @Override public boolean isConcurrencySafe(Map<String, Object> input) {
                return built.isConcurrencySafe(input);
            }
            @Override public CompletableFuture<PermissionResult> checkPermissions(
                    Map<String, Object> input, CallContext ctx) {
                return built.checkPermissions(input, ctx);
            }
            @Override public CompletableFuture<ToolResult> call(
                    Map<String, Object> input, CallContext ctx) {
                return built.call(input, ctx);
            }
        };
    }

    public static Tool.ToolResult call(Map<String, Object> input, Tool.CallContext ctx) {
        String command = (String) input.get("command");
        if (command == null || command.isBlank()) {
            // when the model emits an empty / missing
            // `command` parameter, return an error that
            // explicitly names the missing field AND lists
            // the documented optional fields, so the model
            // can re-emit a correct call without guessing.
            // legacy the error was just "command is required"
            // and a confused model could fall into a
            // 50-turn "fix the tool call format" loop (the
            // exact failure we observed on the v0.2.19
            // desktop real-prompt regression test).
            //
            // The hint lists every accepted parameter so
            // the model can re-read the schema and pick
            // the right one. It also says "STOP retrying
            // and ask the user" if the model is stuck —
            // the loop detector watches for the
            // emptyInputStreak pattern (ProgressLoopDetector)
            // and will hard-stop after 3 empty batches in a
            // row, but the hint is the first line of
            // defense.
            return Tool.ToolResult.error(
                    "command is required (string, e.g. \"ls\" or \"pwd -L\").\n" +
                    "accepted parameters:\n" +
                    "  - command (string, required): the shell command to run\n" +
                    "  - cwd (string, optional): working directory, default = engine cwd\n" +
                    "  - timeout_ms (integer, optional): max runtime in ms, default 180000\n" +
                    "  - stream (boolean, optional): stream stdout chunks live, default true\n" +
                    "  - background (boolean, optional): run in background, default false\n" +
                    "fix: re-read the tool schema in the system prompt and re-emit the call with the `command` parameter set.\n" +
                    "if you cannot determine the right command, STOP retrying and ask the user for guidance (or use a different tool).");
        }
        // default 180_000 (3 min) instead of 30_000.
        // See the schema doc above for the rationale.
        int timeout = input.get("timeout_ms") instanceof Number n ? n.intValue() : 180_000;
        File cwd = input.containsKey("cwd") && input.get("cwd") != null
                ? new File((String) input.get("cwd"))
                : defaultCwd(ctx);
        boolean stream = !Boolean.FALSE.equals(input.get("stream"));  // default true
        boolean background = Boolean.TRUE.equals(input.get("background"));

        // ★ R415: THE SHELL'S WORKING DIRECTORY IS INSIDE THE BOUNDARY ★
        // the `cwd` parameter decides where the command runs, and a
        // command that runs outside the session's tree can write
        // anywhere. Before R415 the parameter was not checked at all: the
        // command TEXT was checked for `cd ..`, but the parameter was
        // not — so the one hole was the easiest one to walk through.
        // ★ AND A RELATIVE `cwd` IS RESOLVED AGAINST THE SESSION ★ the
        // same defect the write tools had (R415's D3): a relative path
        // resolved against the JVM's launch directory is never what the
        // model meant.
        java.nio.file.Path sessionRoot = org.aethercode.core.scope.SessionPaths.cwdOf(ctx);
        java.nio.file.Path resolvedCwd =
                org.aethercode.core.scope.SessionPaths.resolve(cwd.getPath(), sessionRoot);
        boolean scopeEscaped = org.aethercode.core.scope.WriteScope.fromContext(ctx).isEscapeActive();
        if (!scopeEscaped && resolvedCwd != null && sessionRoot != null
                && !org.aethercode.core.scope.SessionPaths.isInside(resolvedCwd, sessionRoot)) {
            return Tool.ToolResult.error(
                    "command refused by the write scope: the working directory "
                            + resolvedCwd + " is outside the session's working directory ("
                            + sessionRoot + "). Run the command inside the tree, or change the "
                            + "session's working directory.");
        }
        if (resolvedCwd != null) cwd = resolvedCwd.toFile();

        // denylist with explicit reason. The earlier
        // isDenylisted() returns boolean only — the model
        // couldn't tell why the command was refused. R130
        // returns the matched pattern + a human-readable
        // reason so the model can rephrase (e.g. "rm -rf
        // / is refused because it deletes the root
        // filesystem" → "use a scoped rm with a path").
        DenyReason dr = checkDenylist(command);
        if (dr != null) {
            return Tool.ToolResult.error(
                "command refused by R130 denylist (" + dr.pattern() + "): "
                + dr.reason() + "\ncommand: " + firstLine(command)
                + "\nfix: rephrase to avoid the pattern (e.g. add a path, "
                + "use --dry-run, or use a scoped tool).");
        }

        // very simple sandbox check — refuse commands
        // that try to cd out of the engine's cwd. The
        // heuristic is a substring match on the command
        // text: any `cd ..` (with optional whitespace) or
        // absolute path that climbs above the engine root
        // is rejected. Absolute paths INSIDE the engine
        // cwd are allowed. This is intentionally coarse —
        // the user can opt out via the
        // AETHERCODE_BASH_ALLOW_SCOPE_ESCAPE env var
        // (set by tests + a few system commands).
        if (!"1".equals(System.getenv("AETHERCODE_BASH_ALLOW_SCOPE_ESCAPE"))) {
            String scopeViolation = checkScopeEscape(command, cwd, ctx);
            if (scopeViolation != null) {
                return Tool.ToolResult.error("command refused by sandbox: " + scopeViolation
                        + " (set AETHERCODE_BASH_ALLOW_SCOPE_ESCAPE=1 to bypass)");
            }
        }

        if (background) {
            // ★ A BACKGROUND COMMAND OUTLIVES THE CALL ★ the "after"
            // index would be taken while it still runs, so the shell
            // channel is not observed for it. Saying so is better than
            // reporting a half-finished diff.
            return spawnBackground(command, cwd, timeout, stream, ctx);
        }

        // ★ R415: THE SHELL CHANNEL IS OBSERVED ★ the command names no
        // path, so the only way to know what it wrote is to look at the
        // tree before and after. The baseline is cached, so consecutive
        // commands pay for one scan, not two.
        org.aethercode.core.scope.ShellWriteDetector detector =
                org.aethercode.core.scope.ShellWriteDetector.fromContext(ctx);
        java.nio.file.Path watchRoot = null;
        org.aethercode.core.consistency.ProjectIndex baseline = null;
        if (detector != null && sessionRoot != null && !isReadOnly(input)) {
            // ★ A READ-ONLY COMMAND IS NOT OBSERVED ★ the classifier
            // refuses a redirect or a mutating command, so a command it
            // calls read-only cannot have written anything — and the
            // scan of the tree is not free.
            watchRoot = sessionRoot;
            baseline = detector.begin(watchRoot);
        }

        Tool.ToolResult result = runForeground(command, cwd, timeout, stream, ctx);

        if (detector != null && watchRoot != null && baseline != null) {
            org.aethercode.core.scope.ShellWriteDetector.Observation observation =
                    detector.end(watchRoot, baseline);
            String report = org.aethercode.core.scope.ShellWriteDetector.report(observation, watchRoot);
            if (report != null) {
                result = appendReport(result, report);
            }
        }
        return result;
    }

    /**
     * Append a note to a tool result, keeping its error flag and attachments.
     *
     * <p>★ THE ERROR FLAG SURVIVES ★ a command that failed AND changed
     * files is still a failed command; the report must not turn it into a
     * success.
     */
    private static Tool.ToolResult appendReport(Tool.ToolResult result, String report) {
        if (result == null) return result;
        String body = result.output() == null ? "" : result.output().toString();
        return new Tool.ToolResult(body + "\n\n" + report, result.attachments(), result.isError());
    }

    /**
     * returns a non-null reason string when the
     * command tries to climb out of the engine's cwd. The
     * check is a best-effort heuristic: a substring scan
     * for {@code cd ..} / {@code cd /} / {@code pushd ..}
     * / {@code popd} plus a quick parse of any absolute
     * path argument that lies outside the engine cwd.
     * False positives are accepted (the user can split
     * the command into multiple invocations); false
     * negatives are limited to cases the user explicitly
     * accepts (the env-var bypass). The function is
     * intentionally simple — a full AST-level sandbox
     * belongs in a future R-round.
     */
    private static String checkScopeEscape(String command, File engineCwd) {
        return checkScopeEscape(command, engineCwd, shellEnv(), null);
    }

    /**
     * R417: the scope check for THIS session — the scratch area is part
     * of the boundary, and the environment the checker expands against
     * is the environment the child runs with (R416's one-map rule).
     */
    private static String checkScopeEscape(String command, File engineCwd, Tool.CallContext ctx) {
        java.nio.file.Path scratch = org.aethercode.core.scope.SessionPaths.scratchRoot(ctx);
        return checkScopeEscape(command, engineCwd, shellEnv(ctx), scratch);
    }

    /**
     * The scope check with an explicit environment.
     *
     * <p>★ R416: THE ENVIRONMENT IS PART OF THE COMMAND ★ the shell expands
     * the environment references in the command text BEFORE it runs
     * anything, so a command that names {@code %SCENARIOS_ROOT%\L1\escape.txt}
     * writes to an absolute path outside the tree while the text the
     * checker sees carries no absolute path at all. R416's live matrix
     * measured it: the write scope refused the literal path twice and the
     * model copied the file out through a variable.
     *
     * <p>⇒ the check now runs on the text the SHELL will act on, and the
     * refusal names the variable the path came from — the diagnosis decides
     * what the model does next.
     */
    private static String checkScopeEscape(String command, File engineCwd,
            java.util.Map<String, String> env) {
        return checkScopeEscape(command, engineCwd, env, null);
    }

    /**
     * The scope check with an explicit environment AND an explicit
     * scratch area.
     *
     * <p>★ R417: THE SCRATCH AREA IS PART OF THE BOUNDARY ★ the
     * scratch directory is outside the tree ON PURPOSE, so a command
     * that writes there is not an escape. Without this, the shell would
     * refuse {@code echo x > %AETHERCODE_SCRATCH%\x.txt} while the file
     * tools happily wrote the same file — two channels, two answers to
     * one question, and the model would learn that the shell is broken.
     */
    private static String checkScopeEscape(String command, File engineCwd,
            java.util.Map<String, String> env, java.nio.file.Path scratchRoot) {
        if (command == null || engineCwd == null) return null;
        // ★ THE EXPANDED TEXT IS THE TEXT THE SHELL SEES ★
        org.aethercode.core.scope.ScopeEnvExpansion.Result expansion =
                org.aethercode.core.scope.ScopeEnvExpansion.expand(command, env, isWindows());
        String effective = expansion.text();
        // The cd / pushd / popd "go up" subcommand.
        String trimmed = effective.strip();
        // ★ R415: A `cd` TO SOMEWHERE ELSE, ANYWHERE IN THE COMMAND ★ the
        // earlier rule was `command.startsWith("cd ..")` plus ONE
        // `indexOf("cd ")` for an absolute target. Both missed the
        // CHAINED form: `echo hi && cd .. && echo x > out.txt` walks
        // straight past a `startsWith` check, and a `cd` changes the
        // working directory of the WHOLE invocation — so the rest of
        // the chain writes one level above the tree. The climb is the
        // request, wherever it sits in the command, so EVERY `cd` /
        // `pushd` target is examined instead of just the first one.
        //
        // ★ AND THE TOKEN, NOT THE REST OF THE LINE ★ the old absolute
        // check took everything after `cd ` ("/tmp && echo x"), resolved
        // THAT as a path, and refused it — so `cd /inside/the/tree &&
        // ...` was refused even though the target is inside. A boundary
        // that cries wolf gets switched off.
        java.util.regex.Matcher cd = CD_TARGET.matcher(trimmed);
        while (cd.find()) {
            String target = cd.group(2).replace("\"", "").replace("'", "");
            if (target.isEmpty()) continue;
            if (target.startsWith("..")) {
                return "refusing 'cd ..' / 'pushd ..' that climbs above the engine cwd";
            }
            // Drive-absolute cd to a non-engine drive: refuse.
            // The engine cwd is absolute; we reject any absolute
            // cd target whose normalised form doesn't start
            // with the engine cwd.
            boolean absTarget = target.startsWith("/") || target.startsWith("\\")
                    || (target.length() >= 2 && target.charAt(1) == ':');
            if (absTarget && !insideBoundary(target, engineCwd, scratchRoot)) {
                return "refusing 'cd' to absolute path outside the engine cwd: " + target;
            }
        }
        // ★ R415: A PARENT-DIRECTORY PATH ★ the command text is a
        // heuristic — but a "../" path is an explicit request to leave
        // the tree, and the read and write tools already refuse that.
        // Without this rule the shell was the one channel that could
        // write outside the boundary: `echo x > ../escape.txt` names no
        // absolute path and runs no `cd`, so it sailed through.
        String unquoted = stripQuoted(trimmed);
        if (PARENT_SEGMENT.matcher(unquoted).find()) {
            return "the command names a parent-directory path ('..'), and the working "
                    + "directory is the boundary";
        }
        // ★ R415: A REDIRECT OUT OF THE TREE ★ a redirect is a WRITE,
        // and its target is named right there in the command. An
        // absolute target is outside the tree by definition.
        java.util.regex.Matcher m = REDIRECT.matcher(unquoted);
        while (m.find()) {
            String target = m.group(1).replace("\"", "").replace("'", "");
            if (target.isEmpty()) continue;
            // ★ THE NULL DEVICE IS NOT A WRITE OUTSIDE THE TREE ★
            // `cmd > /dev/null` is the unix way of saying "discard the
            // output" — it writes NOWHERE. On POSIX it is the kernel's
            // null device; on Windows cmd.exe refuses the path outright
            // ("The system cannot find the path specified.") and
            // creates no D:\dev\null — MEASURED, not assumed. The
            // R415 live matrix caught the cost of getting this wrong:
            // a model's ordinary `... 2>/dev/null` was refused, so the
            // model had to rephrase a read-only command. A boundary
            // that cries wolf gets switched off.
            if (isNullDevice(target)) continue;
            boolean absolute = target.startsWith("/") || target.startsWith("\\")
                    || (target.length() >= 2 && target.charAt(1) == ':');
            if (absolute && !insideBoundary(target, engineCwd, scratchRoot)) {
                return "the command redirects output to '" + target
                        + "', which is outside the working directory";
            }
        }
        // ★ R415: A COMMAND THAT CAN WRITE MAY NOT NAME A PATH OUTSIDE
        // THE TREE ★ the live matrix caught the gap the two rules above
        // leave: they cover a REDIRECT and a ".." segment, but a command
        // that COPIES writes just as well. The model, refused by
        // file_write, staged the file inside the tree and copied it out:
        //
        //   cp .stage_escape.txt "C:\…\escape.txt"
        //
        // — an absolute path outside the tree, written to, and the
        // boundary did not notice. The boundary was not hard, and the
        // only reason it was found is that a live scenario tried to
        // leave the tree.
        //
        // ★ WHY "CAN WRITE" IS THE QUALIFIER ★ a read-only command may
        // look wherever it likes (that is exactly what isReadOnly
        // decides); but a command that can WRITE must not be pointed
        // outside the tree. The tree is the boundary, and a copy is a
        // write.
        if (!isReadOnlyCommand(effective)) {
            java.util.regex.Matcher ap = ABS_PATH.matcher(effective);
            while (ap.find()) {
                String p = ap.group(1);
                if (p == null || p.isEmpty()) continue;
                // ★ THE NULL DEVICE IS A SINK, NOT A LOCATION ★ the same
                // carve-out the redirect rule uses — kept in ONE place so
                // the two rules cannot drift (the first version of this
                // rule checked only redirects, and the general rule
                // caught /dev/null ⇒ the test went red).
                if (isNullDevice(p)) continue;
                // ★ A SINGLE-LETTER SEGMENT IS A FLAG, NOT A PATH ★
                // `dir /s /b .` is a Windows command with two flags, and
                // the boundary used to read `/s` as a POSIX absolute path
                // and refuse the command — with the absurd reason "the
                // command names '/s', which is outside the working
                // directory". R416's live matrix measured the cost: three
                // refusals in one turn, each of which the model answered
                // by rewording the same command. The rule is stated ONCE
                // and is not platform-dependent: a one-character segment
                // is a flag on every platform.
                if (isSingleLetterSegment(p)) continue;
                if (!insideBoundary(p, engineCwd, scratchRoot)) {
                    return "the command names '" + p
                            + "', which is outside the working directory, and a command that can "
                            + "write may not name a path outside the tree"
                            + sourceNote(expansion, p);
                }
            }
        }
        return null;
    }

    /**
     * A one-character path segment: {@code /s}, {@code /a}, {@code /n}.
     *
     * <p>★ ONE RULE, NO PLATFORM BRANCH ★ the null device needed two
     * platform-dependent branches and was rewritten to name both; this one
     * needs none, because a one-character segment is a flag everywhere.
     */
    private static boolean isSingleLetterSegment(String token) {
        if (token == null || token.length() != 2) return false;
        if (token.charAt(0) != '/' && token.charAt(0) != '\\') return false;
        return Character.isLetter(token.charAt(1));
    }

    /**
     * The variable a refused path came from, when it came from one.
     *
     * <p>★ THE DIAGNOSIS DECIDES WHAT THE MODEL DOES NEXT ★ "the command
     * names 'D:\…\escape.txt'" leaves the model hunting for a path it
     * never wrote; "… (from %SCENARIOS_ROOT%)" names the thing it has to
     * change.
     */
    private static String sourceNote(org.aethercode.core.scope.ScopeEnvExpansion.Result expansion,
            String path) {
        if (expansion == null || expansion.expanded() == null) return "";
        for (java.util.Map.Entry<String, String> e : expansion.expanded().entrySet()) {
            String value = e.getValue();
            if (value == null || value.isEmpty()) continue;
            if (path.equals(value) || path.startsWith(value)) {
                return " (from %" + e.getKey() + "%)";
            }
        }
        return "";
    }

    /**
     * Test hook: the scope check with an explicit environment.
     *
     * <p>★ THE MACHINE'S OWN ENVIRONMENT IS NOT A FIXTURE ★ %TEMP% means
     * something different on every machine, so the test says which
     * environment it means.
     */
    static String checkScopeEscapeForTest(String command, File engineCwd,
            java.util.Map<String, String> env) {
        return checkScopeEscape(command, engineCwd, env);
    }

    /**
     * R417 test hook: the scope check with an explicit environment AND an
     * explicit scratch area.
     *
     * <p>★ WHY THE SCRATCH IS STATED ★ the scratch area comes from the
     * ENVIRONMENT (the engine home), and a test cannot set an environment
     * variable. A test that used the ambient value would measure whatever
     * this machine happens to say.
     */
    static String checkScopeEscapeForTest(String command, File engineCwd,
            java.util.Map<String, String> env, java.nio.file.Path scratchRoot) {
        return checkScopeEscape(command, engineCwd, env, scratchRoot);
    }

    /**
     * R417 test hook: the environment a session's shell child runs with.
     */
    static java.util.Map<String, String> shellEnvForTest(Tool.CallContext ctx) {
        return shellEnv(ctx);
    }

    /**
     * Is this command one that cannot write?
     *
     * <p>The permission classifier's answer is reused on purpose: it is
     * already the product's own line between "looking" and "writing", and
     * a second, looser list here would be a second rule to drift.
     */
    private static boolean isReadOnlyCommand(String command) {
        if (command == null || command.isBlank()) return false;
        return isReadOnly(Map.of("command", command));
    }

    /**
     * The null device: a sink, not a location.
     *
     * <p>★ WHY THE RULE IS PLATFORM-INDEPENDENT ★ the null device has
     * two names — {@code /dev/null} on POSIX and {@code NUL} on
     * Windows — and a rule with two platform-dependent branches has
     * two behaviours and no single witness. The carve-out names both,
     * so the rule has ONE meaning and ONE mutation that breaks it.
     *
     * <p>★ THE WINDOWS EDGE, STATED PLAINLY ★ on Windows {@code NUL}
     * is the null device and {@code /dev/null} is a path — but cmd.exe
     * does not create the missing {@code dev} directory, so the
     * command fails and writes nothing (measured). The one hole is a
     * machine that already has {@code D:\dev}: then
     * {@code > /dev/null} would create {@code D:\dev\null}. That is a
     * deliberate, documented trade for one rule and one witness.
     */
    private static boolean isNullDevice(String target) {
        String t = target.trim().toLowerCase(java.util.Locale.ROOT);
        return t.equals("/dev/null") || t.equals("/dev/nul") || t.equals("nul")
                || t.equals("/dev/stdout") || t.equals("/dev/stderr");
    }

    /** A parent-directory path segment: "../x", "..\\x", or a bare "..". */
    private static final java.util.regex.Pattern PARENT_SEGMENT =
            java.util.regex.Pattern.compile("(^|[\\s\"'=:(,])\\.\\.[/\\\\]");

    /**
     * A {@code cd} / {@code pushd} and its target, anywhere in the command.
     *
     * <p>group(1) = the quote that may open the target, group(2) = the
     * target itself. The prefix {@code (?:^|[^a-z0-9_])} keeps a word
     * like {@code abcd} from being read as a {@code cd}.
     */
    private static final java.util.regex.Pattern CD_TARGET =
            java.util.regex.Pattern.compile("(?i)(?:^|[^a-z0-9_])(?:cd|pushd)\\s+([\"']?)([^\\s;&|]+)");

    /** A redirection and its target. */
    private static final java.util.regex.Pattern REDIRECT =
            java.util.regex.Pattern.compile(">>?\\s*([^\\s>]+)");

    /**
     * An absolute path anywhere in the command.
     *
     * <p>group(1) = the path. Three shapes:
     * <ul>
     *   <li>{@code C:\dir\file} — a drive path (Windows),</li>
     *   <li>{@code \\server\share\file} — a UNC path,</li>
     *   <li>{@code /dir/file} — a POSIX path.</li>
     * </ul>
     *
     * <p>★ WHY THE POSIX FORM NEEDS A LOOKBEHIND ★ a URL contains slashes
     * ("https://example.com/x"), and a rule that treats every slash as
     * an absolute path would refuse every download. The lookbehind keeps
     * a path that starts a word (or follows a quote, a bracket or an
     * equals sign) and drops the one that continues a word.
     *
     * <p>★ AND WHY THE DRIVE FORM NEEDS A LOOKAHEAD ★ R416's live matrix
     * measured what the drive form did to a URL: in
     * {@code curl -sI https://repo1.maven.org} the scheme's last letter
     * and its colon read as a DRIVE LETTER, so the boundary refused the
     * command with the reason "the command names
     * 's://repo1.maven.org', which is outside the working directory".
     * The model then tried eight different ways to download the same jar
     * and was refused eight times, with a reason it could not act on.
     * ⇒ a drive path is a letter, a colon, and ONE separator: {@code C:\}
     * or {@code C:/}. A scheme's {@code ://} is two separators, and a
     * URL is not a path.
     */
    private static final java.util.regex.Pattern ABS_PATH =
            java.util.regex.Pattern.compile(
                    "(?i)([A-Za-z]:[\\\\/](?!/)[^\\s\"'<>|;)]*|\\\\\\\\[^\\s\"'<>|;)]*"
                            + "|(?<![\\w:])(?<!/)/[^\\s\"'<>|;)]+)");

    /**
     * Replace every quoted segment with a placeholder.
     *
     * <p>★ WHY ★ a quote is how a shell says "this is TEXT". Without
     * this, {@code grep "a > b"} looks like a redirection and
     * {@code sed 's/>/x/'} looks like one too — and a boundary that
     * cries wolf gets switched off.
     */
    private static String stripQuoted(String command) {
        return command
                .replaceAll("\"[^\"]*\"", "Q")
                .replaceAll("'[^']*'", "Q");
    }

    /**
     * resolve the cwd fallback when the tool
     * invocation doesn't pass an explicit {@code cwd}.
     *
     * <p>legacy this used {@code new File("").getAbsoluteFile()},
     * which is the JVM's startup working directory (the
     * process's user.dir). That's almost never what the
     * user wants: the daemon was launched in some
     * neutral directory, but the actual project is at
     * the engine's session cwd (the path set via the
     * {@code switchProject} / {@code bindSessionCwd}
     * RPC, persisted in {@code AppState.cwd()}).
     *
     * <p>Lookup order:
     * <ol>
     *   <li>{@code ctx.extra("app_state")} cast to
     *       {@code AppState}; use its
     *       {@link org.aethercode.core.app.AppState#cwd()}
     *       if non-null.</li>
     *   <li>Fall back to {@code new File("").getAbsoluteFile()}
     *       (the legacy behaviour) for callers that pass
     *       a context without an AppState (older tests,
     *       standalone bash usage outside the engine).</li>
     * </ol>
     *
     * <p>This is a pure helper, no side effects on the
     * tool's input or the engine.
     */
    private static File defaultCwd(Tool.CallContext ctx) {
        if (ctx != null) {
            Object appStateObj = ctx.extra("app_state");
            if (appStateObj instanceof org.aethercode.core.app.AppState appState) {
                try {
                    java.nio.file.Path p = appState.cwd();
                    if (p != null) {
                        File f = p.toFile();
                        if (f.isDirectory()) {
                            return f;
                        }
                    }
                } catch (Throwable ignored) {
                    // AppState.cwd() can throw if the
                    // engine is partially constructed;
                    // fall through to the user.dir
                    // fallback below.
                }
            }
        }
        return new File("").getAbsoluteFile();
    }

    private static boolean isInsideCwd(String target, File engineCwd) {
        if (target == null || target.isBlank() || engineCwd == null) return true;
        try {
            // ★ BY SEGMENT, NOT BY PREFIX ★ the old check was
            // `tgt.startsWith(engine)`, so a SIBLING whose name merely
            // starts with the tree's name counted as inside:
            // "…\sb-L1X" is inside "…\sb-L1" as a string, and it is NOT
            // inside it as a directory. (The same one-letter bug the
            // write scope's SessionPaths had — Lesson 1604's shape: a
            // boundary that is only a string comparison.)
            java.nio.file.Path engine = engineCwd.getCanonicalFile().toPath();
            java.nio.file.Path tgt = new File(target).getCanonicalFile().toPath();
            return tgt.startsWith(engine);
        } catch (Exception e) {
            return true; // be permissive on IO errors
        }
    }

    /**
     * R417: is this path inside the BOUNDARY? The tree, or the session's
     * scratch area.
     *
     * <p>★ ONE PREDICATE, THREE CALL SITES ★ the {@code cd} rule, the
     * redirect rule and the general "a command that can write may not
     * name a path outside the tree" rule each answer "is this inside?".
     * Three copies of that answer is three answers to one question, and
     * they drift (R380's lesson) — the scratch area would be allowed in
     * one of them and refused in the others.
     *
     * @param target the path as it appears in the command (absolute)
     * @param engineCwd the session's working directory
     * @param scratchRoot the session's scratch area, or null
     */
    private static boolean insideBoundary(String target, File engineCwd,
            java.nio.file.Path scratchRoot) {
        if (isInsideCwd(target, engineCwd)) return true;
        if (scratchRoot == null || target == null || target.isBlank()) return false;
        try {
            java.nio.file.Path tgt = new File(target).getCanonicalFile().toPath();
            // ★ BY SEGMENT, NOT BY PREFIX ★ the same rule the tree check
            // uses: a sibling whose name merely starts with the
            // scratch's name is NOT inside it.
            try {
                // the real path when the scratch exists (so a link INTO
                // the scratch counts as inside)
                return tgt.startsWith(scratchRoot.toRealPath());
            } catch (Exception notCreatedYet) {
                // the scratch directory does not exist yet — the first
                // write of a session creates it. Compare the normalized
                // absolute paths instead.
                return tgt.startsWith(scratchRoot.toAbsolutePath().normalize());
            }
        } catch (Exception e) {
            return false;
        }
    }

    /** foreground mode. Runs the command, streams output,
     *  waits for completion, returns the captured output.
     *  Package-private (not just private) so R326's regression
     *  test can call it directly without going through the
     *  full tool surface. */
    static Tool.ToolResult runForeground(String command, File cwd, int timeout,
                                        boolean stream, Tool.CallContext ctx) {
        Process process = null;
        try {
            Process processRef = start(command, cwd, ctx);
            process = processRef;
            StringBuilder stdout = new StringBuilder();
            StringBuilder stderr = new StringBuilder();
            Thread t1 = drain(process.getInputStream(), stdout, "out", stream, ctx);
            Thread t2 = drain(process.getErrorStream(), stderr, "err", stream, ctx);
            boolean finished = process.waitFor(timeout, TimeUnit.MILLISECONDS);
            if (!finished) {
                // R271: kill the whole process tree — mvn /
                // surefire / test JVM, not just the direct
                // child. Without this the timeout leaks
                // grandchild JVMs that the daemon still
                // considers "busy".
                killProcessTree(process);
                t1.join(2000);
                t2.join(2000);
                return Tool.ToolResult.error("command timed out after " + timeout + "ms");
            }
            t1.join(2000);
            t2.join(2000);
            // If the call was aborted while the process was finishing, report it.
            if (ctx.isAborted()) {
                return Tool.ToolResult.error("command cancelled by user");
            }
            int code = process.exitValue();
            String out = stdout.toString();
            String err = stderr.toString();
            StringBuilder body = new StringBuilder();
            body.append("(exit ").append(code).append(")\n");
            if (!out.isEmpty()) body.append("--- stdout ---\n").append(out);
            if (!err.isEmpty()) body.append("--- stderr ---\n").append(err);
            if (code != 0) {
                return new Tool.ToolResult(body.toString(), null, true);
            }
            return Tool.ToolResult.of(body.toString());
        } catch (Exception e) {
            // R271: same reasoning — exception path
            // (process builder failed, IO broke, etc.) must
            // also tear down the whole tree, not just the
            // direct child.
            if (process != null) killProcessTree(process);
            LOG.warn("bash tool failed: {}", e.toString());
            return Tool.ToolResult.error("command failed: " + e.getMessage());
        }
    }

    /** background mode. Spawns the process, registers it in
     *  the {@link BashJobRegistry}, returns a job id immediately. The
     *  process runs in daemon threads; the call returns synchronously
     *  with a "job started" message. */
    private static Tool.ToolResult spawnBackground(String command, File cwd, int timeout,
                                                    boolean stream, Tool.CallContext ctx) {
        Process process;
        try {
            process = start(command, cwd, ctx);
        } catch (Exception e) {
            return Tool.ToolResult.error("failed to start background command: " + e.getMessage());
        }
        String jobId = JOBS.register(command, process, ctx);
        Thread t1 = drain(process.getInputStream(), JOBS.stdoutOf(jobId), "out", stream, ctx, jobId);
        Thread t2 = drain(process.getErrorStream(), JOBS.stderrOf(jobId), "err", stream, ctx, jobId);
        // Watcher thread: when the process exits, mark the job done.
        Thread watcher = new Thread(() -> {
            try {
                int code = process.waitFor();
                t1.join(2000);
                t2.join(2000);
                JOBS.markCompleted(jobId, code);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, "bash-job-" + jobId);
        watcher.setDaemon(true);
        watcher.start();
        return Tool.ToolResult.of("(background) job " + jobId + " started: " + firstLine(command));
    }

    private static Process start(String command, File cwd) throws java.io.IOException {
        return start(command, cwd, null);
    }

    /**
     * R417: start the child with the SESSION's environment — the one
     * that names the scratch area.
     */
    private static Process start(String command, File cwd, Tool.CallContext ctx)
            throws java.io.IOException {
        // R326: when the agent writes `mkdir -p <path>` (the
        // standard Unix idiom for "create parent dirs and the
        // leaf"), cmd.exe on Windows treats `-p` as a literal
        // directory name and creates a sibling folder named
        // exactly "-p" alongside the intended target. This is
        // silent (no error) and pollutes the user's cwd with
        // junk folders on every session that calls mkdir. The
        // fix: detect `mkdir -p` on Windows and rewrite the
        // command to use PowerShell's `New-Item -ItemType
        // Directory -Force`, which IS recursive. We only
        // rewrite when the command actually starts with `mkdir
        // -p` (or `mkdir -pv` / `mkdir -p -v` variants) to
        // avoid touching unrelated commands.
        String effectiveCommand = command;
        if (isWindows() && startsWithMkdirP(command)) {
            effectiveCommand = rewriteMkdirPForWindows(command);
        }
        ProcessBuilder pb = new ProcessBuilder();
        if (isWindows()) {
            pb.command("cmd.exe", "/c", effectiveCommand);
        } else {
            pb.command("/bin/sh", "-c", effectiveCommand);
        }
        pb.directory(cwd);
        pb.redirectErrorStream(false);
        // ★ R416: THE CHILD MUST NOT INHERIT THE DAEMON'S CREDENTIALS ★
        // the shell is a CHILD of the daemon, so it inherited the daemon's
        // whole environment — and the daemon's environment carries the
        // user's provider keys. R416's live matrix measured the cost: the
        // model ran `set`, and every key in the environment landed in the
        // model's context AND in the session transcript on disk.
        // ⇒ the child gets the environment MINUS the credentials, and the
        // scope check expands against the SAME map — so the checker and
        // the shell see one environment, by construction.
        pb.environment().clear();
        pb.environment().putAll(shellEnv(ctx));
        return pb.start();
    }

    /**
     * The environment the shell child runs with.
     *
     * <p>★ ONE MAP, TWO USERS ★ the scope check expands the command
     * against this map and the child process runs with this map. If they
     * were two maps, the boundary would again be looking at a different
     * language than the one that runs (R416's lesson).
     */
    private static java.util.Map<String, String> shellEnv() {
        return shellEnv(null);
    }

    /**
     * The environment the shell child runs with, for THIS session.
     *
     * <p>★ R417: THE SCRATCH AREA IS NAMED IN THE ENVIRONMENT ★ the
     * child is told where the session's scratch directory is, so a
     * command that needs a temporary file can put it there — and the
     * scope check expands against the SAME map (R416's one-map rule),
     * so the checker and the shell see one environment by construction.
     */
    private static java.util.Map<String, String> shellEnv(Tool.CallContext ctx) {
        java.util.Map<String, String> env = System.getenv();
        if ("1".equals(env.get("AETHERCODE_BASH_ALLOW_CREDENTIALS"))) {
            // ★ THE OPT-OUT ★ a task that genuinely needs a credential in
            // the shell (a deploy with a token in the environment) can ask
            // for it by name. The model cannot set it: the switch is read
            // from the DAEMON's environment, not from the shell's.
            //
            // ★ A COPY ★ System.getenv() is unmodifiable, and the scratch
            // variable has to be added on top of it.
            java.util.Map<String, String> copy = new java.util.LinkedHashMap<>(env);
            putScratch(copy, ctx);
            return copy;
        }
        java.util.Map<String, String> out = sanitizedEnv(env);
        putScratch(out, ctx);
        return out;
    }

    /**
     * R417: name the session's scratch area in the environment.
     *
     * <p>★ ABSENT WHEN THERE IS NO SCRATCH ★ an unknown scratch must not
     * become an empty variable: a shell that expands {@code
     * %AETHERCODE_SCRATCH%} to the empty string would resolve
     * {@code %AETHERCODE_SCRATCH%\x.txt} to {@code \x.txt} — a path at
     * the drive root. Better absent than empty.
     */
    private static void putScratch(java.util.Map<String, String> env, Tool.CallContext ctx) {
        if (env == null || ctx == null) return;
        java.nio.file.Path scratch = org.aethercode.core.scope.SessionPaths.scratchRoot(ctx);
        if (scratch == null) return;
        env.put(SCRATCH_ENV, scratch.toString());
    }

    /**
     * The environment without the credential-shaped names.
     *
     * <p>★ ONE RULE, STATED ONCE ★ a name is credential-shaped when it
     * carries API_KEY / APIKEY / TOKEN / SECRET / PASSWORD / PASSWD /
     * CREDENTIAL / PRIVATE_KEY. The rule is by NAME and not by value, so
     * it never has to look at a secret to decide.
     */
    static java.util.Map<String, String> sanitizedEnv(java.util.Map<String, String> env) {
        java.util.Map<String, String> out = new java.util.LinkedHashMap<>();
        if (env == null) return out;
        for (java.util.Map.Entry<String, String> e : env.entrySet()) {
            if (isCredentialName(e.getKey())) continue;
            out.put(e.getKey(), e.getValue());
        }
        return out;
    }

    /** Is this an environment variable whose NAME says it carries a secret? */
    static boolean isCredentialName(String name) {
        if (name == null) return false;
        String n = name.toUpperCase(java.util.Locale.ROOT);
        return n.contains("API_KEY") || n.contains("APIKEY")
                || n.contains("TOKEN") || n.contains("SECRET")
                || n.contains("PASSWORD") || n.contains("PASSWD")
                || n.contains("CREDENTIAL") || n.contains("PRIVATE_KEY");
    }

    /**
     * R326: returns {@code true} when the given command line
     * starts with a {@code mkdir -p} (with optional extra
     * flags like {@code -v} or {@code -pv}). The check is
     * tolerant of leading whitespace and an optional path
     * prefix like {@code /usr/bin/mkdir}. Anything else —
     * including {@code mkdir} without {@code -p}, or
     * {@code mkdir -p} appearing later in a pipe chain — is
     * left untouched (the second case is rare in practice
     * because the agent almost always runs mkdir as the first
     * segment).
     */
    private static boolean startsWithMkdirP(String command) {
        if (command == null) return false;
        String s = command.stripLeading();
        // Find `mkdir` only at a segment start (preceded by
        // start-of-string, `;`, `&&`, or `||`). Otherwise we
        // misidentify `echo mkdir -p /tmp` as starting with
        // mkdir. Allow an optional path prefix like
        // `/usr/bin/mkdir`.
        java.util.regex.Matcher m = java.util.regex.Pattern.compile(
                "(?:^|;|&&|\\|\\|)\\s*((?:\\S+/)?mkdir)\\b"
            ).matcher(s);
        if (!m.find()) return false;
        // After `mkdir`, scan the next whitespace-delimited
        // tokens. The first must be a flag string containing
        // `-p`; we then look at the next token (the path) to
        // confirm there is one.
        String rest = s.substring(m.end()).stripLeading();
        if (rest.isEmpty()) return false;
        int sp = rest.indexOf(' ');
        String firstArg = sp >= 0 ? rest.substring(0, sp) : rest;
        if (!firstArg.matches("-[a-zA-Z]*p[a-zA-Z]*")) return false;
        // Need at least one path token after the flags.
        String afterFlags = sp >= 0 ? rest.substring(sp + 1).stripLeading() : "";
        return !afterFlags.isEmpty();
    }

    /** R326: package-private wrapper so the regression test can
     *  exercise the detection logic without going through the
     *  full tool surface. */
    static boolean startsWithMkdirPForTest(String command) {
        return startsWithMkdirP(command);
    }

    /**
     * R326: rewrite {@code mkdir -p [-flags] <path> [...more paths]}
     * so that it works under cmd.exe. We replace {@code mkdir -p}
     * with a single {@code powershell -NoProfile -Command
     * "New-Item -ItemType Directory -Force -Path <path1>,<path2>"}
     * call. The original command is otherwise preserved (so
     * {@code && echo done} chaining still works).
     *
     * Implementation: find the first {@code mkdir -p ...}
     * segment, parse out the path arguments (preserving
     * quoting), and replace the whole segment with the
     * PowerShell equivalent. Anything before / after the
     * segment is left untouched.
     */
    private static String rewriteMkdirPForWindows(String command) {
        // Find the `mkdir` token. We accept leading whitespace
        // and a path prefix.
        java.util.regex.Matcher m = java.util.regex.Pattern.compile(
                "(^|\\s|;|&&|\\|\\|)mkdir(\\s+-?[a-zA-Z]+)*(\\s+.*?)(?=;|&&|\\|\\||$)"
            ).matcher(command);
        if (!m.find()) return command;
        String prefix = m.group(1) != null ? m.group(1) : "";
        String flags = m.group(2) != null ? m.group(2) : "";
        String argsAndRest = m.group(3) != null ? m.group(3) : "";
        // Parse out the path arguments — they end at the first
        // shell metacharacter or end-of-string. Quoted paths
        // may contain spaces. We collect everything up to the
        // first unquoted `;`, `&&`, `||`, or end of string.
        java.util.List<String> paths = new java.util.ArrayList<>();
        java.util.regex.Matcher m2 = java.util.regex.Pattern.compile(
                "\"([^\"]*)\"|'([^']*)'|(\\S+)"
            ).matcher(argsAndRest);
        while (m2.find()) {
            String p = m2.group(1) != null ? m2.group(1)
                    : m2.group(2) != null ? m2.group(2)
                    : m2.group(3);
            if (p == null || p.isEmpty()) continue;
            // Stop when we hit a shell control token (it would
            // appear as a bare token like `&&` or `;`).
            if (p.equals("&&") || p.equals("||") || p.equals("|") || p.equals(";")) break;
            // Skip flag-like tokens that aren't paths (e.g.
            // `-m 0755` if user mixed Unix flags in).
            if (p.startsWith("-") && !p.matches("-[a-zA-Z]*p[a-zA-Z]*")) continue;
            paths.add(p);
        }
        if (paths.isEmpty()) return command;
        // Quote each path for PowerShell (escape any inner
        // single quotes by doubling them).
        StringBuilder psPaths = new StringBuilder();
        for (int i = 0; i < paths.size(); i++) {
            if (i > 0) psPaths.append(',');
            String p = paths.get(i);
            psPaths.append('\'').append(p.replace("'", "''")).append('\'');
        }
        String psCmd = "powershell -NoProfile -Command \"New-Item -ItemType Directory -Force -Path "
                + psPaths + "\"";
        String before = command.substring(0, m.start() + prefix.length());
        // Recompute the trailing rest from the original command
        // (the matched group's boundaries don't always line up
        // with `m.end()` once we've inspected sub-patterns).
        int boundary = m.end();
        String restOfCommand = boundary < command.length() ? command.substring(boundary) : "";
        // Drop any flags we consumed (we keep them out of the
        // ps call since `-Force` already implies recursive).
        return before + psCmd + restOfCommand;
    }

    /**
     * R271 (2026-09-15): kill the process AND its entire
     * descendant tree. {@link Process#destroy()} and
     * {@link Process#destroyForcibly()} only terminate the
     * direct child; on Windows that's a single
     * {@code TerminateProcess(pid)} which leaves every
     * grandchild orphaned — which is exactly what happened
     * when {@code mvn test} forked surefire booters and test
     * JVMs: the daemon's {@code BashTool.runForeground} saw
     * {@code process.waitFor} return, but the run was still
     * "busy" because the orchestrator waited on something the
     * user couldn't see. The user pressed Esc, the engine
     * cancelled the run, but {@code destroyForcibly} only
     * killed mvn itself — surefire kept running, the engine
     * never observed the exit, and every subsequent prompt
     * was rejected with "[busy] session is busy with
     * run-1".
     *
     * <p>Implementation:
     * <ul>
     *   <li><b>Windows</b>: shell out to {@code taskkill /T /F
     *       /PID <pid>}. The {@code /T} flag traverses the
     *       child tree, {@code /F} forces. We also
     *       {@code destroyForcibly} the direct handle as a
     *       belt-and-suspenders fallback in case {@code taskkill}
     *       is unavailable (e.g. running under a stripped
     *       Windows container).</li>
     *   <li><b>POSIX</b>: prefer {@code kill -TERM -<pgid>}
     *       which targets the whole process group (POSIX
     *       guarantee, no walking needed). Falls back to
     *       {@code destroyForcibly} when the OS refuses the
     *       group signal.</li>
     * </ul>
     *
     * <p>Best-effort: any failure is logged but never
     * propagated — killing is a side-effect of cancel /
     * timeout, and we don't want the cancel path itself to
     * throw and confuse the engine. */
    public static void killProcessTree(Process process) {
        if (process == null) return;
        long pid = process.pid();
        try {
            if (isWindows()) {
                // taskkill /T /F /PID <pid> traverses the
                // process tree on Windows. /F forces. We
                // swallow the exit code because failure to
                // find the process is "fine" — it likely
                // already died.
                ProcessBuilder pb = new ProcessBuilder(
                        "taskkill", "/T", "/F", "/PID", Long.toString(pid));
                pb.redirectErrorStream(true);
                Process tk = pb.start();
                // Best-effort drain so the taskkill
                // child doesn't leave its own zombie.
                try {
                    tk.getInputStream().readAllBytes();
                } catch (Exception ignored) {}
                tk.waitFor(2, java.util.concurrent.TimeUnit.SECONDS);
            } else {
                // POSIX: signal the whole process group.
                // Process.toHandle() exposes pid(); the group
                // leader is the process whose PID equals its
                // PGID, so kill -<pid> signals the group
                // when the process is the leader.
                try {
                    ProcessHandle ph = process.toHandle();
                    long pgid = (long) ph.getClass()
                            .getMethod("pgid").invoke(ph);
                    if (pgid > 0 && pgid == pid) {
                        // We are the group leader — kill
                        // the whole group.
                        new ProcessBuilder("kill", "-TERM", "-" + pgid)
                                .redirectErrorStream(true).start().waitFor(1,
                                        java.util.concurrent.TimeUnit.SECONDS);
                    }
                } catch (Throwable groupKillFailed) {
                    // The ProcessHandle.pgid() method is JDK
                    // 9+ on Linux/Mac but not on every
                    // platform — fall through to the
                    // single-process fallback.
                    LOG.debug("R271: pgid group-kill unavailable: {}",
                            groupKillFailed.toString());
                }
            }
        } catch (Throwable t) {
            LOG.warn("R271: killProcessTree({}) failed: {}", pid, t.toString());
        }
        // Always also destroy the direct handle — covers the
        // case where taskkill / kill -TERM isn't on PATH and
        // the JVM-fallback is the only thing we have.
        try {
            process.destroyForcibly();
        } catch (Throwable ignored) {}
        try {
            if (!process.waitFor(1, java.util.concurrent.TimeUnit.SECONDS)) {
                // Last resort: nothing left to do. The OS
                // will reap when the parent exits.
                LOG.warn("R271: process {} did not exit within 1s after kill", pid);
            }
        } catch (Throwable ignored) {}
    }

    public static boolean isDestructive(Map<String, Object> input) { return true; }

    // the SMART_PERMISSION mode (UI label "智能授权")
    // auto-allows read-only bash commands so the model can
    // run `ls`, `cat`, `pwd`, `find`, etc. without a prompt.
    // The check is deliberately conservative: if any
    // destructive token (`rm`, `mv`, `chmod`, `>`, `|`, `&&`,
    // `;`, `sudo`, etc.) appears ANYWHERE in the command,
    // we fall through to the standard ask path. The
    // denylist is also layered on top — `rm -rf` is
    // refused outright by `checkDenylist` above; this
    // method only short-circuits the SMART permission
    // path, not the safety net.
    private static final java.util.Set<String> READ_ONLY_COMMANDS = java.util.Set.of(
            // POSIX
            "ls", "cat", "pwd", "echo", "env", "which", "where", "whoami", "date",
            "head", "tail", "wc", "sort", "uniq", "tr", "cut",
            "find", "grep", "egrep", "fgrep", "rg", "ag",
            "stat", "file", "test", "[",
            "df", "du", "tree", "uname", "hostname",
            "ifconfig", "ip", "netstat", "ss", "lsof",
            "less", "more", "man", "tldr",
            "true", "false", "printenv", "id", "logname",
            "basename", "dirname", "realpath", "readlink",
            // Windows equivalents
            "dir", "type", "ver", "systeminfo", "tasklist", "sc", "wmic"
    );
    // Note: `tee` is NOT in READ_ONLY_COMMANDS because tee
    // writes to a file. The segment-split above handles
    // `cat a | head` (both arms read-only) and `cat a | tee
    // b` (second arm's first word is "tee", not in the
    // list, so the verdict is false). `|` is the segment
    // separator so it doesn't need to be in DESTRUCTIVE_TOKENS.
    private static final String[] DESTRUCTIVE_TOKENS = {
            // redirect (NOT `|` — pipe is a segment separator)
            ">", ">>", "<", "&&", "||", ";", "`", "$(",
            // chmod / chown / rm / mv / cp / ln / kill / sudo
            "rm ", "rm\t", " mv ", " cp ", " ln ", " chmod", " chown",
            " kill", " sudo", " su ", " dd ", " mkfs", " shutdown",
            // single & (background) and 2> (stderr redirect)
            "&", "2>", "&>"
    };
    /**
     * classify a bash command as read-only (safe to
     * auto-allow under SMART mode) or mutating. Returns
     * {@code true} when the command is one of the documented
     * read-only commands AND contains no destructive token.
     * Empty / missing commands default to mutating (the
     * tool itself rejects them with a "command required"
     * error, but the prompter sees them first — better to
     * ask than to auto-allow).
     */
    public static boolean isReadOnly(Map<String, Object> input) {
        if (input == null) return false;
        Object cmdObj = input.get("command");
        if (!(cmdObj instanceof String cmd) || cmd.isBlank()) return false;
        String trimmed = cmd.strip();
        if (trimmed.isEmpty()) return false;
        // Split on shell metachars to extract the first
        // command word. We DO support pipes / chained
        // commands — the SAFE chain (every command in the
        // pipe must be read-only) gets auto-allow; the
        // moment we see a destructive token, the whole
        // command is treated as mutating.
        String[] segments = trimmed.split("[&|;]+");
        for (String seg : segments) {
            String t = seg.strip();
            if (t.isEmpty()) continue;
            // Strip leading env-var assignments (FOO=bar cmd).
            while (t.matches("^[A-Za-z_][A-Za-z0-9_]*=\\S+\\s.+")) {
                int sp = t.indexOf(' ');
                if (sp < 0) break;
                t = t.substring(sp + 1).strip();
            }
            // First token is the command (or path).
            int sp = t.indexOf(' ');
            String first = (sp < 0 ? t : t.substring(0, sp)).trim();
            // Strip path prefix (`/bin/ls` → `ls`).
            int slash = first.lastIndexOf('/');
            if (slash >= 0) first = first.substring(slash + 1);
            int bslash = first.lastIndexOf('\\');
            if (bslash >= 0) first = first.substring(bslash + 1);
            // On Windows `.exe` is implicit; strip it.
            if (first.toLowerCase(java.util.Locale.ROOT).endsWith(".exe")) {
                first = first.substring(0, first.length() - 4);
            }
            if (!READ_ONLY_COMMANDS.contains(first.toLowerCase(java.util.Locale.ROOT))) {
                return false;
            }
        }
        // Even if every segment is a known read-only command,
        // a destructive token anywhere makes the call unsafe.
        for (String tok : DESTRUCTIVE_TOKENS) {
            if (trimmed.contains(tok)) return false;
        }
        return true;
    }

    /** drain an output stream into a sink, optionally
     *  emitting each line as a progress message. Returns the
     *  thread so the caller can join on shutdown. */
    private static Thread drain(InputStream in, StringBuilder sink, String kind,
                                 boolean stream, Tool.CallContext ctx) {
        return drain(in, sink, kind, stream, ctx, null);
    }

    private static Thread drain(InputStream in, StringBuilder sink, String kind,
                                 boolean stream, Tool.CallContext ctx, String jobId) {
        // a separate flag for cancellation that the drain
        // thread checks between lines. ctx.isAborted() is the
        // official signal, but we also need to actually destroy
        // the process (otherwise the drain thread just sees
        // EOF and exits normally — the process is still running).
        AtomicBoolean cancelObserved = new AtomicBoolean(false);
        Thread t = new Thread(() -> {
            try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    synchronized (sink) { sink.append(line).append('\n'); }
                    if (stream && ctx != null) {
                        ctx.emit(Message.assistantText("[" + kind + "] " + line));
                    }
                    if (ctx != null && ctx.isAborted() && cancelObserved.compareAndSet(false, true)) {
                        // Best-effort kill. The process handle is
                        // owned by the caller; we can't destroy it
                        // directly from here. Mark cancelObserved and
                        // let the caller destroy the process.
                        if (jobId != null) {
                            // Background mode: kill via the registry.
                            BashJob job = JOBS.get(jobId);
                            if (job != null && job.process != null) killProcessTree(job.process);
                        }
                        // Foreground: the main thread sees isAborted()
                        // and destroys the process. We just stop reading.
                        break;
                    }
                }
            } catch (Exception ignored) {}
        }, "bash-tool-drain-" + kind);
        t.setDaemon(true);
        t.start();
        return t;
    }

    private static boolean isDenylisted(String cmd) {
        return checkDenylist(cmd) != null;
    }

    /** a denylist hit with both the matched pattern
     *  and a human-readable reason. The model reads the
     *  reason from the error message and rephrases. */
    public record DenyReason(String pattern, String reason) {}

    /** built-in denylist patterns. The user can extend
     *  these via the {@code AETHERCODE_BASH_DENYLIST} env
     *  var (regexes, comma-separated); the built-in set is
     *  always active. Each entry is a (regex, reason) pair
     *  so the reject message tells the model *why* the
     *  command was refused, not just *that* it was.
     *
     *  <p>The set targets the most common
     *  "I-trust-the-model-but-the-user-would-prevent-this"
     *  mistakes. False positives are accepted — a user
     *  who genuinely needs to {@code sudo apt update} can
     *  set {@code AETHERCODE_BASH_DENYLIST=""} to disable
     *  the built-in set. The env-var path is appended to
     *  the built-in set, never replaces it. */
    private static final java.util.List<Object[]> BUILTIN_DENY = List.of(
            new Object[]{"\\brm\\s+-rf?\\s+/(?!tmp|var/tmp|home/[^/]+/tmp)(?:\\s|$)",
                    "rm -rf / deletes the root filesystem"},
            new Object[]{"\\brm\\s+-rf?\\s+~/?(?:\\s|$)",
                    "rm -rf ~ deletes the user's home directory"},
            new Object[]{"\\bsudo\\b",
                    "sudo runs commands as root; use a scoped privilege tool or run as a non-root user"},
            new Object[]{"\\bgit\\s+push\\s+(?:-f|--force)",
                    "git push -f rewrites remote history; use a force-with-lease or a revert"},
            new Object[]{"\\bcurl\\b[^|]*\\|\\s*(?:sudo\\s+)?(?:ba)?sh\\b",
                    "curl|sh downloads and executes a script in one step; download + review first"},
            new Object[]{"\\b(?:wget|curl)\\b[^|]*\\|\\s*(?:sudo\\s+)?(?:ba)?sh\\b",
                    "wget|sh downloads and executes a script in one step; download + review first"},
            new Object[]{"\\bmkfs\\b",
                    "mkfs formats a filesystem; this is destructive and irreversible"},
            new Object[]{"\\bdd\\b[^&]*\\bof=\\s*/dev/",
                    "dd of=/dev/* writes raw bytes to a device; this can destroy the disk"},
            new Object[]{"\\bdd\\b[^&]*\\bif=[^&]*\\s+of=/dev/(?:sd|nvme|hd)",
                    "dd of=/dev/sd* /dev/nvme* /dev/hd* destroys the disk's partition table"},
            new Object[]{"\\bchmod\\s+-R\\s+777\\s+/(?:\\s|$)",
                    "chmod -R 777 / makes the entire filesystem world-writable; this is almost never what you want"},
            new Object[]{":\\(\\)\\s*\\{\\s*:\\|:&\\s*\\};:",
                    "fork bomb: classic denial-of-service pattern"},
            new Object[]{"\\b(?:mkfs|fdisk|parted)\\s+/dev/(?:sd|nvme|hd)",
                    "partitioning tools on a block device destroy the existing filesystem"},
            new Object[]{"\\bcurl\\b.*-X\\s+(?:PUT|POST|DELETE|PATCH)\\s+[^|]*\\|\\s*(?:ba)?sh",
                    "curl -X with pipe to sh: an HTTP write piped to a shell, almost certainly a data exfiltration or supply-chain attack"},
            new Object[]{"\\b(?:shutdown|reboot|halt|poweroff)\\b",
                    "shutdown/reboot terminates the daemon itself"}
    );

    /** check the denylist (built-in + env-var) and
     *  return the first match with its reason. Returns
     *  {@code null} when the command is allowed. */
    static DenyReason checkDenylist(String cmd) {
        // Built-in set first — they have explicit reasons
        // that the model can act on.
        for (Object[] entry : BUILTIN_DENY) {
            String pat = (String) entry[0];
            String reason = (String) entry[1];
            try {
                if (java.util.regex.Pattern.compile(pat).matcher(cmd).find()) {
                    return new DenyReason(pat, reason);
                }
            } catch (Exception ignored) {}
        }
        // User extensions: AETHERCODE_BASH_DENYLIST. These
        // don't have reasons (the env var is a plain
        // regex list) so we use a generic "user denylist"
        // message. The user can opt to add their own
        // reasons via a config file in a future round.
        String env = System.getenv("AETHERCODE_BASH_DENYLIST");
        if (env == null || env.isBlank()) return null;
        for (String pat : env.split(",")) {
            if (pat.isBlank()) continue;
            try {
                if (java.util.regex.Pattern.compile(pat).matcher(cmd).find()) {
                    return new DenyReason(pat, "matches user denylist pattern");
                }
            } catch (Exception ignored) {}
        }
        return null;
    }

    private static String firstLine(String s) {
        int idx = s.indexOf('\n');
        return idx < 0 ? s : s.substring(0, idx);
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }

    // -----------------------------------------------------------------
    // background job registry
    // -----------------------------------------------------------------

    /** One background bash job. Captures the command, the
     *  {@link Process} handle, the live output buffers, and a
     *  monotonic sequence counter for progress emission ordering. */
    public static final class BashJob {
        public final String id;
        public final String command;
        public final long startedAtMs;
        public final Process process;
        public final StringBuilder stdout = new StringBuilder();
        public final StringBuilder stderr = new StringBuilder();
        public final AtomicBoolean done = new AtomicBoolean(false);
        public volatile int exitCode = -1;
        public final Tool.CallContext ctx;
        public BashJob(String id, String command, Process process, Tool.CallContext ctx) {
            this.id = id; this.command = command; this.process = process;
            this.startedAtMs = System.currentTimeMillis();
            this.ctx = ctx;
        }
        public boolean isRunning() { return !done.get(); }
    }

    /** registry of background bash jobs. Process-singleton.
     *  Exposed as a static field on {@link BashTool} (see {@link #JOBS}).
     *  Operations are thread-safe; readers and writers can be on
     *  any thread. */
    public static final class BashJobRegistry {
        private final Map<String, BashJob> byId = new ConcurrentHashMap<>();
        private final AtomicLong counter = new AtomicLong();

        public synchronized String register(String command, Process p, Tool.CallContext ctx) {
            String id = "j-" + Long.toString(counter.incrementAndGet(), 36);
            byId.put(id, new BashJob(id, command, p, ctx));
            return id;
        }
        public BashJob get(String id) { return byId.get(id); }
        public List<BashJob> list() {
            List<BashJob> out = new ArrayList<>(byId.values());
            out.sort((a, b) -> Long.compare(b.startedAtMs, a.startedAtMs));
            return out;
        }
        public void markCompleted(String id, int code) {
            BashJob j = byId.get(id);
            if (j != null) {
                j.exitCode = code;
                j.done.set(true);
            }
        }
        public boolean kill(String id) {
            BashJob j = byId.get(id);
            if (j == null || j.done.get()) return false;
            // R271: kill the whole tree so cancelling a
            // backgrounded mvn test actually reaps the surefire
            // grandchild JVMs.
            killProcessTree(j.process);
            return true;
        }
        public StringBuilder stdoutOf(String id) { BashJob j = byId.get(id); return j == null ? new StringBuilder() : j.stdout; }
        public StringBuilder stderrOf(String id) { BashJob j = byId.get(id); return j == null ? new StringBuilder() : j.stderr; }
    }
}
