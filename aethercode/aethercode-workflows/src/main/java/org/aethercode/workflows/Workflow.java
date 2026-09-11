package org.aethercode.workflows;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * One workflow — the parsed form of a YAML file at
 * {@code <UserHome>/.aethercode/workflows/<name>.yaml} or
 * {@code <cwd>/.aethercode/workflows/<name>.yaml}. Schema version 1.
 *
 * <p>Top-level keys:
 * <ul>
 *   <li>{@code version} — schema version. Must be {@code 1}.</li>
 *   <li>{@code name} — kebab-case id, matches the file name.</li>
 *   <li>{@code description} — short human-readable summary.</li>
 *   <li>{@code inputs} — declared inputs (string / number / enum / bool).</li>
 *   <li>{@code skills} — names of skills to compose into the system prompt.</li>
 *   <li>{@code prompts} — ordered list of system/user messages.</li>
 *   <li>{@code todos} — pre-seeded TODO titles (the engine injects them
 *       into the user prompt as a {@code ## Plan} section).</li>
 *   <li>{@code limits} — optional resource caps (wallClock, tokens, …).</li>
 * </ul>
 *
 * <p>Records are immutable; the loader produces a fully validated
 * instance. Mutability in a single place (the limits map) is allowed
 * via {@link #withLimits(Limits)} so callers can override per-run.
 */
public record Workflow(
        int version,
        String name,
        String description,
        Map<String, InputDef> inputs,
        List<String> skills,
        List<PromptDef> prompts,
        List<String> todos,
        Limits limits,
        Map<String, Object> raw
) {

    /** Schema version this code knows about. */
    public static final int CURRENT_VERSION = 1;

    public Workflow {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(description, "description");
        // The remaining lists/maps can be null at the YAML level
        // (the loader normalises them to empty collections so the
        // rest of the engine never has to null-check).
        if (inputs == null)  inputs  = Map.of();
        if (skills == null)  skills  = List.of();
        if (prompts == null) prompts = List.of();
        if (todos == null)   todos   = List.of();
        if (limits == null)  limits  = Limits.empty();
        if (raw == null)     raw     = Map.of();
        // Defensive copies: a record's accessor exposes the underlying
        // collection; an unmodifiable view stops callers from mutating
        // the loader's intermediate state.
        inputs  = Map.copyOf(inputs);
        skills  = List.copyOf(skills);
        prompts = List.copyOf(prompts);
        todos   = List.copyOf(todos);
    }

    /**
     * Return a copy with the given limits (used by {@link WorkflowEngine}
     * to merge per-run overrides into the workflow defaults).
     */
    public Workflow withLimits(Limits newLimits) {
        return new Workflow(version, name, description, inputs, skills,
                prompts, todos,
                newLimits == null ? Limits.empty() : newLimits,
                raw);
    }

    /** Convenience: the {@link PromptDef#role()} for the first
     *  system prompt, or {@code null} if none exists. */
    public PromptDef firstSystemPrompt() {
        for (PromptDef p : prompts) {
            if ("system".equalsIgnoreCase(p.role())) return p;
        }
        return null;
    }

    /** Convenience: all non-system prompts in declaration order. */
    public List<PromptDef> userPrompts() {
        return prompts.stream()
                .filter(p -> !"system".equalsIgnoreCase(p.role()))
                .toList();
    }

    /**
     * Declared input field. Mirrors the YAML shape:
     * <pre>{@code
     *   feature:
     *     type: string | number | enum | bool
     *     required: true
     *     description: "..."
     *     values: [a, b, c]   # only for type=enum
     *     default: 42        # any scalar
     * }</pre>
     */
    public record InputDef(
            String type,
            boolean required,
            String description,
            List<String> values,
            Object defaultValue
    ) {
        public InputDef {
            if (type == null || type.isBlank()) {
                throw new IllegalArgumentException("input type is required");
            }
            if (values == null) values = List.of();
        }

        public static InputDef required(String type, String description) {
            return new InputDef(type, true, description, List.of(), null);
        }

        public static InputDef optional(String type, String description, Object dflt) {
            return new InputDef(type, false, description, List.of(), dflt);
        }
    }

    /**
     * One prompt turn. {@link #content()} may contain
     * {@code {{...}}} placeholders; the engine substitutes them
     * before handing the prompt to the supervisor.
     */
    public record PromptDef(String role, String content) {
        public PromptDef {
            if (role == null || role.isBlank()) {
                throw new IllegalArgumentException("prompt role is required");
            }
            if (content == null) content = "";
        }
    }

    /**
     * Optional resource caps. Each field is nullable so a workflow
     * can set only the ones it cares about. The engine merges
     * workflow defaults with per-run overrides; the supervisor
     * applies the merged map.
     */
    public record Limits(
            Long wallClockMs,
            Long tokens,
            Long calls,
            Long fileWrites,
            Long network
    ) {
        public static Limits empty() {
            return new Limits(null, null, null, null, null);
        }

        /** True when no field is set. */
        public boolean isEmpty() {
            return wallClockMs == null && tokens == null
                    && calls == null && fileWrites == null
                    && network == null;
        }

        /** Render to a map suitable for the supervisor's
         *  {@code task/spawn} {@code limits} field. Null fields
         *  are omitted. */
        public Map<String, Object> toMap() {
            if (isEmpty()) return Map.of();
            Map<String, Object> m = new LinkedHashMap<>();
            if (wallClockMs != null) m.put("wallClockMs", wallClockMs);
            if (tokens != null)      m.put("tokens", tokens);
            if (calls != null)       m.put("calls", calls);
            if (fileWrites != null)  m.put("fileWrites", fileWrites);
            if (network != null)     m.put("network", network);
            return m;
        }
    }
}
