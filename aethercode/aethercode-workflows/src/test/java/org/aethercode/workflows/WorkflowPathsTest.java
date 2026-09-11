package org.aethercode.workflows;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * T-2-08 acceptance tests for {@link WorkflowPaths}. The three
 * tests cover the three behaviour vectors that downstream callers
 * (RPC handlers, CLI, loader) rely on:
 *
 * <ol>
 *   <li>{@code resolve} / {@code list} project-overrides-user — the
 *       central rule in design.md §3.6 + spec.md §11.3.</li>
 *   <li>{@code assertSafeName} rejects path-traversal and out-of-band
 *       characters.</li>
 *   <li>{@code defaultWriteTarget} prefers the project layer and
 *       falls back to the user layer.</li>
 * </ol>
 */
class WorkflowPathsTest {

    @Test
    void projectLayerTakesPrecedenceOverUserLayer(@TempDir Path tmp) throws IOException {
        // Both layers have a workflow named "tdd-feature"; the project
        // copy should be returned by resolve() and the list should
        // contain the name exactly once.
        Path userHome = tmp.resolve("userHome");
        Path cwd = tmp.resolve("project");
        Files.createDirectories(WorkflowPaths.userDir(userHome));
        Files.createDirectories(WorkflowPaths.projectDir(cwd));
        Files.writeString(WorkflowPaths.userYaml(userHome, "tdd-feature"), "user\n");
        Files.writeString(WorkflowPaths.projectYaml(cwd, "tdd-feature"), "project\n");

        Path resolved = WorkflowPaths.resolve(userHome, cwd, "tdd-feature");
        assertThat(resolved).isNotNull();
        assertThat(resolved).isEqualTo(WorkflowPaths.projectYaml(cwd, "tdd-feature"));
        assertThat(Files.readString(resolved, StandardCharsets.UTF_8)).isEqualTo("project\n");

        // list() merges, dedupes, and sorts.
        List<String> names = WorkflowPaths.list(userHome, cwd);
        assertThat(names).containsExactly("tdd-feature");
    }

    @Test
    void userAndProjectAreMergedInList(@TempDir Path tmp) throws IOException {
        // Different names in each layer: list returns both, sorted.
        Path userHome = tmp.resolve("userHome");
        Path cwd = tmp.resolve("project");
        Files.createDirectories(WorkflowPaths.userDir(userHome));
        Files.createDirectories(WorkflowPaths.projectDir(cwd));
        Files.writeString(WorkflowPaths.userYaml(userHome, "explain-code"), "x");
        Files.writeString(WorkflowPaths.userYaml(userHome, "quick-review"), "x");
        Files.writeString(WorkflowPaths.projectYaml(cwd, "ship-it"), "x");
        Files.writeString(WorkflowPaths.projectYaml(cwd, "tdd-feature"), "x");

        List<String> names = WorkflowPaths.list(userHome, cwd);
        assertThat(names).containsExactly("explain-code", "quick-review", "ship-it", "tdd-feature");

        // Each name resolves to its layer's file.
        assertThat(WorkflowPaths.resolve(userHome, cwd, "explain-code"))
                .isEqualTo(WorkflowPaths.userYaml(userHome, "explain-code"));
        assertThat(WorkflowPaths.resolve(userHome, cwd, "ship-it"))
                .isEqualTo(WorkflowPaths.projectYaml(cwd, "ship-it"));
    }

    @Test
    void assertSafeNameRejectsPathTraversalAndSpecialChars(@TempDir Path tmp) {
        Path userHome = tmp.resolve("userHome");
        Path cwd = tmp.resolve("project");

        // Path-traversal and out-of-band characters are all rejected.
        assertThatThrownBy(() -> WorkflowPaths.assertSafeName(".."))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("'.' or '..'");
        assertThatThrownBy(() -> WorkflowPaths.assertSafeName("."))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> WorkflowPaths.assertSafeName("../etc/passwd"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("illegal character");
        assertThatThrownBy(() -> WorkflowPaths.assertSafeName("a/b"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> WorkflowPaths.assertSafeName("a\\b"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> WorkflowPaths.assertSafeName("with space"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> WorkflowPaths.assertSafeName(""))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> WorkflowPaths.assertSafeName(null))
                .isInstanceOf(IllegalArgumentException.class);

        // Acceptable names round-trip.
        for (String ok : new String[]{"tdd-feature", "explain_code", "v1.2.3", "ship.it", "x"}) {
            assertThat(WorkflowPaths.assertSafeName(ok)).isEqualTo(ok);
        }

        // defaultWriteTarget prefers the project layer when writable.
        Path target = WorkflowPaths.defaultWriteTarget(userHome, cwd, "tdd-feature");
        assertThat(target).isEqualTo(WorkflowPaths.projectYaml(cwd, "tdd-feature"));
        assertThat(target.getParent()).isDirectory();

        // defaultWriteTarget on a null userHome still produces a path
        // (caller will surface an error if it can't be written).
        assertThat(WorkflowPaths.defaultWriteTarget(null, cwd, "x"))
                .isEqualTo(WorkflowPaths.projectYaml(cwd, "x"));
    }
}
