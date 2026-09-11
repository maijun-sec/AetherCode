package org.aethercode.permission.categorize;

import java.util.List;

/**
 * T-230..T-234 / design.md §3.2: the built-in rule table that
 * ships with the {@code aethercode-permission} module.
 *
 * <p>Mirrors the table from {@code design.md §3.2} verbatim so
 * the on-disk behaviour matches the spec. Users can extend
 * (T-232) by passing additional rules to
 * {@link RiskCategorizer#withRule(Rule)}; their rules are
 * evaluated in addition to (and after) these defaults.
 *
 * <p>Each rule carries a stable {@code id} so the
 * {@code ConsentPrompt} can show "matched rule: shell_destructive"
 * instead of a positional index.
 */
public final class DefaultRules {

    private DefaultRules() {}

    /** The full default rule table. The order is the evaluation
     *  order: low-risk read tools first (fast allow), then
     *  medium-risk writes, then the destructive / high-risk
     *  shell commands, then MCP / python_run, then the catch-all
     *  shell.command medium rule. The order is documented because
     *  {@code ConsentPrompt} renders it in the "why is this
     *  risky?" help view. */
    public static List<Rule> all() {
        return List.of(
                // ---- read-only / low risk (T-230, design.md) ----
                readOnly("read_only.read_file", "read_file"),
                readOnly("read_only.glob_files", "glob_files"),
                readOnly("read_only.grep_files", "grep_files"),
                readOnly("read_only.ls", "ls"),
                readOnly("read_only.file_read", "file_read"),
                readOnly("read_only.list_files", "list_files"),

                // ---- shell destructive (high) ----
                // rm / rm -rf / mv (destructive to the fs)
                argHigh("shell.destructive", "command",
                        "\\brm\\b.*-[a-zA-Z]*[rf][a-zA-Z]*\\b|\\brm\\b\\s+-[a-zA-Z]+\\b",
                        List.of("shell.command", "shell.destructive")),
                argHigh("shell.destructive_mv", "command",
                        "^\\s*mv\\s+",
                        List.of("shell.command", "shell.destructive")),
                argHigh("shell.destructive_cp_overwrite", "command",
                        "^\\s*cp\\s+.*\\s+/.*",
                        List.of("shell.command", "shell.destructive")),
                argHigh("shell.destructive_kill", "command",
                        "^\\s*kill\\s+",
                        List.of("shell.command", "shell.destructive")),

                // ---- package install (high) ----
                argHigh("shell.package_install.npm", "command",
                        "^\\s*npm\\s+(install|i|add)\\b",
                        List.of("shell.command", "shell.package_install")),
                argHigh("shell.package_install.pip", "command",
                        "^\\s*pip(\\d+)?\\s+install\\b",
                        List.of("shell.command", "shell.package_install")),
                argHigh("shell.package_install.cargo", "command",
                        "^\\s*cargo\\s+add\\b",
                        List.of("shell.command", "shell.package_install")),
                argHigh("shell.package_install.yarn", "command",
                        "^\\s*yarn\\s+(add|install)\\b",
                        List.of("shell.command", "shell.package_install")),
                argHigh("shell.package_install.pnpm", "command",
                        "^\\s*pnpm\\s+(add|install|i)\\b",
                        List.of("shell.command", "shell.package_install")),
                // Maven (`mvn install` / `mvn deploy`) and
                // Gradle (`gradle publish`) are high-risk: install
                // writes into the local Maven cache, deploy
                // pushes a build artefact to a remote repo, and
                // `mvn clean` deletes the target/ directory. The
                // user explicitly asked for `mvn` to land in this
                // list. We match on the bare subcommand token
                // (not the version flag) so `mvn -B install`
                // and `mvn -DskipTests deploy` both trip.
                argHigh("shell.package_install.mvn", "command",
                        "\\bmvn\\b.*\\b(install|deploy|clean|package|verify|release|org\\.apache\\.maven\\.plugins\\.maven-deploy-plugin)\\b",
                        List.of("shell.command", "shell.package_install")),
                argHigh("shell.package_install.gradle", "command",
                        "\\bgradle\\b.*\\b(publish|build|installDist|uploadArchives)\\b",
                        List.of("shell.command", "shell.package_install")),
                // `make` is a generic recipe runner — `make install`
                // often writes to /usr/local, `make clean` deletes
                // build artefacts. Match the bare `install` /
                // `clean` / `uninstall` targets.
                argHigh("shell.package_install.make", "command",
                        "^\\s*make\\b.*\\b(install|uninstall|clean|distclean)\\b",
                        List.of("shell.command", "shell.package_install")),

                // ---- git mutation (high) ----
                argHigh("shell.git_mutation.push_force", "command",
                        "\\bgit\\s+push\\b.*--force\\b|\\bgit\\s+push\\b.*-f\\b",
                        List.of("shell.command", "shell.git_mutation")),
                argHigh("shell.git_mutation.reset_hard", "command",
                        "\\bgit\\s+reset\\b.*--hard\\b",
                        List.of("shell.command", "shell.git_mutation")),
                argHigh("shell.git_mutation.clean_force", "command",
                        "\\bgit\\s+clean\\b.*-[a-zA-Z]*f",
                        List.of("shell.command", "shell.git_mutation")),

                // ---- network (medium, escalated to high on external) ----
                argMedium("shell.network.curl", "command",
                        "^\\s*curl\\s+",
                        List.of("shell.command", "shell.network")),
                argMedium("shell.network.wget", "command",
                        "^\\s*wget\\s+",
                        List.of("shell.command", "shell.network")),
                argMedium("shell.network.nc", "command",
                        "^\\s*nc\\s+|^\\s*netcat\\s+",
                        List.of("shell.command", "shell.network")),

                // ---- bash read-style (medium) ----
                argMedium("shell.read_only.ls", "command",
                        "^\\s*ls\\b",
                        List.of("shell.command")),
                argMedium("shell.read_only.cat", "command",
                        "^\\s*cat\\b",
                        List.of("shell.command")),
                argMedium("shell.read_only.echo", "command",
                        "^\\s*echo\\b",
                        List.of("shell.command")),

                // ---- file mutations (medium / high) ----
                fileMedium("file.write_new", "write_file",
                        List.of("file.write")),
                fileMedium("file.write_file", "file_write",
                        List.of("file.write")),
                fileMedium("file.edit", "file_edit",
                        List.of("file.write", "file.overwrite_existing")),
                fileHigh("file.delete_file", "delete_file",
                        List.of("file.delete")),
                fileHigh("file.delete", "file_delete",
                        List.of("file.delete")),

                // ---- python_run with dangerous code (high) ----
                argHigh("code.python_run.dangerous", "code",
                        "\\b(os\\.system|subprocess|exec\\(|eval\\()",
                        List.of("code.python_run", "shell.command")),
                argMedium("code.python_run", "code",
                        ".*",
                        List.of("code.python_run")),

                // ---- mcp tools (high) ----
                toolHigh("mcp.tool_invocation", "mcp_*",
                        List.of("mcp.tool_invocation")),

                // ---- web fetch (medium) ----
                toolMedium("network.external_request", "web_fetch",
                        List.of("network.external_request"))
        );
    }

    // ---- factories ----------------------------------------------------

    private static Rule readOnly(String id, String toolName) {
        return new Rule.ToolNameRule(id, toolName, Risk.LOW, List.of());
    }

    private static Rule toolMedium(String id, String toolName, List<String> cats) {
        return new Rule.ToolNameRule(id, toolName, Risk.MEDIUM, cats);
    }

    private static Rule toolHigh(String id, String toolName, List<String> cats) {
        return new Rule.ToolNameRule(id, toolName, Risk.HIGH, cats);
    }

    private static Rule fileMedium(String id, String toolName, List<String> cats) {
        return new Rule.ToolNameRule(id, toolName, Risk.MEDIUM, cats);
    }

    private static Rule fileHigh(String id, String toolName, List<String> cats) {
        return new Rule.ToolNameRule(id, toolName, Risk.HIGH, cats);
    }

    private static Rule argMedium(String id, String arg, String regex, List<String> cats) {
        return new Rule.ArgRegexRule(id, arg, regex, Risk.MEDIUM, cats);
    }

    private static Rule argHigh(String id, String arg, String regex, List<String> cats) {
        return new Rule.ArgRegexRule(id, arg, regex, Risk.HIGH, cats);
    }
}
