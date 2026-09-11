package org.aethercode.hooks.builtin;

import org.aethercode.core.tool.Tool;
import org.aethercode.hooks.Hook;
import org.aethercode.hooks.Hook.Outcome;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * prior round: unit tests for {@link EditErrorRecoveryHook}.
 *
 * <p>prior round: the hook now returns
 * {@link Outcome.ContinueWithResult} (not just {@code Continue})
 * so the post-bridge rebuilds the result with the recovery
 * hint appended. We assert:
 * <ul>
 *   <li>non-{@code file_edit} tools → Continue (no action)</li>
 *   <li>successful edits → Continue (no action)</li>
 *   <li>unknown error pattern → Continue (no action)</li>
 *   <li>matched pattern → ContinueWithResult whose
 *       {@code newOutput} contains the original error AND the
 *       reminder</li>
 * </ul>
 */
class EditErrorRecoveryHookTest {

    private final EditErrorRecoveryHook hook = new EditErrorRecoveryHook();

    @Test
    void nonFileEditToolSkipped() throws Exception {
        Outcome o = hook.run(ctx("bash", errorResult("old_string not found"))).get();
        assertThat(o).isInstanceOf(Outcome.Continue.class);
    }

    @Test
    void fileEditSuccessSkipped() throws Exception {
        Outcome o = hook.run(ctx("file_edit", successResult("edited /tmp/foo"))).get();
        assertThat(o).isInstanceOf(Outcome.Continue.class);
    }

    @Test
    void fileEditErrorUnknownPatternSkipped() throws Exception {
        Outcome o = hook.run(ctx("file_edit", errorResult("some other failure"))).get();
        assertThat(o).isInstanceOf(Outcome.Continue.class);
    }

    @Test
    void oldStringIdenticalDetectedAndMutated() throws Exception {
        String err = "old_string and new_string are identical — no-op rejected";
        Outcome o = hook.run(ctx("file_edit", errorResult(err))).get();
        assertThat(o).isInstanceOf(Outcome.ContinueWithResult.class);
        String out = ((Outcome.ContinueWithResult) o).newOutput();
        assertThat(out).contains(err);
        assertThat(out).contains("IMMEDIATE ACTION REQUIRED");
    }

    @Test
    void oldStringNotFoundDetectedAndMutated() throws Exception {
        String err = "old_string not found in /tmp/foo.txt";
        Outcome o = hook.run(ctx("file_edit", errorResult(err))).get();
        assertThat(o).isInstanceOf(Outcome.ContinueWithResult.class);
        assertThat(((Outcome.ContinueWithResult) o).newOutput()).contains("IMMEDIATE ACTION REQUIRED");
    }

    @Test
    void oldStringMultipleMatchesDetectedAndMutated() throws Exception {
        String err = "old_string matches 3 places in /tmp/foo.txt";
        Outcome o = hook.run(ctx("file_edit", errorResult(err))).get();
        assertThat(o).isInstanceOf(Outcome.ContinueWithResult.class);
    }

    @Test
    void caseInsensitive() throws Exception {
        Outcome o = hook.run(ctx("file_edit",
                errorResult("OLD_STRING not found in /tmp/foo.txt"))).get();
        assertThat(o).isInstanceOf(Outcome.ContinueWithResult.class);
    }

    @Test
    void nullResultSkipped() throws Exception {
        Hook.HookContext c = Hook.HookContext.forPost("s1", "file_edit",
                new HashMap<>(), null);
        Outcome o = hook.run(c).get();
        assertThat(o).isInstanceOf(Outcome.Continue.class);
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    private Hook.HookContext ctx(String toolName, Tool.ToolResult result) {
        Map<String, Object> input = new HashMap<>();
        input.put("file_path", "/tmp/foo.txt");
        return Hook.HookContext.forPost("s1", toolName, input, result);
    }

    private Tool.ToolResult errorResult(String body) {
        return new Tool.ToolResult(body, List.of(), true);
    }
    private Tool.ToolResult successResult(String body) {
        return new Tool.ToolResult(body, List.of(), false);
    }
}
