package org.aethercode.permission;

import org.aethercode.config.Action;
import org.aethercode.config.PermissionMatrix;
import org.aethercode.config.defaults.DefaultMatrix;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * tests the default-action computation used by
 * {@code AetherCodeMethods.listToolActions}. The RPC derives a
 * sample input per tool, classifies it with OpKindDetector, and
 * looks up the action in the matrix. Read-only tools get ALLOW
 * unconditionally. Tools with a deny in the matrix return DENY.
 */
class ListToolActionsTest {

    @Test
    void readOnlyTool_isAlwaysSafe() {
        // file_read's default matrix row is "ALLOW"; the tool is
        // also flagged read-only. Either way, isSafe == true.
        PermissionMatrix m = DefaultMatrix.build();
        // Simulate the RPC's action computation: read-only bypasses the matrix.
        // We don't call the RPC directly here — that's a protocol test.
        // Instead we just verify the matrix is permissive for the typical file_read path.
        Action a = m.lookup("file_read", "src/main/java/Foo.java",
                org.aethercode.config.OpKind.READ);
        assertThat(a).isEqualTo(Action.ALLOW);
    }

    @Test
    void fileWrite_toJava_isAskByDefault() {
        // The default matrix is conservative: writes to *.java
        // require an explicit user decision.
        PermissionMatrix m = DefaultMatrix.build();
        Action a = m.lookup("file_write", "src/main/java/Foo.java",
                org.aethercode.config.OpKind.CREATE);
        assertThat(a).isEqualTo(Action.ASK);
    }

    @Test
    void bashDelete_isDenyByDefault() {
        // bash DELETE row in the default matrix is DENY, even if
        // the user is in BYPASS mode. The matrix wins.
        PermissionMatrix m = DefaultMatrix.build();
        Action a = m.lookup("bash", "rm -rf foo", org.aethercode.config.OpKind.DELETE);
        assertThat(a).isEqualTo(Action.DENY);
    }

    @Test
    void userMatrix_canOverrideDefaultSafety() {
        // A user-provided config can mark a previously-ASK path as
        // ALLOW. listToolActions will then show isSafe=true.
        PermissionMatrix custom = DefaultMatrix.build()
                .withOverride("file_write", "src/main/**", org.aethercode.config.OpKind.CREATE, Action.ALLOW)
                .withOverride("file_write", "src/main/**", org.aethercode.config.OpKind.MODIFY, Action.ALLOW);
        Action a = custom.lookup("file_write", "src/main/java/Foo.java",
                org.aethercode.config.OpKind.CREATE);
        assertThat(a).isEqualTo(Action.ALLOW);
    }

    @Test
    void userMatrix_canTightenDefaultSafety() {
        // Conversely, the user can make a previously-ALLOW path
        // require explicit approval.
        PermissionMatrix custom = DefaultMatrix.build()
                .withOverride("file_read", "/etc/**", org.aethercode.config.OpKind.READ, Action.DENY);
        Action a = custom.lookup("file_read", "/etc/passwd", org.aethercode.config.OpKind.READ);
        assertThat(a).isEqualTo(Action.DENY);
    }

    @Test
    void opKindDetector_producesRepresentativeKindPerTool() {
        // Spot-check the sample inputs used by listToolActions.
        var cwd = java.nio.file.Path.of("/tmp/proj");
        assertThat(org.aethercode.config.OpKindDetector.detect(
                "file_read", Map.of("file_path", "src/main/java/Foo.java"), cwd))
                .isEqualTo(org.aethercode.config.OpKind.READ);
        assertThat(org.aethercode.config.OpKindDetector.detect(
                "file_write", Map.of("file_path", "src/main/java/Foo.java"), cwd))
                .isEqualTo(org.aethercode.config.OpKind.CREATE);
        assertThat(org.aethercode.config.OpKindDetector.detect(
                "file_edit", Map.of("file_path", "src/main/java/Foo.java",
                        "old_string", "a", "new_string", "b"), cwd))
                .isEqualTo(org.aethercode.config.OpKind.MODIFY);
        assertThat(org.aethercode.config.OpKindDetector.detect(
                "bash", Map.of("command", "mvn -B test"), cwd))
                .isEqualTo(org.aethercode.config.OpKind.EXEC);
    }

    @Test
    void matrixToolsList_containsAllStandardToolNames() {
        // The listToolActions RPC iterates the tool pool AND the
        // default matrix must have entries for every tool the
        // model can call. The standard set is:
        PermissionMatrix m = DefaultMatrix.build();
        assertThat(m.toolNames())
                .contains("file_read", "file_write", "file_edit", "bash",
                        "web_fetch", "web_search", "glob", "grep");
    }

    @Test
    void unknownTool_failsClosedToAsk() {
        // A tool the user added but the matrix doesn't know about
        // returns ASK. isSafe=false; the UI shows a "?" badge.
        PermissionMatrix m = DefaultMatrix.build();
        Action a = m.lookup("user_added_tool", null, org.aethercode.config.OpKind.EXEC);
        assertThat(a).isEqualTo(Action.ASK);
    }
}
