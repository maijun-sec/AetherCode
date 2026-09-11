package org.aethercode.workflows.ssd;

import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * R236 — the SSD workflow configuration. Loads either:
 * <ul>
 *   <li>a project-local copy at {@code <cwd>/.aethercode/ssd/ssd.yaml}
 *       (lets users edit prompts without rebuilding the jar), or</li>
 *   <li>the bundled default at {@code ssd/ssd-defaults.yaml} on the
 *       classpath (what the release jar ships).</li>
 * </ul>
 *
 * <p>The shape is {@code version, name, description, phases[], hardRules,
 * maxWaitMs, idleEndMs, artefactRoot}. The loader validates that the
 * four required phase ids ({@code spec, design, tasks, dev}) are
 * present and have non-empty prompts; everything else has safe
 * defaults so a partially-edited project file still runs.
 *
 * <p>This class is intentionally a plain POJO with a static loader —
 * no service, no engine, no DI. The runner consumes the {@link Phase}
 * records and is fully testable with a {@link SsdConfig} built in
 * memory.
 */
public final class SsdConfig {

    private static final String BUNDLED_RESOURCE = "ssd/ssd-defaults.yaml";
    private static final Path PROJECT_RELATIVE = Path.of(".aethercode", "ssd", "ssd.yaml");

    private final String name;
    private final String description;
    private final List<Phase> phases;
    private final String hardRules;
    private final long maxWaitMs;
    private final long idleEndMs;
    private final String artefactRoot;
    private final Source source;

    private SsdConfig(String name, String description, List<Phase> phases,
                      String hardRules, long maxWaitMs, long idleEndMs,
                      String artefactRoot, Source source) {
        this.name = Objects.requireNonNull(name, "name");
        this.description = description == null ? "" : description;
        this.phases = List.copyOf(phases);
        this.hardRules = hardRules == null ? "" : hardRules;
        this.maxWaitMs = maxWaitMs;
        this.idleEndMs = idleEndMs;
        this.artefactRoot = artefactRoot;
        this.source = source;
    }

    /** Load from the classpath-bundled default. This is what the CLI
     *  falls back to when there is no project-local override. */
    public static SsdConfig fromBundled() {
        return loadFromResource(BUNDLED_RESOURCE, Source.BUNDLED);
    }

    /**
     * Load from a project-local file ({@code <cwd>/.aethercode/ssd/ssd.yaml})
     * if present, otherwise the bundled default. Project file wins so users
     * can edit prompts without rebuilding the jar.
     */
    public static SsdConfig fromProjectOrBundled(Path cwd) {
        Path file = cwd.resolve(PROJECT_RELATIVE);
        if (Files.isRegularFile(file)) {
            try {
                String body = Files.readString(file, StandardCharsets.UTF_8);
                return parse(body, Source.PROJECT);
            } catch (IOException ioe) {
                // Fall through to bundled; the user can fix the file
                // and re-run. We do NOT throw — a malformed override
                // would be a worse UX than running the default.
                System.err.println("[ssd] could not read " + file
                        + " (" + ioe.getMessage() + "); using bundled default");
            } catch (IllegalArgumentException iae) {
                // The project YAML parsed but failed validation
                // (missing required phases, blank prompt, etc.).
                // Same UX decision: fall back to bundled rather
                // than refuse the whole SSD run; the user can
                // see the error in the diagnostic line below.
                System.err.println("[ssd] " + file + " is invalid ("
                        + iae.getMessage() + "); using bundled default");
            }
        }
        return fromBundled();
    }

    /** Test-only constructor. Parses the given YAML body verbatim. */
    public static SsdConfig parse(String yaml) {
        return parse(yaml, Source.INLINE);
    }

    // -- accessors --------------------------------------------------------

    public String name() { return name; }
    public String description() { return description; }
    public List<Phase> phases() { return phases; }
    public String hardRules() { return hardRules; }
    public long maxWaitMs() { return maxWaitMs; }
    public long idleEndMs() { return idleEndMs; }
    public String artefactRoot() { return artefactRoot; }
    public Source source() { return source; }

    /** Lookup by phase id (case-sensitive). {@link Optional#empty()} if
     *  no phase matches. The id is what the YAML declares (e.g. "spec",
     *  "design"); the {@link Phase#order()} field drives the iteration
     *  order, not this map. */
    public Optional<Phase> phaseById(String id) {
        if (id == null) return Optional.empty();
        for (Phase p : phases) {
            if (id.equals(p.id())) return Optional.of(p);
        }
        return Optional.empty();
    }

    /** Phases sorted by {@link Phase#order() ascending}. The four
     *  bundled phases are 1..4, so this returns them in execution
     *  order regardless of how the YAML listed them. */
    public List<Phase> orderedPhases() {
        List<Phase> out = new ArrayList<>(phases);
        out.sort((a, b) -> Integer.compare(a.order(), b.order()));
        return out;
    }

    // -- internals --------------------------------------------------------

    private static SsdConfig loadFromResource(String resource, Source source) {
        ClassLoader cl = Thread.currentThread().getContextClassLoader();
        if (cl == null) cl = SsdConfig.class.getClassLoader();
        try (InputStream in = cl.getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException("SSD config not on classpath: " + resource);
            }
            String body = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            return parse(body, source);
        } catch (IOException ioe) {
            throw new IllegalStateException("could not read SSD config " + resource, ioe);
        }
    }

    @SuppressWarnings("unchecked")
    private static SsdConfig parse(String yaml, Source source) {
        Yaml y = new Yaml();
        Object loaded = y.load(yaml);
        if (!(loaded instanceof Map)) {
            throw new IllegalArgumentException("SSD config root must be a YAML map, got "
                    + (loaded == null ? "null" : loaded.getClass().getSimpleName()));
        }
        Map<String, Object> root = (Map<String, Object>) loaded;

        String name = stringOr(root.get("name"), "ssd");
        String description = stringOr(root.get("description"), "");
        String hardRules = stringOr(root.get("hardRules"), "");
        long maxWaitMs = longOr(root.get("maxWaitMs"), 180_000L);
        long idleEndMs = longOr(root.get("idleEndMs"), 2_500L);
        String artefactRoot = stringOr(root.get("artefactRoot"), ".aethercode/ssd");

        List<Phase> phases = new ArrayList<>();
        Object rawPhases = root.get("phases");
        if (rawPhases instanceof List<?> list) {
            for (Object o : list) {
                if (o instanceof Map<?, ?> m) {
                    phases.add(parsePhase((Map<String, Object>) m));
                }
            }
        }
        validatePhases(phases);

        return new SsdConfig(name, description, phases, hardRules,
                maxWaitMs, idleEndMs, artefactRoot, source);
    }

    @SuppressWarnings("unchecked")
    private static Phase parsePhase(Map<String, Object> m) {
        String id = stringOr(m.get("id"), null);
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("phase missing 'id': " + m);
        }
        int order = (int) longOr(m.get("order"), 0L);
        String file = stringOr(m.get("file"), id + ".md");
        String title = stringOr(m.get("title"), id);
        String userPromptTemplate = stringOr(m.get("userPromptTemplate"), "");
        String userRevisionTemplate = stringOr(m.get("userRevisionTemplate"), userPromptTemplate);
        String systemPrompt = stringOr(m.get("systemPrompt"), "");
        int maxTokens = (int) longOr(m.get("maxTokens"), 4096L);
        return new Phase(id, order, file, title, systemPrompt,
                userPromptTemplate, userRevisionTemplate, maxTokens);
    }

    private static void validatePhases(List<Phase> phases) {
        if (phases.isEmpty()) {
            throw new IllegalArgumentException("SSD config has no phases (need at least one)");
        }
        // Required phase ids: the runner is hard-wired to look up
        // spec/design/tasks/dev by name, so a missing phase is a
        // hard fail, not a soft warning.
        List<String> required = List.of("spec", "design", "tasks", "dev");
        List<String> present = phases.stream().map(Phase::id).toList();
        List<String> missing = new ArrayList<>();
        for (String r : required) {
            if (!present.contains(r)) missing.add(r);
        }
        if (!missing.isEmpty()) {
            throw new IllegalArgumentException(
                    "SSD config missing required phase(s): " + missing
                            + " (present: " + present + ")");
        }
        for (Phase p : phases) {
            if (p.userPromptTemplate().isBlank()) {
                throw new IllegalArgumentException(
                        "phase '" + p.id() + "' has empty userPromptTemplate");
            }
        }
    }

    private static String stringOr(Object o, String dflt) {
        if (o == null) return dflt;
        String s = String.valueOf(o);
        // SnakeYAML's `|` block scalars come through as a String with
        // trailing newlines preserved. We keep them as-is; the runner
        // does its own template substitution.
        return s;
    }

    private static long longOr(Object o, long dflt) {
        if (o == null) return dflt;
        if (o instanceof Number n) return n.longValue();
        try {
            return Long.parseLong(String.valueOf(o));
        } catch (NumberFormatException nfe) {
            return dflt;
        }
    }

    /** Where this config was loaded from. Surfaced in the CLI banner
     *  and the test assertions so we always know whether we're
     *  running the bundled or a project-overridden copy. */
    public enum Source { BUNDLED, PROJECT, INLINE }

    /** One SSD phase: the prompt templates + the file it writes. */
    public record Phase(
            String id,
            int order,
            String file,
            String title,
            String systemPrompt,
            String userPromptTemplate,
            String userRevisionTemplate,
            int maxTokens
    ) {
        public Phase {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(file, "file");
            Objects.requireNonNull(title, "title");
            Objects.requireNonNull(systemPrompt, "systemPrompt");
            Objects.requireNonNull(userPromptTemplate, "userPromptTemplate");
            Objects.requireNonNull(userRevisionTemplate, "userRevisionTemplate");
        }

        /** Render the initial user prompt with the given variable
         *  bag. The bag is a {@code Map<String,Object>}; missing
         *  variables render as the empty string so a partial bag
         *  (e.g. dev phase with no spec/design) doesn't blow up. */
        public String renderUserPrompt(Map<String, Object> vars) {
            return substitute(userPromptTemplate, vars == null ? Map.of() : vars);
        }

        /** Render the revision prompt. Same variable bag as the
         *  initial prompt; the revision template expects a
         *  {@code priorContent} variable plus the same context
         *  fields (feature, intent, etc.). */
        public String renderRevisionPrompt(Map<String, Object> vars) {
            return substitute(userRevisionTemplate, vars == null ? Map.of() : vars);
        }
    }

    /** Minimal {@code {{var}}} substitution. We avoid a real template
     *  engine — the four phase templates are simple text and bringing
     *  in Handlebars/FreeMarker for this would be more weight than
     *  the SSD runner deserves. Unknown variables render as
     *  {@code ""} (empty), not as the literal {@code {{name}}}, so
     *  a forgotten bag field fails soft, not loud. */
    static String substitute(String template, Map<String, Object> vars) {
        if (template == null || template.isEmpty()) return "";
        StringBuilder out = new StringBuilder(template.length());
        int i = 0;
        int n = template.length();
        while (i < n) {
            int open = template.indexOf("{{", i);
            if (open < 0) {
                out.append(template, i, n);
                break;
            }
            out.append(template, i, open);
            int close = template.indexOf("}}", open + 2);
            if (close < 0) {
                // Unterminated; just paste the rest verbatim so the
                // artefact is still readable rather than throwing.
                out.append(template, open, n);
                break;
            }
            String key = template.substring(open + 2, close).trim();
            Object v = vars.get(key);
            if (v != null) {
                out.append(String.valueOf(v));
            }
            i = close + 2;
        }
        return out.toString();
    }

    // Test helper for the INLINE-source case (parse() is package-private
    // already; this just makes test intent explicit).
    static SsdConfig forTest(String yaml) {
        return parse(yaml, Source.INLINE);
    }
}
