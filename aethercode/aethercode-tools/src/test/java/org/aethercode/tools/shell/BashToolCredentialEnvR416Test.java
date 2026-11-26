package org.aethercode.tools.shell;

import org.aethercode.core.app.AppState;
import org.aethercode.core.scope.ShellWriteDetector;
import org.aethercode.core.scope.WriteScope;
import org.aethercode.core.tool.Tool;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * R416 — the shell must not inherit the daemon's credentials.
 *
 * <h2>★ WHAT THE LIVE MATRIX MEASURED ★</h2>
 * <p>The shell is a CHILD of the daemon, so it inherited the daemon's whole
 * environment — and the daemon's environment carries the user's provider
 * keys. In the L1 scenario the model ran {@code set}, and every key in the
 * environment landed in the model's context <b>and</b> in the session
 * transcript <b>on disk</b>:
 *
 * <pre>
 *   DEEPSEEK_API_KEY=…
 *   MINIMAX_API_KEY=…
 *   ZHIPUAI_API_KEY=…
 * </pre>
 *
 * <p>⇒ the child gets the environment MINUS the credential-shaped names.
 * The rule is by NAME, so it never has to look at a secret to decide.
 *
 * <h2>★ AND THE CHECKER SEES THE SAME ENVIRONMENT ★</h2>
 * <p>The scope check expands the command against the very map the child
 * process runs with. Two maps would mean the boundary is again looking at
 * a different language than the one that runs.
 */
class BashToolCredentialEnvR416Test {

    private static Map<String, Object> input(String command) {
        Map<String, Object> in = new HashMap<>();
        in.put("command", command);
        return in;
    }

    private static Tool.CallContext ctxIn(Path cwd) {
        Tool.CallContext ctx = Tool.CallContext.of("s1");
        ctx.setExtra("app_state", new AppState("s1", cwd));
        ctx.setExtra(WriteScope.EXTRAS_KEY, new WriteScope(cwd, "", false));
        ctx.setExtra(ShellWriteDetector.EXTRAS_KEY, new ShellWriteDetector());
        return ctx;
    }

    private static boolean isWindows() {
        String os = System.getProperty("os.name");
        return os != null && os.toLowerCase(java.util.Locale.ROOT).contains("win");
    }

    @Test
    @DisplayName("R416: a credential-shaped name is not inherited by the shell")
    void aCredentialShapedNameIsNotInheritedByTheShell() {
        Map<String, String> env = new LinkedHashMap<>();
        env.put("PATH", "C:\\bin");
        env.put("MINIMAX_API_KEY", "secret-1");
        env.put("DEEPSEEK_API_KEY", "secret-2");
        env.put("GITHUB_TOKEN", "secret-3");
        env.put("DB_PASSWORD", "secret-4");
        env.put("AWS_SECRET_ACCESS_KEY", "secret-5");

        Map<String, String> out = BashTool.sanitizedEnv(env);

        assertThat(out).doesNotContainKeys(
                "MINIMAX_API_KEY", "DEEPSEEK_API_KEY", "GITHUB_TOKEN",
                "DB_PASSWORD", "AWS_SECRET_ACCESS_KEY");
        assertThat(out).containsEntry("PATH", "C:\\bin");
    }

    @Test
    @DisplayName("R416: an ordinary variable is still inherited")
    void anOrdinaryVariableIsStillInherited() {
        Map<String, String> env = new LinkedHashMap<>();
        env.put("TEMP", "C:\\Temp");
        env.put("JAVA_HOME", "C:\\jdk");
        env.put("AETHERCODE_ALLOW_ANY_PATH", "1");

        Map<String, String> out = BashTool.sanitizedEnv(env);

        assertThat(out).containsEntry("TEMP", "C:\\Temp")
                .containsEntry("JAVA_HOME", "C:\\jdk")
                .containsEntry("AETHERCODE_ALLOW_ANY_PATH", "1");
    }

    @Test
    @DisplayName("R416: the shell's environment dump carries NO credential")
    void theShellsEnvironmentDumpCarriesNoCredential(@TempDir Path root) {
        // ★ THE PROOF ON THE MACHINE ★ the rule test above is
        // deterministic; this one runs the real shell and looks at what it
        // prints. The dump must be NON-EMPTY (so the check is not vacuous)
        // and must carry no credential-shaped name.
        String dump = isWindows() ? "set" : "env";
        Tool.ToolResult r =
                BashTool.call(input(dump), ctxIn(root));
        String out = r.output() == null ? "" : r.output().toString();

        assertThat(r.isError()).isFalse();
        assertThat(out.trim())
                .as("the dump must have produced something, or the assertion below is vacuous")
                .isNotEmpty();
        for (String line : out.split("\\r?\\n")) {
            String name = line.split("=", 2)[0].trim();
            assertThat(BashTool.isCredentialName(name))
                    .as("the shell must not see %s", name)
                    .isFalse();
        }
    }
}
