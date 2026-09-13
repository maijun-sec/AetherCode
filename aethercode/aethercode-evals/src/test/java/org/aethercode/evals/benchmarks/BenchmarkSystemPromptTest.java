package org.aethercode.evals.benchmarks;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class BenchmarkSystemPromptTest {

    @Test
    void mmluPromptMentionsOnlyOneLetter() {
        String p = BenchmarkSystemPrompt.MMLU;
        assertTrue(p.contains("one letter"), "MMLU prompt must tell the LLM to reply with one letter");
        assertTrue(p.contains("A, B, C, or D"), "MMLU prompt must list the answer options");
    }

    @Test
    void humanEvalPromptWantsPythonBlock() {
        String p = BenchmarkSystemPrompt.HUMANEVAL;
        assertTrue(p.toLowerCase().contains("python"), "HumanEval prompt must mention python");
        assertTrue(p.contains("```"), "HumanEval prompt must specify markdown code fence");
    }

    @Test
    void sweBenchPromptWantsDiffBlock() {
        String p = BenchmarkSystemPrompt.SWE_BENCH;
        assertTrue(p.toLowerCase().contains("diff"), "SWE-bench prompt must mention diff");
        assertTrue(p.contains("```"), "SWE-bench prompt must specify markdown code fence");
    }

    @Test
    void agentInstructPromptWantsThinkActFormat() {
        String p = BenchmarkSystemPrompt.AGENT_INSTRUCT;
        assertTrue(p.contains("Think:"), "AgentInstruct prompt must mention Think: format");
        assertTrue(p.contains("Act:"),   "AgentInstruct prompt must mention Act: format");
    }

    @Test
    void everyPromptIsShortEnoughForProviderBudgets() {
        // 1 KB is a safe upper bound; most providers allow 4 KB but
        // we keep prompts small so a future addition doesn't blow the
        // budget.
        assertTrue(BenchmarkSystemPrompt.BASE.length()         < 1024);
        assertTrue(BenchmarkSystemPrompt.MMLU.length()         < 1024);
        assertTrue(BenchmarkSystemPrompt.HUMANEVAL.length()     < 1024);
        assertTrue(BenchmarkSystemPrompt.SWE_BENCH.length()     < 1024);
        assertTrue(BenchmarkSystemPrompt.AGENT_INSTRUCT.length() < 1024);
    }

    @Test
    void everyPromptIsProviderAgnosticPlainText() {
        // No provider-specific markup, no JSON, no XML.
        for (String p : new String[]{
            BenchmarkSystemPrompt.BASE,
            BenchmarkSystemPrompt.MMLU,
            BenchmarkSystemPrompt.HUMANEVAL,
            BenchmarkSystemPrompt.SWE_BENCH,
            BenchmarkSystemPrompt.AGENT_INSTRUCT
        }) {
            assertFalse(p.contains("<|"), p + " must not use chat-template tokens");
            assertFalse(p.contains("anthropic"), p + " must not mention a specific provider");
            assertFalse(p.contains("openai"), p + " must not mention a specific provider");
            assertFalse(p.contains("MiniMax"), p + " must not mention a specific provider");
            assertFalse(p.contains("claude"), p + " must not mention a specific provider");
            assertFalse(p.contains("gpt-"), p + " must not mention a specific model family");
        }
    }

    @Test
    void forBenchmarkPicksCorrectPrompt() {
        assertEquals(BenchmarkSystemPrompt.MMLU, BenchmarkSystemPrompt.forBenchmark("MMLU-philosophy"));
        assertEquals(BenchmarkSystemPrompt.HUMANEVAL, BenchmarkSystemPrompt.forBenchmark("HumanEval"));
        assertEquals(BenchmarkSystemPrompt.SWE_BENCH, BenchmarkSystemPrompt.forBenchmark("SWE-bench"));
        assertEquals(BenchmarkSystemPrompt.AGENT_INSTRUCT, BenchmarkSystemPrompt.forBenchmark("AgentInstruct-os"));
        assertEquals(BenchmarkSystemPrompt.BASE, BenchmarkSystemPrompt.forBenchmark("unknown"));
    }

    @Test
    void composeAppendsSuffixAndStaysUnderBudget() {
        String p = BenchmarkSystemPrompt.compose(
            BenchmarkSystemPrompt.MMLU,
            "Extra line 1.",
            "Extra line 2.",
            null,        // null suffix is skipped
            "  "        // blank suffix is skipped
        );
        assertTrue(p.contains("Extra line 1"));
        assertTrue(p.contains("Extra line 2"));
        assertTrue(p.length() < 1024);
    }
}
