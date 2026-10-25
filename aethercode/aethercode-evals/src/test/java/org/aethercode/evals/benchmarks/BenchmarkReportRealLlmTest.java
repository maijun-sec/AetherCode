package org.aethercode.evals.benchmarks;

import org.aethercode.engine.springai.SpringAiChatClient;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Real-LLM end-to-end benchmark report. Same shape as
 * {@link BenchmarkReportTest} but swaps the {@link MockChatClient} for
 * {@link SpringAiChatClient} targeting MiniMax-M3 (the AetherCode default
 * provider via {@code MINIMAX_API_KEY}).
 *
 * <h2>Why this test exists</h2>
 * {@link MockChatClient} gives a 3.0% baseline (MMLU ~25%, everything else
 * 0% due to format mismatch). Real LLM should beat that on most tasks.
 * Skip when {@code MINIMAX_API_KEY} is unset so CI without secrets still
 * passes.
 *
 * <h2>Cost</h2>
 * ~270 tasks × ~1k tokens each ≈ 270k input tokens + ~50k output tokens.
 * Run on demand, not on every CI.
 *
 * <p><b>R382: opt-in via {@code -Daethercode.evals.realLlm=true}.</b>
 * The default build must not silently spend money or 20 minutes, so this
 * report only runs when the flag is set (and {@code MINIMAX_API_KEY} is
 * present). The two single-call smoke tests next to it
 * ({@link MinimaxM3SmokeTest}, {@link EngineQueryRealLlmTest}) stay on the
 * env-var gate — one call each is cheap and worth the signal.
 */
class BenchmarkReportRealLlmTest {

    @Test
    void runRealLlmBenchmarkReport() {
        // R382: this benchmark is OPT-IN.
        //
        // It issues ~45 real LLM calls (~270k input tokens) across 9
        // benchmarks, so it needs network access, a paid key, and ~20
        // minutes. Leaving it in the default `mvn install` made every
        // build slow, non-hermetic and quietly expensive — and it stalled
        // the 0.2.72 packaging build for 20 minutes straight.
        //
        // The class javadoc already said "Run on demand, not on every CI";
        // this makes the code match that intent. Opt in with:
        //
        //   mvn -pl aethercode-evals test -Daethercode.evals.realLlm=true
        if (!Boolean.parseBoolean(System.getProperty("aethercode.evals.realLlm", "false"))) {
            System.out.println("[skip] real-LLM report is opt-in: "
                    + "re-run with -Daethercode.evals.realLlm=true");
            return;
        }
        String apiKey = System.getenv("MINIMAX_API_KEY");
        if (apiKey == null || apiKey.isBlank()) {
            System.out.println("[skip] MINIMAX_API_KEY unset; real-LLM report skipped");
            return;
        }

        SpringAiChatClient client = new SpringAiChatClient(
            "MiniMax-M3",
            SpringAiChatClient.minimaxDefaults()
        );
        int sampleSize = 5;
        record BenchDef(String name, String dir,
                        java.util.function.Function<java.nio.file.Path, BenchmarkAdapter> factory) {}
        List<BenchDef> defs = List.of(
            new BenchDef("HumanEval",        "openai_openai_humaneval", HumanEvalAdapter::new),
            new BenchDef("MMLU-philosophy", "mmlu-philosophy",         p -> new MMLUAdapter(p, "philosophy")),
            new BenchDef("SWE-bench",        "swe-bench-verified",      SweBenchAdapter::new),
            new BenchDef("AgentInstruct-os",        "agentinstruct-os",        p -> new AgentInstructAdapter(p, "os")),
            new BenchDef("AgentInstruct-db",        "agentinstruct-db",        p -> new AgentInstructAdapter(p, "db")),
            new BenchDef("AgentInstruct-alfworld",  "agentinstruct-alfworld",  p -> new AgentInstructAdapter(p, "alfworld")),
            new BenchDef("AgentInstruct-webshop",  "agentinstruct-webshop",  p -> new AgentInstructAdapter(p, "webshop")),
            new BenchDef("AgentInstruct-kg",        "agentinstruct-kg",        p -> new AgentInstructAdapter(p, "kg")),
            new BenchDef("AgentInstruct-mind2web",  "agentinstruct-mind2web",  p -> new AgentInstructAdapter(p, "mind2web"))
        );
        // R-paper-batch7-grading: use benchmark-specific system prompts
        // so the LLM replies in the format each grader expects.
        // The default BenchmarkLlmAgent uses a generic prompt that
        // hurts pass@1 on MMLU (need just-letter) and HumanEval
        // (need a python block). We override per-benchmark below.
        java.util.Map<String, BenchmarkLlmAgent> agentByBenchmark = new java.util.HashMap<>();
        for (BenchDef d : defs) {
            agentByBenchmark.put(d.name,
                new BenchmarkLlmAgent(client, BenchmarkSystemPrompt.forBenchmark(d.name)));
        }

        int totalPass = 0, totalAttempt = 0;
        System.out.println();
        System.out.println("=".repeat(78));
        System.out.println("AetherCode Real-LLM (MiniMax-M3) Benchmark Report");
        System.out.println("Sample: first " + sampleSize + " tasks per benchmark");
        System.out.println("=".repeat(78));
        System.out.printf(Locale.ROOT, "%-28s %8s %10s%n", "Benchmark", "Attempt", "Pass@1");
        System.out.println("-".repeat(78));

        for (BenchDef d : defs) {
            java.nio.file.Path dir = BenchmarkReportTest.resolveBenchmarkDir(d.dir);
            if (!java.nio.file.Files.isDirectory(dir)) continue;
            BenchmarkAdapter adapter = d.factory.apply(dir);
            if (adapter.size() == 0) continue;
            BenchmarkLlmAgent agent = agentByBenchmark.get(d.name);
            int n = Math.min(sampleSize, adapter.size());
            int passed = 0;
            for (int i = 0; i < n; i++) {
                BenchmarkTask task = adapter.loadAll().get(i);
                String out = agent.run(task);
                boolean ok = adapter.grade(task, out);
                if (ok) passed++;
                String shortOut = out.length() > 60 ? out.substring(0, 60) + "..." : out;
                shortOut = shortOut.replace("\n", "\\n");
                System.out.printf(Locale.ROOT, "    [%s] task %d: expected=%s, got='%s' -> %s%n",
                    d.name, i, task.expectedOutput(), shortOut, ok ? "PASS" : "FAIL");
            }
            double rate = n == 0 ? 0.0 : (double) passed / n;
            System.out.printf(Locale.ROOT, "%-28s %8d %9.1f%%%n",
                d.name, n, rate * 100);
            totalAttempt += n;
            totalPass += passed;
        }
        System.out.println("-".repeat(78));
        double overall = totalAttempt == 0 ? 0.0 : (double) totalPass / totalAttempt;
        System.out.printf(Locale.ROOT, "%-28s %8d %9.1f%%%n",
            "TOTAL", totalAttempt, overall * 100);
        System.out.println("=".repeat(78));
        System.out.println();

        assertTrue(totalAttempt >= 5, "expected at least 5 task attempts; got " + totalAttempt);
    }
}
