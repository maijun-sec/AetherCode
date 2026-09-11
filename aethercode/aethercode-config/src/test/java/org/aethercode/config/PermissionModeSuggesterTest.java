package org.aethercode.config;

import org.aethercode.core.permission.PermissionMode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * tests for the heuristic permission-mode suggester.
 */
class PermissionModeSuggesterTest {

    @Test
    void suggest_nullRoot_returnsAskBeforeTool() {
        // empty / exploration projects now get the explicit
        // ASK_BEFORE_TOOL recommendation (was DEFAULT legacy; both
        // modes are semantically identical — the new name is just
        // more discoverable in the TUI's status bar).
        PermissionModeSuggester.Suggestion s =
                PermissionModeSuggester.suggest(null);
        assertThat(s.mode()).isEqualTo(PermissionMode.ASK_BEFORE_TOOL);
        assertThat(s.reasons()).containsExactly("no project root");
    }

    @Test
    void suggest_emptyDirectory_returnsAskBeforeTool(@TempDir Path tmp) throws Exception {
        // @TempDir already gives an empty dir.
        PermissionModeSuggester.Suggestion s =
                PermissionModeSuggester.suggest(tmp);
        assertThat(s.mode()).isEqualTo(PermissionMode.ASK_BEFORE_TOOL);
        assertThat(s.reasons()).containsExactly("empty directory");
    }

    @Test
    void suggest_justReadme_returnsAskBeforeTool(@TempDir Path tmp) throws Exception {
        Files.writeString(tmp.resolve("README.md"), "# hello");
        PermissionModeSuggester.Suggestion s =
                PermissionModeSuggester.suggest(tmp);
        assertThat(s.mode()).isEqualTo(PermissionMode.ASK_BEFORE_TOOL);
        assertThat(s.reasons()).containsExactly("no src/ or test/ directory");
    }

    @Test
    void suggest_matureSourceProject_returnsAcceptEdits(@TempDir Path tmp) throws Exception {
        // src/ + tests/ — ACCEPT_EDITS.
        Files.createDirectory(tmp.resolve("src"));
        Files.createDirectory(tmp.resolve("tests"));
        Files.writeString(tmp.resolve("src").resolve("Main.java"), "");
        PermissionModeSuggester.Suggestion s =
                PermissionModeSuggester.suggest(tmp);
        assertThat(s.mode()).isEqualTo(PermissionMode.ACCEPT_EDITS);
        assertThat(s.reasons()).containsExactly("has src/ + tests/");
    }

    @Test
    void suggest_matureSourceProjectWithTestDir_returnsAcceptEdits(@TempDir Path tmp) throws Exception {
        // src/ + test/ (singular) — also ACCEPT_EDITS.
        Files.createDirectory(tmp.resolve("src"));
        Files.createDirectory(tmp.resolve("test"));
        PermissionModeSuggester.Suggestion s =
                PermissionModeSuggester.suggest(tmp);
        assertThat(s.mode()).isEqualTo(PermissionMode.ACCEPT_EDITS);
        assertThat(s.reasons()).containsExactly("has src/ + tests/");
    }

    @Test
    void suggest_gitRepoWithCI_returnsAcceptTask(@TempDir Path tmp) throws Exception {
        // .git/ + .github/workflows/ — ACCEPT_TASK.
        Files.createDirectory(tmp.resolve(".git"));
        Files.createDirectories(tmp.resolve(".github").resolve("workflows"));
        Files.writeString(tmp.resolve(".github").resolve("workflows")
                .resolve("ci.yml"), "name: ci");
        PermissionModeSuggester.Suggestion s =
                PermissionModeSuggester.suggest(tmp);
        assertThat(s.mode()).isEqualTo(PermissionMode.ACCEPT_TASK);
        assertThat(s.reasons())
                .contains("has CI config (.github/workflows or .gitlab-ci.yml)");
    }

    @Test
    void suggest_gitLabCi_returnsAcceptTask(@TempDir Path tmp) throws Exception {
        // .gitlab-ci.yml alone (no .git/) — CI signal still wins.
        Files.writeString(tmp.resolve(".gitlab-ci.yml"), "stages: [test]");
        PermissionModeSuggester.Suggestion s =
                PermissionModeSuggester.suggest(tmp);
        assertThat(s.mode()).isEqualTo(PermissionMode.ACCEPT_TASK);
        assertThat(s.reasons())
                .contains("has CI config (.github/workflows or .gitlab-ci.yml)");
    }

    @Test
    void suggest_matureProjectWithCI_prefersAcceptTask(@TempDir Path tmp) throws Exception {
        // src/ + tests/ + CI: BOTH heuristics match.
        // ACCEPT_TASK is the "stronger" suggestion because CI
        // signals a workflow that's already trusted.
        Files.createDirectory(tmp.resolve("src"));
        Files.createDirectory(tmp.resolve("tests"));
        Files.createDirectories(tmp.resolve(".github").resolve("workflows"));
        PermissionModeSuggester.Suggestion s =
                PermissionModeSuggester.suggest(tmp);
        assertThat(s.mode()).isEqualTo(PermissionMode.ACCEPT_TASK);
        assertThat(s.reasons()).hasSize(2);
        // CI reason first (strongest).
        assertThat(s.reasons().get(0))
                .contains("CI config");
    }

    @Test
    void suggest_circleCi_returnsAcceptTask(@TempDir Path tmp) throws Exception {
        // .circleci/config.yml — also CI.
        Files.createDirectory(tmp.resolve(".circleci"));
        Files.writeString(tmp.resolve(".circleci").resolve("config.yml"), "version: 2.1");
        PermissionModeSuggester.Suggestion s =
                PermissionModeSuggester.suggest(tmp);
        assertThat(s.mode()).isEqualTo(PermissionMode.ACCEPT_TASK);
    }

    @Test
    void suggest_onlySrcNoTests_returnsAskBeforeTool(@TempDir Path tmp) throws Exception {
        // src/ without tests/ — heuristic 2 doesn't fire, so the
        // suggester falls through to ASK_BEFORE_TOOL (R163; was
        // DEFAULT legacy).
        Files.createDirectory(tmp.resolve("src"));
        PermissionModeSuggester.Suggestion s =
                PermissionModeSuggester.suggest(tmp);
        assertThat(s.mode()).isEqualTo(PermissionMode.ASK_BEFORE_TOOL);
    }

    @Test
    void suggest_isDeterministic_sameInputSameOutput(@TempDir Path tmp) throws Exception {
        // Two consecutive calls on the same project return
        // equivalent suggestions (equal mode + equal reasons).
        Files.createDirectory(tmp.resolve("src"));
        Files.createDirectory(tmp.resolve("tests"));
        PermissionModeSuggester.Suggestion s1 =
                PermissionModeSuggester.suggest(tmp);
        PermissionModeSuggester.Suggestion s2 =
                PermissionModeSuggester.suggest(tmp);
        assertThat(s1.mode()).isEqualTo(s2.mode());
        assertThat(s1.reasons()).isEqualTo(s2.reasons());
    }

    @Test
    void suggest_reasonsListIsImmutable() throws Exception {
        // The returned reasons list must be unmodifiable.
        @SuppressWarnings("unused")
        Path tmp = Files.createTempDirectory("suggest-test-");
        // skip — we test with a no-arg Suggestion record
        // directly.
        PermissionModeSuggester.Suggestion s = new PermissionModeSuggester.Suggestion(
                PermissionMode.DEFAULT, java.util.List.of("a", "b"));
        org.assertj.core.api.Assertions.assertThatThrownBy(
                () -> s.reasons().add("c"))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
