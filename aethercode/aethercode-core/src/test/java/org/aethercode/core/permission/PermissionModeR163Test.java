package org.aethercode.core.permission;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * contract test for the new {@link PermissionMode#ASK_BEFORE_TOOL}
 * enum value.
 *
 * <p>The user asked for "ask before every tool call, mid-loop" and the
 * legacy {@link PermissionMode#DEFAULT} mode already did that
 * internally, but the name wasn't discoverable in the TUI's status bar
 * or command palette. R163 adds the explicit alias so the user can
 * pick it from a menu / command list and immediately understand the
 * semantic.
 *
 * <p>Behaviour-wise, ASK_BEFORE_TOOL and DEFAULT are interchangeable
 * — both route every non-read-only tool call through the permission
 * prompter (which, in production, is the {@code JsonRpcPermissionPrompter}
 * that emits a {@code permission_request} notification and waits for
 * a {@code permissionResponse}). The only difference is the name the
 * user sees in the status bar and the value the suggester recommends.
 */
class PermissionModeR163Test {

    @Test
    void askBeforeTool_isDeclared() {
        PermissionMode m = PermissionMode.valueOf("ASK_BEFORE_TOOL");
        assertThat(m.name()).isEqualTo("ASK_BEFORE_TOOL");
    }

    @Test
    void allModeCount_isSeven() {
        // The enum must have exactly 7 values:
        //   DEFAULT, ASK_BEFORE_TOOL (prior round), ACCEPT_EDITS,
        //   BYPASS_PERMISSIONS, PLAN, AUTO_READ_ONLY, ACCEPT_TASK
        // = 6 legacy + 1 (R163 ASK_BEFORE_TOOL) = 7.
        // If this number changes, audit the PermissionReasoner
        // and ProjectPermissionPolicy switch expressions for a
        // missing case.
        assertThat(PermissionMode.values()).hasSize(7);
    }

    @Test
    void askBeforeTool_isNotEqualsToDefault() {
        // Distinct enum constant — we don't want callers
        // accidentally treating it as the legacy DEFAULT.
        // The semantically-identical behaviour is asserted
        // in the policy test, not here.
        assertThat(PermissionMode.ASK_BEFORE_TOOL).isNotEqualTo(PermissionMode.DEFAULT);
    }

    @Test
    void askBeforeTool_valueOfCaseSensitive() {
        // Enum names are case-sensitive by definition, but
        // the env-var / config path upper-cases before
        // valueOf so the user can write "ask-before-tool".
        // This test pins the underlying contract.
        assertThat(PermissionMode.valueOf("ASK_BEFORE_TOOL")).isNotNull();
        assertThatThrownBy(() -> PermissionMode.valueOf("ask_before_tool"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}

