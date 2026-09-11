package org.aethercode.sdk;

import org.aethercode.core.tool.Tool;
import org.aethercode.prompts.SystemPrompt;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * prior round integration tests for the rules
 * pipeline. prior round updated the test helper to call
 * the new {@code SystemPrompt}-returning
 * {@code defaultSystemPrompt(Builder)} method and
 * pull the rendered String out via
 * {@code sp.render()}, so a future change to the
 * return type (e.g. {@code RenderedPrompt}) won't
 * require touching this test.
 */
class AetherCodeEngineRulesTest {

    @Test
    void defaultSystemPrompt_includesRulesFromProjectDir(@TempDir Path cwd) throws Exception {
        // when the builder is given a project
        // cwd, rules under .aethercode/rules/ are
        // included in the prompt. This test stages a
        // single file and verifies the rendered prompt
        // contains it.
        Path rulesDir = cwd.resolve(".aethercode/rules");
        Files.createDirectories(rulesDir);
        Files.writeString(rulesDir.resolve("policy.md"),
                "R93A-SENTINEL-12345\ndo not rm -rf");
        AetherCodeEngine.Builder b = new AetherCodeEngine.Builder()
                .cwd(cwd)
                .tools(List.<Tool>of());
        // Stash the builder into a static slot the static method can read.
        Method m = AetherCodeEngine.class.getDeclaredMethod("defaultSystemPrompt", AetherCodeEngine.Builder.class);
        m.setAccessible(true);
        SystemPrompt sp = (SystemPrompt) m.invoke(null, b);
        String prompt = sp.render();
        assertThat(prompt)
                .as("defaultSystemPrompt should include the project's rules")
                .contains("R93A-SENTINEL-12345")
                .contains("do not rm -rf")
                .contains("policy.md");
    }

    @Test
    void defaultSystemPrompt_isCleanWhenNoRulesDir(@TempDir Path cwd) throws Exception {
        AetherCodeEngine.Builder b = new AetherCodeEngine.Builder()
                .cwd(cwd)
                .tools(List.<Tool>of());
        Method m = AetherCodeEngine.class.getDeclaredMethod("defaultSystemPrompt", AetherCodeEngine.Builder.class);
        m.setAccessible(true);
        SystemPrompt sp = (SystemPrompt) m.invoke(null, b);
        String prompt = sp.render();
        assertThat(prompt)
                .as("no rules dir -> no # Project rules header")
                .doesNotContain("# Project rules")
                .doesNotContain("# Global rules")
                // identity / environment / workflow still present
                .contains("AetherCode")
                .contains("Current working directory:");
    }

    @Test
    void defaultSystemPrompt_includesRoleScopedRules(@TempDir Path cwd) throws Exception {
        // when the builder is given a role,
        // rules under .aethercode/rules/roles/<role>/
        // are included in the prompt in addition to the
        // base rules. This test stages a role-scoped
        // file and verifies the rendered prompt
        // contains it.
        Path roleDir = cwd.resolve(".aethercode/rules/roles/coder");
        Files.createDirectories(roleDir);
        Files.writeString(roleDir.resolve("style.md"),
                "R93G-ROLE-SENTINEL\nwrite tests first");
        AetherCodeEngine.Builder b = new AetherCodeEngine.Builder()
                .cwd(cwd)
                .role("coder")
                .tools(List.<Tool>of());
        Method m = AetherCodeEngine.class.getDeclaredMethod("defaultSystemPrompt", AetherCodeEngine.Builder.class);
        m.setAccessible(true);
        SystemPrompt sp = (SystemPrompt) m.invoke(null, b);
        String prompt = sp.render();
        assertThat(prompt)
                .as("role-scoped rules should appear in the prompt")
                .contains("R93G-ROLE-SENTINEL")
                .contains("write tests first")
                .contains("style.md")
                .contains("(role-specific)");
    }

    @Test
    void defaultSystemPrompt_roleEmptyOmitsRoleLayer(@TempDir Path cwd) throws Exception {
        // No role set on the builder -> no role layer
        // in the prompt, even if a role dir exists on
        // disk (a user might have staged a coder/ dir
        // by accident; the engine must not pick it up
        // unless the builder is configured).
        Path roleDir = cwd.resolve(".aethercode/rules/roles/coder");
        Files.createDirectories(roleDir);
        Files.writeString(roleDir.resolve("x.md"), "R93G-UNWANTED");
        AetherCodeEngine.Builder b = new AetherCodeEngine.Builder()
                .cwd(cwd)
                .tools(List.<Tool>of());
        Method m = AetherCodeEngine.class.getDeclaredMethod("defaultSystemPrompt", AetherCodeEngine.Builder.class);
        m.setAccessible(true);
        SystemPrompt sp = (SystemPrompt) m.invoke(null, b);
        String prompt = sp.render();
        assertThat(prompt)
                .as("default role='' should not pull role-scoped rules")
                .doesNotContain("R93G-UNWANTED")
                .doesNotContain("(role-specific)");
    }
}
