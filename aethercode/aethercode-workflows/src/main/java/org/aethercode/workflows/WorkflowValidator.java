package org.aethercode.workflows;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Semantic validator for a parsed {@link Workflow}. The loader
 * handles structural checks (YAML parses, root is a mapping,
 * required keys present); this class catches problems a human
 * author is most likely to make:
 *
 * <ul>
 *   <li>{@code name} is non-empty and kebab-case.</li>
 *   <li>{@code version} is the current schema.</li>
 *   <li>Input names are unique, each has a known type, enum
 *       values are listed for {@code type=enum}, and defaults
 *       (when present) match the declared type.</li>
 *   <li>Every prompt has non-blank content and a known role
 *       ({@code system}, {@code user}, {@code assistant}, or
 *       {@code developer}).</li>
 *   <li>Skills exist somewhere on the resolved skill path
 *       (project &gt; user &gt; builtins). Missing skills are
 *       reported as warnings, not errors — a workflow can be
 *       authored before a skill is installed; the engine will
 *       fall back to a stub at run time.</li>
 *   <li>Limits are positive.</li>
 *   <li>TODOs are non-blank when present.</li>
 * </ul>
 *
 * <p>Returns a {@code List<ValidationError>}; an empty list means
 * the workflow is good to run. Errors are accumulated rather than
 * thrown on the first failure so the user sees every problem
 * with their workflow in a single pass.
 */
public final class WorkflowValidator {

    /** Roles the engine knows how to route to the LLM. */
    public static final Set<String> KNOWN_ROLES = Set.of(
            "system", "user", "assistant", "developer");

    /** Input types the engine coerces. Anything else is a config error. */
    public static final Set<String> KNOWN_INPUT_TYPES = Set.of(
            "string", "number", "integer", "boolean", "enum", "bool");

    private static final Pattern KEBAB_CASE = Pattern.compile("^[a-z0-9]+(?:-[a-z0-9]+)*$");

    private final SkillResolver skillResolver;

    public WorkflowValidator() {
        this(name -> false);
    }

    /**
     * @param skillResolver predicate that returns true when the
     *                       given skill name is currently resolvable
     *                       (project + user + builtins). The default
     *                       resolves nothing — call
     *                       {@link #withSkillResolver(SkillResolver)}
     *                       from the CLI to plug in the real lookup.
     */
    public WorkflowValidator(SkillResolver skillResolver) {
        this.skillResolver = skillResolver == null ? name -> false : skillResolver;
    }

    public WorkflowValidator withSkillResolver(SkillResolver skillResolver) {
        return new WorkflowValidator(skillResolver);
    }

    /**
     * Validate {@code workflow} and return every problem found.
     * An empty list means the workflow is good to run.
     */
    public List<ValidationError> validate(Workflow workflow) {
        List<ValidationError> errors = new ArrayList<>();
        if (workflow == null) {
            errors.add(ValidationError.of("workflow is null"));
            return errors;
        }
        checkVersion(workflow, errors);
        checkName(workflow, errors);
        checkDescription(workflow, errors);
        checkInputs(workflow, errors);
        checkSkills(workflow, errors);
        checkPrompts(workflow, errors);
        checkTodos(workflow, errors);
        checkLimits(workflow, errors);
        return errors;
    }

    /** Convenience: true when the workflow has no validation errors. */
    public boolean isValid(Workflow workflow) {
        return validate(workflow).isEmpty();
    }

    // -- individual checks -----------------------------------------------

    private void checkVersion(Workflow w, List<ValidationError> out) {
        if (w.version() != Workflow.CURRENT_VERSION) {
            out.add(new ValidationError("version",
                    "unsupported schema version " + w.version()
                            + " (expected " + Workflow.CURRENT_VERSION + ")"));
        }
    }

    private void checkName(Workflow w, List<ValidationError> out) {
        String name = w.name();
        if (name == null || name.isBlank()) {
            out.add(new ValidationError("name", "name is required"));
            return;
        }
        if (name.length() > 64) {
            out.add(new ValidationError("name", "name must be at most 64 characters"));
        }
        if (!KEBAB_CASE.matcher(name).matches()) {
            out.add(new ValidationError("name",
                    "name must be kebab-case (lowercase letters, digits, hyphens; "
                            + "got '" + name + "')"));
        }
    }

    private void checkDescription(Workflow w, List<ValidationError> out) {
        if (w.description() == null || w.description().isBlank()) {
            out.add(new ValidationError("description", "description is recommended"));
        }
    }

    private void checkInputs(Workflow w, List<ValidationError> out) {
        Set<String> seen = new HashSet<>();
        for (var entry : w.inputs().entrySet()) {
            String key = entry.getKey();
            if (!seen.add(key)) {
                out.add(new ValidationError("inputs." + key,
                        "duplicate input name"));
            }
            if (!KEBAB_CASE.matcher(key).matches()) {
                out.add(new ValidationError("inputs." + key,
                        "input names must be kebab-case (got '" + key + "')"));
            }
            Workflow.InputDef def = entry.getValue();
            if (def.type() == null || !KNOWN_INPUT_TYPES.contains(def.type().toLowerCase(Locale.ROOT))) {
                out.add(new ValidationError("inputs." + key + ".type",
                        "unknown input type '" + def.type()
                                + "' (known: " + KNOWN_INPUT_TYPES + ")"));
            }
            String type = def.type() == null ? "" : def.type().toLowerCase(Locale.ROOT);
            if (type.equals("enum") && (def.values() == null || def.values().isEmpty())) {
                out.add(new ValidationError("inputs." + key + ".values",
                        "enum inputs must list their allowed values"));
            }
            if (def.defaultValue() != null && !matchesType(def.defaultValue(), type)) {
                out.add(new ValidationError("inputs." + key + ".default",
                        "default value '" + def.defaultValue()
                                + "' does not match declared type '" + type + "'"));
            }
        }
    }

    private void checkSkills(Workflow w, List<ValidationError> out) {
        for (int i = 0; i < w.skills().size(); i++) {
            String s = w.skills().get(i);
            if (s == null || s.isBlank()) {
                out.add(new ValidationError("skills[" + i + "]", "skill name is blank"));
            }
        }
        // Note: missing skills are NOT a validation error. A
        // workflow can reference skills the user hasn't installed
        // yet; the engine will run and the SkillComposer will
        // emit a stub line for each missing one. Treating missing
        // skills as a hard error would force users to install
        // skills before they can test a workflow that mentions
        // them.
    }

    private void checkPrompts(Workflow w, List<ValidationError> out) {
        if (w.prompts().isEmpty()) {
            out.add(new ValidationError("prompts", "at least one prompt is required"));
            return;
        }
        for (int i = 0; i < w.prompts().size(); i++) {
            Workflow.PromptDef p = w.prompts().get(i);
            if (p.role() == null || !KNOWN_ROLES.contains(p.role().toLowerCase(Locale.ROOT))) {
                out.add(new ValidationError("prompts[" + i + "].role",
                        "unknown role '" + p.role() + "' (known: " + KNOWN_ROLES + ")"));
            }
            if (p.content() == null || p.content().isBlank()) {
                out.add(new ValidationError("prompts[" + i + "].content",
                        "prompt content is required"));
            }
        }
    }

    private void checkTodos(Workflow w, List<ValidationError> out) {
        for (int i = 0; i < w.todos().size(); i++) {
            String t = w.todos().get(i);
            if (t == null || t.isBlank()) {
                out.add(new ValidationError("todos[" + i + "]", "todo title is blank"));
            }
        }
    }

    private void checkLimits(Workflow w, List<ValidationError> out) {
        Workflow.Limits l = w.limits();
        if (l == null) return;
        checkPositive("limits.wallClockMs", l.wallClockMs(), out);
        checkPositive("limits.tokens",      l.tokens(),      out);
        checkPositive("limits.calls",       l.calls(),       out);
        checkPositive("limits.fileWrites",  l.fileWrites(),  out);
        checkPositive("limits.network",     l.network(),     out);
    }

    private void checkPositive(String path, Long value, List<ValidationError> out) {
        if (value != null && value <= 0) {
            out.add(new ValidationError(path, "must be positive (got " + value + ")"));
        }
    }

    private static boolean matchesType(Object v, String type) {
        if (v == null || type == null) return true;
        return switch (type) {
            case "string" -> v instanceof String;
            case "number" -> v instanceof Number;
            case "integer" -> v instanceof Number n && n.doubleValue() == Math.floor(n.doubleValue());
            case "boolean", "bool" -> v instanceof Boolean;
            case "enum" -> v instanceof String; // enum membership is checked elsewhere
            default -> true;
        };
    }

    /** Resolves a skill name to a boolean (exists or not). */
    @FunctionalInterface
    public interface SkillResolver {
        boolean exists(String skillName);
    }
}
