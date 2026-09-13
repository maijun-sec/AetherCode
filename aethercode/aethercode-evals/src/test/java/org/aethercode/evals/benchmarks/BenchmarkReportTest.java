package org.aethercode.evals.benchmarks;

import org.aethercode.core.message.ContentBlock;
import org.aethercode.core.message.Message;
import org.aethercode.core.message.Role;
import org.aethercode.core.stream.StreamEvent;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end benchmark report across all loaded benchmarks.
 * <p>
 * Runs the {@link MockChatClient} (deterministic, rule-based) as
 * a stand-in for the real LLM, against every benchmark adapter
 * that has data. Produces a single table summarizing:
 * <ul>
 *   <li>benchmark name</li>
 *   <li>tasks attempted</li>
 *   <li>passed / pass@1</li>
 *   <li>average latency (ms per task)</li>
 *   <li>total LLM calls</li>
 *   <li>approximate token usage</li>
 * </ul>
 *
 * <h2>Why "MockChatClient" instead of real LLM</h2>
 * Real LLM calls require an API key. The mock is rule-based and
 * gives a "baseline" pass rate that a real LLM should beat. To
 * run with a real LLM, swap in {@code new SpringAiChatClient(...)}
 * or your provider's client.
 *
 * <h2>Why this matters</h2>
 * This is the "see our Agent's effect" the user asked for. The
 * table below shows which benchmarks are tractable for the
 * baseline agent and which need prompt engineering / better tools.
 * The mock achieves:
 * <ul>
 *   <li>MMLU: ~25% (random A/B/C/D cycling)</li>
 *   <li>HumanEval: ~0% (naive impls, no test execution)</li>
 *   <li>SWE-bench: 0% (empty patches)</li>
 *   <li>AgentInstruct-os: ~50% (mock bash commands for common patterns)</li>
 *   <li>AgentInstruct-db: ~50% (simple SQL queries)</li>
 * </ul>
 */
class BenchmarkReportTest {

    static Path resolve(String name) {
        Path p1 = Paths.get("reference", "benchmarks", name);
        if (Files.isDirectory(p1)) return p1;
        return Paths.get("D:/work/workspace/idea/engine/AetherCode/reference/benchmarks", name);
    }

    /** package-private alias so other test classes (real-LLM, smoke, …) can reuse. */
    static Path resolveBenchmarkDir(String name) { return resolve(name); }

    private static String abbreviate(String s, int n) {
        if (s == null) return "(null)";
        s = s.replace("\n", "\\n");
        if (s.length() > n) return s.substring(0, n) + "...";
        return s;
    }

    @Test
    void runFullBenchmarkReport() {
        // List of (display name, factory)
        record BenchDef(String displayName, String dir, Function<Path, BenchmarkAdapter> factory) {}
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

        MockChatClient client = new MockChatClient("mock-baseline", 0.0);
        BenchmarkLlmAgent agent = new BenchmarkLlmAgent(client);

        int sampleSize = 30;  // larger sample for statistical stability on MMLU
        List<RunResult> results = new ArrayList<>();
        for (BenchDef d : defs) {
            Path dir = resolve(d.dir);
            if (!Files.isDirectory(dir)) continue;
            BenchmarkAdapter adapter = d.factory.apply(dir);
            if (adapter.size() == 0) continue;
            int n = Math.min(sampleSize, adapter.size());
            int passed = 0;
            long totalLatency = 0;
            for (int i = 0; i < n; i++) {
                BenchmarkTask task = adapter.loadAll().get(i);
                long s = System.currentTimeMillis();
                String out = agent.run(task);
                totalLatency += System.currentTimeMillis() - s;
                boolean ok = adapter.grade(task, out);
                if (ok) passed++;
                if (i < 3 || ok) {
                    System.out.printf(Locale.ROOT,
                        "    [%s] task %d: expected=%s, got='%s' -> %s%n",
                        d.displayName, i, abbreviate(task.expectedOutput(), 20),
                        abbreviate(out, 30), ok ? "PASS" : "FAIL");
                }
            }
            double rate = n == 0 ? 0.0 : (double) passed / n;
            long avg = n == 0 ? 0 : totalLatency / n;
            results.add(new RunResult(d.displayName, n, passed, rate, avg));
        }

        // Print the report
        System.out.println();
        System.out.println("=".repeat(78));
        System.out.println("AetherCode End-to-End Benchmark Report");
        System.out.println("Agent: MockChatClient (deterministic rule-based baseline)");
        System.out.println("Sample: first " + sampleSize + " tasks per benchmark");
        System.out.println("=".repeat(78));
        System.out.printf(Locale.ROOT, "%-28s %8s %10s %12s %14s%n",
            "Benchmark", "Attempt", "Pass@1", "Avg ms", "Total LLM");
        System.out.println("-".repeat(78));
        int totalAttempt = 0, totalPass = 0, totalCalls = 0;
        for (RunResult r : results) {
            System.out.printf(Locale.ROOT, "%-28s %8d %9.1f%% %12d %14d%n",
                r.benchmarkName(),
                r.tasksAttempted(),
                r.passRate() * 100,
                r.avgLatencyMs(),
                client.callCount());
            totalAttempt += r.tasksAttempted();
            totalPass += r.passed();
            totalCalls = client.callCount();
        }
        System.out.println("-".repeat(78));
        double overall = totalAttempt == 0 ? 0.0 : (double) totalPass / totalAttempt;
        System.out.printf(Locale.ROOT, "%-28s %8d %9.1f%% %12s %14d%n",
            "TOTAL", totalAttempt, overall * 100, "-", totalCalls);
        System.out.println("=".repeat(78));
        System.out.printf("Total tokens: %d (in: %d, out: %d)%n",
            client.totalTokens(), client.inputTokens(), client.outputTokens());
        System.out.println();

        // sanity: we should have run at least 5 benchmarks
        assertTrue(results.size() >= 5,
            "expected at least 5 benchmark results; got " + results.size());
        for (RunResult r : results) {
            assertTrue(r.passRate() >= 0.0 && r.passRate() <= 1.0,
                "pass rate out of range: " + r);
        }
    }

    @Test
    void mockChatClientHandlesAllBenchmarkTypes() {
        MockChatClient client = new MockChatClient();

        // MMLU
        String mmlu = "Question: What is 2+2?\nA. 3\nB. 4\nC. 5\nD. 6";
        String out = stream(client, mmlu);
        assertTrue(out.matches("[A-D]"),
            "MMLU mock should produce a single letter A-D: " + out);

        // HumanEval
        String he = "def has_close_elements(numbers: List[float], threshold: float) -> bool:\n    \"\"\"Check if any two are closer than threshold.\"\"\"";
        out = stream(client, he);
        assertTrue(out.contains("return"),
            "HumanEval mock should produce a return: " + out);

        // AgentInstruct os
        String os = "You are an assistant that will act like a person, I'll play the role of linux(ubuntu) operating system. ... Act: bash";
        out = stream(client, os);
        assertTrue(out.toLowerCase().contains("bash") || out.toLowerCase().contains("act"),
            "OS mock should produce a bash action: " + out);

        // Token tracking
        assertTrue(client.callCount() >= 3);
        assertTrue(client.totalTokens() > 0);
    }

    private static String stream(MockChatClient client, String prompt) {
        return client.stream(
            List.of(new Message(null, Role.USER,
                List.of(new ContentBlock.TextBlock(prompt)), null, null)),
            "system", List.of()
        ).map(e -> e instanceof StreamEvent.TextDelta t ? t.text() : "")
         .reduce("", (a, b) -> a + b);
    }

    /** Local copy of RunResult (was nested in BenchmarkEndToEndRunTest). */
    record RunResult(
        String benchmarkName,
        int tasksAttempted,
        int passed,
        double passRate,
        long avgLatencyMs
    ) {}
}
