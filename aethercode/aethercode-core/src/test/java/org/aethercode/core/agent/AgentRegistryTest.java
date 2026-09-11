package org.aethercode.core.agent;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class AgentRegistryTest {

    @Test
    void loadsAgentFromMavisLayout(@TempDir Path tmp) throws IOException {
        Path agents = tmp.resolve("agents");
        Path a = agents.resolve("aethercode-pm");
        Files.createDirectories(a);
        Files.writeString(a.resolve("agent.md"), """
                ---
                name: aethercode-pm
                description: PM for AetherCode
                ---
                # PM body
                """);
        AgentRegistry reg = new AgentRegistry(agents, Duration.ofMinutes(1));
        List<AgentRegistry.AgentMeta> all = reg.list();
        assertEquals(1, all.size());
        assertEquals("aethercode-pm", all.get(0).name());
        Optional<String> body = reg.getBody("aethercode-pm");
        assertTrue(body.isPresent());
        assertTrue(body.get().contains("# PM body"));
    }

    @Test
    void modelFieldRoundTrips(@TempDir Path tmp) throws IOException {
        // agents with a `model:` frontmatter
        // field surface it in AgentMeta.model() so
        // the workflow executor can pick a per-agent
        // ChatClient. The test pins the round-trip
        // (write agent.md → reload → read meta).
        Path agents = tmp.resolve("agents");
        Path a = agents.resolve("glm-coder");
        Files.createDirectories(a);
        Files.writeString(a.resolve("agent.md"), """
                ---
                name: glm-coder
                description: GLM-flash powered coder
                model: glm/glm-4-flash
                ---
                # body
                """);
        AgentRegistry reg = new AgentRegistry(agents, Duration.ofMinutes(1));
        var meta = reg.getMeta("glm-coder");
        assertTrue(meta.isPresent());
        assertEquals("glm/glm-4-flash", meta.get().model());
        // Also verify list() surfaces the model.
        assertEquals("glm/glm-4-flash",
                reg.list().get(0).model());
    }

    @Test
    void modelFieldDefaultsToEmptyStringWhenAbsent(@TempDir Path tmp) throws IOException {
        // legacy agents (no model: frontmatter)
        // get an empty model string, NOT null. The
        // workflow executor treats empty as "use the
        // engine's default model" (the legacy prior round
        // behaviour). null would NPE the executor's
        // modelOverride path.
        Path agents = tmp.resolve("agents");
        Path a = agents.resolve("legacy-agent");
        Files.createDirectories(a);
        Files.writeString(a.resolve("agent.md"), """
                ---
                name: legacy-agent
                description: no model
                ---
                body
                """);
        AgentRegistry reg = new AgentRegistry(agents, Duration.ofMinutes(1));
        var meta = reg.getMeta("legacy-agent");
        assertTrue(meta.isPresent());
        assertEquals("", meta.get().model());
    }

    @Test
    void getMetaReturnsEmptyForUnknownAgent(@TempDir Path tmp) {
        // getMeta is the lookup the
        // workflow executor uses to read an agent's
        // model. Unknown agent → empty (the executor
        // treats it as "no model binding, use
        // engine default").
        AgentRegistry reg = new AgentRegistry(tmp.resolve("agents"), Duration.ofMinutes(1));
        assertTrue(reg.getMeta("ghost").isEmpty());
    }

    @Test
    void renderSystemPromptBlock(@TempDir Path tmp) throws IOException {
        Path agents = tmp.resolve("agents");
        Path a = agents.resolve("test-agent");
        Files.createDirectories(a);
        Files.writeString(a.resolve("agent.md"), """
                ---
                name: test-agent
                description: testing
                ---
                # role
                You are a tester.
                """);
        AgentRegistry reg = new AgentRegistry(agents, Duration.ofMinutes(1));
        Optional<String> block = reg.renderSystemPromptBlock("test-agent");
        assertTrue(block.isPresent());
        assertTrue(block.get().contains("<agent name=\"test-agent\">"));
        assertTrue(block.get().contains("You are a tester."));
    }

    @Test
    void unknownAgentReturnsEmpty(@TempDir Path tmp) {
        AgentRegistry reg = new AgentRegistry(tmp.resolve("agents"), Duration.ofMinutes(1));
        assertTrue(reg.list().isEmpty());
        assertTrue(reg.getBody("ghost").isEmpty());
        assertTrue(reg.renderSystemPromptBlock("ghost").isEmpty());
    }

    @Test
    void missingAgentsDirIsTolerated(@TempDir Path tmp) {
        AgentRegistry reg = new AgentRegistry(tmp.resolve("nonexistent"), Duration.ofMinutes(1));
        assertTrue(reg.list().isEmpty());
    }

    // The Settings panel's "Agents" tab uses
    // create / update / delete to manage the
    // registry from the UI. The tests pin the
    // file shape (frontmatter is rendered from
    // the named params, not from a free-form
    // body) and the atomic-write guarantee
    // (a crash mid-write can't leave a
    // broken agent.md that the registry
    // would refuse to parse).

    @Test
    void create_writesAgentMdWithFrontmatterAndBody(@TempDir Path tmp) throws IOException {
        Path agents = tmp.resolve("agents");
        Files.createDirectories(agents);
        AgentRegistry reg = new AgentRegistry(agents, Duration.ofMinutes(1));
        reg.create("test-agent", "A test agent", "Test Agent",
                "minmax/MiniMax-Text-01",
                "Body content here.");
        // The on-disk file is parseable
        // (re-load picks it up).
        assertTrue(Files.isRegularFile(agents.resolve("test-agent").resolve("agent.md")));
        AgentRegistry reloaded = new AgentRegistry(agents, Duration.ofMinutes(1));
        var all = reloaded.list();
        assertEquals(1, all.size());
        assertEquals("test-agent", all.get(0).name());
        assertEquals("A test agent", all.get(0).description());
        assertEquals("Test Agent", all.get(0).displayName());
        // the model field round-trips through
        // create() + reload().
        assertEquals("minmax/MiniMax-Text-01", all.get(0).model());
        var body = reloaded.getBody("test-agent");
        assertTrue(body.isPresent());
        assertTrue(body.get().contains("Body content here."));
    }

    @Test
    void create_isIdempotent(@TempDir Path tmp) throws IOException {
        Path agents = tmp.resolve("agents");
        Files.createDirectories(agents);
        AgentRegistry reg = new AgentRegistry(agents, Duration.ofMinutes(1));
        reg.create("agent", "first", null, null, "first body");
        reg.create("agent", "second", null, null, "second body");
        AgentRegistry reloaded = new AgentRegistry(agents, Duration.ofMinutes(1));
        var body = reloaded.getBody("agent");
        assertTrue(body.isPresent());
        // The second call wins; we don't
        // throw on duplicate (the file edit
        // path needs the same "always write"
        // semantics).
        assertTrue(body.get().contains("second body"));
    }

    @Test
    void update_throwsWhenAgentMissing(@TempDir Path tmp) throws IOException {
        Path agents = tmp.resolve("agents");
        Files.createDirectories(agents);
        AgentRegistry reg = new AgentRegistry(agents, Duration.ofMinutes(1));
        // update is explicit about "must
        // exist"; create is the first-time
        // path. Throwing on missing is the
        // user's safety net (an accidental
        // rename to a typo'd name doesn't
        // silently create a new agent).
        assertThrows(IllegalArgumentException.class, () ->
                reg.update("nope", "x", null, null, "body"));
    }

    @Test
    void delete_removesDirectoryAndEntry(@TempDir Path tmp) throws IOException {
        Path agents = tmp.resolve("agents");
        Files.createDirectories(agents);
        AgentRegistry reg = new AgentRegistry(agents, Duration.ofMinutes(1));
        reg.create("to-delete", "x", null, null, "body");
        assertTrue(Files.isDirectory(agents.resolve("to-delete")));
        reg.delete("to-delete");
        assertFalse(Files.exists(agents.resolve("to-delete")));
        assertTrue(reg.list().isEmpty());
    }

    @Test
    void delete_throwsWhenAgentMissing(@TempDir Path tmp) throws IOException {
        Path agents = tmp.resolve("agents");
        Files.createDirectories(agents);
        AgentRegistry reg = new AgentRegistry(agents, Duration.ofMinutes(1));
        assertThrows(IllegalArgumentException.class, () -> reg.delete("nope"));
    }

    @Test
    void validateName_rejectsPathTraversal(@TempDir Path tmp) {
        AgentRegistry reg = new AgentRegistry(tmp, Duration.ofMinutes(1));
        assertThrows(IllegalArgumentException.class, () -> reg.validateName("../foo"));
        assertThrows(IllegalArgumentException.class, () -> reg.validateName("a/b"));
        assertThrows(IllegalArgumentException.class, () -> reg.validateName(".hidden"));
        assertThrows(IllegalArgumentException.class, () -> reg.validateName(""));
        // Long names are also rejected.
        String tooLong = "a".repeat(65);
        assertThrows(IllegalArgumentException.class, () -> reg.validateName(tooLong));
    }

    @Test
    void writeAgentMd_preservesAtomicityOnExistingFile(@TempDir Path tmp) throws IOException {
        Path agents = tmp.resolve("agents");
        Files.createDirectories(agents);
        AgentRegistry reg = new AgentRegistry(agents, Duration.ofMinutes(1));
        // First write: simple body.
        reg.create("atom", "first", null, null, "v1");
        // The atomic write means a .tmp
        // file is briefly created and
        // renamed; we should never see
        // a .tmp file lingering after
        // the operation.
        assertFalse(Files.exists(agents.resolve("atom").resolve("agent.md.tmp")));
        // Second write: overwrite.
        reg.update("atom", "second", null, null, "v2");
        var body = reg.getBody("atom");
        assertTrue(body.isPresent());
        assertTrue(body.get().contains("v2"));
        assertFalse(Files.exists(agents.resolve("atom").resolve("agent.md.tmp")));
    }
}
