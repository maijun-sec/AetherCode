package org.aethercode.protocol.methods;

import org.aethercode.protocol.jsonrpc.JsonRpcNotification;
import org.aethercode.protocol.jsonrpc.JsonRpcProtocolException;
import org.aethercode.sdk.AetherCodeEngine;
import org.aethercode.core.tool.Tool;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * contract test for the
 * {@code setAutoApproveLowRisk} + {@code recordAutoApproved}
 * surface in {@link AetherCodeMethods}.
 *
 * <p>The short-circuit semantics are exercised end-to-end
 * via {@code JsonRpcPermissionPrompterR120Test}. This test
 * pins the data-only contract: validation, state mutation,
 * counter increments, and notification emission.
 */
class AetherCodeMethodsR120Test {

    private static AetherCodeEngine engineFor(Path cwd) {
        return new AetherCodeEngine.Builder()
                .cwd(cwd)
                .tools(List.<Tool>of())
                .build();
    }

    private static AetherCodeMethods methodsWith(Path cwd, List<JsonRpcNotification> out) {
        return new AetherCodeMethods(engineFor(cwd), n -> out.add(n));
    }

    @Test
    void defaultFlagIsTrue(@TempDir Path cwd) {
        // Backward-compat: R87's "read-only never asks" behaviour
        // was effectively auto-approve-low-risk. R120 turns that
        // into a first-class flag and defaults it to true so the
        // UX is unchanged for users who never opened Settings.
        AetherCodeMethods m = methodsWith(cwd, new ArrayList<>());
        assertThat(m.isAutoApproveLowRisk()).isTrue();
        assertThat(m.getAutoApprovedCount()).isZero();
    }

    @Test
    void setAutoApproveLowRisk_missingEnabledField_throws(@TempDir Path cwd) {
        AetherCodeMethods m = methodsWith(cwd, new ArrayList<>());
        // The detail is on JsonRpcError.message(); the
        // exception's getMessage() is just "invalid params"
        // (the outer category), so we read the inner error.
        assertThatThrownBy(() -> m.setAutoApproveLowRisk(Map.of()))
                .isInstanceOf(JsonRpcProtocolException.class)
                .matches(t -> ((JsonRpcProtocolException) t).error().message()
                        .contains("missing required field: enabled"),
                        "error().message() should mention 'missing required field: enabled'");
    }

    @Test
    void setAutoApproveLowRisk_nonBooleanField_throws(@TempDir Path cwd) {
        AetherCodeMethods m = methodsWith(cwd, new ArrayList<>());
        assertThatThrownBy(() -> m.setAutoApproveLowRisk(Map.of("enabled", "true")))
                .isInstanceOf(JsonRpcProtocolException.class)
                .matches(t -> ((JsonRpcProtocolException) t).error().message()
                        .contains("enabled must be a boolean"),
                        "error().message() should mention 'enabled must be a boolean'");
    }

    @Test
    void setAutoApproveLowRisk_true_acceptsBoolean(@TempDir Path cwd) {
        AetherCodeMethods m = methodsWith(cwd, new ArrayList<>());
        @SuppressWarnings("unchecked")
        Map<String, Object> r = (Map<String, Object>) m.setAutoApproveLowRisk(
                Map.of("enabled", Boolean.TRUE));
        assertThat(r).containsEntry("ok", true);
        assertThat(r).containsEntry("enabled", true);
        assertThat(r).containsEntry("autoApprovedCount", 0L);
        assertThat(m.isAutoApproveLowRisk()).isTrue();
    }

    @Test
    void setAutoApproveLowRisk_acceptsZeroOneAsNumber(@TempDir Path cwd) {
        // The Tauri→WS bridge sometimes serialises a boolean
        // as 0/1. The setter must accept Number too (0=false,
        // anything else=true) so the renderer doesn't have to
        // do a string-or-bool dance.
        AetherCodeMethods m = methodsWith(cwd, new ArrayList<>());
        @SuppressWarnings("unchecked")
        Map<String, Object> r1 = (Map<String, Object>) m.setAutoApproveLowRisk(
                Map.of("enabled", 0));
        assertThat(m.isAutoApproveLowRisk()).isFalse();
        assertThat(r1).containsEntry("enabled", false);

        @SuppressWarnings("unchecked")
        Map<String, Object> r2 = (Map<String, Object>) m.setAutoApproveLowRisk(
                Map.of("enabled", 1));
        assertThat(m.isAutoApproveLowRisk()).isTrue();
        assertThat(r2).containsEntry("enabled", true);
    }

    @Test
    void setAutoApproveLowRisk_false_flipsFlag(@TempDir Path cwd) {
        AetherCodeMethods m = methodsWith(cwd, new ArrayList<>());
        @SuppressWarnings("unchecked")
        Map<String, Object> r = (Map<String, Object>) m.setAutoApproveLowRisk(
                Map.of("enabled", false));
        assertThat(r).containsEntry("enabled", false);
        assertThat(m.isAutoApproveLowRisk()).isFalse();
    }

    @Test
    void recordAutoApproved_incrementsCounter(@TempDir Path cwd) {
        AetherCodeMethods m = methodsWith(cwd, new ArrayList<>());
        long n1 = m.recordAutoApproved("file_read", new HashMap<>(), "r1");
        long n2 = m.recordAutoApproved("glob_files", new HashMap<>(), "r2");
        long n3 = m.recordAutoApproved("grep", new HashMap<>(), "r3");
        assertThat(n1).isEqualTo(1L);
        assertThat(n2).isEqualTo(2L);
        assertThat(n3).isEqualTo(3L);
        assertThat(m.getAutoApprovedCount()).isEqualTo(3L);
    }

    @Test
    void recordAutoApproved_emitsNotification(@TempDir Path cwd) {
        List<JsonRpcNotification> out = new ArrayList<>();
        AetherCodeMethods m = methodsWith(cwd, out);
        m.recordAutoApproved("file_read", Map.of("path", "/tmp/a"), "read file");
        // 1 notification must have been emitted.
        assertThat(out).hasSize(1);
        JsonRpcNotification n = out.get(0);
        assertThat(n.method()).isEqualTo("permission_auto_approved");
        @SuppressWarnings("unchecked")
        Map<String, Object> p = (Map<String, Object>) n.params();
        assertThat(p).containsEntry("tool", "file_read");
        assertThat(p).containsEntry("reason", "read file");
        assertThat(p).containsEntry("autoApprovedCount", 1L);
        // requestId must be synthetic (auto-<uuid>) and
        // start with the "auto-" prefix so the UI can
        // distinguish it from a real permission_request.
        assertThat((String) p.get("requestId")).startsWith("auto-");
        assertThat(p.get("atMs")).isInstanceOf(Long.class);
    }

    @Test
    void recordAutoApproved_multipleNotificationsCarryCount(@TempDir Path cwd) {
        // Each notification carries the post-increment count
        // so the renderer doesn't have to do a follow-up
        // getState() to keep its badge in sync.
        List<JsonRpcNotification> out = new ArrayList<>();
        AetherCodeMethods m = methodsWith(cwd, out);
        m.recordAutoApproved("file_read", new HashMap<>(), "r1");
        m.recordAutoApproved("file_read", new HashMap<>(), "r2");
        m.recordAutoApproved("file_read", new HashMap<>(), "r3");
        assertThat(out).hasSize(3);
        @SuppressWarnings("unchecked")
        Map<String, Object> p1 = (Map<String, Object>) out.get(0).params();
        @SuppressWarnings("unchecked")
        Map<String, Object> p2 = (Map<String, Object>) out.get(1).params();
        @SuppressWarnings("unchecked")
        Map<String, Object> p3 = (Map<String, Object>) out.get(2).params();
        assertThat(p1).containsEntry("autoApprovedCount", 1L);
        assertThat(p2).containsEntry("autoApprovedCount", 2L);
        assertThat(p3).containsEntry("autoApprovedCount", 3L);
    }

    @Test
    void recordAutoApproved_inputIsForwarded(@TempDir Path cwd) {
        // The notification payload must include the tool input
        // so the RpcDiagnosticsPanel can show "auto-allowed
        // grep(pattern=foo)" without a follow-up tool_get call.
        List<JsonRpcNotification> out = new ArrayList<>();
        AetherCodeMethods m = methodsWith(cwd, out);
        Map<String, Object> input = new HashMap<>();
        input.put("pattern", "foo");
        input.put("path", "/tmp");
        m.recordAutoApproved("grep", input, "search");
        @SuppressWarnings("unchecked")
        Map<String, Object> p = (Map<String, Object>) out.get(0).params();
        @SuppressWarnings("unchecked")
        Map<String, Object> echoed = (Map<String, Object>) p.get("input");
        assertThat(echoed).containsEntry("pattern", "foo");
        assertThat(echoed).containsEntry("path", "/tmp");
    }
}
