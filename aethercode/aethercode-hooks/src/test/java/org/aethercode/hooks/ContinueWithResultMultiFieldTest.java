package org.aethercode.hooks;

import org.aethercode.core.app.AppState;
import org.aethercode.core.engine.StreamingToolExecutor;
import org.aethercode.core.tool.Tool;
import org.aethercode.core.tool.ToolDef;
import org.aethercode.core.tool.Tools;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * tests for the new multi-field
 * {@link Hook.Outcome.ContinueWithResult} variant. The legacy
 * single-arg constructor is preserved by the prior round tests in
 * {@code EditErrorRecoveryHookTest} and
 * {@code HooksAsPostBridgeTest}; the tests here focus on the new
 * capabilities (independent {@code newIsError} / {@code newAttachments}
 * mutation, the named factory methods, and the "all three null →
 * no-op" fast path).
 */
class ContinueWithResultMultiFieldTest {

    private static Tool dummyTool(String name) {
        return Tools.build(new ToolDef(name, "test", Map.of(),
                (in, ctx) -> CompletableFuture.completedFuture(Tool.ToolResult.of("ok"))));
    }

    /** Functional shape for tests that just want to declare the
     *  outcome they expect from a hook, without writing a full
     *  anonymous Hook class. */
    @FunctionalInterface
    private interface OutcomeBuilder {
        Hook.Outcome build();
    }

    private static StreamingToolExecutor.HookBridge.Outcome runWith(
            AppState appState, OutcomeBuilder outcomeBuilder,
            Tool.ToolResult result) {
        HookRegistry reg = new HookRegistry();
        reg.register(new Hook() {
            @Override public Kind kind() { return Kind.POST_TOOL_USE; }
            @Override public CompletableFuture<Outcome> run(HookContext ctx) {
                return CompletableFuture.completedFuture(outcomeBuilder.build());
            }
        });
        return Hooks.asPostBridge(reg).runWithOutcome(
                StreamingToolExecutor.HookBridge.Phase.POST,
                dummyTool("file_edit"),
                "call-1",
                Map.of("file_path", "/tmp/foo.txt"),
                result,
                appState);
    }

    // ---------------------------------------------------------------------------------
    //  newIsError mutation
    // ---------------------------------------------------------------------------------

    @Test
    void markErrorEscalatesSilentFailure(@TempDir Path tmp) {
        AppState appState = new AppState("s1", tmp);
        // Tool returned text that looks like success, but the hook
        // wants to escalate it to an error so the model retries.
        Tool.ToolResult original = new Tool.ToolResult("operation succeeded", List.of(), false);
        StreamingToolExecutor.HookBridge.Outcome out = runWith(appState,
                () -> Hook.Outcome.ContinueWithResult.markError(),
                original);
        assertThat(out.verdict()).isEqualTo(StreamingToolExecutor.HookBridge.Verdict.CONTINUE);
        assertThat(out.newResult()).isNotNull();
        assertThat(out.newResult().isError()).isTrue();
        // Output body is preserved (we did not pass newOutput).
        assertThat(out.newResult().output().toString()).isEqualTo("operation succeeded");
        // Attachments preserved.
        assertThat(out.newResult().attachments()).isEmpty();
    }

    @Test
    void markSuccessDowngradesError(@TempDir Path tmp) {
        AppState appState = new AppState("s1", tmp);
        Tool.ToolResult original = new Tool.ToolResult("nope", List.of(), true);
        StreamingToolExecutor.HookBridge.Outcome out = runWith(appState,
                () -> Hook.Outcome.ContinueWithResult.markSuccess(),
                original);
        assertThat(out.newResult()).isNotNull();
        assertThat(out.newResult().isError()).isFalse();
        assertThat(out.newResult().output().toString()).isEqualTo("nope");
    }

    // ---------------------------------------------------------------------------------
    //  newAttachments mutation
    // ---------------------------------------------------------------------------------

    @Test
    void clearAttachmentsRemovesUiSideChannel(@TempDir Path tmp) {
        AppState appState = new AppState("s1", tmp);
        Tool.Attachment diff = new Tool.Attachment.DiffPreview("/tmp/foo.txt", "a", "b");
        Tool.ToolResult original = new Tool.ToolResult("ok", List.of(diff), false);
        StreamingToolExecutor.HookBridge.Outcome out = runWith(appState,
                Hook.Outcome.ContinueWithResult::clearAttachments,
                original);
        assertThat(out.newResult()).isNotNull();
        assertThat(out.newResult().attachments()).isEmpty();
        // Output and isError preserved.
        assertThat(out.newResult().output().toString()).isEqualTo("ok");
        assertThat(out.newResult().isError()).isFalse();
    }

    @Test
    void replaceAttachmentsReplacesList(@TempDir Path tmp) {
        AppState appState = new AppState("s1", tmp);
        Tool.Attachment oldAtt = new Tool.Attachment.TextPreview("old", "x");
        Tool.Attachment newAtt = new Tool.Attachment.TextPreview("new", "y");
        Tool.ToolResult original = new Tool.ToolResult("ok", List.of(oldAtt), false);
        StreamingToolExecutor.HookBridge.Outcome out = runWith(appState,
                () -> Hook.Outcome.ContinueWithResult.replaceAttachments(List.of(newAtt)),
                original);
        assertThat(out.newResult()).isNotNull();
        assertThat(out.newResult().attachments()).containsExactly(newAtt);
    }

    // ---------------------------------------------------------------------------------
    //  Combined: multiple fields at once
    // ---------------------------------------------------------------------------------

    @Test
    void replaceAsErrorMutatesBothBodyAndFlag(@TempDir Path tmp) {
        AppState appState = new AppState("s1", tmp);
        Tool.ToolResult original = new Tool.ToolResult("partial output", List.of(), false);
        StreamingToolExecutor.HookBridge.Outcome out = runWith(appState,
                () -> Hook.Outcome.ContinueWithResult.replaceAsError("FAILED: please retry"),
                original);
        assertThat(out.newResult()).isNotNull();
        assertThat(out.newResult().isError()).isTrue();
        assertThat(out.newResult().output().toString()).isEqualTo("FAILED: please retry");
    }

    @Test
    void replaceAsSuccessMutatesBothBodyAndFlag(@TempDir Path tmp) {
        AppState appState = new AppState("s1", tmp);
        Tool.ToolResult original = new Tool.ToolResult("transient warning: blah", List.of(), true);
        StreamingToolExecutor.HookBridge.Outcome out = runWith(appState,
                () -> Hook.Outcome.ContinueWithResult.replaceAsSuccess("done"),
                original);
        assertThat(out.newResult()).isNotNull();
        assertThat(out.newResult().isError()).isFalse();
        assertThat(out.newResult().output().toString()).isEqualTo("done");
    }

    @Test
    void allThreeFieldsMutableIndependently(@TempDir Path tmp) {
        AppState appState = new AppState("s1", tmp);
        Tool.Attachment oldAtt = new Tool.Attachment.TextPreview("old", "x");
        Tool.Attachment newAtt = new Tool.Attachment.TextPreview("new", "y");
        Tool.ToolResult original = new Tool.ToolResult("old body", List.of(oldAtt), false);
        StreamingToolExecutor.HookBridge.Outcome out = runWith(appState,
                () -> new Hook.Outcome.ContinueWithResult("new body", true, List.of(newAtt)),
                original);
        assertThat(out.newResult()).isNotNull();
        assertThat(out.newResult().output().toString()).isEqualTo("new body");
        assertThat(out.newResult().isError()).isTrue();
        assertThat(out.newResult().attachments()).containsExactly(newAtt);
    }

    // ---------------------------------------------------------------------------------
    //  No-op fast path
    // ---------------------------------------------------------------------------------

    @Test
    void allNullFieldsYieldsNoReplacement(@TempDir Path tmp) {
        AppState appState = new AppState("s1", tmp);
        Tool.ToolResult original = new Tool.ToolResult("unchanged", List.of(), false);
        StreamingToolExecutor.HookBridge.Outcome out = runWith(appState,
                () -> new Hook.Outcome.ContinueWithResult(null, null, null),
                original);
        // The bridge should treat this as a no-op and pass through.
        assertThat(out.verdict()).isEqualTo(StreamingToolExecutor.HookBridge.Verdict.CONTINUE);
        assertThat(out.newResult()).isNull();
    }

    @Test
    void emptyStringOutputYieldsNoReplacement(@TempDir Path tmp) {
        // prior round convention: empty string means "no body change".
        AppState appState = new AppState("s1", tmp);
        Tool.ToolResult original = new Tool.ToolResult("unchanged", List.of(), false);
        StreamingToolExecutor.HookBridge.Outcome out = runWith(appState,
                () -> new Hook.Outcome.ContinueWithResult(""),
                original);
        assertThat(out.verdict()).isEqualTo(StreamingToolExecutor.HookBridge.Verdict.CONTINUE);
        assertThat(out.newResult()).isNull();
    }

    // ---------------------------------------------------------------------------------
    //  Backward compat
    // ---------------------------------------------------------------------------------

    @Test
    void singleArgConstructorMutatesOnlyOutput(@TempDir Path tmp) {
        AppState appState = new AppState("s1", tmp);
        Tool.Attachment att = new Tool.Attachment.TextPreview("p", "q");
        Tool.ToolResult original = new Tool.ToolResult("orig", List.of(att), true);
        StreamingToolExecutor.HookBridge.Outcome out = runWith(appState,
                () -> new Hook.Outcome.ContinueWithResult("new body"),
                original);
        assertThat(out.newResult()).isNotNull();
        assertThat(out.newResult().output().toString()).isEqualTo("new body");
        // isError + attachments inherited from original.
        assertThat(out.newResult().isError()).isTrue();
        assertThat(out.newResult().attachments()).containsExactly(att);
    }
}
