package org.aethercode.permission;

import org.aethercode.config.Action;
import org.aethercode.config.OpKind;
import org.aethercode.config.PermissionMatrix;
import org.aethercode.core.engine.PermissionPolicy;
import org.aethercode.core.permission.PermissionMode;
import org.aethercode.core.permission.PermissionResult;
import org.aethercode.core.tool.Tool;
import org.aethercode.core.tool.ToolDef;
import org.aethercode.core.tool.Tools;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link MatrixPermissionPolicy#withMode(PermissionMode)} MUST return
 * another {@code MatrixPermissionPolicy} (not a plain {@code
 * ProjectPermissionPolicy} like the parent's implementation does).
 *
 * <p>legacy, {@code AetherCodeEngine.setPermissionMode} called
 * {@code ppp.withMode(newMode)} where {@code ppp} was a
 * {@code MatrixPermissionPolicy} (the engine boots with one whenever
 * {@code .aethercode/config.json} supplies a {@code permissionMatrix}).
 * Because {@code MatrixPermissionPolicy} did NOT override
 * {@code withMode}, Java's virtual dispatch picked up the parent's
 * implementation, which returns a brand-new plain
 * {@code ProjectPermissionPolicy} — the matrix was silently dropped.
 * The user observed this as the perm-mode "jumping between 始终授权 (bypass)
 * and 询问 (ask)": the next tool call was correctly allowed (the new plain
 * policy with mode=BYPASS does allow everything), but on a later flip
 * to {@code ACCEPT_EDITS} or a {@code ConfigWatcher} reload, the
 * matrix's per-tool / per-path rules were gone.
 *
 * <p>The test pins five contracts:
 * <ol>
 *   <li>Return type is {@code MatrixPermissionPolicy} (not
 *       {@code ProjectPermissionPolicy}).</li>
 *   <li>Matrix, projectRoot, prompter, rules, and lowWaterline are
 *       carried over to the new policy.</li>
 *   <li>The {@link #withMode(PermissionMode)} chain is stable — calling
 *       it again on the result is still a {@code MatrixPermissionPolicy}.</li>
 *   <li>Behavioural: the matrix's {@code DENY} rule still blocks after a
 *       {@code withMode(BYPASS)} swap, proving the matrix specialization
 *       was preserved (the legacy path would have allowed the call
 *       because the matrix was gone, falling through to a plain
 *       BYPASS that allows everything).</li>
 *   <li>Source-pin: the {@code withMode} override exists on
 *       {@code MatrixPermissionPolicy} (a refactor that deletes it
 *       would silently regress R207).</li>
 * </ol>
 *
 * <p>Note: tests use {@code file_edit} (not {@code file_write}) because
 * the {@code OpKindDetector} maps {@code file_edit -> MODIFY}
 * deterministically; {@code file_write} depends on whether the file
 * already exists ({@code MODIFY} vs {@code CREATE}), which is brittle
 * in a unit test that doesn't create a real file.
 */
class MatrixPermissionPolicyR207WithModeTest {

    // --- mock tool --------------------------------------------------------

    private static Tool mutatingTool(String name) {
        Tool inner = Tools.build(new ToolDef(name, name, Map.of(),
                (in, ctx) -> CompletableFuture.completedFuture(Tool.ToolResult.of("ok"))));
        return new Tool() {
            @Override public String name() { return name; }
            @Override public String description() { return name; }
            @Override public Map<String, Object> inputSchema() { return Map.of(); }
            @Override public boolean isReadOnly(Map<String, Object> input) { return false; }
            @Override public boolean isDestructive(Map<String, Object> input) { return true; }
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

    private static PermissionMatrix matrixWithDenyFor(String tool, String pathGlob) {
        // Layer a DENY rule for (tool, pathGlob, MODIFY) so the
        // test can prove the matrix survived a withMode() round-trip.
        // We pin the matrix to MODIFY because file_edit -> MODIFY
        // is deterministic; file_write's CREATE/MODIFY split depends
        // on the file's existence on disk, which is brittle.
        PermissionMatrix m = new PermissionMatrix();
        m = m.withOverride(tool, pathGlob, OpKind.MODIFY, Action.DENY);
        return m;
    }

    // --- 1. return type ----------------------------------------------------

    @Test
    void withMode_returnsMatrixPermissionPolicy() {
        // The single most important invariant: a
        // MatrixPermissionPolicy.withMode(...) result MUST be a
        // MatrixPermissionPolicy. The legacy path returned a
        // plain ProjectPermissionPolicy, dropping the matrix.
        ToolPermissionPrompter prompter = (t, i, q) ->
                CompletableFuture.completedFuture(new PermissionResult.Allow(i));
        PermissionMatrix m = matrixWithDenyFor("file_edit", "*");
        PermissionPolicy p = new MatrixPermissionPolicy(
                m, SettingsPermissions.empty(), PermissionMode.DEFAULT, prompter, Path.of("/tmp"));
        PermissionPolicy swapped = ((MatrixPermissionPolicy) p).withMode(PermissionMode.BYPASS_PERMISSIONS);
        assertThat(swapped)
                .as("MatrixPermissionPolicy.withMode must return a MatrixPermissionPolicy, not a plain ProjectPermissionPolicy (R207)")
                .isInstanceOf(MatrixPermissionPolicy.class);
    }

    @Test
    void withMode_carriesMatrix() {
        // The new policy's matrix() accessor must return the SAME
        // matrix instance — a fresh matrix would lose any
        // session-level overrides the user applied via
        // withOverride(). Identity equality is the simplest,
        // strongest pin.
        ToolPermissionPrompter prompter = (t, i, q) ->
                CompletableFuture.completedFuture(new PermissionResult.Allow(i));
        PermissionMatrix m = matrixWithDenyFor("file_edit", "*");
        MatrixPermissionPolicy original = new MatrixPermissionPolicy(
                m, SettingsPermissions.empty(), PermissionMode.DEFAULT, prompter, Path.of("/tmp"));
        MatrixPermissionPolicy swapped = original.withMode(PermissionMode.BYPASS_PERMISSIONS);
        assertThat(swapped.matrix())
                .as("withMode must carry the same matrix instance")
                .isSameAs(m);
    }

    @Test
    void withMode_carriesProjectRoot() {
        ToolPermissionPrompter prompter = (t, i, q) ->
                CompletableFuture.completedFuture(new PermissionResult.Allow(i));
        Path root = Path.of("/tmp/project");
        MatrixPermissionPolicy original = new MatrixPermissionPolicy(
                matrixWithDenyFor("file_edit", "*"),
                SettingsPermissions.empty(), PermissionMode.DEFAULT, prompter, root);
        MatrixPermissionPolicy swapped = original.withMode(PermissionMode.BYPASS_PERMISSIONS);
        assertThat(swapped.projectRoot())
                .as("withMode must carry projectRoot so OpKindDetector and extractPath keep working")
                .isEqualTo(root);
    }

    @Test
    void withMode_carriesMode() {
        // The new policy's mode() must reflect the requested mode,
        // not the original. The engine's logging and the StatusBar
        // both read engine.policy().mode() to render the label.
        ToolPermissionPrompter prompter = (t, i, q) ->
                CompletableFuture.completedFuture(new PermissionResult.Allow(i));
        MatrixPermissionPolicy original = new MatrixPermissionPolicy(
                matrixWithDenyFor("file_edit", "*"),
                SettingsPermissions.empty(), PermissionMode.ACCEPT_EDITS, prompter, Path.of("/tmp"));
        MatrixPermissionPolicy swapped = original.withMode(PermissionMode.BYPASS_PERMISSIONS);
        assertThat(swapped.mode()).isEqualTo(PermissionMode.BYPASS_PERMISSIONS);
    }

    @Test
    void withMode_carriesLowWaterline() {
        // the skip-low waterline is a per-policy setting.
        // withMode must carry it (mirroring withMatrix's behaviour).
        ToolPermissionPrompter prompter = (t, i, q) ->
                CompletableFuture.completedFuture(new PermissionResult.Allow(i));
        MatrixPermissionPolicy original = new MatrixPermissionPolicy(
                matrixWithDenyFor("file_edit", "*"),
                SettingsPermissions.empty(), PermissionMode.DEFAULT, prompter, Path.of("/tmp"));
        original.setLowWaterline(7);
        MatrixPermissionPolicy swapped = original.withMode(PermissionMode.BYPASS_PERMISSIONS);
        assertThat(swapped.lowWaterline())
                .as("withMode must carry the R108 waterline")
                .isEqualTo(7);
    }

    @Test
    void withMode_normalisesNullModeToDefault() {
        // The parent's withMode normalises a null mode to
        // PermissionMode.DEFAULT. We want to keep that contract
        // so the engine never ends up with a policy that has a
        // null mode field.
        ToolPermissionPrompter prompter = (t, i, q) ->
                CompletableFuture.completedFuture(new PermissionResult.Allow(i));
        MatrixPermissionPolicy original = new MatrixPermissionPolicy(
                matrixWithDenyFor("file_edit", "*"),
                SettingsPermissions.empty(), PermissionMode.ACCEPT_EDITS, prompter, Path.of("/tmp"));
        MatrixPermissionPolicy swapped = original.withMode(null);
        assertThat(swapped.mode()).isEqualTo(PermissionMode.DEFAULT);
        assertThat(swapped).isInstanceOf(MatrixPermissionPolicy.class);
    }

    @Test
    void withMode_chainIsStable() {
        // Calling withMode multiple times in a row must keep
        // returning a MatrixPermissionPolicy (the matrix must
        // survive every flip). The legacy path drops the
        // matrix on the first call and never recovers it.
        ToolPermissionPrompter prompter = (t, i, q) ->
                CompletableFuture.completedFuture(new PermissionResult.Allow(i));
        PermissionMatrix m = matrixWithDenyFor("file_edit", "*");
        MatrixPermissionPolicy p = new MatrixPermissionPolicy(
                m, SettingsPermissions.empty(), PermissionMode.DEFAULT, prompter, Path.of("/tmp"));
        PermissionPolicy cur = p;
        for (PermissionMode mode : new PermissionMode[]{
                PermissionMode.BYPASS_PERMISSIONS, PermissionMode.ACCEPT_EDITS,
                PermissionMode.ASK_BEFORE_TOOL, PermissionMode.BYPASS_PERMISSIONS}) {
            cur = ((MatrixPermissionPolicy) cur).withMode(mode);
            assertThat(cur)
                    .as("withMode chain must remain a MatrixPermissionPolicy (mode = " + mode + ")")
                    .isInstanceOf(MatrixPermissionPolicy.class);
            assertThat(((MatrixPermissionPolicy) cur).matrix())
                    .as("matrix must survive the chain (mode = " + mode + ")")
                    .isSameAs(m);
        }
    }

    // --- 2. behavioural: matrix DENY still wins after a swap ---------------

    @Test
    void withMode_matrixDenyStillBlocksAfterSwap() {
        // The behavioural proof: a matrix DENY rule must
        // continue to block a file_edit call even after the user
        // switched to BYPASS_PERMISSIONS. legacy, withMode
        // dropped the matrix, the new plain policy was
        // BYPASS → Allow, and the file_edit would have been
        // allowed (the matrix's DENY was gone). R207 must keep
        // the matrix's DENY authoritative.
        ToolPermissionPrompter prompter = (t, i, q) -> {
            // If this fires, the matrix DENY was lost — the
            // test will fail with a clear message.
            throw new AssertionError("prompter should NOT be called — matrix DENY must short-circuit before mode");
        };
        PermissionMatrix m = matrixWithDenyFor("file_edit", "*");
        MatrixPermissionPolicy p = new MatrixPermissionPolicy(
                m, SettingsPermissions.empty(), PermissionMode.ACCEPT_EDITS, prompter, Path.of("/tmp"));
        MatrixPermissionPolicy bypass = p.withMode(PermissionMode.BYPASS_PERMISSIONS);

        Tool editTool = mutatingTool("file_edit");
        PermissionResult r = bypass.check(editTool,
                Map.of("file_path", "secret.txt"),
                Tool.CallContext.of("s")).join();
        assertThat(r)
                .as("matrix DENY must still block file_edit even under BYPASS (R207 fix)")
                .isInstanceOf(PermissionResult.Deny.class);
    }

    @Test
    void withMode_smartModeStillConsultsMatrix() {
        // Under ACCEPT_EDITS (智能授权 / smart), a read-only tool
        // auto-allows. A mutating tool would normally fall
        // through to resolveAsk → prompter.ask. But if the
        // matrix has a DENY for this tool/path, the matrix
        // should DENY first, never reach the prompter.
        AtomicInteger prompterCalls = new AtomicInteger(0);
        ToolPermissionPrompter prompter = (t, i, q) -> {
            prompterCalls.incrementAndGet();
            return CompletableFuture.completedFuture(new PermissionResult.Allow(i));
        };
        PermissionMatrix m = matrixWithDenyFor("file_edit", "*");
        MatrixPermissionPolicy p = new MatrixPermissionPolicy(
                m, SettingsPermissions.empty(), PermissionMode.ACCEPT_EDITS, prompter, Path.of("/tmp"));
        // Flip to a different mode and back, simulating the
        // user's "jumping" between 始终授权 (bypass) and 询问 (ask).
        MatrixPermissionPolicy afterBypass = p.withMode(PermissionMode.BYPASS_PERMISSIONS);
        MatrixPermissionPolicy backToSmart = afterBypass.withMode(PermissionMode.ACCEPT_EDITS);

        Tool editTool = mutatingTool("file_edit");
        PermissionResult r = backToSmart.check(editTool,
                Map.of("file_path", "secret.txt"),
                Tool.CallContext.of("s")).join();
        assertThat(r)
                .as("after BYPASS → ACCEPT_EDITS round-trip, matrix DENY must still block file_edit")
                .isInstanceOf(PermissionResult.Deny.class);
        assertThat(prompterCalls.get())
                .as("prompter must never fire when the matrix DENY is in effect")
                .isEqualTo(0);
    }

    // --- 3. source-pin -----------------------------------------------------

    @Test
    void withMode_overrideExistsOnMatrixPermissionPolicy() {
        // Source-pin: MatrixPermissionPolicy must declare its
        // own `public MatrixPermissionPolicy withMode(PermissionMode)`
        // override. A refactor that deletes the override would
        // re-introduce the R207 regression silently — the
        // class still compiles, the unit test (the first one
        // above) still passes for the type's instance, but the
        // matrix is gone at runtime.
        //
        // We resolve the source file relative to the test cwd
        // (which is the aethercode-permission project root when
        // surefire runs the test). This matches the convention
        // used by R203's ProjectPermissionPolicyR203Test for the
        // same kind of structural source-pin.
        String src;
        try {
            src = java.nio.file.Files.readString(java.nio.file.Path.of(
                    "src/main/java/org/aethercode/permission/MatrixPermissionPolicy.java"));
        } catch (Exception e) {
            throw new RuntimeException("could not read MatrixPermissionPolicy source (cwd="
                    + java.nio.file.Path.of("").toAbsolutePath() + "): " + e, e);
        }
        assertThat(src)
                .as("MatrixPermissionPolicy must override withMode to keep the matrix (R207)")
                .contains("public MatrixPermissionPolicy withMode(");
        assertThat(src)
                .as("override must carry @Override annotation (R207 contract)")
                .contains("@Override");
    }

    @Test
    void withMode_doesNotShareCurrentSubTaskIdWithParent() {
        // R86 currentSubTaskId: the new policy MUST NOT inherit
        // the parent's live sub-task id. The reason is structural —
        // the field is private to ProjectPermissionPolicy, and the
        // engine (R206's StreamingToolExecutor.setPolicy) re-pushes
        // the live id via setCurrentSubTaskId() right after the
        // swap returns. So withMode() returns a child with
        // currentSubTaskId == null; the engine then sets it.
        //
        // A copy that "leaked" the parent's id would race the
        // parent's updates: setting the child's id would mutate
        // shared state, so a future parent.setCurrentSubTaskId(...)
        // would silently overwrite the child too. The R207 contract
        // is "the child is independent, the engine wires it up".
        ToolPermissionPrompter prompter = (t, i, q) ->
                CompletableFuture.completedFuture(new PermissionResult.Allow(i));
        MatrixPermissionPolicy original = new MatrixPermissionPolicy(
                matrixWithDenyFor("file_edit", "*"),
                SettingsPermissions.empty(), PermissionMode.DEFAULT, prompter, Path.of("/tmp"));
        original.setCurrentSubTaskId("sub-1");

        MatrixPermissionPolicy swapped = original.withMode(PermissionMode.BYPASS_PERMISSIONS);
        // The child starts with no sub-task id; the engine
        // (StreamingToolExecutor.setPolicy) is responsible for
        // pushing the live id via the public setter.
        assertThat(swapped.currentSubTaskId())
                .as("withMode must NOT inherit parent's currentSubTaskId (R207 contract; the engine re-pushes via setCurrentSubTaskId)")
                .isNull();
        // The parent is unaffected.
        assertThat(original.currentSubTaskId())
                .as("parent's currentSubTaskId must be untouched after withMode")
                .isEqualTo("sub-1");
        // Setting the child's id must not affect the parent.
        swapped.setCurrentSubTaskId("sub-2");
        assertThat(original.currentSubTaskId())
                .as("mutating the child's sub-task id must not leak into the parent (R207 invariant)")
                .isEqualTo("sub-1");
        assertThat(swapped.currentSubTaskId())
                .as("child's sub-task id must reflect the independent setter call")
                .isEqualTo("sub-2");
    }
}
