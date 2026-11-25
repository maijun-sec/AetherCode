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

package org.aethercode.prompts;

import org.aethercode.core.tool.Tool;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * R409: the prompt's TWO PER-TURN sections.
 *
 * <h2>Why this exists</h2>
 * <p>Before R409 the system prompt was rendered ONCE, at construction time,
 * and every turn inherited it. Two of its facts are per-turn:
 *
 * <ul>
 *   <li>the <b>tool table</b> — the profile prunes the tool pool per turn,
 *       so the table must be rendered from THIS turn's pool;</li>
 *   <li>the <b>working directory</b> — the session's cwd changes
 *       ({@code switchProject}).</li>
 * </ul>
 *
 * <p>R408 measured the cost of freezing them: the prompt advertised tools
 * the API had not offered, the model called them, and 48 calls went into
 * hunting for the "real" name of a tool that was never going to exist.
 *
 * <h2>★ WHY THESE TESTS ASSERT ON THE SECTION ★</h2>
 * <p>The whole prompt mentions {@code file_write} — the default identity
 * text says "you MUST call the actual `file_write` tool". So an assertion
 * over the WHOLE prompt ("the prompt does not contain file_write") is not
 * a test of the tool table at all; it is a test that would have to fail
 * for a reason that has nothing to do with the table.
 *
 * <p>⇒ The assertions below read the {@code tooling} / {@code environment}
 * SECTION. That is where the fact lives, and a section that says the wrong
 * thing cannot be excused by another section saying the right thing.
 */
class SystemPromptR409Test {

    private static String section(RenderedPrompt rp, String name) {
        for (RenderedPrompt.Section s : rp.sections()) {
            if (name.equals(s.name())) return s.text();
        }
        return null;
    }

    // ------------------------------------------------------------------
    //  1. the tool table
    // ------------------------------------------------------------------

    @Test
    void withTooling_listsExactlyTheToolsItWasGiven() {
        SystemPrompt base = SystemPrompt.builder()
                .toolingFrom(List.of(tool("file_read", "Read a file."), tool("file_write", "Write a file.")))
                .build();
        assertThat(section(base.renderWithSources(), "tooling"))
                .as("the base prompt still lists both tools")
                .contains("file_read")
                .contains("file_write");

        SystemPrompt pruned = base.withTooling(List.of(tool("file_read", "Read a file.")));

        assertThat(section(pruned.renderWithSources(), "tooling"))
                .as("★ the pruned table must NOT advertise a tool the model cannot call")
                .contains("file_read")
                .doesNotContain("file_write");
    }

    @Test
    void theToolTable_carriesTheDescriptionButNotTheSchema() {
        // The description is the model's hint about WHEN to call the tool;
        // the schema is the API's business (it is sent with the request).
        SystemPrompt p = SystemPrompt.builder()
                .toolingFrom(List.of(tool("file_write", "Write text into a file.")))
                .build();

        assertThat(section(p.renderWithSources(), "tooling"))
                .contains("Write text into a file.")
                .as("★ the schema must NOT be rendered into the prompt — the API already sends it (R408 P0-5)")
                .doesNotContain("Input schema");
    }

    @Test
    void withTooling_replacesTheTableRatherThanAppendingToIt() {
        // A copy that APPENDED would leave the old tools in the prompt —
        // the exact defect R408 measured.
        SystemPrompt base = SystemPrompt.builder()
                .toolingFrom(List.of(tool("bash", "Run a shell command.")))
                .build();
        SystemPrompt pruned = base.withTooling(List.of(tool("file_read", "Read a file.")));

        assertThat(section(pruned.renderWithSources(), "tooling"))
                .doesNotContain("Run a shell command.")
                .doesNotContain("bash");
    }

    // ------------------------------------------------------------------
    //  2. the environment section
    // ------------------------------------------------------------------

    @Test
    void withEnvironment_followsTheWorkingDirectory() {
        // The expectation is the Path's own string: on Windows
        // Path.of("/old/project") renders as "\old\project", so a
        // hand-written "/old/project" would be a DIFFERENT string and the
        // test would be measuring the platform, not the behaviour.
        String oldDir = Path.of("/old/project").toString();
        String newDir = Path.of("/new/project").toString();
        SystemPrompt base = SystemPrompt.builder()
                .environmentFrom(Path.of("/old/project"), "Linux")
                .build();
        assertThat(section(base.renderWithSources(), "environment")).contains(oldDir);

        SystemPrompt moved = base.withEnvironment(Path.of("/new/project"), "Linux");

        assertThat(section(moved.renderWithSources(), "environment"))
                .as("★ the prompt must name the cwd the session is actually in")
                .contains(newDir)
                .doesNotContain(oldDir);
    }

    @Test
    void withEnvironment_keepsTheSectionWhenThereIsNoDirectory() {
        // A session with no cwd is NOT a session whose cwd is "null".
        String oldDir = Path.of("/old/project").toString();
        SystemPrompt base = SystemPrompt.builder()
                .environmentFrom(Path.of("/old/project"), "Linux")
                .build();

        SystemPrompt kept = base.withEnvironment(null, "Linux");

        assertThat(section(kept.renderWithSources(), "environment"))
                .contains(oldDir)
                .doesNotContain("null");
    }

    // ------------------------------------------------------------------
    //  3. copying must not lose the other sections
    // ------------------------------------------------------------------

    @Test
    void copyingKeepsEveryOtherSection() {
        SystemPrompt base = SystemPrompt.builder()
                .identity("You are AetherCode.")
                .rules("PROJECT RULE")
                .agents("AGENT INDEX")
                .workflow("WORKFLOW")
                .environmentFrom(Path.of("/old/project"), "Linux")
                .toolingFrom(List.of(tool("file_write", "Write a file.")))
                .build();

        RenderedPrompt rp = base
                .withEnvironment(Path.of("/new/project"), "Linux")
                .withTooling(List.of(tool("file_read", "Read a file.")))
                .renderWithSources();

        assertThat(section(rp, "identity")).contains("You are AetherCode.");
        assertThat(section(rp, "rules")).contains("PROJECT RULE");
        assertThat(section(rp, "agents")).contains("AGENT INDEX");
        assertThat(section(rp, "workflow")).contains("WORKFLOW");
        assertThat(section(rp, "environment")).contains(Path.of("/new/project").toString());
        assertThat(section(rp, "tooling")).contains("file_read");
    }

    @Test
    void withTooling_doesNotMutateThePromptItWasCalledOn() {
        // The base prompt is shared: the SDK keeps it and re-renders it per
        // turn. If withTooling mutated it, turn 2 would inherit turn 1's
        // table.
        SystemPrompt base = SystemPrompt.builder()
                .toolingFrom(List.of(tool("file_read", "Read a file."), tool("file_write", "Write a file.")))
                .build();

        base.withTooling(List.of(tool("file_read", "Read a file.")));

        assertThat(section(base.renderWithSources(), "tooling"))
                .as("the base prompt must be untouched")
                .contains("file_write");
    }

    // ------------------------------------------------------------------
    //  helpers
    // ------------------------------------------------------------------

    private static Tool tool(String name, String description) {
        return new Tool() {
            @Override public String name() { return name; }
            @Override public String description() { return description; }
            @Override public Map<String, Object> inputSchema() { return Map.of(); }
            @Override public CompletableFuture<org.aethercode.core.permission.PermissionResult>
                    checkPermissions(Map<String, Object> input, CallContext ctx) {
                return CompletableFuture.completedFuture(
                        new org.aethercode.core.permission.PermissionResult.Allow(Map.of()));
            }
            @Override public CompletableFuture<ToolResult> call(Map<String, Object> input, CallContext ctx) {
                return CompletableFuture.completedFuture(ToolResult.of("ok"));
            }
        };
    }
}
