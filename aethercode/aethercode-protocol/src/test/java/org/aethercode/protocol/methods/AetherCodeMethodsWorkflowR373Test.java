package org.aethercode.protocol.methods;

import org.aethercode.core.tool.Tool;
import org.aethercode.core.workflow.WorkflowPaths;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * R373 — the {@code listWorkflows} response must let a client START
 * what it was shown, and must not turn "unreadable files" into "no
 * workflows".
 *
 * <p>Two contracts pinned here:
 *
 * <ol>
 *   <li><b>Addressability.</b> {@code runWorkflow} /
 *       {@code getWorkflow} resolve a FILE by stem
 *       ({@link WorkflowPaths#workflowFile}), but {@code listWorkflows}
 *       used to publish only the {@code name:} the YAML declares.
 *       When those differ, the client could not start the workflow it
 *       was just shown. R373 publishes {@code id} — the stem — and
 *       this test asserts the published id actually RESOLVES to the
 *       file it was derived from.</li>
 *   <li><b>Honest emptiness.</b> {@code skipped} names the files the
 *       daemon could not read. Without it, "there are no workflows
 *       here" and "every workflow here is unreadable" are the same
 *       answer, and a picker rendering an empty list would be
 *       asserting something it does not know.</li>
 * </ol>
 *
 * <p>The daemon resolves the workflow directory from
 * {@code System.getProperty("user.dir")}, so the test points that
 * property at a temp tree and restores it afterwards.
 */
class AetherCodeMethodsWorkflowR373Test {

    @TempDir
    Path tmp;

    private String originalUserDir;
    private AetherCodeMethods methods;

    @BeforeEach
    void wire() {
        originalUserDir = System.getProperty("user.dir");
        System.setProperty("user.dir", tmp.toAbsolutePath().toString());
        org.aethercode.sdk.AetherCodeEngine engine =
                new org.aethercode.sdk.AetherCodeEngine.Builder()
                        .cwd(tmp.toAbsolutePath())
                        .agentsDir(tmp.toAbsolutePath())
                        .tools(List.<Tool>of())
                        .build();
        methods = new AetherCodeMethods(engine, n -> { });
    }

    @AfterEach
    void restore() {
        if (originalUserDir != null) {
            System.setProperty("user.dir", originalUserDir);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> listWorkflows() {
        Object r = methods.listWorkflows(null);
        assertTrue(r instanceof Map, "listWorkflows must answer with an object");
        return (Map<String, Object>) r;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> workflows(Map<String, Object> envelope) {
        Object ws = envelope.get("workflows");
        assertTrue(ws instanceof List, "the envelope must carry a `workflows` list");
        return (List<Map<String, Object>>) ws;
    }

    @Test
    void publishesTheFileStemAsTheAddressableId() throws Exception {
        // The decisive case: the file's stem and the YAML's declared
        // name are DIFFERENT strings. Only the stem resolves.
        Path wrap = tmp.resolve(".aethercode").resolve("workflows");
        Files.createDirectories(wrap);
        Files.writeString(wrap.resolve("deploy.yaml"),
                "name: ship-it\ndescription: ship it\nsteps:\n  - id: x\n    type: shell\n");

        Map<String, Object> env = listWorkflows();
        List<Map<String, Object>> ws = workflows(env);
        assertEquals(1, ws.size(), "expected one workflow, got " + ws.size());

        Map<String, Object> w = ws.get(0);
        assertEquals("ship-it", w.get("name"),
                "the declared name is still published — the desktop renders it");
        assertEquals("deploy.yaml", w.get("id"),
                "the id is the FILE NAME, which is what runWorkflow resolves");

        // The id must actually resolve, or the picker hands the user a
        // dead button.
        assertTrue(Files.isRegularFile(WorkflowPaths.workflowFile(tmp, "deploy.yaml")),
                "the published id must resolve to a real workflow file");
    }

    @Test
    void everyPublishedIdResolvesToItsOwnFile() throws Exception {
        // A property over the whole list, not one hand-picked row: a
        // single-row assertion passes even if the id is published for
        // the first workflow and omitted for the rest.
        //
        // The `.yml` file is in this list on purpose. Before R373 the
        // resolver only ever produced `.yaml`, so `beta.yml` was
        // LISTED but could not be started, written to, or deleted.
        // A hand-picked `.yaml` row would never have caught it.
        Path wrap = tmp.resolve(".aethercode").resolve("workflows");
        Files.createDirectories(wrap);
        Files.writeString(wrap.resolve("alpha.yaml"), "name: Alpha\n");
        Files.writeString(wrap.resolve("beta.yml"), "name: Beta\n");
        Files.writeString(wrap.resolve("gamma.yaml"), "name: Gamma\n");

        Map<String, Object> env = listWorkflows();
        List<Map<String, Object>> ws = workflows(env);
        assertEquals(3, ws.size(), "expected three workflows, got " + ws.size());
        for (Map<String, Object> w : ws) {
            Object id = w.get("id");
            assertNotNull(id, "every workflow must publish an id");
            assertTrue(Files.isRegularFile(WorkflowPaths.workflowFile(tmp, String.valueOf(id))),
                    "published id must resolve: " + id);
        }
    }

    @Test
    void countMatchesTheNumberOfListedWorkflows() throws Exception {
        Path wrap = tmp.resolve(".aethercode").resolve("workflows");
        Files.createDirectories(wrap);
        Files.writeString(wrap.resolve("one.yaml"), "name: one\n");
        Files.writeString(wrap.resolve("two.yaml"), "name: two\n");

        Map<String, Object> env = listWorkflows();
        assertEquals(2, env.get("count"),
                "count must match the list length, not a separate count");
        assertEquals(2, workflows(env).size());
    }

    @Test
    void publishesTheDirectoryItScanned() throws Exception {
        // The empty state needs to say WHERE to put a workflow, and
        // that path is a fact only the daemon knows.
        Map<String, Object> env = listWorkflows();
        assertEquals(WorkflowPaths.workflowDir(tmp).toString(), env.get("dir"));
    }

    @Test
    void anEmptyDirectoryIsEmptyAndSaysSo() {
        Map<String, Object> env = listWorkflows();
        assertTrue(workflows(env).isEmpty(), "no workflows in an empty tree");
        assertEquals(0, env.get("count"));
        assertNotNull(env.get("skipped"),
                "the envelope always carries `skipped` — an absent field and an "
                        + "empty list are different things to a client");
        assertTrue(((List<?>) env.get("skipped")).isEmpty(),
                "no files, so nothing was skipped");
    }

    @Test
    void skippedIsPublishedAsAList() throws Exception {
        // The field's TYPE is part of the contract: a client that
        // expects a list and gets `null` has to special-case the
        // happy path, and the happy path is the one nobody tests.
        Path wrap = tmp.resolve(".aethercode").resolve("workflows");
        Files.createDirectories(wrap);
        Files.writeString(wrap.resolve("one.yaml"), "name: one\n");

        Map<String, Object> env = listWorkflows();
        assertTrue(env.get("skipped") instanceof List,
                "`skipped` must be a list, got " + env.get("skipped"));
    }

    @Test
    void getWorkflowPublishesTheSameId() throws Exception {
        // `getWorkflow` resolves a file, so it can publish the same
        // addressable id. Two methods answering with two different
        // spellings of the same thing is how a client ends up
        // addressing the wrong one.
        Path wrap = tmp.resolve(".aethercode").resolve("workflows");
        Files.createDirectories(wrap);
        Files.writeString(wrap.resolve("deploy.yaml"), "name: ship-it\n");

        @SuppressWarnings("unchecked")
        Map<String, Object> r = (Map<String, Object>) methods.getWorkflow(
                Map.of("name", "deploy.yaml"));
        assertEquals(Boolean.TRUE, r.get("ok"));
        assertEquals("deploy.yaml", r.get("id"),
                "getWorkflow must publish the same id listWorkflows publishes");
    }
}
