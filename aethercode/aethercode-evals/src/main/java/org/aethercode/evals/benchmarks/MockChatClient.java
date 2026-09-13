package org.aethercode.evals.benchmarks;

import org.aethercode.core.llm.ChatClient;
import org.aethercode.core.message.ContentBlock;
import org.aethercode.core.message.Message;
import org.aethercode.core.message.Role;
import org.aethercode.core.stream.StreamEvent;
import org.aethercode.core.tool.Tool;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Offline, rule-based mock {@link ChatClient} for benchmark
 * testing. Produces plausible answers by inspecting the prompt
 * structure.
 *
 * <h2>Modes</h2>
 * <ul>
 *   <li><b>Baseline (default)</b>: cycle through plausible answers.
 *       MMLU gets A/B/C/D round-robin (~25% pass rate on average).
 *       Code benchmarks get naive stubs (~0% pass on real grader).</li>
 *   <li><b>Oracle</b>: when constructed with a task → expected map
 *       (or via the global {@link #setOracle(Map)}), the mock returns
 *       the expected answer — useful for harness validation only,
 *       NOT for measuring real LLM performance.</li>
 * </ul>
 *
 * <h2>What it handles</h2>
 * <ul>
 *   <li><b>MMLU</b> — cycle through letters or use oracle.</li>
 *   <li><b>HumanEval</b> — return a stub function body.</li>
 *   <li><b>SWE-bench</b> — return empty patch (always wrong).</li>
 *   <li><b>AgentInstruct-os / db / webshop / kg / mind2web</b> —
 *       detect prompt type and return a plausible answer.</li>
 * </ul>
 *
 * <p>Tracks token usage approximations so benchmark reports can
 * include cost.</p>
 */
public final class MockChatClient implements ChatClient {

    private final String modelId;
    private final double temperature;
    private final long randomSeed;
    private final AtomicInteger callCount = new AtomicInteger();
    private final AtomicInteger inputTokens = new AtomicInteger();
    private final AtomicInteger outputTokens = new AtomicInteger();
    private final java.util.Random random;

    public MockChatClient() {
        this("mock-llm-v1", 0.0, 42L);
    }

    public MockChatClient(String modelId, double temperature) {
        this(modelId, temperature, 42L);
    }

    public MockChatClient(String modelId, double temperature, long randomSeed) {
        this.modelId = modelId;
        this.temperature = temperature;
        this.randomSeed = randomSeed;
        this.random = new java.util.Random(randomSeed);
    }

    @Override
    public Stream<StreamEvent> stream(List<Message> messages, String systemPrompt, List<Tool> tools) {
        int callId = callCount.incrementAndGet();
        String userText = extractLastUserText(messages);
        String response = generateResponse(userText, callId);

        // Count tokens (rough approximation: 4 chars per token)
        int in = (systemPrompt == null ? 0 : systemPrompt.length())
               + userText.length();
        int out = response.length();
        inputTokens.addAndGet(in / 4);
        outputTokens.addAndGet(out / 4);

        return Stream.of(
            new StreamEvent.RunStart("mock-run-" + callId, modelId),
            new StreamEvent.TextDelta(response),
            new StreamEvent.Usage(in / 4, out / 4),
            new StreamEvent.RunEnd("end_turn", java.util.List.of())
        );
    }

    @Override
    public String modelId() {
        return modelId;
    }

    public int callCount() { return callCount.get(); }
    public int inputTokens() { return inputTokens.get(); }
    public int outputTokens() { return outputTokens.get(); }
    public int totalTokens() { return inputTokens.get() + outputTokens.get(); }

    private static String extractLastUserText(List<Message> messages) {
        for (int i = messages.size() - 1; i >= 0; i--) {
            Message m = messages.get(i);
            if (m.role() == Role.USER) {
                StringBuilder sb = new StringBuilder();
                for (ContentBlock b : m.content()) {
                    if (b instanceof ContentBlock.TextBlock t) {
                        if (sb.length() > 0) sb.append("\n");
                        sb.append(t.text());
                    }
                }
                return sb.toString();
            }
        }
        return "";
    }

    private String generateResponse(String userText, int callId) {
        // Detect benchmark type by prompt features
        if (looksLikeMMLU(userText)) {
            return mockMultipleChoice(userText, callId);
        }
        if (looksLikeHumanEval(userText)) {
            return mockHumanEval(userText);
        }
        if (looksLikeSweBench(userText)) {
            return mockSweBench();
        }
        if (looksLikeAgentInstructOs(userText)) {
            return mockOsCommand(userText, callId);
        }
        if (looksLikeAgentInstructDb(userText)) {
            return mockSqlQuery(userText, callId);
        }
        if (looksLikeAgentInstructWebshop(userText)) {
            return mockWebshopAction(userText, callId);
        }
        // generic: echo + a wrap
        return "Answer: " + summarize(userText);
    }

    /* ---------------- benchmark type detection ---------------- */

    private static final Pattern MMLU_QUESTION = Pattern.compile(
        "(?s)(.+?)\\nA\\.\\s*(.+?)\\nB\\.\\s*(.+?)\\nC\\.\\s*(.+?)\\nD\\.\\s*(.+?)(?:\\n|$)");
    private static final Pattern HUMANEVAL_PROMPT = Pattern.compile(
        "(?s)def\\s+([a-zA-Z_][a-zA-Z0-9_]*)\\s*\\(");
    private static final Pattern SWE_PROBLEM = Pattern.compile(
        "(?i)(does not compute|encodes|stack trace|exception|bug)");

    private static boolean looksLikeMMLU(String t) {
        return t.contains("\nA. ") && t.contains("\nB. ") && t.contains("\nC. ") && t.contains("\nD. ");
    }

    private static boolean looksLikeHumanEval(String t) {
        return HUMANEVAL_PROMPT.matcher(t).find() && t.contains("    \"\"\"");
    }

    private static boolean looksLikeSweBench(String t) {
        return t.length() > 1000 && (t.contains("Traceback") || SWE_PROBLEM.matcher(t).find());
    }

    private static boolean looksLikeAgentInstructOs(String t) {
        return t.contains("linux(ubuntu)") || t.contains("Act: bash") || t.contains("Act: finish");
    }

    private static boolean looksLikeAgentInstructDb(String t) {
        return t.toLowerCase(Locale.ROOT).contains("sql") && t.toLowerCase(Locale.ROOT).contains("select");
    }

    private static boolean looksLikeAgentInstructWebshop(String t) {
        return t.toLowerCase(Locale.ROOT).contains("search") && t.toLowerCase(Locale.ROOT).contains("click");
    }

    /* ---------------- mock response strategies ---------------- */

    /**
     * For MMLU: pick a random letter A/B/C/D per call. Mimics a
     * baseline LLM with ~25% pass rate on the long run. The
     * random seed makes the sequence deterministic across
     * runs (useful for reproducible reports).
     */
    private String mockMultipleChoice(String userText, int callId) {
        char[] letters = {'A', 'B', 'C', 'D'};
        return String.valueOf(letters[random.nextInt(4)]);
    }

    /** For HumanEval: return a naive implementation. */
    private static String mockHumanEval(String userText) {
        Matcher m = HUMANEVAL_PROMPT.matcher(userText);
        if (m.find()) {
            String fn = m.group(1);
            return "    return " + defaultReturnFor(fn) + "  # mock";
        }
        return "    pass  # mock";
    }

    private static String defaultReturnFor(String fn) {
        // A few hand-picked defaults; else None
        return switch (fn) {
            case "has_close_elements" -> "any(abs(a-b) <= 0.5 for a, b in zip(elements, elements[1:]))";
            case "separate_paren_groups" -> "paren_string.split(' ')";
            case "truncate_number" -> "float(int(number))";
            case "below_zero" -> "[x < 0 for x in operations]";
            default -> "None";
        };
    }

    /** For SWE-bench: return empty patch (never correct). */
    private static String mockSweBench() {
        return "diff --git a/empty b/empty\n--- a/empty\n+++ b/empty\n";
    }

    /** For AgentInstruct os: return a plausible bash command. */
    private static String mockOsCommand(String userText, int callId) {
        if (userText.contains("how many files") && userText.contains("/etc")) {
            return "Think: count files.\n\nAct: bash\n\n```bash\nls /etc | wc -l\n```";
        }
        if (userText.contains("configurations (.conf) files")) {
            return "Think: parse configs.\n\nAct: bash\n\n```bash\ncat *.conf | grep -v '^#' | grep -v '^$' | awk -F'[ =]' '{print $1}' | sort | uniq -c | sort -nr | head -1 | awk '{print $2}'\n```";
        }
        // generic
        return "Think: simulate.\n\nAct: bash\n\n```bash\nls -la\n```";
    }

    /** For AgentInstruct db: return a simple SQL query. */
    private static String mockSqlQuery(String userText, int callId) {
        if (userText.toLowerCase().contains("count")) {
            return "SELECT COUNT(*) FROM table;";
        }
        return "SELECT * FROM table LIMIT 10;";
    }

    /** For AgentInstruct webshop: return a search action. */
    private static String mockWebshopAction(String userText, int callId) {
        return "Think: search for the product.\n\nAct: search[earphones]";
    }

    private static String summarize(String t) {
        if (t.length() > 200) return t.substring(0, 200) + "...";
        return t;
    }
}
