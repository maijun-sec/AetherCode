package org.aethercode.core.output;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * output style. Modelled on the TS {@code outputStyles/} loader. The style
 * is a system-prompt suffix plus a stream-event filter that affects how
 * {@link org.aethercode.tui.MessageRenderer} paints the output. The four built-ins:
 *
 * <ul>
 *   <li>{@link #DEFAULT} — verbose explanations, friendly tone, good default</li>
 *   <li>{@link #TERSE} — short, code-first, no narration</li>
 *   <li>{@link #EXPLANATORY} — longer reasoning, like a teacher walking through</li>
 *   <li>{@link #JSON} — emits structured JSON envelopes around tool calls (for scripts)</li>
 * </ul>
 *
 * <p>Callers may register custom styles via {@link Builder}.
 */
public final class OutputStyle {

    public static final String DEFAULT_ID = "default";
    public static final String TERSE_ID = "terse";
    public static final String EXPLANATORY_ID = "explanatory";
    public static final String JSON_ID = "json";

    public static final OutputStyle DEFAULT = new OutputStyle(DEFAULT_ID,
            "Be concise and direct. Use code blocks for commands or paths.");
    public static final OutputStyle TERSE = new OutputStyle(TERSE_ID,
            "Reply with the absolute minimum. No prose. No greetings. No summaries. Code only.");
    public static final OutputStyle EXPLANATORY = new OutputStyle(EXPLANATORY_ID,
            "Explain your reasoning before every tool call. Walk the user through the trade-offs " +
            "of each decision. Include one short paragraph of context after each step.");
    public static final OutputStyle JSON = new OutputStyle(JSON_ID,
            "For each tool call, output a JSON envelope: {\"tool\":<name>,\"input\":{...}}. " +
            "For text responses, wrap the answer in {\"type\":\"text\",\"content\":\"...\"}.");

    private static final Map<String, OutputStyle> REGISTRY = new LinkedHashMap<>();
    static {
        REGISTRY.put(DEFAULT_ID, DEFAULT);
        REGISTRY.put(TERSE_ID, TERSE);
        REGISTRY.put(EXPLANATORY_ID, EXPLANATORY);
        REGISTRY.put(JSON_ID, JSON);
    }

    private final String id;
    private final String systemPromptSuffix;

    public OutputStyle(String id, String systemPromptSuffix) {
        this.id = id;
        this.systemPromptSuffix = systemPromptSuffix;
    }

    public String id() { return id; }
    public String systemPromptSuffix() { return systemPromptSuffix; }

    /** register a custom style. Overwrites any previous entry under the same id. */
    public static OutputStyle register(String id, String suffix) {
        OutputStyle s = new OutputStyle(id, suffix);
        REGISTRY.put(id, s);
        return s;
    }

    public static OutputStyle byId(String id) {
        OutputStyle s = REGISTRY.get(id);
        if (s == null) throw new IllegalArgumentException("unknown output style: " + id);
        return s;
    }

    public static OutputStyle byIdOrDefault(String id) {
        if (id == null) return DEFAULT;
        OutputStyle s = REGISTRY.get(id);
        return s == null ? DEFAULT : s;
    }

    public static java.util.Set<String> knownIds() { return REGISTRY.keySet(); }

    public static class Builder {
        private String id = DEFAULT_ID;
        public Builder id(String i) { this.id = i; return this; }
        public OutputStyle build(String suffix) { return register(id, suffix); }
    }
}
