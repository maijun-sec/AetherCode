package org.aethercode.tools.task;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.dataformat.yaml.YAMLGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

/**
 * R375.2: persistent store for per-role concurrency quota
 * overrides. Reads + writes {@code ~/.aethercode/agents.yaml}
 * so the value the user sets in the desktop dashboard
 * survives daemon restarts.
 *
 * <p>File format (YAML):
 * <pre>
 * agents:
 *   pm:
 *     quota: 3
 *   coder:
 *     quota: 2
 * </pre>
 *
 * <p>Only entries whose quota differs from
 * {@link SubagentConcurrencyLimiter#DEFAULT_QUOTA} (= 1) are
 * persisted. The default is implicit; storing "quota: 1" for
 * every role would clutter the file with no information.
 *
 * <p>Writes are atomic: the new YAML is written to a sibling
 * {@code agents.yaml.tmp} file and then renamed over the
 * target. A crash mid-write leaves the previous file intact.
 *
 * <p>Reads tolerate a missing file (returns empty map; the
 * daemon starts with all-default quotas) and a malformed
 * file (logs a warning, returns empty map; the next
 * {@link #save} call will rewrite it cleanly).
 */
public final class AgentQuotaStore {

    private static final Logger LOG = LoggerFactory.getLogger(AgentQuotaStore.class);

    /** filename under {@link org.aethercode.tasks.supervisor.SupervisorHome#dir()} */
    public static final String DEFAULT_FILE_NAME = "agents.yaml";

    /** Top-level YAML key. Future agent-level fields (e.g.
     *  model override, custom tags) can be added under the
     *  same key without breaking existing files. */
    public static final String AGENTS_KEY = "agents";

    /** Sub-key under each agent. Currently only "quota" is
     *  recognised; future fields (model override, etc.)
     *  can sit alongside without breaking this loader. */
    public static final String QUOTA_KEY = "quota";

    private final Path yamlPath;
    private final SubagentRegistry registry;
    private final ObjectMapper yaml;

    public AgentQuotaStore(Path yamlPath, SubagentRegistry registry) {
        this.yamlPath = yamlPath;
        this.registry = registry;
        // Disable quoting of YAML strings — keeps the file
        // diff-friendly (no quote noise around role names).
        this.yaml = new ObjectMapper(
                new YAMLFactory()
                        .disable(YAMLGenerator.Feature.WRITE_DOC_START_MARKER)
                        .disable(YAMLGenerator.Feature.MINIMIZE_QUOTES));
    }

    /**
     * Read every quota override from the YAML file and
     * apply it to {@link #registry}. Returns the map that
     * was applied. A missing file yields an empty map (the
     * all-defaults case).
     *
     * <p>Malformed input is non-fatal: we log a warning
     * and return an empty map so the daemon can still
     * start. The next {@link #save} call will rewrite the
     * file with the current in-memory state.
     */
    public Map<String, Integer> load() {
        if (!Files.exists(yamlPath)) {
            LOG.debug("R375.2: no agents.yaml at {} (all quotas default)",
                    yamlPath);
            return Map.of();
        }
        try {
            byte[] raw = Files.readAllBytes(yamlPath);
            Map<String, Object> root = yaml.readValue(
                    raw, new TypeReference<Map<String, Object>>() {});
            Map<String, Integer> result = new LinkedHashMap<>();
            Object agentsObj = root.get(AGENTS_KEY);
            if (!(agentsObj instanceof Map<?, ?> agentsMap)) {
                LOG.warn("R375.2: agents.yaml top-level '{}' missing or not a map — treating as empty",
                        AGENTS_KEY);
                return Map.of();
            }
            for (Map.Entry<?, ?> entry : agentsMap.entrySet()) {
                if (!(entry.getKey() instanceof String role)) continue;
                if (!(entry.getValue() instanceof Map<?, ?> fields)) continue;
                Object quotaObj = fields.get(QUOTA_KEY);
                if (!(quotaObj instanceof Number n)) continue;
                int quota = n.intValue();
                // Apply via the registry so the same
                // clamp / fallback rules apply (e.g. a
                // bogus negative gets DEFAULT_QUOTA).
                registry.setQuota(role, quota);
                result.put(role, quota);
            }
            LOG.info("R375.2: loaded {} quota override(s) from {}",
                    result.size(), yamlPath);
            return Collections.unmodifiableMap(result);
        } catch (IOException e) {
            LOG.warn("R375.2: failed to read {} ({}) — treating as empty",
                    yamlPath, e.getMessage());
            return Map.of();
        } catch (RuntimeException e) {
            // Jackson wraps type mismatches in RuntimeException;
            // the most useful user message is the cause.
            LOG.warn("R375.2: malformed agents.yaml at {} ({}) — treating as empty",
                    yamlPath, e.getMessage());
            return Map.of();
        }
    }

    /**
     * Persist the supplied quota map. The map is the full
     * set of overrides; entries equal to
     * {@link SubagentConcurrencyLimiter#DEFAULT_QUOTA}
     * are omitted from the file (the default is implicit).
     *
     * <p>The write is atomic: the new content goes to a
     * sibling {@code .tmp} file, then {@code Files.move}
     * replaces the target. If {@code ATOMIC_MOVE} is
     * unsupported on the volume (e.g. some Windows
     * configurations), the call falls back to a plain
     * REPLACE_EXISTING rename — still crash-safe because
     * we only call move after a successful write of the
     * temp file.
     *
     * <p>Pass an empty map to clear the file (used by the
     * "remove override" path: the user sets quota=0, which
     * the registry maps back to DEFAULT_QUOTA, then we
     * save the now-empty override set).
     */
    public void save(Map<String, Integer> quotas) {
        // Drop entries that match the default — they add
        // no information and clutter the file.
        Map<String, Integer> toWrite = new TreeMap<>();
        for (Map.Entry<String, Integer> e : quotas.entrySet()) {
            if (e.getValue() == null) continue;
            if (e.getValue() == SubagentConcurrencyLimiter.DEFAULT_QUOTA) continue;
            toWrite.put(e.getKey(), e.getValue());
        }
        // Build the nested structure. Jackson serialises a
        // three-level Map<String, Map<String, Map<String,Integer>>>
        // directly to the agents.<role>.quota: N layout.
        // We use inline LinkedHashMap-of-one for the
        // inner-most level instead of Map.of(QUOTA_KEY,
        // value) because the latter can't infer the value
        // type from a generic Iterator.next() (Java 11/17
        // still struggles with that path). The
        // LinkedHashMap is explicit and obvious.
        Map<String, Map<String, Map<String, Integer>>> root = new LinkedHashMap<>();
        Map<String, Map<String, Integer>> agents = new LinkedHashMap<>();
        for (Map.Entry<String, Integer> e : toWrite.entrySet()) {
            Map<String, Integer> inner = new LinkedHashMap<>();
            inner.put(QUOTA_KEY, e.getValue());
            agents.put(e.getKey(), inner);
        }
        root.put(AGENTS_KEY, agents);
        Path tmp = yamlPath.resolveSibling(yamlPath.getFileName().toString() + ".tmp");
        try {
            // Ensure parent dir exists. The daemon's first
            // run on a fresh machine would otherwise fail.
            Path parent = yamlPath.getParent();
            if (parent != null) Files.createDirectories(parent);
            byte[] bytes = yaml.writeValueAsBytes(root);
            Files.write(tmp, bytes);
            try {
                Files.move(tmp, yamlPath,
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException amns) {
                // Some volumes (FAT32, some SMB shares) can't
                // do an atomic rename. Fall back to a plain
                // replace — still atomic from the user's
                // perspective because the tmp file is fully
                // written before the rename.
                Files.move(tmp, yamlPath, StandardCopyOption.REPLACE_EXISTING);
            }
            LOG.debug("R375.2: persisted {} quota override(s) to {}",
                    toWrite.size(), yamlPath);
        } catch (IOException e) {
            LOG.warn("R375.2: failed to write {} ({})",
                    yamlPath, e.getMessage());
            // Best-effort cleanup of the leftover temp
            // file. We don't propagate — a failed save
            // shouldn't break the in-memory quota update.
            try { Files.deleteIfExists(tmp); } catch (IOException ignored) {}
        }
    }

    /** The YAML path the store reads/writes. */
    public Path path() { return yamlPath; }

    /**
     * Convenience: load from {@code ~/.aethercode/agents.yaml}
     * via {@link org.aethercode.tasks.supervisor.SupervisorHome}.
     * Returns a store bound to the process-wide
     * {@link SubagentRegistry#instance()}.
     */
    public static AgentQuotaStore defaultHome() {
        return new AgentQuotaStore(
                org.aethercode.tasks.supervisor.SupervisorHome.dir()
                        .resolve(DEFAULT_FILE_NAME),
                SubagentRegistry.instance());
    }

    /** Apply the persisted overrides in one call. */
    public static Map<String, Integer> loadFromHome() {
        AgentQuotaStore s = defaultHome();
        Map<String, Integer> applied = s.load();
        LOG.info("R375.2: AgentQuotaStore loaded {} overrides", applied.size());
        return applied;
    }
}