package org.aethercode.evals.benchmarks;

import org.aethercode.engine.springai.SpringAiChatClient;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Smoke test for MiniMax-M3 connectivity. Single task, single call, verify
 * a sensible answer comes back. Run first before the full
 * {@link BenchmarkReportRealLlmTest} so we don't burn 270 calls on a
 * misconfigured provider.
 */
class MinimaxM3SmokeTest {

    @Test
    void minimaxM3AnswersSimpleMMLU() {
        String apiKey = System.getenv("MINIMAX_API_KEY");
        if (apiKey == null || apiKey.isBlank()) {
            System.out.println("[skip] MINIMAX_API_KEY unset");
            return;
        }

        SpringAiChatClient client = new SpringAiChatClient(
            "MiniMax-M3",
            SpringAiChatClient.minimaxDefaults()
        );
        BenchmarkLlmAgent agent = new BenchmarkLlmAgent(client,
            "You are a careful multiple-choice test taker. Reply with exactly one letter A, B, C, or D.");

        // A trivial MMLU-style question with a known answer (B = 4).
        BenchmarkTask task = new BenchmarkTask(
            "smoke-0",
            "Question: What is 2 + 2?\nA. 3\nB. 4\nC. 5\nD. 6\nAnswer:",
            "B",
            List.of("A", "B", "C", "D"),
            java.util.Map.of()
        );

        long s = System.currentTimeMillis();
        String out = agent.run(task);
        long ms = System.currentTimeMillis() - s;

        System.out.printf(Locale.ROOT, "MiniMax-M3 smoke call: %d ms, response='%s'%n", ms, out);
        assertNotNull(out, "response must not be null");
        assertFalse(out.isBlank(), "response must not be blank");

        // accept any of: think block stripped to a single letter, or B mentioned as token.
        // MiniMax-M3 often prepends <think>...</think> blocks; strip them first.
        String stripped = out.replaceAll("(?s)<think>.*?</think>", "").trim();
        char firstUpper = stripped.isEmpty() ? '?' : Character.toUpperCase(stripped.charAt(0));
        boolean pass = "ABCD".indexOf(firstUpper) >= 0
            || out.contains(" B ") || out.endsWith(" B") || out.endsWith("B\n")
            || out.contains("B. ") || out.contains("B)")
            || out.toUpperCase().contains("OPTION B")
            || out.toUpperCase().contains("ANSWER IS B");
        assertTrue(pass,
            "expected response to mention B (4). stripped='" + stripped + "', raw='" + out + "'");
    }
}
