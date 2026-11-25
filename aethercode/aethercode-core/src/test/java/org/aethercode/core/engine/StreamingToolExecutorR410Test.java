/*
 * Copyright (c) 2026 maijun
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package org.aethercode.core.engine;

import org.aethercode.core.app.AppState;
import org.aethercode.core.profile.Profile;
import org.aethercode.core.profile.ProfileRegistry;
import org.aethercode.core.tool.Tool;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * R410: a read-only posture must be REAL, and its refusals must be
 * LEGIBLE.
 *
 * <h2>★ DEFECT 1 — THE POSTURE LEAKED ★</h2>
 * <p>R401 denied the write tools and the shell to a read-only profile. It
 * did not deny the <b>agent-registry</b> tools — and the agent registry
 * lives in {@code ~/.aethercode/agents/}, OUTSIDE the working tree. So
 * "read-only" meant "cannot change the project", while the user's own
 * agent definitions were still writable.
 *
 * <p>R408 measured it happening: under the read-only {@code navigator}
 * profile the model called {@code update_agent(name=end-user,
 * body=probe)} and it EXECUTED. The user's agent file became 30 bytes of
 * "probe".
 *
 * <h2>★ DEFECT 2 — THE REFUSAL SAID "UNKNOWN" ★</h2>
 * <p>A tool this turn does not have was refused with
 * {@code "unknown tool: bash"}. "Unknown" says the tool does not EXIST,
 * and the model read it that way: it went hunting for the right name (48
 * calls in R408's S1), and eventually guessed a name the provider had
 * never heard of ({@code CompatFileWrite4d3b7c}).
 *
 * <p>The refusal has to carry the CONCLUSION the model can act on: this
 * turn is read-only, here are the tools you may call, ask the user if you
 * need the others.
 */
class StreamingToolExecutorR410Test {

    private static Tool tool(String name) {
        return org.aethercode.core.tool.Tools.build(new org.aethercode.core.tool.ToolDef(
                name, name, Map.of("type", "object", "properties", Map.of()),
                (input, ctx) -> CompletableFuture.completedFuture(Tool.ToolResult.of("ok"))));
    }

    private static List<Tool> pool(String... names) {
        List<Tool> out = new ArrayList<>();
        for (String n : names) out.add(tool(n));
        return out;
    }

    private static List<String> names(List<Tool> pool) {
        List<String> out = new ArrayList<>();
        for (Tool t : pool) out.add(t.name());
        return out;
    }

    // ------------------------------------------------------------------
    //  1. the refusal
    // ------------------------------------------------------------------

    @Test
    void theRefusalNamesTheConstraintAndTheAllowedTools() {
        AppState state = new AppState("s1", Path.of("."));
        state.activeProfile(ProfileRegistry.ANALYST);
        List<Tool> allowed = pool("file_read", "grep", "glob");

        String refusal = StreamingToolExecutor.toolNotAvailable("file_write", allowed, state);

        assertThat(refusal)
                .as("★ the refusal must NOT say 'unknown' — that is what sent the model hunting for a name")
                .doesNotContain("unknown tool")
                .contains("file_write")
                .as("★ the refusal must name the constraint, so the model can conclude 'not allowed', "
                        + "not 'I misnamed it'")
                .contains("analyst")
                .as("★ the refusal must list the tools it MAY call")
                .contains("file_read")
                .contains("grep")
                .contains("glob");
    }

    @Test
    void theRefusalSaysTheToolIsRefusedByDesignWhenTheProfileDeniedIt() {
        AppState state = new AppState("s1", Path.of("."));
        state.activeProfile(ProfileRegistry.ANALYST);
        List<Tool> allowed = pool("file_read");

        String refusal = StreamingToolExecutor.toolNotAvailable("file_write", allowed, state);

        assertThat(refusal)
                .as("a denied tool must be called out as a DESIGN decision, not a missing one")
                .contains("BY DESIGN");
    }

    @Test
    void theRefusalStillListsTheToolsWhenThereIsNoProfile() {
        // A turn with no posture has no profile name to report, but the
        // tool table is still known — and listing it is the part that
        // stops the guessing.
        AppState state = new AppState("s1", Path.of("."));
        List<Tool> allowed = pool("file_read", "web_search");

        String refusal = StreamingToolExecutor.toolNotAvailable("mystery_tool", allowed, state);

        assertThat(refusal)
                .doesNotContain("unknown tool")
                .contains("file_read")
                .contains("web_search");
    }

    // ------------------------------------------------------------------
    //  2. the posture
    // ------------------------------------------------------------------

    @Test
    void aReadOnlyProfileHasNoAgentRegistryWrite() {
        // ★ THE LIVE DEFECT ★ the agent registry is outside the working
        // tree, so denying the write tools did not deny it.
        List<Tool> full = pool("file_read", "grep", "glob", "list_agents",
                "create_agent", "update_agent", "delete_agent", "spawn_agent");
        for (Profile p : List.of(ProfileRegistry.ANALYST, ProfileRegistry.REVIEWER,
                                 ProfileRegistry.NAVIGATOR, ProfileRegistry.RESEARCHER)) {
            List<String> kept = names(ProfileRegistry.prune(full, p));
            assertThat(kept)
                    .as("★ profile " + p.name() + " must not be able to rewrite the user's agents")
                    .doesNotContain("create_agent")
                    .doesNotContain("update_agent")
                    .doesNotContain("delete_agent");
            assertThat(kept)
                    .as("profile " + p.name() + " keeps the READING tools")
                    .contains("file_read", "grep", "glob", "list_agents");
        }
    }

    @Test
    void theDenySetIsNamedSoItCanBeRead() {
        // The deny set is a LIST of every way the user's state can change.
        // It is a named constant on purpose: a reader can see what a
        // read-only posture refuses, and a test can pin it.
        assertThat(ProfileRegistry.READ_ONLY_DENY)
                .contains("file_write", "file_edit", "notebook_edit", "bash")
                .containsAll(ProfileRegistry.AGENT_WRITE_TOOLS);
        assertThat(ProfileRegistry.AGENT_WRITE_TOOLS)
                .containsExactlyInAnyOrder("create_agent", "update_agent", "delete_agent");
    }

    @Test
    void theResearcherProfileIsReadOnlyAndRefusesToDelegate() {
        // RESEARCH = read-only + no delegation. Built FROM the read-only
        // set, so the two cannot drift apart.
        assertThat(ProfileRegistry.RESEARCH_DENY)
                .containsAll(ProfileRegistry.READ_ONLY_DENY)
                .contains(ProfileRegistry.SPAWN_TOOL);
        List<String> kept = names(ProfileRegistry.prune(
                pool("web_search", "file_read", "bash", "spawn_agent", "update_agent"),
                ProfileRegistry.RESEARCHER));
        assertThat(kept).containsExactly("web_search", "file_read");
    }

    @Test
    void anActionProfileKeepsTheAgentTools() {
        // The deny set belongs to the READ-ONLY profiles. A turn that was
        // asked to change something may still set an agent up.
        List<String> kept = names(ProfileRegistry.prune(
                pool("file_write", "update_agent", "spawn_agent"),
                ProfileRegistry.BUILDER));
        assertThat(kept).containsExactly("file_write", "update_agent", "spawn_agent");
    }
}
