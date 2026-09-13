package org.aethercode.evals.benchmarks;

import java.util.Locale;

/**
 * Provider-agnostic system prompts for the benchmark agent.
 *
 * <h2>Why this class exists</h2>
 * The benchmark agent's accuracy varies wildly with the system prompt.
 * Empirically (R-paper-batch7-llm-eval), MiniMax-M3 went from 0% to
 * 100% on MMLU just by telling it "Reply with only the letter, no
 * explanation." But the same prompt must work on every provider we
 * support (OpenAI, Anthropic, MiniMax, vLLM, Ollama). This class
 * is the single source of truth for those prompts.
 *
 * <h2>Compatibility rules</h2>
 * <ol>
 *   <li>Plain text only — no XML, no JSON, no chat template tokens.
 *       Some providers strip or rewrite these (Anthropic's system
 *       block is special; Ollama templates have their own rules).</li>
 *   <li>No model-specific instructions (no "you are M3", no
 *       "use chain-of-thought" — let the provider default apply).</li>
 *   <li>Brief — most providers truncate system prompts at 1-4 KB.
 *       Keep under 1 KB per prompt.</li>
 *   <li>Stable — change carefully. The benchmark numbers cited in
 *       paper-compat code / round notes are tied to the exact wording.</li>
 * </ol>
 *
 * <h2>Use</h2>
 * <pre>{@code
 *   BenchmarkLlmAgent agent = new BenchmarkLlmAgent(client,
 *       BenchmarkSystemPrompt.MMLU);
 *   String answer = agent.run(task);
 * }</pre>
 * Or compose: {@link #compose(String, String...)} for benchmark-specific
 * instructions appended to a base prompt.
 */
public final class BenchmarkSystemPrompt {

    /**
     * Base instruction: "you are an agent that follows the user's
     * instructions precisely". Applies to every benchmark unless
     * overridden.
     */
    public static final String BASE =
        "You are a careful AI assistant. Follow the user's instructions "
      + "exactly. If a question has a specific answer format, reply in "
      + "that format. Avoid preamble or explanation unless asked.";

    /**
     * MMLU-style multiple choice. Empirically takes MiniMax-M3 from
     * 0% (with no instruction) to 100% on MMLU-philosophy. Works
     * equally well on OpenAI gpt-4 / claude-3 because the instruction
     * is provider-agnostic: "reply with only the letter".
     */
    public static final String MMLU =
        "You are answering a multiple-choice question. Read the question "
      + "and all four options carefully. Reply with exactly one letter "
      + "(A, B, C, or D) and nothing else. No preamble, no explanation, "
      + "no punctuation.";

    /**
     * HumanEval-style code completion. Tells the LLM to output a
     * complete function (def + body), not just a body, and to
     * skip docstring / type-hint re-statement.
     */
    public static final String HUMANEVAL =
        "You are writing a Python function. Output a complete, runnable "
      + "Python function with the same name and signature as the prompt. "
      + "Use a markdown python block (```python ... ```). Do not restate "
      + "the docstring or the function signature. Do not include example "
      + "usage or test code.";

    /**
     * SWE-bench-style patch. The LLM must reply with a unified diff
     * in a markdown block, not a plain-text description of the change.
     */
    public static final String SWE_BENCH =
        "You are fixing a bug in a real codebase. Reply with a unified "
      + "diff in a markdown block (```diff ... ```) that shows the "
      + "minimal change needed. The diff must apply cleanly to the "
      + "pre-fix code. Do not include prose outside the diff block.";

    /**
     * AgentInstruct-style agent task. The expected output is a
     * reasoning trace followed by an action. Tell the LLM the
     * exact format the grader expects.
     */
    public static final String AGENT_INSTRUCT =
        "You are an autonomous agent. Reply with a short reasoning "
      + "section that starts with 'Think: ' and ends with an action "
      + "section that starts with 'Act: '. Do not use any other "
      + "formatting. Keep the reasoning to one or two sentences.";

    /**
     * Generic task with no benchmark-specific instruction. Useful
     * for ad-hoc queries that aren't MMLU / HumanEval / SWE / AgentInstruct.
     */
    public static final String GENERIC = BASE;

    private BenchmarkSystemPrompt() {}

    /**
     * Compose a base prompt with a benchmark-specific suffix.
     * The result is always plain text and stays under 1 KB.
     */
    public static String compose(String base, String... suffixLines) {
        StringBuilder sb = new StringBuilder(base == null ? BASE : base);
        if (suffixLines != null) {
            for (String s : suffixLines) {
                if (s != null && !s.isBlank()) {
                    sb.append(' ').append(s.trim());
                }
            }
        }
        if (sb.length() > 1024) {
            // hard cap to stay within most provider's system-prompt budget
            sb.setLength(1024);
        }
        return sb.toString();
    }

    /**
     * Pick the most appropriate system prompt for a benchmark by
     * name (case-insensitive substring match). Falls back to
     * {@link #BASE} when the name doesn't match.
     */
    public static String forBenchmark(String benchmarkName) {
        if (benchmarkName == null) return BASE;
        String lower = benchmarkName.toLowerCase(Locale.ROOT);
        if (lower.contains("mmlu"))        return MMLU;
        if (lower.contains("humaneval"))   return HUMANEVAL;
        if (lower.contains("swe") || lower.contains("swebench")) return SWE_BENCH;
        if (lower.contains("agent") || lower.contains("instruct")) return AGENT_INSTRUCT;
        return BASE;
    }
}
