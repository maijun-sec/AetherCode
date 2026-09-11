package org.aethercode.core.workflow;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** unit tests for the minimal YAML reader. The reader is
 *  regex-based, so the tests focus on the field extraction the
 *  desktop relies on: {@code name}, {@code description},
 *  {@code inputs[]}, and the {@code steps[].{id, type}} pair.
 *  Schema-level validation is the executor's job (the agent
 *  that loads the {@code workflow-system} skill). */
class WorkflowReaderTest {

    @Test
    void parseExtractsTopLevelMetadata() {
        String raw = """
                name: quick-review
                description: 3-step review
                version: 1

                inputs:
                  note:
                    type: string
                    default: ""

                steps:
                  - id: list-changes
                    type: shell
                  - id: run-tests
                    type: shell
                """;
        var doc = WorkflowReader.parse(raw, "fallback");
        assertEquals("quick-review", doc.name());
        assertEquals("3-step review", doc.description());
        assertEquals(List.of("note"), doc.inputs());
        assertEquals(2, doc.steps().size());
    }

    @Test
    void parseFallsBackWhenNameIsMissing() {
        String raw = """
                description: no name
                steps:
                  - id: a
                    type: shell
                """;
        var doc = WorkflowReader.parse(raw, "fallback-name");
        assertEquals("fallback-name", doc.name());
    }

    @Test
    void parseCollectsStepIdsAndTypes() {
        String raw = """
                name: t
                steps:
                  - id: pull
                    type: shell
                  - id: test
                    type: shell
                  - id: review
                    type: skill
                  - id: commit
                    type: gate
                """;
        var doc = WorkflowReader.parse(raw, "t");
        assertEquals(4, doc.steps().size());
        assertEquals("pull", doc.steps().get(0).id());
        assertEquals("shell", doc.steps().get(0).type());
        assertEquals("skill", doc.steps().get(2).type());
        assertEquals("gate", doc.steps().get(3).type());
    }

    @Test
    void parseStripsQuotesFromStringValues() {
        String raw = """
                name: "quoted-name"
                description: 'single quoted'
                steps:
                  - id: "step-1"
                    type: 'shell'
                """;
        var doc = WorkflowReader.parse(raw, "fb");
        assertEquals("quoted-name", doc.name());
        assertEquals("single quoted", doc.description());
        assertEquals("step-1", doc.steps().get(0).id());
        assertEquals("shell", doc.steps().get(0).type());
    }

    @Test
    void parseIgnoresInlineComments() {
        String raw = """
                name: t  # this is a comment
                description: desc  # another
                steps:
                  - id: a  # inline
                    type: shell
                """;
        var doc = WorkflowReader.parse(raw, "fb");
        assertEquals("t", doc.name());
        assertEquals("desc", doc.description());
        assertEquals("a", doc.steps().get(0).id());
    }

    @Test
    void listReadsFromCwd() throws IOException {
        // The reader takes cwd and joins `.aethercode/workflows` —
        // so we set up a fake project root with that structure and
        // call list(cwd) where cwd is the project root.
        Path dir = Files.createTempDirectory("wf-reader-test");
        try {
            // Create .aethercode/workflows/{a.yaml, b.yml, readme.md}
            Path wrap = dir.resolve(".aethercode").resolve("workflows");
            Files.createDirectories(wrap);
            Files.writeString(wrap.resolve("a.yaml"),
                    "name: a\nsteps:\n  - id: x\n    type: shell\n");
            Files.writeString(wrap.resolve("b.yml"),
                    "name: b\nsteps:\n  - id: y\n    type: shell\n");
            // Non-yaml file is ignored.
            Files.writeString(wrap.resolve("readme.md"), "ignore me");
            // Pass the project root, not the workflow dir.
            var docs = WorkflowReader.list(dir);
            assertEquals(2, docs.size(),
                    "expected 2 yaml workflows, got " + docs.size());
            assertEquals("a", docs.get(0).name());
            assertEquals("b", docs.get(1).name());
        } finally {
            try (var s = Files.walk(dir)) {
                s.sorted((x, y) -> y.compareTo(x)).forEach((p) -> {
                    try { Files.deleteIfExists(p); } catch (IOException ignored) {}
                });
            }
        }
    }

    @Test
    void listReturnsEmptyForMissingDir() {
        Path missing = Path.of("Z:/definitely/not/here/for/the/test");
        var docs = WorkflowReader.list(missing);
        assertTrue(docs.isEmpty(), "missing dir should yield empty list");
    }

    @Test
    void workflowFileSanitisesPathTraversal() {
        Path cwd = Path.of("C:/work");
        assertThrows(IllegalArgumentException.class,
                () -> WorkflowPaths.workflowFile(cwd, "../etc/passwd"));
        assertThrows(IllegalArgumentException.class,
                () -> WorkflowPaths.workflowFile(cwd, "..\\windows"));
        assertThrows(IllegalArgumentException.class,
                () -> WorkflowPaths.workflowFile(cwd, "a/b"));
        assertThrows(IllegalArgumentException.class,
                () -> WorkflowPaths.workflowFile(cwd, ""));
        assertThrows(IllegalArgumentException.class,
                () -> WorkflowPaths.workflowFile(cwd, null));
    }

    @Test
    void workflowFileAppendsYamlExtensionIfMissing() {
        Path cwd = Path.of("C:/work");
        Path p = WorkflowPaths.workflowFile(cwd, "quick-review");
        assertTrue(p.toString().endsWith("quick-review.yaml"),
                "expected .yaml appended: " + p);
    }
}
