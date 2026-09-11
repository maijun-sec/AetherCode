package org.aethercode.sdk.agent;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * tests for {@link AgentRegistry}. The registry parses
 * simple Markdown files with YAML frontmatter and indexes them by
 * name.
 */
class AgentRegistryTest {

    @Test
    void parse_minimalFrontmatter(@TempDir Path tmp) throws IOException {
        Path md = tmp.resolve("greeter.md");
        Files.writeString(md, """
                ---
                name: greeter
                description: A friendly greeter agent
                ---
                You are a friendly agent. Say hi and ask how you can help.
                """);
        AgentRegistry r = new AgentRegistry().loadFrom(tmp);
        assertEquals(1, r.size());
        AgentDefinition d = r.get("greeter");
        assertNotNull(d);
        assertEquals("greeter", d.name());
        assertEquals("A friendly greeter agent", d.description());
        assertTrue(d.system().contains("friendly agent"));
        // No tools whitelist in frontmatter → empty list.
        assertEquals(List.of(), d.tools());
    }

    @Test
    void parse_toolsListInlineArray(@TempDir Path tmp) throws IOException {
        Path md = tmp.resolve("reviewer.md");
        Files.writeString(md, """
                ---
                name: reviewer
                description: Code reviewer
                tools: [file_read, glob, grep]
                ---
                You are a code reviewer.
                """);
        AgentRegistry r = new AgentRegistry().loadFrom(tmp);
        AgentDefinition d = r.get("reviewer");
        assertNotNull(d);
        assertEquals(List.of("file_read", "glob", "grep"), d.tools());
    }

    @Test
    void parse_missingFrontmatterIsSkipped(@TempDir Path tmp) throws IOException {
        Path md = tmp.resolve("broken.md");
        Files.writeString(md, "no frontmatter here, just prose\n");
        AgentRegistry r = new AgentRegistry().loadFrom(tmp);
        assertEquals(0, r.size());
    }

    @Test
    void loadFrom_missingDirIsNoOp(@TempDir Path tmp) {
        AgentRegistry r = new AgentRegistry();
        // No throw, just empty.
        r.loadFrom(tmp.resolve("does-not-exist"));
        assertEquals(0, r.size());
    }

    @Test
    void loadFrom_multipleAgentsAndOrdering(@TempDir Path tmp) throws IOException {
        Files.writeString(tmp.resolve("a.md"), """
                ---
                name: alpha
                ---
                alpha body
                """);
        Files.writeString(tmp.resolve("b.md"), """
                ---
                name: bravo
                ---
                bravo body
                """);
        Files.writeString(tmp.resolve("c.md"), """
                ---
                name: charlie
                ---
                charlie body
                """);
        AgentRegistry r = new AgentRegistry().loadFrom(tmp);
        assertEquals(3, r.size());
        assertTrue(r.contains("alpha"));
        assertTrue(r.contains("bravo"));
        assertTrue(r.contains("charlie"));
        // all() is sorted by name
        var names = r.all().stream().map(AgentDefinition::name).toList();
        assertEquals(List.of("alpha", "bravo", "charlie"), names);
    }

    @Test
    void loadFrom_silentlyIgnoresNonMdFiles(@TempDir Path tmp) throws IOException {
        Files.writeString(tmp.resolve("readme.txt"), "not an agent");
        Files.writeString(tmp.resolve("agent.md"), """
                ---
                name: agent
                ---
                body
                """);
        AgentRegistry r = new AgentRegistry().loadFrom(tmp);
        assertEquals(1, r.size());
    }
}
