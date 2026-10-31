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
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

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

    // --- R373: the stem is the addressable id ------------------------

    @Test
    void scanKeepsTheFileNameSeparateFromTheDeclaredName() throws IOException {
        // The load-bearing case: a file called `deploy.yaml` that
        // declares `name: ship-it` is ADDRESSED by "deploy.yaml",
        // because `WorkflowPaths.workflowFile(cwd, "ship-it")`
        // resolves `ship-it.yaml` — which does not exist. Before R373
        // the only published field was the declared name, so a client
        // had no way to start what it was shown.
        Path dir = Files.createTempDirectory("wf-scan-stem");
        try {
            Path wrap = dir.resolve(".aethercode").resolve("workflows");
            Files.createDirectories(wrap);
            Files.writeString(wrap.resolve("deploy.yaml"),
                    "name: ship-it\nsteps:\n  - id: x\n    type: shell\n");
            var listing = WorkflowReader.scan(dir);
            assertEquals(1, listing.entries().size(),
                    "expected exactly one workflow, got " + listing.entries().size());
            var e = listing.entries().get(0);
            assertEquals("deploy.yaml", e.fileName(),
                    "the id is the FILE NAME, not the declared name");
            assertEquals("ship-it", e.doc().name(),
                    "the declared name is still the display name");
            // The decisive assertion: the published id RESOLVES.
            assertTrue(Files.isRegularFile(WorkflowPaths.workflowFile(dir, e.fileName())),
                    "the published id must resolve to a real file");
        } finally {
            deleteTree(dir);
        }
    }

    @Test
    void workflowFileResolvesTheYmlExtensionToo() throws IOException {
        // R373: the reader accepted BOTH `.yaml` and `.yml`, but the
        // resolver only ever produced `.yaml`. A workflow stored as
        // `beta.yml` was therefore listed and could not be started,
        // written to, or deleted — every one of those three went
        // through `workflowFile` and every one of them missed it.
        Path dir = Files.createTempDirectory("wf-yml-ext");
        try {
            Path wrap = dir.resolve(".aethercode").resolve("workflows");
            Files.createDirectories(wrap);
            Files.writeString(wrap.resolve("beta.yml"), "name: beta\n");

            // The bare name must find the `.yml` file.
            assertEquals("beta.yml",
                    WorkflowPaths.workflowFile(dir, "beta").getFileName().toString(),
                    "a bare name must resolve to the existing .yml file");

            // ...and the id the listing publishes must resolve too.
            var listing = WorkflowReader.scan(dir);
            assertEquals(1, listing.entries().size());
            assertEquals("beta.yml", listing.entries().get(0).fileName());
            assertTrue(Files.isRegularFile(
                    WorkflowPaths.workflowFile(dir, listing.entries().get(0).fileName())));
        } finally {
            deleteTree(dir);
        }
    }

    @Test
    void workflowFilePrefersYamlWhenBothExtensionsExist() throws IOException {
        // The tie-break has to be deterministic, and it has to be the
        // SAME one the listing uses — otherwise the first row and the
        // second row of the picker would launch different files for
        // the same name.
        Path dir = Files.createTempDirectory("wf-both-ext");
        try {
            Path wrap = dir.resolve(".aethercode").resolve("workflows");
            Files.createDirectories(wrap);
            Files.writeString(wrap.resolve("both.yaml"), "name: both\n");
            Files.writeString(wrap.resolve("both.yml"), "name: both\n");
            assertEquals("both.yaml",
                    WorkflowPaths.workflowFile(dir, "both").getFileName().toString());
        } finally {
            deleteTree(dir);
        }
    }

    @Test
    void scanFallsBackToTheStemWhenTheYamlDeclaresNoName() throws IOException {
        Path dir = Files.createTempDirectory("wf-scan-noname");
        try {
            Path wrap = dir.resolve(".aethercode").resolve("workflows");
            Files.createDirectories(wrap);
            Files.writeString(wrap.resolve("anon.yaml"),
                    "description: no name here\nsteps:\n  - id: x\n    type: shell\n");
            var listing = WorkflowReader.scan(dir);
            assertEquals(1, listing.entries().size());
            assertEquals("anon.yaml", listing.entries().get(0).fileName());
            assertEquals("anon", listing.entries().get(0).doc().name(),
                    "the declared name falls back to the stem");
        } finally {
            deleteTree(dir);
        }
    }

    @Test
    void scanReportsFilesItCouldNotReadInsteadOfDroppingThem() throws IOException {
        // The branch that keeps "no workflows here" from being a lie.
        //
        // It used to be unreachable from a test: file locks are
        // ADVISORY on Linux, so a lock-based fixture would skip itself
        // on half the platforms. The reader seam makes it reachable
        // everywhere — and a branch that no test can reach is a branch
        // that rots.
        Path dir = Files.createTempDirectory("wf-scan-skip");
        try {
            Path wrap = dir.resolve(".aethercode").resolve("workflows");
            Files.createDirectories(wrap);
            Files.writeString(wrap.resolve("good.yaml"), "name: good\n");
            Files.writeString(wrap.resolve("bad.yaml"), "name: bad\n");
            var listing = WorkflowReader.scan(dir, p -> {
                if (p.getFileName().toString().startsWith("bad")) {
                    throw new IOException("simulated unreadable workflow file");
                }
                return WorkflowReader.read(p);
            });
            assertEquals(1, listing.entries().size(),
                    "the readable file is still listed");
            assertEquals("good.yaml", listing.entries().get(0).fileName());
            assertEquals(List.of("bad.yaml"), listing.skipped(),
                    "the unreadable file is REPORTED, not dropped");
        } finally {
            deleteTree(dir);
        }
    }

    @Test
    void scanOfAMissingDirectoryIsEmptyNotAnError() {
        Path missing = Path.of("Z:/definitely/not/here/for/the/test");
        var listing = WorkflowReader.scan(missing);
        assertTrue(listing.entries().isEmpty(), "missing dir yields no entries");
        assertTrue(listing.skipped().isEmpty(),
                "a missing dir is not a skipped file — those are different facts");
    }

    @Test
    void listStillReturnsDocumentsOnly() throws IOException {
        // `list` is the pre-R373 API. R373 added `scan`; it must NOT
        // change what `list` returns.
        Path dir = Files.createTempDirectory("wf-list-compat");
        try {
            Path wrap = dir.resolve(".aethercode").resolve("workflows");
            Files.createDirectories(wrap);
            Files.writeString(wrap.resolve("a.yaml"), "name: a\n");
            var docs = WorkflowReader.list(dir);
            assertEquals(1, docs.size());
            assertEquals("a", docs.get(0).name());
        } finally {
            deleteTree(dir);
        }
    }

    /** Best-effort recursive delete; the tests only ever create small
     *  temp trees, so a failure here must not fail the test. */
    private static void deleteTree(Path dir) {
        try (var s = Files.walk(dir)) {
            s.sorted((x, y) -> y.compareTo(x)).forEach(p -> {
                try { Files.deleteIfExists(p); } catch (IOException ignored) { }
            });
        } catch (IOException ignored) { }
    }
}
