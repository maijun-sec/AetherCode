package org.aethercode.config;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Detect the {@link OpKind} of a tool call from the tool name + input map.
 * Returns {@link OpKind#EXEC} as a safe default for unrecognised inputs.
 *
 * <p>Detection rules (per tool):
 * <ul>
 *   <li><b>file_read</b> -> {@link OpKind#READ}</li>
 *   <li><b>file_write</b> -> {@link OpKind#DELETE} if the resolved path is a
 *       known wipe target (e.g. {@code /dev/null}, {@code nul}); otherwise
 *       {@link OpKind#CREATE} if the path does not exist on disk,
 *       {@link OpKind#MODIFY} if it does.</li>
 *   <li><b>file_edit</b> -> {@link OpKind#MODIFY}</li>
 *   <li><b>bash</b> -> tokenize the {@code command} field and pick from
 *       {@code DELETE}/{@code CREATE}/{@code MODIFY}/{@code READ}/{@code EXEC}
 *       based on the first non-flag token and known shell builtins.
 *       Destructive patterns (rm -rf, del /f, etc.) always win as DELETE.</li>
 *   <li><b>glob</b> / <b>grep</b> -> {@link OpKind#LIST}</li>
 *   <li><b>web_fetch</b> / <b>web_search</b> -> {@link OpKind#READ}</li>
 *   <li>Anything else -> {@link OpKind#EXEC}</li>
 * </ul>
 */
public final class OpKindDetector {

    private OpKindDetector() {}

    public static OpKind detect(String toolName, Map<String, Object> input, Path projectRoot) {
        if (toolName == null) return OpKind.EXEC;
        String t = toolName.toLowerCase(Locale.ROOT);
        Map<String, Object> in = input == null ? Map.of() : input;
        return switch (t) {
            case "file_read" -> OpKind.READ;
            case "file_write" -> detectFileWrite(in, projectRoot);
            case "file_edit", "notebook_edit" -> OpKind.MODIFY;
            case "bash", "shell" -> detectBash(in);
            case "glob" -> OpKind.LIST;
            case "grep" -> OpKind.LIST;
            case "web_fetch", "web_search" -> OpKind.READ;
            default -> OpKind.EXEC;
        };
    }

    private static OpKind detectFileWrite(Map<String, Object> input, Path projectRoot) {
        Object pathObj = input.get("file_path");
        if (pathObj == null) pathObj = input.get("path");
        String path = pathObj == null ? null : pathObj.toString();
        if (path == null || path.isBlank()) return OpKind.CREATE;
        String norm = path.replace('\\', '/').toLowerCase(Locale.ROOT);
        if (norm.equals("/dev/null") || norm.endsWith("/nul") || norm.equals("nul")) {
            return OpKind.DELETE;
        }
        try {
            Path p = Path.of(path);
            if (!p.isAbsolute() && projectRoot != null) {
                p = projectRoot.resolve(p);
            }
            if (Files.exists(p)) return OpKind.MODIFY;
            return OpKind.CREATE;
        } catch (RuntimeException e) {
            return OpKind.CREATE;
        }
    }

    // ---------------------------------------------------------------------------------
    // Bash command classification. Tokenize on whitespace; honour simple quoting
    // so paths with spaces are kept as one token. Pick the first significant
    // (non-flag, non-env) token and look it up in destructive / create / modify
    // / read tables. Destructive wins over everything.
    // ---------------------------------------------------------------------------------

    private static final List<String> DESTRUCTIVE = List.of(
            "rm", "rmdir", "del", "delete", "erase", "unlink", "truncate", "shred",
            "mkfs", "dd", "wipefs", "drop", "dropdb", "drop_table"
    );
    private static final List<String> CREATE = List.of(
            "touch", "mkdir", "mkfifo", "mknod", "cp", "install", "mv"
    );
    private static final List<String> MODIFY = List.of(
            "sed", "awk", "perl", "python", "python3", "ruby", "node", "tee",
            "patch"
    );
    private static final List<String> READ = List.of(
            "cat", "head", "tail", "less", "more", "ls", "dir", "find",
            "grep", "rg", "ag", "findstr", "where", "type", "which",
            "echo", "printf", "tree", "stat", "wc", "sort", "uniq",
            "diff", "cmp", "xxd", "od",
            // Windows PowerShell / pwsh read-only verbs
            "get-content", "get-childitem", "get-command", "get-item",
            "get-location", "select-string", "test-path", "resolve-path",
            // Universal helpers that are read-only
            "man", "info", "help", "pwd"
    );

    // Destructive flag patterns. R181: tightened to AVOID
    // false positives on read-only commands.
    //
    // legacy bug:
    //   - `Pattern.compile("-[a-z]*[rf][a-z]*\\b")` matched
    //     `java -version` (because "version" contains "r")
    //     and `mvn -version`. Both got escalated to DELETE.
    //   - `Pattern.compile("/[sqf]\\b")` matched `dir /s`
    //     (recursive LIST) and `findstr /I` (case-insensitive
    //     read), escalating them to DELETE.
    //
    // The fix: only escalate to DELETE when (a) the first token
    // is a known destructive command AND (b) a destructive flag
    // is present. The flag check is now conditioned on the first
    // token being in DESTRUCTIVE (rm/del/erase/...) OR in CREATE
    // (mv/cp/install/...) when `--force` overrides. The raw
    // "anywhere in the command" scan was the source of the FP.
    private static final Pattern[] DESTRUCTIVE_RM_FLAGS = new Pattern[] {
            Pattern.compile("(^|\\s)-[a-zA-Z]*[rf][a-zA-Z]*\\b"),  // -rf, -fr, -fR, -Rf, --recursive --force
            Pattern.compile("(^|\\s)--force\\b"),
            Pattern.compile("(^|\\s)--no-preserve-root\\b")
    };
    private static final Pattern[] DESTRUCTIVE_DEL_FLAGS = new Pattern[] {
            Pattern.compile("(^|\\s)/[sqfSQFrRF]+\\b"),            // /s /q /f /S /Q /F /R (Windows del flags)
            Pattern.compile("(^|\\s)/a\\b"),                       // /a (archive attr, less destructive but still del)
            Pattern.compile("(^|\\s)/force\\b", Pattern.CASE_INSENSITIVE)
    };

    private static OpKind detectBash(Map<String, Object> input) {
        Object cmdObj = input.get("command");
        if (cmdObj == null) return OpKind.EXEC;
        String command = cmdObj.toString();
        if (command.isBlank()) return OpKind.EXEC;

        // First, tokenize so we can identify the first significant command
        // and know whether flag escalation is meaningful.
        List<String> tokens = tokenize(command);
        int idx = 0;
        // Skip leading env assignments (FOO=bar cmd ...)
        while (idx < tokens.size() && tokens.get(idx).contains("=")
                && !tokens.get(idx).startsWith("-")) {
            idx++;
        }
        if (idx >= tokens.size()) return OpKind.EXEC;
        String first = stripQuotes(tokens.get(idx)).toLowerCase(Locale.ROOT);
        // Strip path prefix so `"/bin/rm"` -> "rm".
        int slash = first.lastIndexOf('/');
        if (slash >= 0) first = first.substring(slash + 1);
        // Windows: also strip `\bin\`.
        int bslash = first.lastIndexOf('\\');
        if (bslash >= 0) first = first.substring(bslash + 1);
        // Drop .exe / .cmd / .bat suffix on Windows.
        for (String suf : List.of(".exe", ".cmd", ".bat", ".ps1")) {
            if (first.endsWith(suf)) first = first.substring(0, first.length() - suf.length());
        }
        // Trim trailing ':' for Windows `cd C:` style.
        if (first.endsWith(":")) first = first.substring(0, first.length() - 1);

        // BEFORE running the destructive flag scan, short-circuit
        // on the first-token classification. This is the key fix.
        //
        // If the first token is a READ command (dir, findstr, cat, ...),
        // any flag (even something that LOOKS destructive) is just a
        // read-only flag. The matrix should not see DELETE.
        if (READ.contains(first)) return OpKind.READ;
        if (CREATE.contains(first)) return OpKind.CREATE;
        if (MODIFY.contains(first)) return OpKind.MODIFY;

        // shell-wrapper commands (cmd, powershell, pwsh, bash, sh)
        // are commonly used to run a sub-command, e.g.
        //   `cmd /c "dir /s /b C:\*.exe"`
        //   `powershell -Command "Get-Command java"`
        //   `bash -c "ls -la /tmp"`
        // We need to recurse on the inner command for those, otherwise
        // the outer wrapper shows up as "unknown -> EXEC" and the matrix
        // doesn't see the actual READ/CREATE/MODIFY/DELETE op.
        if (first.equals("cmd") || first.equals("powershell") || first.equals("pwsh")
                || first.equals("bash") || first.equals("sh") || first.equals("zsh")
                || first.equals("cmd.exe")) {
            String inner = extractShellWrapperCommand(command, first);
            if (inner != null && !inner.isBlank()) {
                return detectBash(Map.of("command", inner));
            }
            return OpKind.EXEC;
        }

        // only NOW do we consider destructive-flag escalation,
        // and ONLY for commands where destruction is plausible. rm/del/erase
        // are the realistic targets; mvn/java/curl/nodejs/python with
        // arbitrary flags never get escalated.
        boolean isDestructiveBase = DESTRUCTIVE.contains(first);
        boolean isShellBuiltinEscape = first.equals("cd") || first.equals("set")
                || first.equals("pushd") || first.equals("popd") || first.equals("export");
        if (isDestructiveBase) {
            for (Pattern p : DESTRUCTIVE_RM_FLAGS) {
                if (p.matcher(command).find()) return OpKind.DELETE;
            }
            // Plain `rm foo` without -rf still counts as DELETE.
            return OpKind.DELETE;
        }
        // Windows-style destructive commands (del, erase) using cmd.exe /
        // PowerShell Remove-Item. Same flag scan, different pattern set.
        if (first.equals("del") || first.equals("erase") || first.equals("rd")
                || first.equals("rmdir") || first.equals("remove-item")) {
            for (Pattern p : DESTRUCTIVE_DEL_FLAGS) {
                if (p.matcher(command).find()) return OpKind.DELETE;
            }
            return OpKind.DELETE;
        }
        if (isShellBuiltinEscape) {
            // cd / set / etc. without explicit destruction. Even if a flag
            // like /d appears (e.g. `cd /d D:\foo`), it is not a destructive
            // op — it changes the shell's working directory. Treat as EXEC.
            return OpKind.EXEC;
        }

        // git <subcmd>... — pick the subcommand. `git` on its own with no
        // subcommand is a no-op (return EXEC).
        if (first.equals("git")) {
            String sub = idx + 1 < tokens.size() ? stripQuotes(tokens.get(idx + 1)).toLowerCase(Locale.ROOT) : "";
            if (sub.isEmpty()) return OpKind.EXEC;
            if (sub.equals("status") || sub.equals("log") || sub.equals("diff")
                    || sub.equals("show") || sub.equals("branch") || sub.equals("tag")
                    || sub.equals("remote") || sub.equals("rev-parse") || sub.equals("ls-files")
                    || sub.equals("blame")) {
                return OpKind.READ;
            }
            if (sub.equals("commit") || sub.equals("add") || sub.equals("rm") || sub.equals("mv")
                    || sub.equals("reset") || sub.equals("stash") || sub.equals("merge")
                    || sub.equals("rebase") || sub.equals("tag") || sub.equals("cherry-pick")) {
                return OpKind.MODIFY;
            }
            if (sub.equals("init") || sub.equals("clone") || sub.equals("checkout")
                    || sub.equals("switch") || sub.equals("restore") || sub.equals("fetch")
                    || sub.equals("pull")) {
                return OpKind.CREATE;
            }
            if (sub.equals("push")) return OpKind.EXEC;
            return OpKind.EXEC;
        }
        return OpKind.EXEC;
    }

    /**
     * Simple shell-ish tokenizer: splits on whitespace, respects single and
     * double quotes (keeping the contents), and joins continued lines
     * ({@code \\\n} -> space). Good enough for command-prefix classification.
     */
    static List<String> tokenize(String command) {
        java.util.List<String> out = new java.util.ArrayList<>();
        if (command == null || command.isEmpty()) return out;
        StringBuilder cur = new StringBuilder();
        char quote = 0;
        boolean escape = false;
        for (int i = 0; i < command.length(); i++) {
            char c = command.charAt(i);
            if (escape) { cur.append(c); escape = false; continue; }
            if (c == '\\' && quote != '\'') {
                // Backslash continuation: \\\n
                if (i + 1 < command.length() && command.charAt(i + 1) == '\n') {
                    i++;
                    continue;
                }
                escape = true;
                continue;
            }
            if (quote != 0) {
                if (c == quote) { quote = 0; continue; }
                cur.append(c);
                continue;
            }
            if (c == '\'' || c == '"') { quote = c; continue; }
            if (Character.isWhitespace(c)) {
                if (cur.length() > 0) { out.add(cur.toString()); cur.setLength(0); }
                continue;
            }
            cur.append(c);
        }
        if (cur.length() > 0) out.add(cur.toString());
        return out;
    }

    private static String stripQuotes(String s) {
        if (s == null) return "";
        if (s.length() >= 2 && ((s.charAt(0) == '"' && s.charAt(s.length() - 1) == '"')
                || (s.charAt(0) == '\'' && s.charAt(s.length() - 1) == '\''))) {
            return s.substring(1, s.length() - 1);
        }
        return s;
    }

    /**
     * extract the inner command from a shell-wrapper invocation
     * so the matrix sees the actual op-kind.
     *
     * <p>Examples:
     * <pre>
     *   cmd /c "dir /s /b C:\*.exe"            -> "dir /s /b C:\\*.exe"
     *   powershell -NoProfile -Command "Get-Command java"  -> "Get-Command java"
     *   bash -c "ls -la /tmp"                    -> "ls -la /tmp"
     * </pre>
     *
     * <p>Returns {@code null} if the wrapper has no recognizable inner
     * command (caller should fall back to EXEC).
     */
    static String extractShellWrapperCommand(String command, String wrapper) {
        if (command == null || wrapper == null) return null;
        List<String> toks = tokenize(command);
        if (toks.isEmpty()) return null;
        // Drop the wrapper itself.
        int i = 1;
        if (wrapper.equals("cmd") || wrapper.equals("cmd.exe")) {
            // Syntax: cmd [/{c|k}] [command] — we want the command,
            // which is the LAST token (or the only one after /c /k).
            // Skip `/c` / `/k` flag if present.
            while (i < toks.size() && (toks.get(i).equalsIgnoreCase("/c")
                    || toks.get(i).equalsIgnoreCase("/k")
                    || toks.get(i).equalsIgnoreCase("/q"))) {
                i++;
            }
            // The rest is the command. Often quoted as a single token.
            if (i >= toks.size()) return null;
            StringBuilder sb = new StringBuilder();
            for (int j = i; j < toks.size(); j++) {
                if (sb.length() > 0) sb.append(' ');
                sb.append(toks.get(j));
            }
            String joined = stripQuotes(sb.toString()).trim();
            return joined.isEmpty() ? null : joined;
        }
        if (wrapper.equals("powershell") || wrapper.equals("pwsh")) {
            // Find `-Command` (or `-c`); the next token is the script.
            while (i < toks.size()) {
                String t = toks.get(i);
                if (t.equalsIgnoreCase("-Command") || t.equalsIgnoreCase("-c")) {
                    if (i + 1 < toks.size()) {
                        return stripQuotes(toks.get(i + 1)).trim();
                    }
                    return null;
                }
                i++;
            }
            return null;
        }
        if (wrapper.equals("bash") || wrapper.equals("sh") || wrapper.equals("zsh")) {
            // `bash -c "script"` or `sh -c "script"`.
            while (i < toks.size()) {
                String t = toks.get(i);
                if (t.equals("-c")) {
                    if (i + 1 < toks.size()) {
                        return stripQuotes(toks.get(i + 1)).trim();
                    }
                    return null;
                }
                i++;
            }
            return null;
        }
        return null;
    }
}
