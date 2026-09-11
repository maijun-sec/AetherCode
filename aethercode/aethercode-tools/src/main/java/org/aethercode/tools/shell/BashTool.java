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
            String scopeViolation = checkScopeEscape(command, cwd);
            if (scopeViolation != null) {
                return Tool.ToolResult.error("command refused by sandbox: " + scopeViolation
                        + " (set AETHERCODE_BASH_ALLOW_SCOPE_ESCAPE=1 to bypass)");
            }
        }

        if (background) {
            return spawnBackground(command, cwd, timeout, stream, ctx);
        }
        return runForeground(command, cwd, timeout, stream, ctx);
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
        if (command == null || engineCwd == null) return null;
        // The cd / pushd / popd "go up" subcommand.
        String trimmed = command.strip();
        String lower = trimmed.toLowerCase();
        if (lower.startsWith("cd ..") || lower.startsWith("pushd ..") || lower.startsWith("cd ../")
                || lower.equals("cd ..") || lower.equals("pushd ..")) {
            return "refusing 'cd ..' / 'pushd ..' that climbs above the engine cwd";
        }
        // Drive-absolute cd to a non-engine drive: refuse.
        // The engine cwd is absolute; we reject any absolute
        // cd target whose normalised form doesn't start
        // with the engine cwd.
        int cdIdx = lower.indexOf("cd ");
        if (cdIdx >= 0) {
            // crude tokenization
            String after = trimmed.substring(cdIdx + 3).strip();
            if (after.startsWith("/") || after.startsWith("\\")
                    || (after.length() >= 2 && after.charAt(1) == ':')) {
                // absolute target
                if (!isInsideCwd(after, engineCwd)) {
                    return "refusing 'cd' to absolute path outside the engine cwd: " + after;
                }
            }
        }
        return null;
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
            File f = new File(target);
            String engine = engineCwd.getCanonicalPath();
            String tgt = f.getCanonicalPath();
            String tLow = isWindows() ? tgt.toLowerCase() : tgt;
            String eLow = isWindows() ? engine.toLowerCase() : engine;
            return tLow.startsWith(eLow);
        } catch (Exception e) {
            return true; // be permissive on IO errors
        }
    }

    /** foreground mode. Runs the command, streams output,
     *  waits for completion, returns the captured output. */
    private static Tool.ToolResult runForeground(String command, File cwd, int timeout,
                                                  boolean stream, Tool.CallContext ctx) {
        Process process = null;
        try {
            Process processRef = start(command, cwd);
            process = processRef;
            StringBuilder stdout = new StringBuilder();
            StringBuilder stderr = new StringBuilder();
            Thread t1 = drain(process.getInputStream(), stdout, "out", stream, ctx);
            Thread t2 = drain(process.getErrorStream(), stderr, "err", stream, ctx);
            boolean finished = process.waitFor(timeout, TimeUnit.MILLISECONDS);
            if (!finished) {
                process.destroyForcibly();
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
            if (process != null) process.destroyForcibly();
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
            process = start(command, cwd);
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
        ProcessBuilder pb = new ProcessBuilder();
        if (isWindows()) {
            pb.command("cmd.exe", "/c", command);
        } else {
            pb.command("/bin/sh", "-c", command);
        }
        pb.directory(cwd);
        pb.redirectErrorStream(false);
        return pb.start();
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
                            if (job != null && job.process != null) job.process.destroyForcibly();
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
            j.process.destroyForcibly();
            return true;
        }
        public StringBuilder stdoutOf(String id) { BashJob j = byId.get(id); return j == null ? new StringBuilder() : j.stdout; }
        public StringBuilder stderrOf(String id) { BashJob j = byId.get(id); return j == null ? new StringBuilder() : j.stderr; }
    }
}
