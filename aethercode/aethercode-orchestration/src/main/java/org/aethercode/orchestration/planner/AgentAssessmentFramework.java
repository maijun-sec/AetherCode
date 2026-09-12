package org.aethercode.orchestration.planner;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Paper 2512.12791-style 4-dimension agent assessment.
 * <p>
 * Aggregates 4 dimension scores (LLM / Memory / Tools / Environment) into an
 * overall assessment, plus cross-run variance and best/worst run.
 */
public final class AgentAssessmentFramework {

    /** A single agent run. */
    public record AgentRun(
        String id,
        double llmScore,       // 0..1, planning / reflection / correction
        double memoryScore,    // 0..1, episodic / semantic / procedural / forgetting
        double toolsScore,     // 0..1, tool selection / parameters / multi-tool / canary
        double envScore        // 0..1, protocol / security / cost
    ) {
        public AgentRun {
            Objects.requireNonNull(id, "id");
            if (llmScore < 0 || llmScore > 1) throw new IllegalArgumentException("llmScore 0..1");
            if (memoryScore < 0 || memoryScore > 1) throw new IllegalArgumentException("memoryScore 0..1");
            if (toolsScore < 0 || toolsScore > 1) throw new IllegalArgumentException("toolsScore 0..1");
            if (envScore < 0 || envScore > 1) throw new IllegalArgumentException("envScore 0..1");
        }
    }

    /** The aggregated assessment. */
    public record AssessmentReport(
        double llmScore,
        double memoryScore,
        double toolsScore,
        double envScore,
        double overall,
        double variance,
        String bestRun,
        String worstRun,
        Map<String, Double> subMetrics
    ) {
        public double overall() { return overall; }
    }

    private final double llmWeight;
    private final double memoryWeight;
    private final double toolsWeight;
    private final double envWeight;

    public AgentAssessmentFramework() {
        this(0.30, 0.25, 0.25, 0.20);
    }

    public AgentAssessmentFramework(double llm, double memory, double tools, double env) {
        double total = llm + memory + tools + env;
        if (Math.abs(total - 1.0) > 0.001) {
            throw new IllegalArgumentException("weights must sum to 1.0, got " + total);
        }
        this.llmWeight = llm;
        this.memoryWeight = memory;
        this.toolsWeight = tools;
        this.envWeight = env;
    }

    public AssessmentReport assess(List<AgentRun> runs) {
        Objects.requireNonNull(runs, "runs");
        if (runs.isEmpty()) {
            return new AssessmentReport(0, 0, 0, 0, 0, 0, null, null, Map.of());
        }
        double llm = mean(runs, AgentRun::llmScore);
        double mem = mean(runs, AgentRun::memoryScore);
        double tool = mean(runs, AgentRun::toolsScore);
        double env = mean(runs, AgentRun::envScore);
        double overall = llmWeight * llm + memoryWeight * mem + toolsWeight * tool + envWeight * env;

        double var = crossRunVariance(runs);
        String best = findBest(runs);
        String worst = findWorst(runs);

        Map<String, Double> sub = new LinkedHashMap<>();
        sub.put("llmWeight", llmWeight);
        sub.put("memoryWeight", memoryWeight);
        sub.put("toolsWeight", toolsWeight);
        sub.put("envWeight", envWeight);
        sub.put("numRuns", (double) runs.size());

        return new AssessmentReport(llm, mem, tool, env, overall, var, best, worst, Collections.unmodifiableMap(sub));
    }

    /** Cross-run variance: 1 - mean pairwise agreement. Paper 2512.12791 §3.2. */
    public double crossRunVariance(List<AgentRun> runs) {
        if (runs.size() < 2) return 0.0;
        double total = 0;
        int count = 0;
        for (int i = 0; i < runs.size(); i++) {
            for (int j = i + 1; j < runs.size(); j++) {
                double a = runOverall(runs.get(i));
                double b = runOverall(runs.get(j));
                double agreement = 1.0 - Math.abs(a - b);
                total += agreement;
                count++;
            }
        }
        return 1.0 - (total / count);
    }

    private double runOverall(AgentRun r) {
        return llmWeight * r.llmScore()
            + memoryWeight * r.memoryScore()
            + toolsWeight * r.toolsScore()
            + envWeight * r.envScore();
    }

    private static String findBest(List<AgentRun> runs) {
        AgentRun best = runs.get(0);
        for (AgentRun r : runs) {
            if (r.llmScore() + r.memoryScore() + r.toolsScore() + r.envScore()
                > best.llmScore() + best.memoryScore() + best.toolsScore() + best.envScore()) {
                best = r;
            }
        }
        return best.id();
    }

    private static String findWorst(List<AgentRun> runs) {
        AgentRun worst = runs.get(0);
        for (AgentRun r : runs) {
            if (r.llmScore() + r.memoryScore() + r.toolsScore() + r.envScore()
                < worst.llmScore() + worst.memoryScore() + worst.toolsScore() + worst.envScore()) {
                worst = r;
            }
        }
        return worst.id();
    }

    private static double mean(List<AgentRun> runs, java.util.function.ToDoubleFunction<AgentRun> getter) {
        double sum = 0;
        for (AgentRun r : runs) sum += getter.applyAsDouble(r);
        return sum / runs.size();
    }
}
