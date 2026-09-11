package org.aethercode.workflows;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T-2-06 / Phase 2.1 acceptance: skill composition produces a
 * system-prompt-suffix that lists each installed skill and
 * reports missing ones. Four tests cover the empty, installed,
 * missing, and project-overrides-user cases.
 */
class SkillComposerTest {

    @Test
    void emptySkillListProducesEmptySuffix() {
        SkillComposer composer = new SkillComposer((Path) null, (Path) null);
        assertThat(composer.compose(List.of())).isEmpty();
    }

    @Test
    void installedSkillsAreRenderedWithBody() throws IOException {
        Path userDir = writeSkill(null, "tdd", """
                ---
                name: tdd
                description: Test-driven development workflow.
                ---

                Write a failing test first.
                Implement to green.
                Refactor for clarity.
                """);
        SkillComposer composer = new SkillComposer(userDir, null);
        String out = composer.compose(List.of("tdd"));
        assertThat(out).contains("# Injected skills");
        assertThat(out).contains("**tdd**");
        assertThat(out).contains("Test-driven development workflow");
        assertThat(out).contains("Write a failing test first");
    }

    @Test
    void missingSkillsAreReportedAsStubs() {
        SkillComposer composer = new SkillComposer((Path) null, (Path) null);
        String out = composer.compose(List.of("not-there"));
        assertThat(out).contains("'not-there' is not installed");
    }

    @Test
    void projectSkillOverridesUserSkill() throws IOException {
        Path userDir = writeSkill(null, "shared", """
                ---
                name: shared
                description: User copy.
                ---

                USER BODY
                """);
        Path projectDir = writeSkill(null, "shared", """
                ---
                name: shared
                description: Project copy.
                ---

                PROJECT BODY
                """);
        SkillComposer composer = new SkillComposer(userDir, projectDir);
        String out = composer.compose(List.of("shared"));
        assertThat(out).contains("Project copy");
        assertThat(out).contains("PROJECT BODY");
        assertThat(out).doesNotContain("USER BODY");
    }

    // -- helpers ----------------------------------------------------------

    private static Path writeSkill(Path tmp, String name, String body) throws IOException {
        if (tmp == null) {
            // JUnit's @TempDir is parameter-scoped; this helper
            // is called from tests that already have one. The
            // (Path)null overload creates a fresh temp dir.
            tmp = Files.createTempDirectory("skills-test-");
        }
        Path dir = tmp.resolve("skills");
        Files.createDirectories(dir);
        Path file = dir.resolve(name + ".md");
        Files.writeString(file, body, StandardCharsets.UTF_8);
        return dir;
    }
}
