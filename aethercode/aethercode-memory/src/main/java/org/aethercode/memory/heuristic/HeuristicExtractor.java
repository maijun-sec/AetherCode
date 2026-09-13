package org.aethercode.memory.heuristic;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Paper 2603.24639 (ERL, Illuin) — Experiential Reflective Learning.
 * <p>
 * Extracts reusable heuristics from a single agent trajectory
 * (steps + outcomes). Heuristics are higher-level rules that
 * transfer across tasks, in contrast to raw trajectories that
 * are over-fitted to the specific episode.
 * <p>
 * The extractor is intentionally a small, deterministic,
 * LLM-free implementation: it pattern-matches on step
 * outcomes and emits candidate heuristics. The 3 families:
 * <ul>
 *   <li><b>STRATEGY</b> — a success-pattern ("when X then Y")</li>
 *   <li><b>RECOVERY</b> — a failure-recovery pattern ("if Y fails, try Z")</li>
 *   <li><b>AVOIDANCE</b> — a failure-mode ("don't do W in this context")</li>
 * </ul>
 * <p>
 * The extraction quality is bounded — real systems should wire
 * an LLM in production to refine the candidate rules. The
 * deterministic version is enough for a Tier-3 paper-compat
 * "sketch" that demonstrates the shape and is unit-testable.
 *
 * @see <a href="https://arxiv.org/abs/2603.24639">ERL paper</a>
 */
public final class HeuristicExtractor {

    /** A step in the agent trajectory. */
    public record Step(
        String description,
        Outcome outcome,
        String errorMessage
    ) {
        public Step {
            Objects.requireNonNull(description, "description");
            Objects.requireNonNull(outcome, "outcome");
        }
    }

    /** Outcome of one trajectory step. */
    public enum Outcome { SUCCESS, FAILURE, PARTIAL }

    /** The 3 heuristic families. */
    public enum Kind { STRATEGY, RECOVERY, AVOIDANCE }

    /** A single extracted heuristic. */
    public record Heuristic(
        Kind kind,
        String rule,
        String applicability,
        int sourceEpisodeSteps,
        int confidence  // 0..100, simple occurrence-based score
    ) {
        public Heuristic {
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(rule, "rule");
            Objects.requireNonNull(applicability, "applicability");
        }
    }

    /** The full extraction result. */
    public record ExtractionResult(
        List<Heuristic> heuristics,
        int totalSteps,
        int successSteps,
        int failureSteps
    ) {}

    /** Matches "when <cond> then <act>" patterns. */
    private static final Pattern WHEN_THEN = Pattern.compile(
        "(?i)\\bwhen\\s+([^.;\\n]+?)\\s+then\\s+([^.;\\n]+?)(?:[.;\\n]|$)");

    /** Matches "if <cond> fails[ then] <recovery>". */
    private static final Pattern IF_FAILS = Pattern.compile(
        "(?i)\\bif\\s+([^.;\\n]+?)\\s+fails?,?\\s+(?:then\\s+)?([^.;\\n]+?)(?:[.;\\n]|$)");

    /** Matches "don't/avoid <something>" at the start of a step. */
    private static final Pattern DONT_IN = Pattern.compile(
        "(?i)\\b(?:don't|do not|avoid|never)\\s+([^.\\n;]+)");

    /**
     * Extract heuristics from a trajectory.
     *
     * @param trajectory the ordered list of steps; may be empty
     * @return an extraction result with 0..N heuristics and stats
     */
    public ExtractionResult extract(List<Step> trajectory) {
        Objects.requireNonNull(trajectory, "trajectory");
        List<Heuristic> out = new ArrayList<>();
        int succ = 0, fail = 0;
        for (Step s : trajectory) {
            if (s.outcome() == Outcome.SUCCESS) succ++;
            else if (s.outcome() == Outcome.FAILURE) fail++;
        }

        // Strategy patterns: extract from SUCCESS steps
        for (Step s : trajectory) {
            if (s.outcome() != Outcome.SUCCESS) continue;
            addMatches(out, s.description(), WHEN_THEN, Kind.STRATEGY,
                m -> "when " + m.group(1).trim() + " then " + m.group(2).trim(),
                succ);
        }

        // Recovery patterns: extract from FAILURE steps
        for (Step s : trajectory) {
            if (s.outcome() != Outcome.FAILURE) continue;
            addMatches(out, s.description(), IF_FAILS, Kind.RECOVERY,
                m -> "if " + m.group(1).trim() + " fails, " + m.group(2).trim(),
                fail);
        }

        // Avoidance patterns: extract from FAILURE steps too
        for (Step s : trajectory) {
            if (s.outcome() != Outcome.FAILURE) continue;
            addMatches(out, s.description(), DONT_IN, Kind.AVOIDANCE,
                m -> "avoid " + m.group(1).trim(),
                fail);
        }

        return new ExtractionResult(out, trajectory.size(), succ, fail);
    }

    private static void addMatches(
        List<Heuristic> out, String text, Pattern p, Kind kind,
        java.util.function.Function<Matcher, String> formatter, int episodeSize) {
        Matcher m = p.matcher(text);
        while (m.find()) {
            String rule = formatter.apply(m);
            // Confidence: episode size as a rough proxy. Larger
            // episodes imply a more thoroughly-tested rule. Capped
            // at 100.
            int conf = Math.min(100, episodeSize * 10);
            out.add(new Heuristic(
                kind,
                rule,
                // applicability defaults to the heuristic kind
                // (caller can refine via LLM in production)
                kind.name().toLowerCase(),
                episodeSize,
                conf
            ));
        }
    }

    /** Convenience: extract from a list of (description, outcome, error) tuples. */
    public ExtractionResult extractFromTuples(List<Map.Entry<String, String>> stepOutcomes) {
        Objects.requireNonNull(stepOutcomes, "stepOutcomes");
        List<Step> steps = new ArrayList<>(stepOutcomes.size());
        for (var e : stepOutcomes) {
            String desc = e.getKey();
            String tag = e.getValue() == null ? "SUCCESS" : e.getValue().toUpperCase();
            Outcome outcome;
            try {
                outcome = Outcome.valueOf(tag);
            } catch (IllegalArgumentException ex) {
                outcome = Outcome.PARTIAL;
            }
            steps.add(new Step(desc, outcome, null));
        }
        return extract(steps);
    }
}
