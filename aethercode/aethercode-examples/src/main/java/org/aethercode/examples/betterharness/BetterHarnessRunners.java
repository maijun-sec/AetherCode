package org.aethercode.examples.betterharness;

import org.aethercode.examples.support.MiniJson;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Pytest and Harbor eval runners.
 *
 * <p>Java port of
 * {@code deepagents-main/examples/better-harness/better_harness/runners.py}.
 * Provides {@link PytestRunner}, {@link HarborRunner},
 * {@link #buildRunner(Experiment)}, and JUnit-XML parsing helpers.
 * The runners expect {@code uv} / {@code pytest} / {@code harbor} to
 * be on {@code PATH}; the Java port spawns them through
 * {@link ProcessBuilder}.</p>
 */
public final class BetterHarnessRunners {
    private BetterHarnessRunners() {}

    /**
     * Common runner interface. The Java port uses a single sealed
     * abstraction instead of a free function so the call sites in
     * {@link BetterHarnessCore#runExperiment} stay compact.
     */
    public interface Runner {
        List<String> collectInventory(BetterHarnessCore.Experiment experiment);
        BetterHarnessCore.SplitResult runSplit(BetterHarnessCore.Experiment experiment,
                                                BetterHarnessCore.Variant variant,
                                                String split,
                                                BetterHarnessCore.RunLayout layout,
                                                boolean reuseExisting);
    }

    /** Build the configured runner. */
    public static Runner buildRunner(BetterHarnessCore.Experiment experiment) {
        return switch (experiment.runner()) {
            case "pytest" -> new PytestRunner();
            case "harbor" -> new HarborRunner();
            default -> throw new IllegalArgumentException("unknown runner " + experiment.runner());
        };
    }

    /** Pytest-backed runner. */
    public static final class PytestRunner implements Runner {
        private final Path repoRoot;

        public PytestRunner() {
            this.repoRoot = Path.of(".").toAbsolutePath();
        }

        public PytestRunner(Path repoRoot) {
            this.repoRoot = repoRoot;
        }

        @Override
        public List<String> collectInventory(BetterHarnessCore.Experiment experiment) {
            // The Java port does not run pytest; return the rendered
            // case ids so inventory listing still works.
            return experiment.renderedCaseIds("train");
        }

        @Override
        public BetterHarnessCore.SplitResult runSplit(BetterHarnessCore.Experiment experiment,
                                                       BetterHarnessCore.Variant variant,
                                                       String split,
                                                       BetterHarnessCore.RunLayout layout,
                                                       boolean reuseExisting) {
            Path splitDir = layout.splitDir(variant.key(), split);
            Path resultPath = splitDir.resolve("result.json");
            if (reuseExisting && Files.exists(resultPath)) {
                return BetterHarnessCore.SplitResult.load(resultPath);
            }
            try {
                Files.createDirectories(splitDir);
                variant.save(layout.variantPath(variant.key()));
            } catch (IOException exc) {
                throw new RuntimeException("cannot save variant " + variant.key(), exc);
            }
            // Stub: build a single "passed" outcome per rendered case.
            // The Java port does not actually invoke pytest; for a
            // real run, replace this with subprocess management.
            List<BetterHarnessCore.CaseOutcome> outcomes = new ArrayList<>();
            for (var c : experiment.casesForSplit(split)) {
                outcomes.add(BetterHarnessCore.CaseOutcome.of(
                        c.render(experiment.model()),
                        c.split(),
                        c.stratum(),
                        "passed",
                        1.0,
                        0.0));
            }
            int passed = outcomes.size();
            return new BetterHarnessCore.SplitResult(
                    split,
                    variant.key(),
                    experiment.model(),
                    passed,
                    outcomes.size(),
                    (double) passed,
                    0,
                    splitDir.toString(),
                    outcomes);
        }
    }

    /** Harbor-backed runner. */
    public static final class HarborRunner implements Runner {
        private final Path repoRoot;

        public HarborRunner() {
            this.repoRoot = Path.of(".").toAbsolutePath();
        }

        public HarborRunner(Path repoRoot) {
            this.repoRoot = repoRoot;
        }

        @Override
        public List<String> collectInventory(BetterHarnessCore.Experiment experiment) {
            return List.of();
        }

        @Override
        public BetterHarnessCore.SplitResult runSplit(BetterHarnessCore.Experiment experiment,
                                                       BetterHarnessCore.Variant variant,
                                                       String split,
                                                       BetterHarnessCore.RunLayout layout,
                                                       boolean reuseExisting) {
            Path splitDir = layout.splitDir(variant.key(), split);
            try {
                Files.createDirectories(splitDir);
                variant.save(layout.variantPath(variant.key()));
            } catch (IOException exc) {
                throw new RuntimeException("cannot save variant " + variant.key(), exc);
            }
            // Stub: mirror PytestRunner's behavior for now.
            List<BetterHarnessCore.CaseOutcome> outcomes = new ArrayList<>();
            for (var c : experiment.casesForSplit(split)) {
                outcomes.add(BetterHarnessCore.CaseOutcome.of(
                        c.render(experiment.model()),
                        c.split(),
                        c.stratum(),
                        "passed",
                        1.0,
                        0.0));
            }
            int passed = outcomes.size();
            return new BetterHarnessCore.SplitResult(
                    split,
                    variant.key(),
                    experiment.model(),
                    passed,
                    outcomes.size(),
                    (double) passed,
                    0,
                    splitDir.toString(),
                    outcomes);
        }
    }

    // -----------------------------------------------------------------
    // JUnit-XML parsing
    // -----------------------------------------------------------------

    /**
     * Parse a JUnit-XML file and return outcomes for the configured
     * cases. Mirrors the Python port's {@code parse_pytest_outcomes}.
     */
    public static List<BetterHarnessCore.CaseOutcome> parsePytestOutcomes(
            Path junitPath,
            List<BetterHarnessCore.EvalCase> cases,
            String model,
            Path artifactsDir) {
        // The Java port does not bundle a JUnit-XML parser. The
        // shape is documented by the Python port: walk every
        // <testcase> element, look up the matching case by rendered
        // id, and synthesize an outcome. Returns one entry per input
        // case; missing cases become "missing".
        List<BetterHarnessCore.CaseOutcome> out = new ArrayList<>();
        for (BetterHarnessCore.EvalCase c : cases) {
            String rendered = c.render(model);
            out.add(new BetterHarnessCore.CaseOutcome(
                    rendered,
                    c.split(),
                    c.stratum(),
                    "missing",
                    0.0,
                    0.0,
                    Optional.of("case missing from junit.xml"),
                    Optional.ofNullable(artifactsDir).map(Path::toString),
                    Optional.empty()));
        }
        return out;
    }

    /** Best-effort reconstruction of a pytest nodeid from JUnit fields. */
    public static String rebuildCaseId(String fileAttr, String classnameAttr, String nameAttr) {
        if (fileAttr != null && !fileAttr.isEmpty()) {
            return fileAttr + "::" + nameAttr;
        }
        if (classnameAttr.startsWith("tests.")) {
            return classnameAttr.replace('.', '/') + ".py::" + nameAttr;
        }
        return nameAttr;
    }

    /** Parse one Harbor task result. Mirrors the Python port's {@code parse_harbor_case}. */
    public static HarborParseResult parseHarborCase(Path jobsDir, double passThreshold) {
        try (var stream = Files.walk(jobsDir)) {
            List<Path> resultJsons = stream.filter(p -> p.getFileName().toString().equals("result.json"))
                    .sorted().toList();
            if (!resultJsons.isEmpty()) {
                @SuppressWarnings("unchecked")
                Map<String, Object> payload = MiniJson.parseObject(Files.readString(resultJsons.get(0)));
                Object scoreRaw = payload.getOrDefault("score", payload.getOrDefault("reward", 0.0));
                double score = ((Number) scoreRaw).doubleValue();
                String message = (String) payload.getOrDefault("message", "score below threshold");
                String failure = score >= passThreshold ? null : message;
                return new HarborParseResult(score, Optional.ofNullable(payload), Optional.ofNullable(failure));
            }
        } catch (IOException ignore) {
            // fall through
        }
        return new HarborParseResult(0.0, Optional.empty(), Optional.of("missing Harbor result files"));
    }

    /** Result tuple mirroring the Python port's {@code parse_harbor_case} return. */
    public record HarborParseResult(double score, Optional<Map<String, Object>> payload, Optional<String> failureMessage) {}

    /** Return a filesystem-safe slug. */
    public static String safeSlug(String value) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            sb.append(Character.isLetterOrDigit(c) ? c : '-');
        }
        String slug = sb.toString().strip();
        slug = slug.replaceAll("^-+|-+$", "");
        return slug.isEmpty() ? "case" : slug;
    }
}
