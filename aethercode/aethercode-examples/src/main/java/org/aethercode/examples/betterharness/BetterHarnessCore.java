package org.aethercode.examples.betterharness;

import org.aethercode.examples.support.MiniArgParser;
import org.aethercode.examples.support.MiniJson;
import org.aethercode.examples.support.TinyToml;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.MatchResult;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Core data model, config loading, run loop, and trace helpers.
 *
 * <p>Java port of
 * {@code deepagents-main/examples/better-harness/better_harness/core.py}.
 * Defines {@link Experiment}, {@link Surface}, {@link EvalCase},
 * {@link Variant}, {@link CaseOutcome}, {@link SplitResult},
 * {@link Proposal}, {@link CandidateEvaluation},
 * {@link IterationRecord}, {@link RunReport}, and {@link RunLayout}.
 *
 * <p>The run loop ({@link #runExperiment}) is a faithful translation
 * of the Python port: load config, run baseline, propose variants,
 * evaluate, accept/reject, repeat. The CLI is split into
 * {@link #buildParser()} + {@link #main(String[])} to mirror the
 * Python port's argparse layout.</p>
 */
public final class BetterHarnessCore {
    private BetterHarnessCore() {}

    /** Valid split names. */
    public static final List<String> VALID_SPLITS = List.of("train", "holdout", "scorecard");
    /** Map of legacy split aliases to canonical names. */
    public static final Map<String, String> SPLIT_ALIASES = Map.of(
            "acceptance", "scorecard",
            "final_eval", "scorecard");
    /** Splits that surface to the proposer workspace. */
    public static final Set<String> VISIBLE_SPLITS = Set.of("train");
    /** Splits that stay private. */
    public static final Set<String> PRIVATE_SPLITS = Set.of("holdout", "scorecard");
    /** Surface kinds. */
    public static final List<String> VALID_SURFACE_KINDS = List.of("module_attr", "workspace_file");
    /** Runners. */
    public static final List<String> VALID_RUNNERS = List.of("pytest", "harbor");

    private static final Pattern ENV_PATTERN = Pattern.compile("\\$\\{([^}]+)\\}");
    private static final Pattern URL_PATTERN = Pattern.compile("https?://[^\\s\"'>]+");
    private static final Pattern UUID_PATTERN = Pattern.compile(
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

    // -----------------------------------------------------------------
    // Data classes (records)
    // -----------------------------------------------------------------

    /** One editable harness surface. */
    public record Surface(String name, String kind, String target, String baseValue, String filename) {}

    /** One explicit eval assignment. */
    public record EvalCase(String caseId, String split, String stratum) {
        public String render(String model) {
            return caseId.replace("{model}", model);
        }
    }

    /** Loaded experiment config. */
    public record Experiment(
            Path path,
            String name,
            String runner,
            Path workspaceRoot,
            String model,
            int maxIterations,
            String betterAgentModel,
            int betterAgentMaxTurns,
            Path betterAgentDeepagentsRoot,
            String betterAgentSystemPrompt,
            Map<String, Object> runnerConfig,
            Map<String, Surface> surfaces,
            List<EvalCase> cases) {

        public Experiment {
            runnerConfig = runnerConfig == null ? Map.of() : Map.copyOf(runnerConfig);
            surfaces = surfaces == null ? Map.of() : Map.copyOf(surfaces);
            cases = cases == null ? List.of() : List.copyOf(cases);
        }

        public List<EvalCase> casesForSplit(String split) {
            return cases.stream().filter(c -> c.split().equals(split)).toList();
        }
        public List<String> renderedCaseIds(String split) {
            return casesForSplit(split).stream().map(c -> c.render(model)).toList();
        }
        public Set<String> strataForSplit(String split) {
            return casesForSplit(split).stream().map(EvalCase::stratum).collect(Collectors.toCollection(LinkedHashSet::new));
        }
        public boolean hasSplit(String split) {
            return !casesForSplit(split).isEmpty();
        }
    }

    /** Materialized set of surface values. */
    public static final class Variant {
        private final String label;
        private final String model;
        private final List<String> changedSurfaces;
        private final Map<String, Surface> surfaces;
        private final Map<String, String> values;

        public Variant(String label,
                       String model,
                       List<String> changedSurfaces,
                       Map<String, Surface> surfaces,
                       Map<String, String> values) {
            this.label = label;
            this.model = model;
            this.changedSurfaces = List.copyOf(changedSurfaces);
            this.surfaces = Map.copyOf(surfaces);
            this.values = Map.copyOf(values);
        }

        public String label() { return label; }
        public String model() { return model; }
        public List<String> changedSurfaces() { return changedSurfaces; }
        public Map<String, Surface> surfaces() { return surfaces; }
        public Map<String, String> values() { return values; }
        public String key() { return label; }

        public Map<String, String> attrOverrides() {
            Map<String, String> out = new LinkedHashMap<>();
            for (Map.Entry<String, Surface> e : surfaces.entrySet()) {
                if ("module_attr".equals(e.getValue().kind())) {
                    out.put(e.getValue().target(), values.get(e.getKey()));
                }
            }
            return out;
        }

        public Map<String, String> fileOverrides() {
            Map<String, String> out = new LinkedHashMap<>();
            for (Map.Entry<String, Surface> e : surfaces.entrySet()) {
                if ("workspace_file".equals(e.getValue().kind())) {
                    out.put(e.getValue().target(), values.get(e.getKey()));
                }
            }
            return out;
        }

        public Map<String, Object> toMap() {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("label", label);
            out.put("model", model);
            out.put("changed_surfaces", changedSurfaces);
            Map<String, Object> surfaceMaps = new LinkedHashMap<>();
            for (Map.Entry<String, Surface> e : surfaces.entrySet()) {
                Surface s = e.getValue();
                Map<String, Object> sm = new LinkedHashMap<>();
                sm.put("name", s.name());
                sm.put("kind", s.kind());
                sm.put("target", s.target());
                sm.put("base_value", s.baseValue());
                sm.put("filename", s.filename());
                surfaceMaps.put(e.getKey(), sm);
            }
            out.put("surfaces", surfaceMaps);
            out.put("values", values);
            return out;
        }

        public void save(Path path) {
            try {
                Files.createDirectories(path.getParent());
                Files.writeString(path, MiniJson.toJson(toMap()));
            } catch (IOException exc) {
                throw new RuntimeException("cannot save variant " + path, exc);
            }
        }

        public static Variant loadFromJson(String json) {
            Map<String, Object> payload = MiniJson.parseObject(json);
            String label = (String) payload.get("label");
            String model = (String) payload.get("model");
            @SuppressWarnings("unchecked")
            List<Object> changed = (List<Object>) payload.getOrDefault("changed_surfaces", List.of());
            @SuppressWarnings("unchecked")
            Map<String, Object> surfaceMaps = (Map<String, Object>) payload.get("surfaces");
            @SuppressWarnings("unchecked")
            Map<String, Object> values = (Map<String, Object>) payload.get("values");
            Map<String, Surface> surfaces = new LinkedHashMap<>();
            if (surfaceMaps != null) {
                for (Map.Entry<String, Object> e : surfaceMaps.entrySet()) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> sm = (Map<String, Object>) e.getValue();
                    surfaces.put(e.getKey(), new Surface(
                            (String) sm.get("name"),
                            (String) sm.get("kind"),
                            (String) sm.get("target"),
                            (String) sm.get("base_value"),
                            (String) sm.get("filename")));
                }
            }
            Map<String, String> stringValues = new LinkedHashMap<>();
            if (values != null) {
                for (Map.Entry<String, Object> e : values.entrySet()) {
                    stringValues.put(e.getKey(), e.getValue() == null ? "" : e.getValue().toString());
                }
            }
            List<String> changedList = new ArrayList<>();
            for (Object o : changed) changedList.add(o == null ? "" : o.toString());
            return new Variant(label, model, changedList, surfaces, stringValues);
        }
    }

    /** One case-level outcome. */
    public record CaseOutcome(
            String caseId,
            String split,
            String stratum,
            String status,
            double score,
            double durationSeconds,
            Optional<String> failureMessage,
            Optional<String> artifactsDir,
            Optional<String> traceRef) {
        public boolean passed() { return "passed".equals(status); }

        public static CaseOutcome of(String caseId, String split, String stratum,
                                     String status, double score, double duration) {
            return new CaseOutcome(caseId, split, stratum, status, score, duration,
                    Optional.empty(), Optional.empty(), Optional.empty());
        }
    }

    /** One split result. */
    public record SplitResult(
            String split,
            String variant,
            String model,
            int passed,
            int total,
            double score,
            int returnCode,
            String runDir,
            List<CaseOutcome> outcomes) {

        public SplitResult {
            outcomes = outcomes == null ? List.of() : List.copyOf(outcomes);
        }

        public double correctness() {
            return total == 0 ? 0.0 : (double) passed / total;
        }

        public Set<String> passingCaseIds() {
            return outcomes.stream().filter(CaseOutcome::passed)
                    .map(CaseOutcome::caseId).collect(Collectors.toCollection(LinkedHashSet::new));
        }

        public List<CaseOutcome> failingOutcomes() {
            return outcomes.stream().filter(o -> !"passed".equals(o.status())).toList();
        }

        public Map<String, Object> toMap() {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("split", split);
            out.put("variant", variant);
            out.put("model", model);
            out.put("passed", passed);
            out.put("total", total);
            out.put("score", score);
            out.put("correctness", correctness());
            out.put("returncode", returnCode);
            out.put("run_dir", runDir);
            List<Map<String, Object>> outcomeMaps = new ArrayList<>();
            for (CaseOutcome o : outcomes) {
                Map<String, Object> om = new LinkedHashMap<>();
                om.put("case_id", o.caseId());
                om.put("split", o.split());
                om.put("stratum", o.stratum());
                om.put("status", o.status());
                om.put("score", o.score());
                om.put("duration_s", o.durationSeconds());
                o.failureMessage().ifPresent(v -> om.put("failure_message", v));
                o.artifactsDir().ifPresent(v -> om.put("artifacts_dir", v));
                o.traceRef().ifPresent(v -> om.put("trace_ref", v));
                outcomeMaps.add(om);
            }
            out.put("outcomes", outcomeMaps);
            return out;
        }

        public void save(Path path) {
            try {
                Files.createDirectories(path.getParent());
                Files.writeString(path, MiniJson.toJson(toMap()));
            } catch (IOException exc) {
                throw new RuntimeException("cannot save split result " + path, exc);
            }
        }

        public static SplitResult load(Path path) {
            try {
                return loadFromJson(Files.readString(path));
            } catch (IOException exc) {
                throw new RuntimeException("cannot read split result " + path, exc);
            }
        }

        public static SplitResult loadFromJson(String json) {
            Map<String, Object> payload = MiniJson.parseObject(json);
            @SuppressWarnings("unchecked")
            List<Object> rawOutcomes = (List<Object>) payload.getOrDefault("outcomes", List.of());
            List<CaseOutcome> outcomes = new ArrayList<>();
            for (Object o : rawOutcomes) {
                @SuppressWarnings("unchecked")
                Map<String, Object> om = (Map<String, Object>) o;
                outcomes.add(new CaseOutcome(
                        (String) om.get("case_id"),
                        (String) om.get("split"),
                        (String) om.get("stratum"),
                        (String) om.get("status"),
                        ((Number) om.getOrDefault("score", 0)).doubleValue(),
                        ((Number) om.getOrDefault("duration_s", 0)).doubleValue(),
                        Optional.ofNullable((String) om.get("failure_message")),
                        Optional.ofNullable((String) om.get("artifacts_dir")),
                        Optional.ofNullable((String) om.get("trace_ref"))));
            }
            return new SplitResult(
                    (String) payload.get("split"),
                    (String) payload.get("variant"),
                    (String) payload.get("model"),
                    ((Number) payload.getOrDefault("passed", 0)).intValue(),
                    ((Number) payload.getOrDefault("total", 0)).intValue(),
                    ((Number) payload.getOrDefault("score", 0)).doubleValue(),
                    ((Number) payload.getOrDefault("returncode", 0)).intValue(),
                    (String) payload.get("run_dir"),
                    outcomes);
        }
    }

    /** One outer-loop Deep Agent proposal. */
    public record Proposal(
            List<String> changedSurfaces,
            String workspaceDir,
            String summary,
            Optional<String> finalMessage) {

        public Proposal {
            changedSurfaces = changedSurfaces == null ? List.of() : List.copyOf(changedSurfaces);
        }

        public Map<String, Object> toMap() {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("changed_surfaces", changedSurfaces);
            out.put("workspace_dir", workspaceDir);
            out.put("summary", summary);
            finalMessage.ifPresent(v -> out.put("final_message", v));
            return out;
        }
    }

    /** One candidate evaluation. */
    public record CandidateEvaluation(
            String variant,
            Proposal proposal,
            SplitResult train,
            SplitResult holdout,
            boolean accepted,
            String reason) {

        public int combinedPassed() {
            return train.passed() + holdout.passed();
        }
    }

    /** One optimization iteration. */
    public record IterationRecord(
            int iteration,
            String startingVariant,
            Optional<CandidateEvaluation> candidate) {

        public static IterationRecord ofCandidate(int i, String starting, CandidateEvaluation c) {
            return new IterationRecord(i, starting, Optional.ofNullable(c));
        }
        public static IterationRecord noCandidate(int i, String starting) {
            return new IterationRecord(i, starting, Optional.empty());
        }
    }

    /** Final run report. */
    public record RunReport(
            String createdAt,
            String configPath,
            String model,
            String betterAgentModel,
            Variant baseline,
            Variant finalVariant,
            SplitResult baselineTrain,
            SplitResult baselineHoldout,
            SplitResult finalTrain,
            SplitResult finalHoldout,
            Optional<SplitResult> baselineScorecard,
            Optional<SplitResult> finalScorecard,
            List<IterationRecord> iterations) {

        public Map<String, Object> toMap() {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("created_at", createdAt);
            out.put("config_path", configPath);
            out.put("model", model);
            out.put("better_agent_model", betterAgentModel);
            out.put("baseline", baseline.toMap());
            out.put("final", finalVariant.toMap());
            out.put("baseline_train", baselineTrain.toMap());
            out.put("baseline_holdout", baselineHoldout.toMap());
            out.put("final_train", finalTrain.toMap());
            out.put("final_holdout", finalHoldout.toMap());
            out.put("baseline_scorecard", baselineScorecard.map(SplitResult::toMap).orElse(null));
            out.put("final_scorecard", finalScorecard.map(SplitResult::toMap).orElse(null));
            List<Map<String, Object>> iterMaps = new ArrayList<>();
            for (IterationRecord ir : iterations) {
                Map<String, Object> im = new LinkedHashMap<>();
                im.put("iteration", ir.iteration());
                im.put("starting_variant", ir.startingVariant());
                if (ir.candidate().isPresent()) {
                    CandidateEvaluation c = ir.candidate().get();
                    Map<String, Object> cm = new LinkedHashMap<>();
                    cm.put("variant", c.variant());
                    cm.put("proposal", c.proposal().toMap());
                    cm.put("accepted", c.accepted());
                    cm.put("reason", c.reason());
                    cm.put("train", c.train().toMap());
                    cm.put("holdout", c.holdout().toMap());
                    im.put("candidate", cm);
                } else {
                    im.put("candidate", null);
                }
                iterMaps.add(im);
            }
            out.put("iterations", iterMaps);
            return out;
        }

        public String toMarkdown() {
            StringBuilder sb = new StringBuilder();
            sb.append("# better-harness report\n\n");
            sb.append("- Target model: `").append(model).append("`\n");
            sb.append("- Better-agent model: `").append(betterAgentModel).append("`\n");
            sb.append("- Baseline changed surfaces: `")
                    .append(String.join(", ", baseline.changedSurfaces())).append("`\n");
            sb.append("- Final changed surfaces: `")
                    .append(String.join(", ", finalVariant.changedSurfaces())).append("`\n\n");
            sb.append("| Split | Baseline | Final |\n| --- | --- | --- |\n");
            sb.append("| Train | `")
                    .append(baselineTrain.passed()).append("/").append(baselineTrain.total())
                    .append("` | `")
                    .append(finalTrain.passed()).append("/").append(finalTrain.total()).append("` |\n");
            sb.append("| Holdout | `")
                    .append(baselineHoldout.passed()).append("/").append(baselineHoldout.total())
                    .append("` | `")
                    .append(finalHoldout.passed()).append("/").append(finalHoldout.total()).append("` |\n");
            if (baselineScorecard.isPresent() && finalScorecard.isPresent()) {
                sb.append("| Scorecard | `")
                        .append(baselineScorecard.get().passed()).append("/")
                        .append(baselineScorecard.get().total())
                        .append("` | `")
                        .append(finalScorecard.get().passed()).append("/")
                        .append(finalScorecard.get().total()).append("` |\n");
            }
            sb.append("\n## Iterations\n\n");
            for (IterationRecord ir : iterations) {
                if (ir.candidate().isEmpty()) {
                    sb.append("- Iteration ").append(ir.iteration()).append(": no candidate produced\n");
                    continue;
                }
                CandidateEvaluation c = ir.candidate().get();
                sb.append("- Iteration ").append(ir.iteration())
                        .append(c.accepted() ? ": accepted `" : ": rejected `")
                        .append(c.variant()).append("`\n");
                sb.append("  - Changed surfaces: `")
                        .append(String.join(", ", c.proposal().changedSurfaces())).append("`\n");
                sb.append("  - Train: `").append(c.train().passed())
                        .append("/").append(c.train().total()).append("`\n");
                sb.append("  - Holdout: `").append(c.holdout().passed())
                        .append("/").append(c.holdout().total()).append("`\n");
                sb.append("  - Reason: ").append(c.reason()).append("\n");
            }
            sb.append("\n");
            return sb.toString();
        }

        public void write(Path outputDir) {
            try {
                Files.createDirectories(outputDir);
                Files.writeString(outputDir.resolve("report.json"), MiniJson.toJson(toMap()));
                Files.writeString(outputDir.resolve("report.md"), toMarkdown());
            } catch (IOException exc) {
                throw new RuntimeException("cannot write report " + outputDir, exc);
            }
        }
    }

    /** Filesystem layout for one experiment run. */
    public static final class RunLayout {
        private final Path root;
        public RunLayout(Path root) { this.root = root; }

        public Path root() { return root; }
        public Path variantsDir() { return root.resolve("variants"); }
        public Path visibleRoot() { return root.resolve("history").resolve("visible"); }
        public Path privateRoot() { return root.resolve("history").resolve("private"); }
        public Path visibleIterationsDir() { return visibleRoot().resolve("iterations"); }
        public Path runtimeDir() { return root.resolve("_runtime"); }

        public Path splitDir(String variantKey, String split) {
            Path base = VISIBLE_SPLITS.contains(split) ? visibleRoot() : privateRoot();
            return base.resolve(split).resolve(variantKey);
        }

        public Path variantPath(String variantKey) {
            return variantsDir().resolve(variantKey + ".json");
        }

        public Path proposerWorkspaceDir(int iteration) {
            return visibleIterationsDir().resolve(String.format("%03d", iteration))
                    .resolve("proposer_workspace");
        }

        public Path iterationDir(int iteration) {
            return visibleIterationsDir().resolve(String.format("%03d", iteration));
        }

        public void writeManifest(Experiment experiment) {
            try {
                Files.createDirectories(root);
                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("name", experiment.name());
                payload.put("runner", experiment.runner());
                payload.put("workspace_root", experiment.workspaceRoot().toString());
                payload.put("model", experiment.model());
                payload.put("max_iterations", experiment.maxIterations());
                payload.put("better_agent_model", experiment.betterAgentModel());
                payload.put("better_agent_max_turns", experiment.betterAgentMaxTurns());
                payload.put("better_agent_deepagents_root",
                        experiment.betterAgentDeepagentsRoot() == null
                                ? null : experiment.betterAgentDeepagentsRoot().toString());
                Files.writeString(root.resolve("manifest.json"), MiniJson.toJson(payload));
                writeSplitManifest(experiment, root);
            } catch (IOException exc) {
                throw new RuntimeException("cannot write manifest " + root, exc);
            }
        }

        public void writeIterationDecision(int iteration,
                                            String startingVariant,
                                            Proposal proposal,
                                            CandidateEvaluation candidate) {
            Path iterationDir = iterationDir(iteration);
            try {
                Files.createDirectories(iterationDir);
                String decision = candidate.accepted() ? "accepted" : "rejected";
                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("iteration", iteration);
                payload.put("starting_variant", startingVariant);
                payload.put("candidate_variant", candidate.variant());
                payload.put("decision", decision);
                payload.put("reason", candidate.reason());
                payload.put("changed_surfaces", proposal.changedSurfaces());
                payload.put("train_passed", candidate.train().passed());
                payload.put("train_total", candidate.train().total());
                payload.put("summary", proposal.summary());
                proposal.finalMessage().ifPresent(v -> payload.put("final_message", v));
                Files.writeString(iterationDir.resolve("decision.json"), MiniJson.toJson(payload));

                StringBuilder md = new StringBuilder();
                md.append("# Iteration ").append(iteration).append("\n\n");
                md.append("- Starting variant: `").append(startingVariant).append("`\n");
                md.append("- Candidate variant: `").append(candidate.variant()).append("`\n");
                md.append("- Decision: `").append(decision).append("`\n");
                md.append("- Train: `").append(candidate.train().passed())
                        .append("/").append(candidate.train().total()).append("`\n");
                md.append("- Changed surfaces: `")
                        .append(String.join(", ", proposal.changedSurfaces())).append("`\n");
                md.append("- Reason: ").append(candidate.reason()).append("\n\n");
                md.append("## Proposal Summary\n\n");
                md.append(proposal.summary().isEmpty() ? "_No proposal summary written._"
                        : proposal.summary()).append("\n");
                Files.writeString(iterationDir.resolve("decision.md"), md.toString());
            } catch (IOException exc) {
                throw new RuntimeException("cannot write iteration decision " + iterationDir, exc);
            }
        }

        public void writeReport(RunReport report) {
            report.write(root);
        }
    }

    // -----------------------------------------------------------------
    // Helper functions
    // -----------------------------------------------------------------

    /** Expand {@code ${ENV_VAR}} references in a string. */
    public static String expandEnv(String value) {
        Matcher m = ENV_PATTERN.matcher(value);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String var = m.group(1);
            String replacement = System.getenv(var);
            m.appendReplacement(sb, Matcher.quoteReplacement(replacement == null ? "" : replacement));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /** Normalize one split name and apply aliases. */
    public static String normalizeSplit(String value) {
        return SPLIT_ALIASES.getOrDefault(value, value);
    }

    /** Resolve a path string against the experiment config directory. */
    public static Path resolvePath(Path configPath, String raw) {
        Path p = Path.of(expandEnv(raw)).toAbsolutePath();
        if (!p.isAbsolute()) {
            p = configPath.getParent().resolve(p).toAbsolutePath();
        }
        return p;
    }

    private static List<String> resolveCommandTokens(Path configPath, List<String> tokens) {
        List<String> out = new ArrayList<>();
        for (String token : tokens) {
            String expanded = expandEnv(token);
            Path candidate = configPath.getParent().resolve(expanded);
            if ((expanded.contains("/") || expanded.endsWith(".py")) && Files.exists(candidate)) {
                out.add(candidate.toAbsolutePath().toString());
            } else {
                out.add(expanded);
            }
        }
        return out;
    }

    private static String surfaceFilename(String name, String target, String baseSuffix,
                                          String kind, Map<String, Object> payload) {
        Object override = payload.get("filename");
        if (override != null) return override.toString();
        if ("workspace_file".equals(kind)) {
            return Path.of(target).getFileName().toString();
        }
        String suffix = baseSuffix == null ? ".txt" : baseSuffix;
        return name + suffix;
    }

    // -----------------------------------------------------------------
    // Config loading
    // -----------------------------------------------------------------

    /** Load one experiment config. Mirrors the Python port's {@code load_experiment}. */
    public static Experiment loadExperiment(String path, String modelOverride) {
        Path configPath = Path.of(path).toAbsolutePath();
        Map<String, Object> raw;
        try {
            raw = TinyToml.parse(Files.readString(configPath));
        } catch (IOException exc) {
            throw new RuntimeException("cannot read config " + configPath, exc);
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> experiment = (Map<String, Object>) raw.getOrDefault("experiment", Map.of());
        String runner = (String) experiment.getOrDefault("runner", "pytest");
        @SuppressWarnings("unchecked")
        Map<String, Object> runnerSection = (Map<String, Object>) raw.getOrDefault("runner", Map.of());
        @SuppressWarnings("unchecked")
        Map<String, Object> runnerConfig = new LinkedHashMap<>(
                (Map<String, Object>) runnerSection.getOrDefault(runner, Map.of()));

        if ("pytest".equals(runner)
                && experiment.containsKey("evals_project")
                && !runnerConfig.containsKey("project_root")) {
            runnerConfig.put("project_root", experiment.get("evals_project").toString());
        }
        if ("pytest".equals(runner)) {
            runnerConfig.putIfAbsent("project_root", "libs/evals");
            runnerConfig.putIfAbsent("pytest_args", List.of("-q"));
        } else if ("harbor".equals(runner)) {
            runnerConfig.putIfAbsent("tasks_root", "tasks");
            runnerConfig.putIfAbsent("command", List.of("harbor"));
            runnerConfig.putIfAbsent("extra_args", List.of());
            runnerConfig.putIfAbsent("pass_threshold", 1.0);
        }
        if (runnerConfig.containsKey("command")) {
            @SuppressWarnings("unchecked")
            List<String> tokens = (List<String>) runnerConfig.get("command");
            runnerConfig.put("command", resolveCommandTokens(configPath, tokens));
        }
        for (String key : List.of("project_root", "tasks_root")) {
            if (runnerConfig.containsKey(key)) {
                runnerConfig.put(key, resolvePath(configPath, runnerConfig.get(key).toString()).toString());
            }
        }
        String name = (String) experiment.get("name");
        Path workspaceRoot = resolvePath(configPath, (String) experiment.get("workspace_root"));
        String model = modelOverride != null ? modelOverride : (String) experiment.getOrDefault("model", "default-model");
        int maxIterations = ((Number) experiment.getOrDefault("max_iterations", 3)).intValue();

        @SuppressWarnings("unchecked")
        Map<String, Object> betterAgent = (Map<String, Object>) raw.getOrDefault("better_agent", Map.of());
        String betterAgentModel = (String) betterAgent.getOrDefault("model", model);
        int betterAgentMaxTurns = ((Number) betterAgent.getOrDefault("max_turns", 11000)).intValue();
        Path betterAgentRoot = null;
        Object rawRoot = betterAgent.get("deepagents_root");
        if (rawRoot != null) {
            betterAgentRoot = resolvePath(configPath, rawRoot.toString());
        } else {
            String envRoot = System.getenv("DEEPAGENTS_ROOT");
            if (envRoot != null) {
                betterAgentRoot = Path.of(envRoot).toAbsolutePath();
            }
        }
        String betterAgentSystemPrompt = null;
        Object rawPrompt = betterAgent.get("system_prompt_file");
        if (rawPrompt != null) {
            try {
                betterAgentSystemPrompt = Files.readString(
                        resolvePath(configPath, rawPrompt.toString())).strip();
            } catch (IOException exc) {
                throw new RuntimeException("cannot read system prompt file", exc);
            }
        }
        Map<String, Surface> surfaces = new LinkedHashMap<>();
        @SuppressWarnings("unchecked")
        Map<String, Object> rawSurfaces = (Map<String, Object>) raw.getOrDefault("surfaces", Map.of());
        for (Map.Entry<String, Object> entry : rawSurfaces.entrySet()) {
            String surfaceName = entry.getKey();
            @SuppressWarnings("unchecked")
            Map<String, Object> payload = (Map<String, Object>) entry.getValue();
            String kind = (String) payload.get("kind");
            String target = (String) payload.get("target");
            boolean hasBaseFile = payload.containsKey("base_file");
            boolean hasBaseValue = payload.containsKey("base_value");
            if (hasBaseFile == hasBaseValue) {
                throw new IllegalArgumentException(
                        "surface '" + surfaceName + "' must define exactly one of base_file or base_value");
            }
            String baseValue;
            String baseSuffix;
            if (hasBaseFile) {
                Path baseFile = resolvePath(configPath, (String) payload.get("base_file"));
                try {
                    baseValue = Files.readString(baseFile).strip();
                } catch (IOException exc) {
                    throw new RuntimeException("cannot read surface base file " + baseFile, exc);
                }
                String fileName = baseFile.getFileName().toString();
                int dot = fileName.lastIndexOf('.');
                baseSuffix = dot >= 0 ? fileName.substring(dot) : ".txt";
            } else {
                baseValue = ((String) payload.get("base_value")).strip();
                baseSuffix = null;
            }
            surfaces.put(surfaceName, new Surface(
                    surfaceName, kind, target, baseValue,
                    surfaceFilename(surfaceName, target, baseSuffix, kind, payload)));
        }
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rawCases = (List<Map<String, Object>>) raw.getOrDefault("cases", List.of());
        List<EvalCase> cases = new ArrayList<>();
        for (Map<String, Object> c : rawCases) {
            String caseId = c.containsKey("case_id")
                    ? (String) c.get("case_id")
                    : (String) c.get("nodeid");
            String split = normalizeSplit((String) c.get("split"));
            String stratum = (String) c.get("stratum");
            cases.add(new EvalCase(caseId, split, stratum));
        }
        Experiment loaded = new Experiment(
                configPath, name, runner, workspaceRoot, model, maxIterations,
                betterAgentModel, betterAgentMaxTurns, betterAgentRoot,
                betterAgentSystemPrompt, runnerConfig, surfaces, cases);
        validateExperiment(loaded);
        return loaded;
    }

    /** Validate one experiment config. Mirrors the Python port's {@code validate_experiment}. */
    public static void validateExperiment(Experiment experiment) {
        if (!VALID_RUNNERS.contains(experiment.runner())) {
            throw new IllegalArgumentException("invalid runner " + experiment.runner());
        }
        if (experiment.surfaces().isEmpty()) {
            throw new IllegalArgumentException("config must define at least one surface");
        }
        if (experiment.maxIterations() < 1) {
            throw new IllegalArgumentException("max_iterations must be at least 1");
        }
        if (experiment.betterAgentMaxTurns() < 1) {
            throw new IllegalArgumentException("better_agent.max_turns must be at least 1");
        }
        for (Surface s : experiment.surfaces().values()) {
            if (!VALID_SURFACE_KINDS.contains(s.kind())) {
                throw new IllegalArgumentException("invalid surface kind " + s.kind());
            }
        }
        Set<String> splits = experiment.cases().stream().map(EvalCase::split).collect(Collectors.toSet());
        Set<String> unknown = new LinkedHashSet<>(splits);
        unknown.removeAll(VALID_SPLITS);
        if (!unknown.isEmpty()) {
            throw new IllegalArgumentException("unknown split names: " + new ArrayList<>(unknown));
        }
        for (String split : List.of("train", "holdout")) {
            if (experiment.casesForSplit(split).isEmpty()) {
                throw new IllegalArgumentException("split '" + split + "' must include at least one case");
            }
        }
        List<String> rendered = experiment.cases().stream().map(c -> c.render(experiment.model())).toList();
        Set<String> unique = new LinkedHashSet<>(rendered);
        if (unique.size() != rendered.size()) {
            throw new IllegalArgumentException("rendered case ids must be unique across all splits");
        }
        Set<String> trainStrata = experiment.strataForSplit("train");
        Set<String> holdoutStrata = experiment.strataForSplit("holdout");
        if (!trainStrata.equals(holdoutStrata)) {
            throw new IllegalArgumentException(
                    "train and holdout must cover the same strata; got train="
                            + new ArrayList<>(trainStrata) + " holdout="
                            + new ArrayList<>(holdoutStrata));
        }
        if ("pytest".equals(experiment.runner())
                && experiment.runnerConfig().get("project_root") == null) {
            throw new IllegalArgumentException("pytest runner requires runner.pytest.project_root");
        }
        if ("harbor".equals(experiment.runner())) {
            if (experiment.runnerConfig().get("tasks_root") == null) {
                throw new IllegalArgumentException("harbor runner requires runner.harbor.tasks_root");
            }
            if (experiment.runnerConfig().get("command") == null) {
                throw new IllegalArgumentException("harbor runner requires runner.harbor.command");
            }
        }
    }

    /** Write the split manifest to a directory. */
    public static void writeSplitManifest(Experiment experiment, Path outputDir) {
        try {
            Files.createDirectories(outputDir);
            Map<String, Object> payload = new LinkedHashMap<>();
            for (String split : VALID_SPLITS) {
                List<EvalCase> cases = experiment.casesForSplit(split);
                if (cases.isEmpty()) continue;
                List<Map<String, Object>> items = new ArrayList<>();
                for (EvalCase c : cases) {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("case_id", c.render(experiment.model()));
                    item.put("stratum", c.stratum());
                    items.add(item);
                }
                payload.put(split, items);
            }
            Files.writeString(outputDir.resolve("split.json"), MiniJson.toJson(payload));
            StringBuilder md = new StringBuilder("# Split Manifest\n\n");
            for (Map.Entry<String, Object> entry : payload.entrySet()) {
                md.append("## ").append(capitalize(entry.getKey())).append("\n\n");
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> items = (List<Map<String, Object>>) entry.getValue();
                for (Map<String, Object> item : items) {
                    md.append("- `").append(item.get("stratum")).append("`: `")
                            .append(item.get("case_id")).append("`\n");
                }
                md.append("\n");
            }
            Files.writeString(outputDir.resolve("split.md"), md.toString());
        } catch (IOException exc) {
            throw new RuntimeException("cannot write split manifest", exc);
        }
    }

    private static String capitalize(String s) {
        if (s.isEmpty()) return s;
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    // -----------------------------------------------------------------
    // Trace-ref helpers
    // -----------------------------------------------------------------

    /** Extract URL references from structured payloads and raw logs. */
    public static List<String> extractTraceRefs(Map<String, Object> payload, String stdout, String stderr) {
        Set<String> urls = new LinkedHashSet<>();
        walk(payload, urls);
        urls.addAll(URL_PATTERN.matcher(stdout == null ? "" : stdout).results()
                .map(MatchResult::group).toList());
        urls.addAll(URL_PATTERN.matcher(stderr == null ? "" : stderr).results()
                .map(MatchResult::group).toList());
        return new ArrayList<>(urls).stream().sorted().toList();
    }

    private static void walk(Object value, Set<String> urls) {
        if (value instanceof Map<?, ?> m) {
            for (Object v : m.values()) walk(v, urls);
        } else if (value instanceof List<?> l) {
            for (Object v : l) walk(v, urls);
        } else if (value instanceof String s) {
            Matcher m = URL_PATTERN.matcher(s);
            while (m.find()) urls.add(m.group());
        }
    }

    /** Persist trace references if any were found. */
    public static void writeTraceRefs(Path splitDir, List<String> refs) {
        if (refs == null || refs.isEmpty()) return;
        try {
            Files.createDirectories(splitDir);
            boolean isLangSmith = refs.stream().anyMatch(r -> r.contains("smith.langchain"));
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("provider", isLangSmith ? "langsmith" : "generic");
            payload.put("urls", refs);
            Files.writeString(splitDir.resolve("trace_refs.json"), MiniJson.toJson(payload));
            StringBuilder md = new StringBuilder("# Trace References\n\n");
            for (String r : refs) md.append("- ").append(r).append("\n");
            Files.writeString(splitDir.resolve("trace_refs.md"), md.toString());
        } catch (IOException exc) {
            throw new RuntimeException("cannot write trace refs " + splitDir, exc);
        }
    }

    /** Extract a LangSmith run id from a URL. */
    public static Optional<String> extractLangSmithTraceId(String url) {
        if (url == null || !url.contains("smith.langchain")) return Optional.empty();
        Matcher m = UUID_PATTERN.matcher(url);
        String last = null;
        while (m.find()) last = m.group();
        return Optional.ofNullable(last);
    }

    /** Collect all saved trace reference files under one run. */
    public static List<Map<String, Object>> collectTraceRefs(Path runDir) {
        List<Map<String, Object>> out = new ArrayList<>();
        try (Stream<Path> stream = Files.walk(runDir)) {
            List<Path> files = stream.filter(p -> p.getFileName().toString().equals("trace_refs.json"))
                    .sorted()
                    .toList();
            for (Path p : files) {
                Map<String, Object> payload = MiniJson.parseObject(Files.readString(p));
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("path", p.toString());
                entry.put("provider", payload.getOrDefault("provider", "generic"));
                entry.put("urls", payload.getOrDefault("urls", List.of()));
                out.add(entry);
            }
        } catch (IOException exc) {
            // best-effort
        }
        return out;
    }

    // -----------------------------------------------------------------
    // Run loop
    // -----------------------------------------------------------------

    /** Run the better-harness optimization loop. */
    public static RunReport runExperiment(Experiment experiment, Path outputDir,
                                          int maxIterationsOverride, boolean reuseExisting) {
        BetterHarnessRunners.Runner runner = BetterHarnessRunners.buildRunner(experiment);
        RunLayout layout = new RunLayout(outputDir.toAbsolutePath());
        layout.writeManifest(experiment);

        int iterationLimit = maxIterationsOverride > 0 ? maxIterationsOverride : experiment.maxIterations();
        Variant baseline = BetterHarnessPatching.buildBaselineVariant(experiment);
        Variant current = baseline;
        SplitResult baselineTrain = runner.runSplit(experiment, baseline, "train", layout, reuseExisting);
        SplitResult baselineHoldout = runner.runSplit(experiment, baseline, "holdout", layout, reuseExisting);
        SplitResult currentTrain = baselineTrain;
        SplitResult currentHoldout = baselineHoldout;

        List<IterationRecord> iterations = new ArrayList<>();
        for (int index = 1; index <= iterationLimit; index++) {
            if (currentTrain.passed() == currentTrain.total()
                    && currentHoldout.passed() == currentHoldout.total()) {
                break;
            }
            BetterHarnessAgent.ProposalPair pair = BetterHarnessAgent.proposeVariant(
                    experiment, current, currentTrain, layout, index);
            if (pair == null) {
                iterations.add(IterationRecord.noCandidate(index, current.key()));
                break;
            }
            Proposal proposal = pair.proposal();
            Variant candidateVariant = pair.candidate();
            if (proposal.changedSurfaces().isEmpty()) {
                iterations.add(IterationRecord.noCandidate(index, current.key()));
                break;
            }
            SplitResult train = runner.runSplit(experiment, candidateVariant, "train", layout, reuseExisting);
            SplitResult holdout = runner.runSplit(experiment, candidateVariant, "holdout", layout, reuseExisting);
            int currentCombined = currentTrain.passed() + currentHoldout.passed();
            int candidateCombined = train.passed() + holdout.passed();
            boolean accepted = candidateCombined > currentCombined;
            String reason = accepted
                    ? "improved combined train + holdout pass count"
                    : "did not improve combined train + holdout pass count";
            CandidateEvaluation candidate = new CandidateEvaluation(
                    candidateVariant.key(), proposal, train, holdout, accepted, reason);
            layout.writeIterationDecision(index, current.key(), proposal, candidate);
            iterations.add(IterationRecord.ofCandidate(index, current.key(), candidate));
            if (accepted) {
                current = candidateVariant;
                currentTrain = train;
                currentHoldout = holdout;
            }
        }
        Optional<SplitResult> baselineScorecard = runOptionalScorecard(experiment, runner, baseline, layout, reuseExisting);
        Optional<SplitResult> finalScorecard = runOptionalScorecard(experiment, runner, current, layout, reuseExisting);
        RunReport report = new RunReport(
                Instant.now().toString(),
                experiment.path().toString(),
                experiment.model(),
                experiment.betterAgentModel(),
                baseline,
                current,
                baselineTrain,
                baselineHoldout,
                currentTrain,
                currentHoldout,
                baselineScorecard,
                finalScorecard,
                iterations);
        layout.writeReport(report);
        return report;
    }

    private static Optional<SplitResult> runOptionalScorecard(Experiment experiment,
                                                                BetterHarnessRunners.Runner runner,
                                                                Variant variant,
                                                                RunLayout layout,
                                                                boolean reuseExisting) {
        if (!experiment.hasSplit("scorecard")) return Optional.empty();
        return Optional.ofNullable(runner.runSplit(experiment, variant, "scorecard", layout, reuseExisting));
    }

    /** Build the inventory payload. */
    public static Map<String, Object> inventoryPayload(Experiment experiment) {
        BetterHarnessRunners.Runner runner = BetterHarnessRunners.buildRunner(experiment);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("name", experiment.name());
        out.put("runner", experiment.runner());
        out.put("workspace_root", experiment.workspaceRoot().toString());
        out.put("model", experiment.model());
        out.put("cases", runner.collectInventory(experiment));
        return out;
    }

    // -----------------------------------------------------------------
    // CLI
    // -----------------------------------------------------------------

    /** Build the CLI parser. Mirrors the Python port's {@code build_parser}. */
    public static MiniArgParser buildParser() {
        MiniArgParser parser = new MiniArgParser("Improve an agent harness with a Deep Agent outer loop");
        MiniArgParser.SubCommand validate = parser.addSubcommand("validate", "Validate one experiment config");
        validate.addPositional("config", "path");
        validate.addOption("--model");
        MiniArgParser.SubCommand inventory = parser.addSubcommand("inventory", "List available eval units");
        inventory.addPositional("config", "path");
        inventory.addOption("--model");
        inventory.addOption("--output");
        MiniArgParser.SubCommand split = parser.addSubcommand("split", "Write the configured split manifest");
        split.addPositional("config", "path");
        split.addOption("--model");
        split.addOption("--output-dir", "split");
        MiniArgParser.SubCommand run = parser.addSubcommand("run", "Run the outer Deep Agent optimization loop");
        run.addPositional("config", "path");
        run.addOption("--model");
        run.addOption("--max-iterations", "0");
        run.addFlag("--reuse-existing");
        run.addOption("--output-dir");
        MiniArgParser.SubCommand inspect = parser.addSubcommand("inspect", "Summarize one run directory");
        inspect.addPositional("run_dir", "path");
        MiniArgParser.SubCommand traces = parser.addSubcommand("traces", "List saved local and LangSmith trace refs");
        traces.addPositional("run_dir", "path");
        return parser;
    }

    /** CLI entrypoint. Mirrors the Python port's {@code main}. */
    public static int main(String[] argv) {
        MiniArgParser.Parsed args;
        try {
            args = buildParser().parse(argv == null ? new String[0] : argv);
        } catch (RuntimeException exc) {
            System.err.println("error: " + exc.getMessage());
            return 2;
        }
        if ("inspect".equals(args.command())) {
            Path runDir = Path.of(args.positional(0));
            Path reportPath = runDir.resolve("report.json");
            try {
                System.out.println(Files.readString(reportPath));
                return 0;
            } catch (IOException exc) {
                System.err.println("cannot read " + reportPath);
                return 1;
            }
        }
        if ("traces".equals(args.command())) {
            Path runDir = Path.of(args.positional(0));
            List<Map<String, Object>> refs = collectTraceRefs(runDir);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("count", refs.size());
            out.put("items", refs);
            System.out.println(MiniJson.toJson(out));
            return 0;
        }
        String modelOverride = args.option("--model");
        Experiment experiment;
        try {
            experiment = loadExperiment(args.positional(0), modelOverride);
        } catch (RuntimeException exc) {
            System.err.println("error: " + exc.getMessage());
            return 1;
        }
        if ("validate".equals(args.command())) {
            System.out.println("Config valid: " + experiment.path());
            System.out.println("Runner: " + experiment.runner());
            System.out.println("Workspace: " + experiment.workspaceRoot());
            System.out.println("Model: " + experiment.model());
            System.out.println("Better-agent model: " + experiment.betterAgentModel());
            System.out.println("Surfaces: " + String.join(", ", experiment.surfaces().keySet()));
            System.out.println("Train: " + experiment.casesForSplit("train").size() + " cases");
            System.out.println("Holdout: " + experiment.casesForSplit("holdout").size() + " cases");
            System.out.println("Scorecard: " + experiment.casesForSplit("scorecard").size() + " cases");
            return 0;
        }
        if ("inventory".equals(args.command())) {
            Map<String, Object> payload = inventoryPayload(experiment);
            String output = args.option("--output");
            if (output != null) {
                Path outPath = Path.of(output);
                try {
                    Files.createDirectories(outPath.getParent());
                    Files.writeString(outPath, MiniJson.toJson(payload));
                    System.out.println(outPath);
                } catch (IOException exc) {
                    System.err.println("cannot write " + outPath);
                    return 1;
                }
            } else {
                System.out.println(MiniJson.toJson(payload));
            }
            return 0;
        }
        if ("split".equals(args.command())) {
            Path outputDir = Path.of(args.optionOrDefault("--output-dir", "split"));
            try {
                Files.createDirectories(outputDir);
                writeSplitManifest(experiment, outputDir);
                System.out.println(outputDir.resolve("split.json"));
                return 0;
            } catch (IOException exc) {
                System.err.println("cannot write " + outputDir);
                return 1;
            }
        }
        if ("run".equals(args.command())) {
            Path outputDir = args.option("--output-dir") == null
                    ? Path.of("runs/" + experiment.name())
                    : Path.of(args.option("--output-dir"));
            int maxIters = Integer.parseInt(args.optionOrDefault("--max-iterations", "0"));
            boolean reuse = args.flag("--reuse-existing");
            RunReport report = runExperiment(experiment, outputDir, maxIters, reuse);
            System.out.println(report.toMarkdown());
            return 0;
        }
        System.err.println("unknown command: " + args.command());
        return 2;
    }
}
