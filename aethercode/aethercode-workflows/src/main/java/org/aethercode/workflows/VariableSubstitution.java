package org.aethercode.workflows;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Resolves {@code {{...}}} placeholders inside a workflow's prompt
 * and todo content. The supported grammar is intentionally tiny so
 * the user can read the substitution rules off a single screen:
 *
 * <ul>
 *   <li>{@code {{inputs.X}} — a workflow input value. The
 *       {@code inputs} prefix is the only required one; the engine
 *       fails loudly if the input is missing or has no value.</li>
 *   <li>{@code {{cwd}} — the absolute path of the working
 *       directory the workflow is running in.</li>
 *   <li>{@code {{date}} — today, ISO-8601 ({@code yyyy-MM-dd}).</li>
 *   <li>{@code {{os}} — {@code "windows"}, {@code "macos"},
 *       {@code "linux"}, or {@code "other"} (lowercased).</li>
 * </ul>
 *
 * <p>Missing inputs throw a {@link WorkflowParserException} so the
 * caller sees the offending placeholder rather than a silently
 * blank string. The other variables are always defined.
 */
public final class VariableSubstitution {

    /** Matcher for {@code {{ name }}} (whitespace inside the braces
     *  is tolerated; the canonical form is {@code {{name}}}). */
    private static final Pattern PLACEHOLDER =
            Pattern.compile("\\{\\{\\s*([a-zA-Z][a-zA-Z0-9_.]*)\\s*}}");

    /** The only top-level prefixes the engine understands. */
    public static final String PREFIX_INPUTS = "inputs";
    public static final String VAR_CWD = "cwd";
    public static final String VAR_DATE = "date";
    public static final String VAR_OS = "os";

    private final Map<String, Object> inputs;
    private final String cwd;
    private final String date;
    private final String os;

    public VariableSubstitution(Map<String, Object> inputs, String cwd) {
        this(inputs, cwd, LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE), detectOs());
    }

    /** Full constructor — exposed for tests that need to pin the
     *  date / os values. */
    public VariableSubstitution(Map<String, Object> inputs, String cwd,
                                String date, String os) {
        this.inputs = inputs == null ? Map.of() : new LinkedHashMap<>(inputs);
        this.cwd = cwd == null ? "" : cwd;
        this.date = date == null ? "" : date;
        this.os = os == null ? "" : os;
    }

    /** Resolve every {@code {{...}}} in {@code template}. Throws
     *  {@link WorkflowParserException} for unknown placeholders or
     *  missing input values. */
    public String substitute(String template) {
        if (template == null || template.isEmpty()) return template;
        Matcher m = PLACEHOLDER.matcher(template);
        StringBuilder out = new StringBuilder(template.length());
        int last = 0;
        while (m.find()) {
            out.append(template, last, m.start());
            String key = m.group(1);
            out.append(resolve(key));
            last = m.end();
        }
        if (last == 0) {
            // No placeholders at all — return the original string
            // unchanged so the caller gets the same instance (small
            // but useful for tests).
            return template;
        }
        out.append(template, last, template.length());
        return out.toString();
    }

    /**
     * Walk every {@code {{...}}} in {@code template} and return the
     * names that appear. Useful for a "lint" pass that warns when
     * a placeholder has no matching input.
     */
    public java.util.Set<String> referencedPlaceholders(String template) {
        java.util.LinkedHashSet<String> out = new java.util.LinkedHashSet<>();
        if (template == null) return out;
        Matcher m = PLACEHOLDER.matcher(template);
        while (m.find()) out.add(m.group(1));
        return out;
    }

    /** Resolve a single placeholder. Public for unit tests. */
    public String resolve(String key) {
        Objects.requireNonNull(key, "key");
        if (key.startsWith(PREFIX_INPUTS + ".")) {
            String inputName = key.substring(PREFIX_INPUTS.length() + 1);
            if (!inputs.containsKey(inputName)) {
                throw new WorkflowParserException(
                        "workflow references undefined input '"
                                + inputName + "' (no value supplied at run time)");
            }
            Object v = inputs.get(inputName);
            if (v == null) {
                throw new WorkflowParserException(
                        "workflow input '" + inputName + "' is null");
            }
            return String.valueOf(v);
        }
        return switch (key) {
            case VAR_CWD -> cwd;
            case VAR_DATE -> date;
            case VAR_OS -> os;
            default -> throw new WorkflowParserException(
                    "unknown placeholder '{{" + key + "}}' "
                            + "(supported: inputs.<name>, " + VAR_CWD + ", "
                            + VAR_DATE + ", " + VAR_OS + ")");
        };
    }

    /** Render the substitution context as a string map. Useful for
     *  the engine's debug log when a placeholder fails. */
    public Map<String, String> context() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put(VAR_CWD, cwd);
        m.put(VAR_DATE, date);
        m.put(VAR_OS, os);
        for (var e : inputs.entrySet()) {
            m.put(PREFIX_INPUTS + "." + e.getKey(),
                    e.getValue() == null ? "" : String.valueOf(e.getValue()));
        }
        return m;
    }

    private static String detectOs() {
        String name = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (name.contains("win")) return "windows";
        if (name.contains("mac") || name.contains("darwin")) return "macos";
        if (name.contains("nix") || name.contains("nux") || name.contains("aix")) return "linux";
        return "other";
    }
}
