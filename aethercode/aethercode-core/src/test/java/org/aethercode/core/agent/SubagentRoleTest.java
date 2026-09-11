package org.aethercode.core.agent;

import org.aethercode.core.tool.Tool;
import org.aethercode.core.tool.ToolDef;
import org.aethercode.core.tool.Tools;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * tests for {@link SubagentRole}. The role
 * presets are pure data + a tiny lookup helper; we
 * verify (a) the three built-in roles are registered,
 * (b) the lookup is case-insensitive and falls back to
 * {@code general-purpose}, (c) the tool filter drops
 * denied tools and (when an allow-list is set) keeps
 * only the allowed ones.
 */
class SubagentRoleTest {

    @Test
    void allThreeBuiltinRolesRegistered() {
        assertThat(SubagentRole.names()).contains("explore", "general-purpose", "coder");
    }

    @Test
    void lookupIsCaseInsensitive() {
        assertThat(SubagentRole.lookup("EXPLORE").name()).isEqualTo("explore");
        assertThat(SubagentRole.lookup("Explore").name()).isEqualTo("explore");
        assertThat(SubagentRole.lookup("Coder").name()).isEqualTo("coder");
    }

    @Test
    void unknownNameFallsBackToGeneralPurpose() {
        assertThat(SubagentRole.lookup("nope-not-a-role").name())
                .isEqualTo("general-purpose");
        assertThat(SubagentRole.lookup(null).name()).isEqualTo("general-purpose");
        assertThat(SubagentRole.lookup("").name()).isEqualTo("general-purpose");
    }

    @Test
    void exploreRoleBlocksWrites() {
        SubagentRole.RolePreset explore = SubagentRole.lookup("explore");
        assertThat(explore.deniedTools())
                .contains("file_write", "file_edit", "spawn_agent");
        assertThat(explore.allowedTools())
                .contains("file_read", "bash", "grep", "glob");
    }

    @Test
    void coderRoleBlocksWeb() {
        SubagentRole.RolePreset coder = SubagentRole.lookup("coder");
        assertThat(coder.deniedTools())
                .contains("web_fetch", "web_search", "spawn_agent");
    }

    @Test
    void generalPurposeAllowsEverything() {
        SubagentRole.RolePreset gp = SubagentRole.lookup("general-purpose");
        // null allow-list + empty deny-list = unrestricted
        assertThat(gp.allowedTools()).isNull();
        assertThat(gp.deniedTools()).isEmpty();
    }

    @Test
    void filterToolsRemovesDenied() {
        List<Tool> pool = List.of(
                tool("file_read"),
                tool("file_write"),
                tool("file_edit"),
                tool("bash"),
                tool("spawn_agent")
        );
        List<Tool> filtered = SubagentRole.filterTools(pool, SubagentRole.lookup("explore"));
        assertThat(filtered.stream().map(Tool::name))
                .containsExactlyInAnyOrder("file_read", "bash");
    }

    @Test
    void filterToolsKeepsAllForGeneralPurpose() {
        List<Tool> pool = List.of(
                tool("file_read"),
                tool("file_write"),
                tool("bash")
        );
        List<Tool> filtered = SubagentRole.filterTools(pool, SubagentRole.lookup("general-purpose"));
        assertThat(filtered).hasSize(3);
    }

    @Test
    void customRoleRegistration() {
        SubagentRole.RolePreset custom = new SubagentRole.RolePreset(
                "test-custom",
                "A test role",
                "Test prompt",
                null,
                java.util.Set.of()
        );
        SubagentRole.register(custom);
        assertThat(SubagentRole.lookup("test-custom").name()).isEqualTo("test-custom");
    }

    @Test
    void duplicateRoleRegistrationThrows() {
        // 'explore' is already registered; trying to register
        // a new instance of the same name must throw.
        assertThatThrownBy(() -> SubagentRole.register(
                new SubagentRole.RolePreset("explore", "dup", "dup", null, null)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("explore");
    }

    @Test
    void buildSystemPromptAppendsRole() {
        String base = "You are the base prompt.";
        String out = SubagentRole.buildSystemPrompt(base, SubagentRole.lookup("explore"));
        assertThat(out).contains(base);
        assertThat(out).contains("subagent-role name=\"explore\"");
        assertThat(out).contains("You are the AetherCode explore subagent");
    }

    @Test
    void buildSystemPromptNoOpForGeneralPurpose() {
        String base = "You are the base prompt.";
        String out = SubagentRole.buildSystemPrompt(base, SubagentRole.GENERAL_PURPOSE);
        assertThat(out).isEqualTo(base);
    }

    private static Tool tool(String name) {
        return Tools.build(new ToolDef(name, "test", Map.of(),
                (in, ctx) -> CompletableFuture.completedFuture(Tool.ToolResult.of("ok"))));
    }
}
