package org.aethercode.protocol.methods;

import org.aethercode.protocol.jsonrpc.JsonRpcNotification;
import org.aethercode.sdk.AetherCodeEngine;
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
 * {@code setAutoApproveMediumHigh} RPC + the
 * elevated-risk {@code recordAutoApproved} path.
 *
 * <p>The legacy {@code setAutoApproveLowRisk} flow is
 * covered by {@link AetherCodeMethodsR120Test}; this
 * suite focuses on the NEW behaviour: medium- and
 * high-risk short-circuit, the elevated counter, and
 * the dual-counter notification payload.
 */
class AetherCodeMethodsR126Test {

    private static AetherCodeMethods methodsWith(Path cwd, List<JsonRpcNotification> sink) {
        AetherCodeEngine engine = new AetherCodeEngine.Builder()
                .cwd(cwd)
                .tools(List.of())
                .build();
        return new AetherCodeMethods(engine, sink == null ? n -> {} : sink::add);
    }

    @Test
    void setAutoApproveMediumHigh_defaultFalse(@TempDir Path cwd) {
        // No env var, no setter call — must default to
        // false so a daemon never silently auto-approves
        // high-risk calls without an opt-in.
        AetherCodeMethods m = methodsWith(cwd, null);
        assertThat(m.isAutoApproveMediumHigh()).isFalse();
    }

    @Test
    void setAutoApproveMediumHigh_localSetterToggles(@TempDir Path cwd) {
        AetherCodeMethods m = methodsWith(cwd, null);
        m.setAutoApproveMediumHigh(true);
        assertThat(m.isAutoApproveMediumHigh()).isTrue();
        m.setAutoApproveMediumHigh(false);
        assertThat(m.isAutoApproveMediumHigh()).isFalse();
    }

    @Test
    void setAutoApproveMediumHighRpc_validatesParams(@TempDir Path cwd) {
        AetherCodeMethods m = methodsWith(cwd, null);
        // missing field — the JsonRpcProtocolException
        // wraps the detail in error().message(), not
        // the top-level getMessage() (which is just
        // "invalid params"). Mirror the R120 assertion
        // shape: matches(...) on the error's message.
        assertThatThrownBy(() -> m.setAutoApproveMediumHigh(Map.of()))
                .isInstanceOf(org.aethercode.protocol.jsonrpc.JsonRpcProtocolException.class)
                .matches(t -> ((org.aethercode.protocol.jsonrpc.JsonRpcProtocolException) t).error().message()
                        .contains("missing required field: enabled"),
                        "error().message() should mention 'missing required field: enabled'");
        // wrong type
        assertThatThrownBy(() -> m.setAutoApproveMediumHigh(Map.of("enabled", "yes")))
                .isInstanceOf(org.aethercode.protocol.jsonrpc.JsonRpcProtocolException.class)
                .matches(t -> ((org.aethercode.protocol.jsonrpc.JsonRpcProtocolException) t).error().message()
                        .contains("enabled must be a boolean"),
                        "error().message() should mention 'enabled must be a boolean'");
    }

    @Test
    void setAutoApproveMediumHighRpc_acceptsBooleanAndNumber(@TempDir Path cwd) {
        // Same as R120: 0/1 are accepted for clients
        // that serialise booleans as numbers.
        AetherCodeMethods m = methodsWith(cwd, null);
        Object r1 = m.setAutoApproveMediumHigh(Map.of("enabled", true));
        assertThat(r1).extracting("ok", "enabled").containsExactly(true, true);
        Object r2 = m.setAutoApproveMediumHigh(Map.of("enabled", 0));
        assertThat(r2).extracting("ok", "enabled").containsExactly(true, false);
        Object r3 = m.setAutoApproveMediumHigh(Map.of("enabled", 1));
        assertThat(r3).extracting("ok", "enabled").containsExactly(true, true);
    }

    @Test
    void recordAutoApproved_routesToCorrectCounter(@TempDir Path cwd) {
        // Low-risk calls bump autoApprovedCount.
        // Anything else (medium/high/critical) bumps
        // autoApprovedElevatedCount. The two counters
        // are independent.
        AetherCodeMethods m = methodsWith(cwd, null);
        long n1 = m.recordAutoApproved("file_read", Map.of(), "r", "low");
        long n2 = m.recordAutoApproved("bash", Map.of("command", "ls"), "r", "high");
        long n3 = m.recordAutoApproved("file_write", Map.of(), "r", "medium");
        long n4 = m.recordAutoApproved("file_read", Map.of(), "r", "low");
        assertThat(m.getAutoApprovedCount()).isEqualTo(2L);
        assertThat(m.getAutoApprovedElevatedCount()).isEqualTo(2L);
        // legacy 3-arg path still routes to low
        long n5 = m.recordAutoApproved("file_read", Map.of(), "r");
        assertThat(m.getAutoApprovedCount()).isEqualTo(3L);
        assertThat(m.getAutoApprovedElevatedCount()).isEqualTo(2L);
    }

    @Test
    void recordAutoApproved_elevatedEmitsNotificationWithRiskLevel(@TempDir Path cwd) {
        // The notification payload carries riskLevel
        // + BOTH counters so the renderer's
        // StatusBar can colour-code the badge.
        List<JsonRpcNotification> out = new ArrayList<>();
        AetherCodeMethods m = methodsWith(cwd, out);
        m.recordAutoApproved("bash", Map.of("command", "ls"), "r", "high");
        m.recordAutoApproved("file_write", Map.of("path", "/x"), "r", "medium");
        assertThat(out).hasSize(2);
        @SuppressWarnings("unchecked")
        Map<String, Object> p1 = (Map<String, Object>) out.get(0).params();
        @SuppressWarnings("unchecked")
        Map<String, Object> p2 = (Map<String, Object>) out.get(1).params();
        assertThat(p1.get("riskLevel")).isEqualTo("high");
        assertThat(p2.get("riskLevel")).isEqualTo("medium");
        assertThat(p1).containsKeys("autoApprovedCount", "autoApprovedElevatedCount");
    }

    @Test
    void methodTags_includesSetAutoApproveMediumHigh(@TempDir Path cwd) {
        // R124 contract: every wired RPC must appear
        // in METHOD_TAGS. R126 added a new RPC, so
        // the tag map must include it.
        assertThat(AetherCodeMethods.METHOD_TAGS).containsKey("setAutoApproveMediumHigh");
        String[] tags = AetherCodeMethods.METHOD_TAGS.get("setAutoApproveMediumHigh");
        assertThat(tags).contains(AetherCodeMethods.TAG_WRITE,
                AetherCodeMethods.TAG_ENGINE,
                AetherCodeMethods.TAG_PERMISSION);
    }

    @Test
    void methodTags_setAutoApproveMediumHighIsReadWriteExclusive(@TempDir Path cwd) {
        // R124 invariant: read + write tags are
        // mutually exclusive. setAutoApproveMediumHigh
        // is a write so it must NOT carry TAG_READ.
        String[] tags = AetherCodeMethods.METHOD_TAGS.get("setAutoApproveMediumHigh");
        assertThat(tags).doesNotContain(AetherCodeMethods.TAG_READ);
    }
}
