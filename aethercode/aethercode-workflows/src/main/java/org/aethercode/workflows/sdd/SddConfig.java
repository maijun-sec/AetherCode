package org.aethercode.workflows.sdd;

import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * R292 — SDD (Spec-Driven Development) configuration. Loads:
 * <ul>
 *   <li>The project-level {@code constitution} from
 *       {@code <cwd>/.specify/memory/constitution.md} (if present)
 *       or the bundled default at
 *       {@code spec-kit/memory/constitution-template.md} on the
 *       classpath.</li>
 *   <li>Per-phase markdown templates from
 *       {@code <cwd>/.specify/templates/<id>-template.md} (if present)
 *       or the bundled defaults under
 *       {@code spec-kit/templates/} on the classpath. The loader
 *       does <em>not</em> parse the templates — it surfaces them as
 *       raw text so the runner can render {@code {{feature}}} /
 *       {@code {{intent}}} / {@code {{priorContent}}} substitutions.</li>
 *   <li>Hard-rules text from {@code <cwd>/.specify/sdd.yaml} or the
 *       bundled default (mirrors the R236 SSD shape so existing
 *       override habits port over).</li>
 * </ul>
 *
 * <h2>Resolution order</h2>
 * <p>For each resource, project-local wins. If the project file is
 * missing or unreadable, the bundled default is used. We never
 * partially override: either the project supplies a complete
 * constitution, or we use the bundled default verbatim.
 *
 * <h2>Why markdown templates, not YAML</h2>
 * <p>Spec Kit ships its phase bodies as plain markdown with
 * placeholder tokens like {@code [FEATURE NAME]} and
 * {@code [DATE]}. We mirror that shape so the artefacts AetherCode
 * generates can be consumed unchanged by a Spec Kit agent if the
 * user ever decides to hand them off. The runner performs the
 * {@code {{feature}}} / {@code {{intent}}} / {@code {{priorContent}}}
 * substitution at render time using the same {@code substitute}
 * helper the R236 SSD runner used.
 *
 * <h2>Why no SnakeYAML for the templates</h2>
 * <p>Phase templates are markdown with bracketed placeholders, not
 * YAML structures. Parsing them as YAML would force us to encode
 * the body as a block scalar in {@code sdd.yaml}, which is harder
 * to diff in PRs than a separate {@code .md} file per phase.
 *
 * <h2>Why a separate {@link PhaseId} enum</h2>
 * <p>The runner and the UI both switch on phase kind. An enum is
 * the right shape: ordered (the integer order is what we use to
 * walk phases), named (the kebab-case id is what we write to disk
 * and send over NDJSON), and discoverable (the UI's
 * {@code phase-list} event uses {@link PhaseId#specKitId()}).
 */
public final class SddConfig {

    /** Each Spec Kit slash command / SDD phase. The {@link #order()}
     *  is what the runner sorts by; the {@link #specKitId()} is the
     *  kebab-case identifier we write to disk and emit over the
     *  NDJSON protocol. */
    public enum PhaseId {
        CONSTITUTION(0, "constitution", "Constitution"),
        SPECIFY(1, "specify", "Specify"),
        CLARIFY(2, "clarify", "Clarify"),
        PLAN(3, "plan", "Plan"),
        ANALYZE(4, "analyze", "Analyze"),
        TASKS(5, "tasks", "Tasks"),
        IMPLEMENT(6, "implement", "Implement"),
        CONVERGE(7, "converge", "Converge");

        private final int order;
        private final String specKitId;
        private final String title;

        PhaseId(int order, String specKitId, String title) {
            this.order = order;
            this.specKitId = specKitId;
            this.title = title;
        }

        public int order() { return order; }
        public String specKitId() { return specKitId; }
        public String title() { return title; }

        /** Optional quality gates sit between the main phases.
         *  Used by the UI to render them as half-opacity chips
         *  so the user can opt in. */
        public boolean isOptional() {
            return this == CLARIFY || this == ANALYZE || this == CONVERGE;
        }

        /** Reverse-lookup from the kebab-case id the wire / disk
         *  uses. Case-insensitive; underscores and dashes are
         *  treated as equivalent. */
        public static Optional<PhaseId> fromSpecKitId(String id) {
            if (id == null) return Optional.empty();
            String norm = id.toLowerCase().replace('_', '-').trim();
            for (PhaseId p : values()) {
                if (p.specKitId().equals(norm)) return Optional.of(p);
            }
            return Optional.empty();
        }
    }

    /** Branch numbering strategy. Spec Kit defaults to {@code
     *  SEQUENTIAL} ({@code 001-feature-name}); we expose the same
     *  shape and let the CLI override to {@code TIMESTAMP} via
     *  {@code --branch-numbering}. */
    public enum SlugPolicy {
        SEQUENTIAL, TIMESTAMP;

        public static SlugPolicy parse(String s) {
            if (s == null || s.isBlank()) return SEQUENTIAL;
            // Accept "sequential", "SEQUENTIAL", "timestamp",
            // "TIMESTAMP", "time-stamp", "time_stamp" — strip
            // separators and uppercase so the enum lookup is
            // case-insensitive. Enum names are uppercase by
            // Java convention; valueOf is case-sensitive.
            String norm = s.trim().toUpperCase()
                    .replace('-', '_')
                    .replace(" ", "")
                    .replace("_", "");
            try {
                return SlugPolicy.valueOf(norm);
            } catch (IllegalArgumentException iae) {
                throw new IllegalArgumentException("unknown --branch-numbering: '" + s
                        + "' (expected: sequential | timestamp)");
            }
        }
    }

    // ----- bundled-resource paths --------------------------------------

    private static final String BUNDLED_CONSTITUTION = "spec-kit/memory/constitution-template.md";
    private static final String BUNDLED_PHASE_TPL_PREFIX = "spec-kit/templates/";
    private static final String BUNDLED_PHASE_TPL_SUFFIX = "-template.md";
    private static final String BUNDLED_HARD_RULES_RESOURCE = "spec-kit/hard-rules.md";
    private static final String BUNDLED_SDD_YAML = "spec-kit/sdd-defaults.yaml";

    // ----- project-override paths --------------------------------------
    // R294: project overrides follow the AetherCode internal SDD
    // path layout (.aethercode/sdd/) — the directory name uses
    // lowercase `sdd` to match the daemon's `aethercode sdd`
    // subcommand name. The user wants products to land at
    // <cwd>/.aethercode/sdd/<slug>/{constitution.md, spec.md,
    // design.md, tasks.md, dev.log, clarify.json, analyze.json,
    // convergence.json}.

    private static final Path PROJECT_CONSTITUTION = Path.of(".aethercode", "sdd", "constitution.md");
    private static final Path PROJECT_TEMPLATES_DIR = Path.of(".aethercode", "sdd", "templates");
    private static final Path PROJECT_SDD_YAML = Path.of(".aethercode", "sdd", "sdd.yaml");

    // ----- fields ------------------------------------------------------

    private final String name;
    private final String description;
    private final String constitutionBody;
    private final Map<PhaseId, String> phaseTemplates;
    private final String hardRules;
    private final long maxWaitMs;
    private final long idleEndMs;
    private final String artefactRoot;
    private final SlugPolicy slugPolicy;
    private final boolean enableClarify;
    private final boolean enableAnalyze;
    private final boolean enableConverge;
    private final Source constitutionSource;
    private final Map<PhaseId, Source> templateSources;
    private final Source hardRulesSource;

    private SddConfig(String name,
                      String description,
                      String constitutionBody,
                      Map<PhaseId, String> phaseTemplates,
                      String hardRules,
                      long maxWaitMs,
                      long idleEndMs,
                      String artefactRoot,
                      SlugPolicy slugPolicy,
                      boolean enableClarify,
                      boolean enableAnalyze,
                      boolean enableConverge,
                      Source constitutionSource,
                      Map<PhaseId, Source> templateSources,
                      Source hardRulesSource) {
        this.name = Objects.requireNonNull(name, "name");
        this.description = description == null ? "" : description;
        this.constitutionBody = constitutionBody == null ? "" : constitutionBody;
        this.phaseTemplates = Map.copyOf(phaseTemplates);
        this.hardRules = hardRules == null ? "" : hardRules;
        this.maxWaitMs = maxWaitMs;
        this.idleEndMs = idleEndMs;
        this.artefactRoot = Objects.requireNonNull(artefactRoot, "artefactRoot");
        this.slugPolicy = Objects.requireNonNull(slugPolicy, "slugPolicy");
        this.enableClarify = enableClarify;
        this.enableAnalyze = enableAnalyze;
        this.enableConverge = enableConverge;
        this.constitutionSource = constitutionSource;
        this.templateSources = Map.copyOf(templateSources);
        this.hardRulesSource = hardRulesSource;
    }

    // ----- loaders -----------------------------------------------------

    /** Load the bundled defaults only (what tests + the
     *  {@code --show} / {@code --validate} subcommands need when no
     *  cwd is supplied). All bundled templates + the
     *  constitution are loaded eagerly so the returned config
     *  has a populated {@code phaseTemplates} map. */
    public static SddConfig fromBundled() {
        Map<PhaseId, String> templates = new LinkedHashMap<>();
        Map<PhaseId, Source> sources = new LinkedHashMap<>();
        for (PhaseId phase : PhaseId.values()) {
            if (phase == PhaseId.CONSTITUTION) {
                templates.put(phase, loadBundledOrEmpty(BUNDLED_CONSTITUTION));
            } else {
                String fileName = phase.specKitId() + BUNDLED_PHASE_TPL_SUFFIX;
                templates.put(phase, loadBundledOrEmpty(BUNDLED_PHASE_TPL_PREFIX + fileName));
            }
            sources.put(phase, Source.BUNDLED);
        }
        String constitution = templates.get(PhaseId.CONSTITUTION);
        return loadFromResources(null, constitution, templates, sources,
                Source.BUNDLED, Source.BUNDLED);
    }

    /** Load the project-aware config: project-local files win,
     *  bundled defaults fill in. Project files we cannot parse or
     *  read are silently skipped (we log to stderr and fall back)
     *  so a malformed override never aborts the whole run. */
    public static SddConfig fromProjectOrBundled(Path cwd) {
        // 1) sdd.yaml: optional project-level hard-rules / flags.
        Path sddYaml = cwd.resolve(PROJECT_SDD_YAML);
        Map<String, Object> projectYaml = null;
        Source yamlSource = Source.BUNDLED;
        if (Files.isRegularFile(sddYaml)) {
            try {
                String body = Files.readString(sddYaml, StandardCharsets.UTF_8);
                Object loaded = new Yaml().load(body);
                if (loaded instanceof Map<?, ?> m) {
                    projectYaml = stringKeyedMap((Map<String, Object>) m);
                    yamlSource = Source.PROJECT;
                }
            } catch (IOException | RuntimeException ex) {
                System.err.println("[sdd] could not read " + sddYaml
                        + " (" + ex.getMessage() + "); using bundled defaults");
            }
        }
        // 2) constitution: project file → jar bundled template.
        Path constPath = cwd.resolve(PROJECT_CONSTITUTION);
        Source constSource = Source.BUNDLED;
        String constitution = loadBundled(BUNDLED_CONSTITUTION);
        if (Files.isRegularFile(constPath)) {
            try {
                constitution = Files.readString(constPath, StandardCharsets.UTF_8);
                constSource = Source.PROJECT;
            } catch (IOException ioe) {
                System.err.println("[sdd] could not read " + constPath
                        + " (" + ioe.getMessage() + "); using bundled constitution template");
            }
        }
        // 3) phase templates: project <cwd>/.specify/templates/<id>-template.md
        //    → jar bundled spec-kit/templates/<id>-template.md
        //    (constitution is special: it lives under .specify/memory/,
        //    not .specify/templates/, mirroring Spec Kit's repo layout).
        Path tplDir = cwd.resolve(PROJECT_TEMPLATES_DIR);
        Map<PhaseId, String> templates = new LinkedHashMap<>();
        Map<PhaseId, Source> sources = new LinkedHashMap<>();
        for (PhaseId phase : PhaseId.values()) {
            if (phase == PhaseId.CONSTITUTION) {
                // Constitution uses BUNDLED_CONSTITUTION
                // (spec-kit/memory/constitution-template.md), not
                // the templates/ folder.
                templates.put(phase, loadBundledOrEmpty(BUNDLED_CONSTITUTION));
                sources.put(phase, Source.BUNDLED);
                continue;
            }
            String fileName = phase.specKitId() + BUNDLED_PHASE_TPL_SUFFIX;
            String body = loadBundledOrEmpty(BUNDLED_PHASE_TPL_PREFIX + fileName);
            templates.put(phase, body);
            sources.put(phase, Source.BUNDLED);
            Path proj = tplDir.resolve(fileName);
            if (Files.isRegularFile(proj)) {
                try {
                    templates.put(phase, Files.readString(proj, StandardCharsets.UTF_8));
                    sources.put(phase, Source.PROJECT);
                } catch (IOException ioe) {
                    System.err.println("[sdd] could not read " + proj
                            + " (" + ioe.getMessage() + "); using bundled phase template");
                }
            }
        }
        return loadFromResources(projectYaml, constitution, templates, sources, constSource, yamlSource);
    }

    /** Test-only constructor. Parses the given YAML body verbatim
     *  (no project lookup). */
    public static SddConfig parse(String yaml) {
        Object loaded = new Yaml().load(yaml);
        if (loaded == null) {
            return fromBundled();
        }
        if (!(loaded instanceof Map<?, ?>)) {
            throw new IllegalArgumentException("SDD config root must be a YAML map, got "
                    + loaded.getClass().getSimpleName());
        }
        Map<String, Object> root = stringKeyedMap((Map<String, Object>) loaded);
        Map<PhaseId, String> templates = new LinkedHashMap<>();
        Map<PhaseId, Source> sources = new LinkedHashMap<>();
        for (PhaseId phase : PhaseId.values()) {
            String fileName = phase.specKitId() + BUNDLED_PHASE_TPL_SUFFIX;
            templates.put(phase, loadBundled(BUNDLED_PHASE_TPL_PREFIX + fileName));
            sources.put(phase, Source.INLINE);
        }
        return loadFromResources(root,
                loadBundled(BUNDLED_CONSTITUTION),
                templates, sources,
                Source.INLINE, Source.INLINE);
    }

    // ----- accessors ---------------------------------------------------

    public String name() { return name; }
    public String description() { return description; }
    /** Raw constitution markdown. The runner concatenates this
     *  with the resolved per-phase system prompt so the model
     *  always sees the project's governance alongside the phase
     *  prompt. */
    public String constitutionBody() { return constitutionBody; }
    public Source constitutionSource() { return constitutionSource; }
    /** Raw phase template (markdown with bracketed placeholders).
     *  The runner renders {@code {{feature}}}, {@code {{intent}}},
     *  {@code {{priorContent}}}, etc. via {@link #substitute}.
     *  When the jar does not ship a template for this phase
     *  (Spec Kit only ships 4: spec / plan / tasks / checklist)
     *  we fall back to a built-in generic prompt so the runner
     *  still has a system prompt to concatenate. */
    public String phaseTemplate(PhaseId phase) {
        String tpl = phaseTemplates.get(phase);
        if (tpl != null && !tpl.isEmpty()) return tpl;
        return genericPhasePrompt(phase);
    }
    public Source phaseSource(PhaseId phase) {
        return templateSources.getOrDefault(phase, Source.BUNDLED);
    }
    public String hardRules() { return hardRules; }
    public Source hardRulesSource() { return hardRulesSource; }
    public long maxWaitMs() { return maxWaitMs; }
    public long idleEndMs() { return idleEndMs; }
    /** Spec Kit-compatible artefact root. The default
     *  {@code .specify} matches what {@code specify init} creates,
     *  so users can hand off our artefacts to a Spec Kit agent
     *  unchanged. */
    public String artefactRoot() { return artefactRoot; }
    public SlugPolicy slugPolicy() { return slugPolicy; }
    public boolean enableClarify() { return enableClarify; }
    public boolean enableAnalyze() { return enableAnalyze; }
    public boolean enableConverge() { return enableConverge; }

    /** Build the runner-visible phase list, honouring the
     *  {@code enableClarify / enableAnalyze / enableConverge}
     *  flags. The order is by {@link PhaseId#order()}. */
    public List<PhaseId> activePhases() {
        List<PhaseId> out = new ArrayList<>();
        for (PhaseId p : PhaseId.values()) {
            if (p == PhaseId.CLARIFY && !enableClarify) continue;
            if (p == PhaseId.ANALYZE && !enableAnalyze) continue;
            if (p == PhaseId.CONVERGE && !enableConverge) continue;
            out.add(p);
        }
        return out;
    }

    // ----- internals ---------------------------------------------------

    private static SddConfig loadFromResources(Map<String, Object> yaml,
                                               String constitution,
                                               Map<PhaseId, String> templates,
                                               Source constitutionSource,
                                               Source yamlSource) {
        return loadFromResources(yaml, constitution, templates,
                sourcesFor(templates),
                constitutionSource, yamlSource);
    }

    private static SddConfig loadFromResources(Map<String, Object> yaml,
                                               String constitution,
                                               Map<PhaseId, String> templates,
                                               Map<PhaseId, Source> templateSources,
                                               Source constitutionSource,
                                               Source yamlSource) {
        String name = stringOr(yaml == null ? null : yaml.get("name"), "sdd");
        String description = stringOr(yaml == null ? null : yaml.get("description"), "");
        long maxWaitMs = longOr(yaml == null ? null : yaml.get("maxWaitMs"), 240_000L);
        long idleEndMs = longOr(yaml == null ? null : yaml.get("idleEndMs"), 2_500L);
        // R294: default to AetherCode's internal SDD layout
        // (<cwd>/.aethercode/sdd/<slug>/{constitution.md, spec.md,
        // design.md, tasks.md, dev.log, clarify.json, analyze.json,
        // convergence.json}). The previous R292 default of
        // .specify/specs/<NNN>-<slug>/ followed the upstream
        // Spec Kit convention but didn't match what the user
        // asked for in the round notes (line 21: "制品路径:
        // <cwd>/.aethercode/sdd/<feature>/{spec,design,tasks}.md +
        // dev.log"). Project-level `.aethercode/sdd/sdd.yaml`
        // overrides still work — the override just sets
        // `artefactRoot:`.
        String artefactRoot = stringOr(yaml == null ? null : yaml.get("artefactRoot"), ".aethercode/sdd");
        SlugPolicy slugPolicy = SlugPolicy.parse(stringOr(yaml == null ? null : yaml.get("slugPolicy"), "sequential"));
        boolean enableClarify = boolOr(yaml == null ? null : yaml.get("enableClarify"), true);
        boolean enableAnalyze = boolOr(yaml == null ? null : yaml.get("enableAnalyze"), true);
        boolean enableConverge = boolOr(yaml == null ? null : yaml.get("enableConverge"), true);

        String hardRules;
        Source hardRulesSource;
        if (yaml != null && yaml.get("hardRules") instanceof String s && !s.isBlank()) {
            hardRules = s;
            hardRulesSource = yamlSource;
        } else {
            hardRules = loadBundledOrEmpty(BUNDLED_HARD_RULES_RESOURCE);
            hardRulesSource = Source.BUNDLED;
        }

        return new SddConfig(name, description, constitution, templates,
                hardRules, maxWaitMs, idleEndMs, artefactRoot,
                slugPolicy, enableClarify, enableAnalyze, enableConverge,
                constitutionSource, templateSources, hardRulesSource);
    }

    private static Map<PhaseId, Source> sourcesFor(Map<PhaseId, String> templates) {
        Map<PhaseId, Source> out = new LinkedHashMap<>();
        for (PhaseId p : templates.keySet()) {
            out.put(p, Source.BUNDLED);
        }
        return out;
    }

    private static String loadBundled(String resource) {
        String body = loadBundledOrEmpty(resource);
        if (body.isEmpty()) {
            throw new IllegalStateException("SDD resource missing on classpath: " + resource);
        }
        return body;
    }

    /** Fallback phase prompts for the 4 phases Spec Kit does
     *  not ship a markdown template for (constitution /
     *  clarify / analyze / implement / converge). We use these
     *  when no jar resource is present at
     *  {@code spec-kit/templates/<id>-template.md}. Keep the
     *  prompts short and concrete — the runner concatenates the
     *  constitution + hard-rules on top, so the model already
     *  sees the governance context. */
    private static String genericPhasePrompt(PhaseId phase) {
        return switch (phase) {
            case CONSTITUTION -> """
                    # Project Constitution (governance)

                    Write or update `.aethercode/sdd/<slug>/constitution.md`. The
                    document MUST include:

                    ## Core Principles
                    Code quality, testing standards, UX consistency,
                    performance & reliability, security.

                    ## Governance
                    How the constitution is enforced, amended, and
                    versioned. Include a SemVer version line.
                    """;
            case CLARIFY -> """
                    # Clarify (optional quality gate)

                    Read the spec.md the runner just drafted and surface
                    1-3 short, high-leverage questions about areas that
                    are underspecified. One per line, prefixed with `Q:`.
                    If nothing is unclear, output `Q: NONE`. Do not call
                    tools; output plain text only.
                    """;
            case ANALYZE -> """
                    # Analyze (optional quality gate)

                    Cross-check spec.md / design.md / tasks.md for
                    consistency. List any requirement in spec.md that is
                    not covered by tasks.md, any task that is not
                    justified by design.md, or any contradiction. Output a
                    short bullet list and end with a line `CONVERGED: yes`
                    or `CONVERGED: no`.
                    """;
            case IMPLEMENT -> """
                    # Implement (per-task loop)

                    For each task in tasks.md produce the implementation
                    code. Output the patch in markdown fenced blocks plus
                    a 1-2 sentence verification note. Do not call tools.
                    """;
            case CONVERGE -> """
                    # Converge (post-implementation loop)

                    Read spec.md and the latest dev.log. Output a
                    JSON-ish object with shape {converged: bool, issues:
                    [string]}. Converged is true only if every FR is
                    covered and there are no contradictions. End with
                    either `CONVERGED: true` or `CONVERGED: false`.
                    """;
            case SPECIFY -> ""; // fall through to jar template
            case PLAN    -> ""; // fall through to jar template
            case TASKS   -> ""; // fall through to jar template
        };
    }

    private static String loadBundledOrEmpty(String resource) {
        ClassLoader cl = Thread.currentThread().getContextClassLoader();
        if (cl == null) cl = SddConfig.class.getClassLoader();
        try (InputStream in = cl.getResourceAsStream(resource)) {
            if (in == null) return "";
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException ioe) {
            throw new IllegalStateException("could not read SDD resource " + resource, ioe);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> stringKeyedMap(Map<String, Object> in) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : in.entrySet()) {
            out.put(e.getKey(), e.getValue());
        }
        return out;
    }

    private static String stringOr(Object o, String dflt) {
        return o == null ? dflt : String.valueOf(o);
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

    private static boolean boolOr(Object o, boolean dflt) {
        if (o == null) return dflt;
        if (o instanceof Boolean b) return b;
        String s = String.valueOf(o).trim().toLowerCase();
        if (s.equals("true") || s.equals("yes") || s.equals("1") || s.equals("on")) return true;
        if (s.equals("false") || s.equals("no") || s.equals("0") || s.equals("off")) return false;
        return dflt;
    }

    /** Where a config piece was loaded from. Surfaced in the CLI
     *  banner so operators always know whether they're running the
     *  bundled defaults or a project-overridden copy. */
    public enum Source { BUNDLED, PROJECT, INLINE }

    /** Minimal {@code {{var}}} substitution. We avoid a real template
     *  engine — Spec Kit's phase templates are simple markdown and
     *  pulling in Handlebars/FreeMarker would dwarf the runner.
     *  Unknown variables render as empty string (not the literal
     *  {@code {{name}}}) so a forgotten bag field fails soft, not
     *  loud. Mirrors {@code SsdConfig.substitute} from R236 so the
     *  same prompt templates port over without churn. */
    public static String substitute(String template, Map<String, Object> vars) {
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
                out.append(template, open, n);
                break;
            }
            String key = template.substring(open + 2, close).trim();
            Object v = vars.get(key);
            if (v != null) out.append(String.valueOf(v));
            i = close + 2;
        }
        return out.toString();
    }

    /** Test helper: parses the given YAML body verbatim (no
     *  project lookup). INLINE source. */
    static SddConfig forTest(String yaml) {
        return parse(yaml);
    }

    // ----- with* (CLI flag overrides) ---------------------------------

    /** Return a copy with the slug policy replaced. The CLI uses
     *  this when {@code --branch-numbering} overrides the
     *  project / bundled default. */
    public SddConfig withSlugPolicy(SlugPolicy slugPolicy) {
        return new SddConfig(name, description, constitutionBody, phaseTemplates,
                hardRules, maxWaitMs, idleEndMs, artefactRoot,
                slugPolicy, enableClarify, enableAnalyze, enableConverge,
                constitutionSource, templateSources, hardRulesSource);
    }

    /** Return a copy with {@code enableClarify} replaced. */
    public SddConfig withEnableClarify(boolean enable) {
        return new SddConfig(name, description, constitutionBody, phaseTemplates,
                hardRules, maxWaitMs, idleEndMs, artefactRoot,
                slugPolicy, enable, enableAnalyze, enableConverge,
                constitutionSource, templateSources, hardRulesSource);
    }

    /** Return a copy with {@code enableAnalyze} replaced. */
    public SddConfig withEnableAnalyze(boolean enable) {
        return new SddConfig(name, description, constitutionBody, phaseTemplates,
                hardRules, maxWaitMs, idleEndMs, artefactRoot,
                slugPolicy, enableClarify, enable, enableConverge,
                constitutionSource, templateSources, hardRulesSource);
    }

    /** Return a copy with {@code enableConverge} replaced. */
    public SddConfig withEnableConverge(boolean enable) {
        return new SddConfig(name, description, constitutionBody, phaseTemplates,
                hardRules, maxWaitMs, idleEndMs, artefactRoot,
                slugPolicy, enableClarify, enableAnalyze, enable,
                constitutionSource, templateSources, hardRulesSource);
    }
}