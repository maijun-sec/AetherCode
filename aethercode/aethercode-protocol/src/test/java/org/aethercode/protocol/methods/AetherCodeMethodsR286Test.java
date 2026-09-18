package org.aethercode.protocol.methods;

import org.aethercode.core.agent.AgentRegistry;
import org.aethercode.sdk.AetherCodeEngine;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * R286: per-agent variant field surfaces in the
 * desktop-facing RPCs.
 *
 * <p>listAgents / getAgentBody / createAgent /
 * updateAgent all carry the new {@code variant}
 * field. Tests write an agent.md with a
 * {@code variant: high} line, then assert:
 * <ol>
 *   <li>{@code listAgents()} returns a row whose
 *       {@code variant} field equals "high";</li>
 *   <li>{@code getAgentBody(name)} returns the
 *       same field in the response;</li>
 *   <li>createAgent / updateAgent round-trip
 *       a variant value through the on-disk
 *       frontmatter.</li>
 * </ol>
 */
class AetherCodeMethodsR286Test {

    private static Path writeAgent(Path agentsDir, String name, String body) throws IOException {
        Path dir = agentsDir.resolve(name);
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("agent.md"), body);
        return dir;
    }

    @Test
    void listAgentsSurfacesVariantField(@TempDir Path tmp) throws Exception {
        Path agents = tmp.resolve("agents");
        writeAgent(agents, "reviewer", """
                ---
                name: reviewer
                description: code reviewer
                model: glm/glm-4-flash
                variant: high
                ---
                # body
                """);
        writeAgent(agents, "no-variant", """
                ---
                name: no-variant
                description: pre-R286 agent
                model: glm/glm-4-flash
                ---
                # body
                """);

        AetherCodeEngine engine = AetherCodeEngine.builder()
                .cwd(tmp)
                .agentsDir(agents)
                .build();
        AetherCodeMethods methods = new AetherCodeMethods(engine, (n) -> {});

        Object res = methods.listAgents(null);
        assertThat(res).isInstanceOf(Map.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> map = (Map<String, Object>) res;
        assertThat(map.get("ok")).isEqualTo(true);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>) map.get("agents");
        assertThat(rows).hasSize(2);

        var reviewer = rows.stream()
                .filter(r -> "reviewer".equals(r.get("name")))
                .findFirst().orElseThrow();
        var noVariant = rows.stream()
                .filter(r -> "no-variant".equals(r.get("name")))
                .findFirst().orElseThrow();

        assertThat(reviewer.get("variant")).isEqualTo("high");
        // Legacy agent (no variant: line) must
        // surface an empty string so the
        // renderer's prefilled form has a
        // valid signal.
        assertThat(noVariant.get("variant")).isEqualTo("");
    }

    @Test
    void getAgentBodySurfacesVariantField(@TempDir Path tmp) throws Exception {
        Path agents = tmp.resolve("agents");
        writeAgent(agents, "xhigh-coder", """
                ---
                name: xhigh-coder
                description: xhigh quality coder
                model: glm/glm-4-flash
                variant: xhigh
                ---
                # body
                """);

        AetherCodeEngine engine = AetherCodeEngine.builder()
                .cwd(tmp)
                .agentsDir(agents)
                .build();
        AetherCodeMethods methods = new AetherCodeMethods(engine, (n) -> {});

        Object res = methods.getAgentBody(Map.of("name", "xhigh-coder"));
        assertThat(res).isInstanceOf(Map.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> map = (Map<String, Object>) res;
        assertThat(map.get("ok")).isEqualTo(true);
        assertThat(map.get("variant")).isEqualTo("xhigh");
        // The other frontmatter fields
        // stay intact — the variant
        // addition is additive.
        assertThat(map.get("model")).isEqualTo("glm/glm-4-flash");
        assertThat(map.get("description")).isEqualTo("xhigh quality coder");
    }

    @Test
    void createAgentWritesVariantToFrontmatter(@TempDir Path tmp) throws Exception {
        Path agents = tmp.resolve("agents");
        Files.createDirectories(agents);
        AetherCodeEngine engine = AetherCodeEngine.builder()
                .cwd(tmp)
                .agentsDir(agents)
                .build();
        AetherCodeMethods methods = new AetherCodeMethods(engine, (n) -> {});

        Map<String, Object> createOpts = new HashMap<>();
        createOpts.put("name", "new-coder");
        createOpts.put("description", "fresh agent");
        createOpts.put("model", "glm/glm-4-flash");
        createOpts.put("variant", "low");
        createOpts.put("body", "# body");

        Object res = methods.createAgent(createOpts);
        assertThat(res).isInstanceOf(Map.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> map = (Map<String, Object>) res;
        assertThat(map.get("ok")).isEqualTo(true);

        // The on-disk frontmatter must
        // contain the variant: line.
        String written = Files.readString(agents.resolve("new-coder").resolve("agent.md"));
        assertThat(written).contains("variant: low");

        // The list view picks it up too.
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>)
                ((Map<String, Object>) methods.listAgents(null)).get("agents");
        var row = rows.stream()
                .filter(r -> "new-coder".equals(r.get("name")))
                .findFirst().orElseThrow();
        assertThat(row.get("variant")).isEqualTo("low");
    }

    @Test
    void updateAgentChangesVariantField(@TempDir Path tmp) throws Exception {
        Path agents = tmp.resolve("agents");
        writeAgent(agents, "switchable", """
                ---
                name: switchable
                description: starts at medium
                model: glm/glm-4-flash
                variant: medium
                ---
                # body
                """);
        AetherCodeEngine engine = AetherCodeEngine.builder()
                .cwd(tmp)
                .agentsDir(agents)
                .build();
        AetherCodeMethods methods = new AetherCodeMethods(engine, (n) -> {});

        // The user picks a new quality
        // preset from the editor's
        // dropdown. The daemon writes
        // through and the reload picks
        // it up.
        Map<String, Object> updateOpts = new HashMap<>();
        updateOpts.put("name", "switchable");
        updateOpts.put("description", "starts at medium");
        updateOpts.put("model", "glm/glm-4-flash");
        updateOpts.put("variant", "xhigh");
        updateOpts.put("body", "# body");
        methods.updateAgent(updateOpts);

        String written = Files.readString(agents.resolve("switchable").resolve("agent.md"));
        assertThat(written).contains("variant: xhigh");

        // listAgents echoes the new value.
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>)
                ((Map<String, Object>) methods.listAgents(null)).get("agents");
        var row = rows.stream()
                .filter(r -> "switchable".equals(r.get("name")))
                .findFirst().orElseThrow();
        assertThat(row.get("variant")).isEqualTo("xhigh");
    }

    @Test
    void blankVariantOmittedFromFrontmatter(@TempDir Path tmp) throws Exception {
        // The "(inherit)" dropdown option
        // sends variant="" (or null). The
        // daemon must NOT write a `variant:`
        // line so the legacy "missing field
        // = use default" path keeps working.
        Path agents = tmp.resolve("agents");
        Files.createDirectories(agents);
        AetherCodeEngine engine = AetherCodeEngine.builder()
                .cwd(tmp)
                .agentsDir(agents)
                .build();
        AetherCodeMethods methods = new AetherCodeMethods(engine, (n) -> {});

        Map<String, Object> opts = new HashMap<>();
        opts.put("name", "inherit-coder");
        opts.put("description", "inherits from env");
        opts.put("model", "glm/glm-4-flash");
        opts.put("variant", "");
        opts.put("body", "# body");
        methods.createAgent(opts);

        String written = Files.readString(agents.resolve("inherit-coder").resolve("agent.md"));
        assertThat(written).doesNotContain("variant:");

        // listAgents echoes empty string
        // (the renderer's "(inherit)"
        // dropdown option is the matching
        // UI signal).
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>)
                ((Map<String, Object>) methods.listAgents(null)).get("agents");
        var row = rows.stream()
                .filter(r -> "inherit-coder".equals(r.get("name")))
                .findFirst().orElseThrow();
        assertThat(row.get("variant")).isEqualTo("");
    }
}