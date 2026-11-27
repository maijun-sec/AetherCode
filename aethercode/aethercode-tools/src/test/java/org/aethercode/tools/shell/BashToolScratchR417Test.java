package org.aethercode.tools.shell;

import org.aethercode.core.app.AppState;
import org.aethercode.core.scope.SessionPaths;
import org.aethercode.core.scope.ShellWriteDetector;
import org.aethercode.core.scope.WriteScope;
import org.aethercode.core.tool.Tool;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * R417 — the shell must know about the session's scratch area, and the
 * shell's boundary must accept it.
 *
 * <h2>★ WHY THIS EXISTS ★</h2>
 * <p>R416's live matrix measured a turn that left nine files in the
 * user's tree — a {@code .verify/} scaffolding directory, a verification
 * script, a transcript of the run. All of them were LEGAL writes (they
 * were inside the tree), so a tighter boundary could not have helped:
 * the model chose the wrong place.
 *
 * <p>⇒ the engine now owns a per-session scratch area, and the shell is
 * TOLD about it through the environment — the same shape the industry
 * uses when it points a sandbox's temporary directory at the tools
 * through an environment variable.
 *
 * <h2>★ THE TWO CHANNELS MUST AGREE ★</h2>
 * <p>The file tools write into the scratch area (the write scope's W0),
 * and the shell must be allowed to as well. If the shell's boundary
 * refused a redirect into the scratch while the file tools allowed the
 * same write, the model would learn that one of the two is broken — and
 * would work around it in the user's tree.
 */
class BashToolScratchR417Test {

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

    @Test
    @DisplayName("R417: the shell's environment names the session's scratch area")
    void theShellsEnvironmentNamesTheScratchArea(@TempDir Path root) {
        Tool.CallContext ctx = ctxIn(root);

        Map<String, String> env = BashTool.shellEnvForTest(ctx);

        assertThat(env).containsKey(BashTool.SCRATCH_ENV);
        assertThat(env.get(BashTool.SCRATCH_ENV))
                .as("the variable must name THIS session's scratch area")
                .isEqualTo(SessionPaths.scratchRoot("s1").toString());
    }

    @Test
    @DisplayName("R417: a redirect into the scratch area is allowed")
    void aRedirectIntoTheScratchIsAllowed(@TempDir Path root, @TempDir Path scratch) {
        Map<String, String> env = new HashMap<>();
        String target = scratch.resolve("out.txt").toString();

        String violation = BashTool.checkScopeEscapeForTest(
                "echo hello > " + target, root.toFile(), env, scratch);

        assertThat(violation)
                .as("the scratch area is outside the tree ON PURPOSE — the shell's boundary "
                        + "must accept it, exactly like the file tools'")
                .isNull();
    }

    @Test
    @DisplayName("R417: a redirect outside the tree AND the scratch area is still refused")
    void aRedirectOutsideBothIsStillRefused(@TempDir Path root, @TempDir Path scratch) {
        Map<String, String> env = new HashMap<>();
        String target = root.getParent().resolve("escape-" + root.getFileName() + ".txt").toString();

        String violation = BashTool.checkScopeEscapeForTest(
                "echo hello > " + target, root.toFile(), env, scratch);

        assertThat(violation)
                .as("the scratch area is a CARVEOUT, not a wider hole")
                .isNotNull();
        assertThat(violation).contains("outside the working directory");
    }

    @Test
    @DisplayName("R417: a command that can write may name the scratch area")
    void aWriteCommandMayNameTheScratchArea(@TempDir Path root, @TempDir Path scratch) {
        Map<String, String> env = new HashMap<>();
        String target = scratch.resolve("copy.txt").toString();

        String violation = BashTool.checkScopeEscapeForTest(
                "cp source.txt " + target, root.toFile(), env, scratch);

        assertThat(violation)
                .as("the general rule (a command that can write may not name a path outside the "
                        + "tree) must know about the scratch area too")
                .isNull();
    }

    @Test
    @DisplayName("R417: without a scratch area the boundary is exactly as before")
    void withoutAScratchAreaTheBoundaryIsUnchanged(@TempDir Path root, @TempDir Path scratch) {
        Map<String, String> env = new HashMap<>();
        String target = scratch.resolve("out.txt").toString();

        String violation = BashTool.checkScopeEscapeForTest(
                "echo hello > " + target, root.toFile(), env, null);

        assertThat(violation)
                .as("a scope with no scratch area has no carve-out")
                .isNotNull();
    }
}
