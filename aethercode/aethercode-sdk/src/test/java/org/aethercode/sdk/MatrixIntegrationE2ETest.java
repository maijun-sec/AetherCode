package org.aethercode.sdk;

import org.aethercode.config.AetherCodeConfig;
import org.aethercode.config.Action;
import org.aethercode.config.ConfigEngine;
import org.aethercode.config.OpKind;
import org.aethercode.config.OpKindDetector;
import org.aethercode.config.PermissionMatrix;
import org.aethercode.config.SkipConfirmationDetector;
import org.aethercode.config.SkipConfirmationRegistry;
import org.aethercode.config.defaults.DefaultMatrix;
import org.aethercode.core.permission.PermissionMode;
import org.aethercode.permission.MatrixPermissionPolicy;
import org.aethercode.permission.SettingsPermissions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * end-to-end test for the {@code .aethercode/config.json} +
 * matrix + skip-confirmation wiring. This is a "wiring" test, not a
 * full engine boot — it exercises the loaders and policies without
 * spinning up a ChatClient. The full engine boot is covered by the
 * aethercode-cli tests.
 *
 * <p>What we verify:
 * <ol>
 *   <li>Default matrix recognises the standard tools (file_read,
 *       file_write, file_edit, bash, web_fetch, glob, grep).</li>
 *   <li>The default matrix denies rm -rf even in BYPASS mode.</li>
 *   <li>A custom config.json overrides the matrix for a specific path.</li>
 *   <li>Skip-confirmation is auto-armed when the prompt text contains
 *       the canonical pattern.</li>
 *   <li>The skip counter decrements per permission check and stops
 *       prompting after exhaustion.</li>
 *   <li>OpKindDetector classifies the right OpKind for each tool
 *       + input shape.</li>
 * </ol>
 */
class MatrixIntegrationE2ETest {

    @Test
    void defaultMatrix_coversStandardTools(@TempDir Path tmp) {
        PermissionMatrix m = DefaultMatrix.build();
        assertThat(m.toolNames())
                .contains("file_read", "file_write", "file_edit", "bash",
                        "web_fetch", "web_search", "glob", "grep");
    }

    @Test
    void defaultMatrix_aethercodeConfigPath_isDeny(@TempDir Path tmp) {
        // The default matrix explicitly denies any write to .aethercode/**
        // so the model cannot rewrite its own config.
        PermissionMatrix m = DefaultMatrix.build();
        for (OpKind op : List.of(OpKind.CREATE, OpKind.MODIFY)) {
            assertThat(m.lookup("file_write", ".aethercode/config.json", op))
                    .as(".aethercode/** should be denied for " + op)
                    .isEqualTo(Action.DENY);
            assertThat(m.lookup("file_edit", ".aethercode/config.json", op))
                    .as(".aethercode/** should be denied for " + op)
                    .isEqualTo(Action.DENY);
        }
    }

    @Test
    void projectConfig_overridesDefault(@TempDir Path tmp) throws IOException {
        // Project wants src/main/** file_write to be ALLOW.
        Path configFile = tmp.resolve(".aethercode/config.json");
        Files.createDirectories(configFile.getParent());
        Files.writeString(configFile, """
                {
                  "version": 1,
                  "permissionMatrix": {
                    "file_write": {
                      "src/main/**": { "CREATE": "ALLOW", "MODIFY": "ALLOW" }
                    }
                  }
                }
                """);
        AetherCodeConfig cfg = ConfigEngine.loadFrom(configFile);
        // The override applies to the project root.
        assertThat(cfg.permissionMatrix.lookup("file_write", "src/main/java/Foo.java", OpKind.CREATE))
                .isEqualTo(Action.ALLOW);
        // Bash still falls through to ASK (no override).
        assertThat(cfg.permissionMatrix.lookup("bash", null, OpKind.EXEC))
                .isEqualTo(Action.ASK);
    }

    @Test
    void matrixPolicy_deniesBashDelete_evenInBypassMode(@TempDir Path tmp) throws Exception {
        PermissionMatrix m = DefaultMatrix.build();
        MatrixPermissionPolicy p = new MatrixPermissionPolicy(
                m, SettingsPermissions.empty(), PermissionMode.BYPASS_PERMISSIONS, null, tmp);
        // The matrix's bash DELETE row is DENY, and the matrix wins over mode.
        var result = p.check(
                stubTool("bash", false),
                Map.of("command", "rm -rf /tmp/important"),
                new org.aethercode.core.tool.Tool.CallContext("s1", null, Map.of())
        ).get();
        assertThat(result).isInstanceOf(org.aethercode.core.permission.PermissionResult.Deny.class);
    }

    @Test
    void skipConfirmation_autoArmsOnCanonicalPattern() {
        // No state. detect() is the trigger.
        assertThat(SkipConfirmationDetector.detect("no confirmation needed for next 6 rounds, go"))
                .isEqualTo(6);
    }

    @Test
    void skipConfirmation_counterDecrementsAcrossCalls(@TempDir Path tmp) throws Exception {
        // Wire: matrix returns ASK on file_write. With skip-rounds = 2,
        // the first two calls auto-allow, the third falls through.
        PermissionMatrix m = new PermissionMatrix()
                .withOverride("file_write", "**", OpKind.CREATE, Action.ASK);
        MatrixPermissionPolicy p = new MatrixPermissionPolicy(
                m, SettingsPermissions.empty(), PermissionMode.DEFAULT,
                // The "ask" prompter denies, so any fall-through ends in Deny.
                (tool, input, q) -> java.util.concurrent.CompletableFuture.completedFuture(
                        org.aethercode.core.permission.PermissionResult.Deny.of("user said no")),
                tmp);
        SkipConfirmationRegistry reg = new SkipConfirmationRegistry();
        p.setSkipConfirmationRegistry(reg);
        reg.set("s1", 2);

        var ctx = new org.aethercode.core.tool.Tool.CallContext("s1", null, Map.of());
        var tool = stubTool("file_write", false);

        // Skip applies twice.
        var r1 = p.check(tool, Map.of("file_path", "a.txt"), ctx).get();
        var r2 = p.check(tool, Map.of("file_path", "b.txt"), ctx).get();
        assertThat(r1).isInstanceOf(org.aethercode.core.permission.PermissionResult.Allow.class);
        assertThat(r2).isInstanceOf(org.aethercode.core.permission.PermissionResult.Allow.class);

        // Third call: skip exhausted, prompter denies.
        var r3 = p.check(tool, Map.of("file_path", "c.txt"), ctx).get();
        assertThat(r3).isInstanceOf(org.aethercode.core.permission.PermissionResult.Deny.class);
        assertThat(reg.remaining("s1")).isEqualTo(0);
    }

    @Test
    void opKindDetector_recognisesAllStandardShapes() {
        // file_write to new file = CREATE
        assertThat(OpKindDetector.detect("file_write", Map.of("file_path", "/tmp/new.txt"), null))
                .isEqualTo(OpKind.CREATE);
        // file_edit = always MODIFY
        assertThat(OpKindDetector.detect("file_edit",
                Map.of("file_path", "/tmp/x", "old_string", "a", "new_string", "b"), null))
                .isEqualTo(OpKind.MODIFY);
        // bash rm -rf = DELETE (via destructive-flag detection)
        assertThat(OpKindDetector.detect("bash", Map.of("command", "rm -rf /tmp/foo"), null))
                .isEqualTo(OpKind.DELETE);
        // bash mvn = EXEC
        assertThat(OpKindDetector.detect("bash", Map.of("command", "mvn -B test"), null))
                .isEqualTo(OpKind.EXEC);
        // web_fetch = READ
        assertThat(OpKindDetector.detect("web_fetch", Map.of("url", "http://x"), null))
                .isEqualTo(OpKind.READ);
    }

    @Test
    void pathBucket_classifiesProjectFiles(@TempDir Path tmp) {
        // Project-relative paths inside the tmp dir classify correctly.
        assertThat(org.aethercode.config.PathBucket.classify("src/main/java/Foo.java", tmp))
                .isEqualTo(org.aethercode.config.PathBucket.SRC_MAIN);
        assertThat(org.aethercode.config.PathBucket.classify("src/test/java/FooTest.java", tmp))
                .isEqualTo(org.aethercode.config.PathBucket.SRC_TEST);
        assertThat(org.aethercode.config.PathBucket.classify("target/classes/Foo.class", tmp))
                .isEqualTo(org.aethercode.config.PathBucket.BUILD);
    }

    private static org.aethercode.core.tool.Tool stubTool(String name, boolean readOnly) {
        return new org.aethercode.core.tool.Tool() {
            @Override public String name() { return name; }
            @Override public String description() { return "stub"; }
            @Override public Map<String, Object> inputSchema() { return Map.of(); }
            @Override public boolean isReadOnly(Map<String, Object> input) { return readOnly; }
            @Override public java.util.concurrent.CompletableFuture<
                    org.aethercode.core.permission.PermissionResult> checkPermissions(
                    Map<String, Object> input, CallContext ctx) {
                return java.util.concurrent.CompletableFuture.completedFuture(
                        new org.aethercode.core.permission.PermissionResult.Allow(input));
            }
            @Override public java.util.concurrent.CompletableFuture<ToolResult> call(
                    Map<String, Object> input, CallContext ctx) {
                return java.util.concurrent.CompletableFuture.completedFuture(ToolResult.of("ok"));
            }
        };
    }
}
