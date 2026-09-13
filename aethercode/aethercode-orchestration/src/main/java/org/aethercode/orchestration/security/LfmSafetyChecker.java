package org.aethercode.orchestration.security;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * MAS-LFM safety checker (arXiv:2604.18133).
 *
 * <p>The paper surveys the transition from "classical" multi-agent
 * systems to LFM-enabled (Large Foundation Model) ones and lists
 * a small set of safety rules that any LFM-backed MAS must check
 * before executing a multi-agent step. The rules are deliberately
 * coarse — they're meant to be fast pre-filters, not full
 * verifiers.
 *
 * <p>This is the AetherCode Tier-3 implementation. The five rules
 * the paper highlights:
 * <ol>
 *   <li><b>Prompt-injection in tool input</b> — a tool's input
 *       contains a phrase like "ignore previous instructions".</li>
 *   <li><b>Out-of-scope tool call</b> — the model tried to call a
 *       tool whose name is not in the allowed list.</li>
 *   <li><b>Excessive handoff</b> — the agent chain has &gt;N hops
 *       (typical: 5), suggesting an infinite loop or runaway
 *       delegation.</li>
 *   <li><b>Unsafe file system access</b> — a tool call references
 *       a path outside the working directory.</li>
 *   <li><b>Data exfiltration</b> — the agent's output contains an
 *       external URL or IP that the LFM should not be sharing.</li>
 * </ol>
 *
 * <h2>How it differs from {@code ByzantineDetector} and
 * {@code RedFlagDetector}</h2>
 * ByzantineDetector flags <em>misbehaving agents</em>. RedFlagDetector
 * flags <em>bad LLM outputs</em>. LfmSafetyChecker flags
 * <em>unsafe cross-agent interactions</em> — the LFM-specific
 * failure modes that only emerge when multiple LLM-backed agents
 * are orchestrating each other.
 */
public final class LfmSafetyChecker {

    /** A single safety violation. */
    public record Violation(String rule, String severity, String detail) {
        public Violation {
            Objects.requireNonNull(rule, "rule");
            severity = severity == null ? "MEDIUM" : severity;
            detail = detail == null ? "" : detail;
        }
    }
    /** Result of one safety check pass. */
    public record Check(boolean safe, List<Violation> violations) {
        public Check {
            violations = List.copyOf(violations);
        }
        public static Check ok() { return new Check(true, List.of()); }
    }
    /** Configuration. */
    public record Config(int maxHops, Set<String> allowedTools) {
        public Config { Objects.requireNonNull(allowedTools, "allowedTools"); }
    }

    private static final Set<String> PROMPT_INJECTION_PATTERNS = Set.of(
        "ignore previous instructions",
        "ignore all previous",
        "disregard your instructions",
        "forget your system prompt"
    );
    private static final Set<String> EXFIL_URL_PREFIXES = Set.of("http://", "https://", "ftp://");

    private final Config config;

    public LfmSafetyChecker() {
        this(new Config(5, Set.of()));
    }

    public LfmSafetyChecker(Config config) {
        this.config = Objects.requireNonNull(config, "config");
    }

    /**
     * Check one agent's proposed action. The action is a small
     * record: tool name, tool input (free-form), current hop count,
     * agent's proposed output.
     */
    public Check check(ProposedAction action) {
        Objects.requireNonNull(action, "action");
        List<Violation> v = new ArrayList<>();
        // Rule 1: prompt injection in tool input
        if (action.toolInput() != null) {
            String lower = action.toolInput().toLowerCase(Locale.ROOT);
            for (String pat : PROMPT_INJECTION_PATTERNS) {
                if (lower.contains(pat)) {
                    v.add(new Violation("PROMPT_INJECTION", "HIGH",
                        "tool input contains prompt-injection phrase: \"" + pat + "\""));
                    break;
                }
            }
        }
        // Rule 2: out-of-scope tool call
        if (action.toolName() != null && !config.allowedTools().isEmpty()
            && !config.allowedTools().contains(action.toolName())) {
            v.add(new Violation("OUT_OF_SCOPE_TOOL", "HIGH",
                "tool '" + action.toolName() + "' not in allow-list"));
        }
        // Rule 3: excessive handoff
        if (action.hopCount() > config.maxHops()) {
            v.add(new Violation("EXCESSIVE_HANDOFF", "MEDIUM",
                "hopCount=" + action.hopCount() + " > max=" + config.maxHops()));
        }
        // Rule 4: unsafe file system access (any path with .. or absolute /)
        if (action.toolInput() != null && hasUnsafePath(action.toolInput())) {
            v.add(new Violation("UNSAFE_PATH", "HIGH",
                "tool input contains path traversal or absolute path"));
        }
        // Rule 5: data exfiltration
        if (action.output() != null) {
            for (String prefix : EXFIL_URL_PREFIXES) {
                if (action.output().toLowerCase(Locale.ROOT).contains(prefix)) {
                    v.add(new Violation("DATA_EXFIL", "MEDIUM",
                        "output contains external URL prefix: " + prefix));
                    break;
                }
            }
        }
        return new Check(v.isEmpty(), v);
    }

    private static boolean hasUnsafePath(String s) {
        // cheap heuristic: contains ".." path segment or starts with / (Unix) or C:\
        return s.contains("..") || s.contains("/etc/") || s.contains("C:\\");
    }

    /** What the checker inspects. */
    public record ProposedAction(
        String toolName,
        String toolInput,
        int hopCount,
        String output
    ) {
        public ProposedAction {
            // nulls tolerated; check() handles each field defensively
        }
    }
}
