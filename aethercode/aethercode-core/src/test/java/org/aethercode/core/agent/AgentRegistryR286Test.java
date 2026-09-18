package org.aethercode.core.agent;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

/**
 * R286: per-agent quality preset (variant:) in agent.md.
 *
 * <p>The {@code variant:} frontmatter field is the
 * R286 sibling of the {@code model:} field — it
 * tells the workflow executor which R285
 * quality preset (low / medium / high / xhigh)
 * to apply to a child session spawned via
 * {@code kind: agent}. The registry now stores
 * it on {@link AgentRegistry.AgentMeta#variant()}
 * and round-trips through the
 * {@code create}/{@code update} path.
 *
 * <p>The tests pin three guarantees:
 * <ol>
 *   <li>an agent with {@code variant: high} exposes
 *       {@code meta.variant() == "high"} on read;</li>
 *   <li>create + update write the field through to
 *       disk so a subsequent {@code reload()} picks
 *       it up;</li>
 *   <li>an empty / missing variant field stays
 *       empty (so the executor falls back to the
 *       engine's AETHERCODE_SUBAGENT_VARIANT env
 *       override, then the bundled default).</li>
 * </ol>
 */
class AgentRegistryR286Test {

    @Test
    void variantFieldParsedFromFrontmatter(@TempDir Path tmp) throws IOException {
        Path agents = tmp.resolve("agents");
        Path a = agents.resolve("code-reviewer");
        Files.createDirectories(a);
        Files.writeString(a.resolve("agent.md"), """
                ---
                name: code-reviewer
                description: Reviews code for security issues
                model: glm/glm-4-flash
                variant: high
                ---
                # body
                """);
        AgentRegistry reg = new AgentRegistry(agents, Duration.ofMinutes(1));
        var meta = reg.getMeta("code-reviewer");
        assertTrue(meta.isPresent());
        assertEquals("high", meta.get().variant());
        // The model field must still be there —
        // the variant addition is additive, not
        // a replacement for the per-agent model
        // binding.
        assertEquals("glm/glm-4-flash", meta.get().model());
    }

    @Test
    void missingVariantFieldStaysEmpty(@TempDir Path tmp) throws IOException {
        // Legacy agents written before R286
        // don't have a variant: line. The
        // registry must NOT default to
        // "medium" or any other preset — the
        // empty string is the "inherit from
        // env / bundled default" signal.
        Path agents = tmp.resolve("agents");
        Path a = agents.resolve("legacy-agent");
        Files.createDirectories(a);
        Files.writeString(a.resolve("agent.md"), """
                ---
                name: legacy-agent
                description: written before R286
                model: minimax/MiniMax-M3
                ---
                # body
                """);
        AgentRegistry reg = new AgentRegistry(agents, Duration.ofMinutes(1));
        var meta = reg.getMeta("legacy-agent");
        assertTrue(meta.isPresent());
        assertEquals("", meta.get().variant());
    }

    @Test
    void variantFieldRoundTripsThroughCreateAndUpdate(@TempDir Path tmp) throws IOException {
        // create() → reload() → meta() must
        // surface the variant. update() →
        // reload() must reflect the new
        // value. This is the "settings panel
        // saves a new quality preset" path.
        Path agents = tmp.resolve("agents");
        Files.createDirectories(agents);
        AgentRegistry reg = new AgentRegistry(agents, Duration.ofMinutes(1));

        reg.create("a", "test agent", "Test Agent", "glm/glm-4-flash", "xhigh", "body");
        var meta = reg.getMeta("a");
        assertTrue(meta.isPresent());
        assertEquals("xhigh", meta.get().variant());

        reg.update("a", "test agent", "Test Agent", "glm/glm-4-flash", "low", "body");
        meta = reg.getMeta("a");
        assertTrue(meta.isPresent());
        assertEquals("low", meta.get().variant());
    }

    @Test
    void blankVariantStringOmitsTheFrontmatterLine(@TempDir Path tmp) throws IOException {
        // Empty / null variant strings must NOT
        // write a `variant:` line to the
        // on-disk frontmatter — the legacy
        // "missing field = use default" path
        // only works when the line is absent.
        // A `variant: ` (empty value) would
        // parse back as "" and trigger the
        // same path, but the cleaner
        // representation is to omit the line
        // entirely.
        Path agents = tmp.resolve("agents");
        Files.createDirectories(agents);
        AgentRegistry reg = new AgentRegistry(agents, Duration.ofMinutes(1));

        reg.create("b", "test", null, "glm/glm-4-flash", null, "body");
        String written = Files.readString(agents.resolve("b").resolve("agent.md"));
        assertFalse(written.contains("variant:"),
                "blank variant must not emit a frontmatter line, got:\n" + written);

        reg.create("c", "test", null, "glm/glm-4-flash", "  ", "body");
        written = Files.readString(agents.resolve("c").resolve("agent.md"));
        assertFalse(written.contains("variant:"),
                "whitespace-only variant must not emit a frontmatter line, got:\n" + written);

        reg.create("d", "test", null, "glm/glm-4-flash", "medium", "body");
        written = Files.readString(agents.resolve("d").resolve("agent.md"));
        assertTrue(written.contains("variant: medium"),
                "non-blank variant must emit a frontmatter line, got:\n" + written);
    }

    @Test
    void listSurfacesVariantField(@TempDir Path tmp) throws IOException {
        // The list() path is what listAgents
        // RPCs use; it must surface the
        // variant field too so the desktop
        // can render a "high / think" badge
        // next to each row without a
        // per-row getMeta round-trip.
        Path agents = tmp.resolve("agents");
        Files.createDirectories(agents);
        Path a = agents.resolve("alpha");
        Files.createDirectories(a);
        Files.writeString(a.resolve("agent.md"), """
                ---
                name: alpha
                description: first
                variant: xhigh
                ---
                """);
        Path b = agents.resolve("bravo");
        Files.createDirectories(b);
        Files.writeString(b.resolve("agent.md"), """
                ---
                name: bravo
                description: second
                ---
                """);
        AgentRegistry reg = new AgentRegistry(agents, Duration.ofMinutes(1));
        var all = reg.list();
        assertEquals(2, all.size());
        var alpha = all.stream().filter(m -> m.name().equals("alpha")).findFirst().orElseThrow();
        var bravo = all.stream().filter(m -> m.name().equals("bravo")).findFirst().orElseThrow();
        assertEquals("xhigh", alpha.variant());
        assertEquals("", bravo.variant());
    }
}