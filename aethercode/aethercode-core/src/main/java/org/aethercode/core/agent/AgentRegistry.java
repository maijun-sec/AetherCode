package org.aethercode.core.agent;

import org.aethercode.core.skill.Frontmatter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/**
 * read-only registry of Mavis agents under
 * {@code ~/.minimax/agents/<name>/agent.md}.
 *
 * <p>AetherCode does <b>not</b> own agent definitions — Mavis does.
 * AetherCode reads them at startup, caches the metadata + body in
 * memory, and surfaces them via the {@code listAgents} RPC. The
 * workflow executor's {@code kind: agent} step looks up the
 * named agent here and uses the body as the child session's
 * system-prompt context.
 *
 * <p>The shape mirrors {@link org.aethercode.core.skill.SkillRegistry}
 * (file-system scan + atomic snapshot + background re-scan) so
 * the daemon's behaviour is predictable across both registries.
 */
public final class AgentRegistry {

    private static final Logger LOG = LoggerFactory.getLogger(AgentRegistry.class);

    /** One parsed {@code agent.md}. prior round added the {@code
     *  model} field — the workflow executor's
     *  {@code kind: agent} step reads it to build a
     *  per-agent {@link org.aethercode.core.llm.ChatClient}
     *  (e.g. {@code glm/glm-4-flash}) for the child
     *  session. The shape is {@code "provider/model"};
     *  an empty string means "use the engine's default
     *  model" (the legacy behaviour). */
    public record AgentMeta(
            String name,
            String description,
            String displayName,
            String model,
            Path path,
            long lastModifiedMs
    ) {
        public String displayLabel() {
            return (displayName == null || displayName.isEmpty()) ? name : displayName;
        }
    }

    private final Path agentsDir;
    private final long reloadIntervalMs;
    private final AtomicLong lastReloadAt = new AtomicLong(0);
    private volatile Map<String, Entry> byName = Map.of();
    private volatile long currentGeneration = 0;

    public AgentRegistry(Path agentsDir, Duration reloadInterval) {
        this.agentsDir = agentsDir;
        this.reloadIntervalMs = reloadInterval == null ? 10_000L : reloadInterval.toMillis();
        try { reload(); } catch (Exception e) { LOG.warn("initial agent load failed: {}", e.getMessage()); }
    }

    public List<AgentMeta> list() {
        ensureFresh();
        return byName.values().stream()
                .map(e -> e.meta)
                .sorted(Comparator.comparing(AgentMeta::name, String.CASE_INSENSITIVE_ORDER))
                .collect(Collectors.toList());
    }

    public Optional<String> getBody(String name) {
        ensureFresh();
        Entry e = byName.get(name);
        return Optional.ofNullable(e).map(en -> en.body);
    }

    /** full metadata for one agent. Used by
     *  the workflow executor's {@code kind: agent}
     *  step to read the agent's {@code model:}
     *  frontmatter field without re-parsing the
     *  file. Returns {@link Optional#empty()} when
     *  the agent is not registered. */
    public Optional<AgentMeta> getMeta(String name) {
        ensureFresh();
        Entry e = byName.get(name);
        return Optional.ofNullable(e).map(en -> en.meta);
    }

    /** Render the agent's body as a system-prompt preamble. The
     *  shape is {@code <agent name="<name>">\n<body>\n</agent>},
     *  matching the skill registry's convention so the LLM
     *  parses both consistently. Returns empty when the agent is
     *  not found, so the caller can decide whether to fail the
     *  workflow step or run with a generic prompt. */
    public Optional<String> renderSystemPromptBlock(String name) {
        Optional<String> body = getBody(name);
        if (body.isEmpty() || body.get().isBlank()) return Optional.empty();
        StringBuilder sb = new StringBuilder();
        sb.append("\n\n<agent name=\"").append(escape(name)).append("\">\n");
        sb.append(body.get().strip());
        sb.append("\n</agent>\n");
        return Optional.of(sb.toString());
    }

    public synchronized int reload() {
        Map<String, Entry> next = new LinkedHashMap<>();
        if (agentsDir != null && Files.isDirectory(agentsDir)) {
            try (var stream = Files.list(agentsDir)) {
                stream.filter(Files::isDirectory).forEach(d -> {
                    Path md = d.resolve("agent.md");
                    if (!Files.isRegularFile(md)) return;
                    try {
                        String content = Files.readString(md);
                        Frontmatter.Parsed p = Frontmatter.parse(content);
                        Map<String, Object> f = p.fields();
                        String name = Frontmatter.string(f, "name");
                        if (name == null || name.isBlank()) {
                            name = d.getFileName().toString();
                        }
                        String desc = Frontmatter.string(f, "description");
                        String dn = Frontmatter.string(f, "displayName");
                        // read the per-agent model
                        // binding. The value is the raw
                        // frontmatter scalar (e.g.
                        // "glm/glm-4-flash"); resolution to a
                        // ChatClient happens at workflow
                        // execution time when the executor
                        // has access to the ProviderRegistry.
                        String m = Frontmatter.string(f, "model");
                        long lm = Files.getLastModifiedTime(md).toMillis();
                        AgentMeta meta = new AgentMeta(name,
                                desc == null ? "" : desc,
                                dn == null ? "" : dn,
                                m == null ? "" : m,
                                md, lm);
                        next.put(name, new Entry(meta, p.body()));
                    } catch (IOException e) {
                        LOG.warn("skipping agent {}: {}", md, e.getMessage());
                    }
                });
            } catch (IOException e) {
                LOG.warn("agent registry scan failed for {}: {}", agentsDir, e.getMessage());
            }
        }
        this.byName = Map.copyOf(next);
        this.lastReloadAt.set(System.currentTimeMillis());
        this.currentGeneration++;
        LOG.info("agent registry reloaded: {} agent(s) from {}",
                next.size(), agentsDir);
        return next.size();
    }

    public long lastReloadMs() { return lastReloadAt.get(); }
    public long generation() { return currentGeneration; }

    // AetherCode owns the on-disk representation
    // (Mavis registers the agents as files at
    // <agentsDir>/<name>/agent.md; AetherCode can
    // create / update / delete them on the
    // user's behalf via the Settings panel).
    // The write path is "all or nothing": we
    // build the full agent.md content (frontmatter
    // + body) and write it atomically (write
    // to a tmp file, then rename), so a partial
    // write can never leave a broken agent.md
    // that the registry would refuse to parse.
    // The reload() at the end of each method
    // picks up the change immediately; the
    // background re-scan is also still active
    // so a write from another process is also
    // picked up within `reloadIntervalMs`.

    /** validate an agent name. The same
     *  rules the file-system layer needs:
     *  non-empty, no path separators, no `..`,
     *  no leading dot. The registry stores
     *  agents under <agentsDir>/<name>/agent.md;
     *  a malicious name like {@code ../../foo}
     *  could escape the agents directory. */
    public static void validateName(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("agent name is required");
        }
        if (name.contains("/") || name.contains("\\") || name.contains("..")) {
            throw new IllegalArgumentException(
                    "agent name must not contain path separators or '..': " + name);
        }
        if (name.startsWith(".")) {
            throw new IllegalArgumentException(
                    "agent name must not start with '.': " + name);
        }
        if (name.length() > 64) {
            throw new IllegalArgumentException(
                    "agent name too long (max 64 chars): " + name);
        }
    }

    /** create a new agent. The
     *  {@code frontmatter} block is built from
     *  the optional {@code description},
     *  {@code displayName}, and
     *  {@code model} fields; the {@code body}
     *  is the markdown content the user
     *  wrote in the editor. Idempotent: if
     *  the agent already exists, this
     *  overwrites (same shape as the legacy
     *  file edit). Forces a reload so the
     *  new entry is queryable immediately. */
    public synchronized void create(String name, String description,
                                    String displayName, String model,
                                    String body) throws IOException {
        validateName(name);
        if (agentsDir == null) {
            throw new IllegalStateException("agentsDir is not wired");
        }
        if (byName.containsKey(name)) {
            // Idempotent: caller can call create
            // twice and the second call wins.
            // (updateAgent is a thin alias if
            // you want to be explicit.)
            LOG.info("agent {} already exists, overwriting", name);
        }
        writeAgentMd(name, description, displayName, model, body);
        reload();
    }

    /** update an existing agent.
     *  Throws if the agent doesn't exist (use
     *  {@link #create} for first-time writes).
     *  Same shape as create; the explicit
     *  "must exist" check is the only
     *  difference. */
    public synchronized void update(String name, String description,
                                    String displayName, String model,
                                    String body) throws IOException {
        validateName(name);
        if (!byName.containsKey(name)) {
            throw new IllegalArgumentException("agent not found: " + name);
        }
        writeAgentMd(name, description, displayName, model, body);
        reload();
    }

    /** delete an agent. Removes the
     *  entire {@code <name>/} directory. The
     *  reload() at the end picks up the
     *  removal. Throws if the agent doesn't
     *  exist. */
    public synchronized void delete(String name) throws IOException {
        validateName(name);
        if (agentsDir == null) {
            throw new IllegalStateException("agentsDir is not wired");
        }
        if (!byName.containsKey(name)) {
            throw new IllegalArgumentException("agent not found: " + name);
        }
        java.nio.file.Path dir = agentsDir.resolve(name);
        // Defensive: don't try to delete
        // something outside agentsDir (the
        // validateName check catches the
        // obvious cases, but a symlink could
        // still escape; resolve + check
        // ancestry).
        java.nio.file.Path real = dir.toRealPath();
        if (!real.startsWith(agentsDir.toRealPath())) {
            throw new IOException("refusing to delete outside agentsDir: " + dir);
        }
        try (var stream = Files.walk(dir)) {
            stream.sorted(Comparator.reverseOrder())
                    .forEach(p -> { try { Files.delete(p); } catch (IOException e) { /* walk continues */ } });
        }
        reload();
    }

    private void writeAgentMd(String name, String description,
                              String displayName, String model,
                              String body) throws IOException {
        Files.createDirectories(agentsDir.resolve(name));
        StringBuilder fm = new StringBuilder();
        fm.append("---\n");
        fm.append("name: ").append(name).append("\n");
        if (description != null && !description.isEmpty()) {
            fm.append("description: ").append(quoteYaml(description)).append("\n");
        }
        if (displayName != null && !displayName.isEmpty()) {
            fm.append("displayName: ").append(quoteYaml(displayName)).append("\n");
        }
        if (model != null && !model.isBlank()) {
            // model binding. Storing the
            // model in the frontmatter lets the
            // workflow executor's
            // queryInChildSession pick the right
            // model for the agent.
            fm.append("model: ").append(quoteYaml(model)).append("\n");
        }
        fm.append("---\n\n");
        fm.append(body == null ? "" : body);
        if (!body.endsWith("\n")) fm.append("\n");
        java.nio.file.Path target = agentsDir.resolve(name).resolve("agent.md");
        // Atomic write: write to a tmp file
        // then rename. A crash mid-write
        // leaves the original agent.md
        // intact.
        java.nio.file.Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
        Files.writeString(tmp, fm.toString());
        try {
            Files.move(tmp, target,
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException e) {
            // Some filesystems don't support
            // atomic move; fall back to a
            // non-atomic replace.
            Files.move(tmp, target,
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /** minimal YAML scalar quoter. We
     *  quote any value that contains a colon
     *  (which would parse as a mapping in
     *  flow context), a hash (comment marker),
     *  or starts with a special character.
     *  Plain values (the common case) are
     *  written unquoted for human readability. */
    private static String quoteYaml(String s) {
        if (s == null) return "";
        if (s.matches(".*[:#].*") || s.startsWith(" ")
                || s.startsWith("\"") || s.startsWith("'")) {
            return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
        }
        return s;
    }

    private void ensureFresh() {
        long now = System.currentTimeMillis();
        if (now - lastReloadAt.get() > reloadIntervalMs) {
            try { reload(); }
            catch (Exception e) { LOG.debug("background agent reload failed: {}", e.getMessage()); }
        }
    }

    private static String escape(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;");
    }

    private record Entry(AgentMeta meta, String body) {}
}
