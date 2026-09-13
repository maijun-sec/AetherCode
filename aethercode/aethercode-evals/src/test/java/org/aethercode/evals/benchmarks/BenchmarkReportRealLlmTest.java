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
 */
class BenchmarkReportRealLlmTest {

    @Test
    void runRealLlmBenchmarkReport() {
        String apiKey = System.getenv("MINIMAX_API_KEY");
        if (apiKey == null || apiKey.isBlank()) {
            System.out.println("[skip] MINIMAX_API_KEY unset; real-LLM report skipped");
            return;
        }

        SpringAiChatClient client = new SpringAiChatClient(
            "MiniMax-M3",
            SpringAiChatClient.minimaxDefaults()
        );
        BenchmarkLlmAgent agent = new BenchmarkLlmAgent(client);

        int sampleSize = 30;
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
