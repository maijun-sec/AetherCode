package org.aethercode.workflows;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * The high-level entry point for "run this workflow". Pulls the
 * four collaborator pieces together:
 *
 * <ol>
 *   <li>{@link WorkflowLoader} — parses the YAML.</li>
 *   <li>{@link WorkflowValidator} — semantic checks.</li>
 *   <li>{@link SkillComposer} — turns the workflow's
 *       {@code skills} list into a system-prompt-suffix.</li>
 *   <li>{@link VariableSubstitution} — resolves
 *       {@code {{...}}} placeholders.</li>
 * </ol>
 *
 * <p>After substitution the engine composes the final prompt
 * (system + user messages + a {@code ## Plan} section that
 * pre-seeds the TODOs), then hands it to a {@link SessionSpawner}
 * — the supervisor in production, a fake in tests.
 *
 * <p>The engine does not own state. {@code run(...)} is the only
 * public method; everything else is package-private plumbing.
 */
public final class WorkflowEngine {

    private static final Logger LOG = LoggerFactory.getLogger(WorkflowEngine.class);

    private final WorkflowLoader loader;
    private final WorkflowValidator validator;
    private final SkillComposer skillComposer;
    private final SessionSpawner spawner;
    private final Path userHome;

    public WorkflowEngine(WorkflowLoader loader,
                          WorkflowValidator validator,
                          SkillComposer skillComposer,
                          SessionSpawner spawner) {
        this(loader, validator, skillComposer, spawner,
                Path.of(System.getProperty("user.home")));
    }

    public WorkflowEngine(WorkflowLoader loader,
                          WorkflowValidator validator,
                          SkillComposer skillComposer,
                          SessionSpawner spawner,
                          Path userHome) {
        this.loader = Objects.requireNonNull(loader, "loader");
        this.validator = Objects.requireNonNull(validator, "validator");
        this.skillComposer = Objects.requireNonNull(skillComposer, "skillComposer");
        this.spawner = Objects.requireNonNull(spawner, "spawner");
        this.userHome = userHome == null
                ? Path.of(System.getProperty("user.home")) : userHome;
    }

    /** Build a default engine rooted at {@code cwd} that uses a
     *  caller-supplied spawner. Skill resolution and the bundled
     *  workflows both look at {@code cwd} for project-local
     *  resources. */
    public static WorkflowEngine forCwd(Path cwd, SessionSpawner spawner) {
        return new WorkflowEngine(
                new WorkflowLoader(),
                new WorkflowValidator(),
                SkillComposer.forCwd(cwd),
                spawner);
    }

    /**
     * Load, validate, and run a workflow. Steps:
     * <ol>
     *   <li>Resolve the YAML on disk (project dir → user dir →
     *       bundled resources).</li>
     *   <li>Parse + validate. Validation errors throw
     *       {@link WorkflowParserException} with a multi-line
     *       message so the user can fix every issue at once.</li>
     *   <li>Coerce caller inputs into the declared types, fill
     *       in defaults, drop unknowns (with a warning).</li>
     *   <li>Compose the skill suffix and inject it into the first
     *       system prompt (or a new one if the workflow is
     *       user-only).</li>
     *   <li>Substitute {@code {{...}}} placeholders everywhere.</li>
     *   <li>Build the final prompt and hand it to the spawner.</li>
     * </ol>
     *
     * @param name  the workflow name (e.g. {@code "tdd-feature"})
     * @param rawInputs the caller-supplied input values, keyed by
     *                   workflow input name. Missing required
     *                   inputs and unknown types throw.
     * @param cwd   the working directory the session will run in
     * @param model optional model id; null lets the supervisor pick
     */
    public SessionRef run(String name,
                          Map<String, Object> rawInputs,
                          Path cwd,
                          String model) {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(cwd, "cwd");
        if (!WorkflowPaths.isValidName(name)) {
            throw new WorkflowParserException("invalid workflow name: " + name);
        }
        Workflow wf = resolveWorkflow(name, cwd);
        List<ValidationError> errors = validator.validate(wf);
        if (!errors.isEmpty()) {
            throw new WorkflowParserException(formatErrors(name, errors));
        }
        Map<String, Object> inputs = coerceInputs(wf, rawInputs == null ? Map.of() : rawInputs);
        Map<String, Object> meta = buildConfigMetadata(wf, inputs);
        // Substitute the prompts and todos. The skill suffix
        // composes the system prompt before substitution so a
        // skill body can contain {{cwd}} etc.
        String skillSuffix = skillComposer.compose(wf.skills());
        VariableSubstitution sub = new VariableSubstitution(inputs, cwd.toAbsolutePath().toString());
        String prompt = composePrompt(wf, skillSuffix, sub);
        String planBlock = composePlanBlock(wf, sub);
        String finalPrompt = planBlock.isEmpty() ? prompt : prompt + "\n\n" + planBlock;

        SessionRef ref = spawner.spawn(finalPrompt, cwd.toString(), model, meta);
        LOG.info("workflow {} -> session {}", name, ref.shortLabel());
        return ref;
    }

    // -- helpers ---------------------------------------------------------

    private Workflow resolveWorkflow(String name, Path cwd) {
        WorkflowPaths paths = new WorkflowPaths(userHome, cwd);
        Path file = paths.resolveFile(name);
        if (file != null) {
            return loader.load(file);
        }
        // Fall back to the bundled resource (the four shipped
        // workflows live in src/main/resources/workflows/).
        String resource = "workflows/" + name + ".yaml";
        try {
            return loader.loadResource(resource);
        } catch (WorkflowParserException notFound) {
            throw new WorkflowParserException(
                    "workflow '" + name + "' not found "
                            + "(looked in " + paths.projectDir() + " and "
                            + paths.userDir() + " and the bundled resources)");
        }
    }

    private Map<String, Object> coerceInputs(Workflow wf, Map<String, Object> raw) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (var e : wf.inputs().entrySet()) {
            String name = e.getKey();
            Workflow.InputDef def = e.getValue();
            Object v = raw.get(name);
            if (v == null) v = def.defaultValue();
            if (v == null && def.required()) {
                throw new WorkflowParserException(
                        "workflow '" + wf.name() + "' requires input '"
                                + name + "' (no value supplied and no default set)");
            }
            if (v != null) {
                out.put(name, coerce(name, v, def));
            }
        }
        // Unknown inputs are kept (callers sometimes pass extras
        // for downstream tools) but logged so the user notices.
        for (String supplied : raw.keySet()) {
            if (!wf.inputs().containsKey(supplied)) {
                LOG.warn("workflow '{}': ignoring unknown input '{}'",
                        wf.name(), supplied);
            }
        }
        return out;
    }

    private static Object coerce(String name, Object v, Workflow.InputDef def) {
        if (v == null) return null;
        return switch (def.type().toLowerCase(Locale.ROOT)) {
            case "string" -> v.toString();
            case "number" -> {
                if (v instanceof Number n) yield n.doubleValue();
                try { yield Double.parseDouble(v.toString()); }
                catch (NumberFormatException nfe) {
                    throw new WorkflowParserException(
                            "input '" + name + "' expected number, got '" + v + "'");
                }
            }
            case "integer" -> {
                if (v instanceof Number n) yield n.longValue();
                try { yield Long.parseLong(v.toString()); }
                catch (NumberFormatException nfe) {
                    throw new WorkflowParserException(
                            "input '" + name + "' expected integer, got '" + v + "'");
                }
            }
            case "boolean", "bool" -> {
                if (v instanceof Boolean b) yield b;
                String s = v.toString().trim().toLowerCase(Locale.ROOT);
                if (s.equals("true") || s.equals("yes") || s.equals("1")) yield true;
                if (s.equals("false") || s.equals("no") || s.equals("0")) yield false;
                throw new WorkflowParserException(
                        "input '" + name + "' expected boolean, got '" + v + "'");
            }
            case "enum" -> {
                String s = v.toString();
                if (!def.values().isEmpty() && !def.values().contains(s)) {
                    throw new WorkflowParserException(
                            "input '" + name + "' must be one of " + def.values()
                                    + " (got '" + s + "')");
                }
                yield s;
            }
            default -> v;
        };
    }

    private Map<String, Object> buildConfigMetadata(Workflow wf, Map<String, Object> inputs) {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("workflow", wf.name());
        meta.put("inputs", inputs);
        if (wf.skills() != null && !wf.skills().isEmpty()) {
            meta.put("skills", wf.skills());
        }
        if (wf.todos() != null && !wf.todos().isEmpty()) {
            meta.put("todos", wf.todos());
        }
        Workflow.Limits l = wf.limits();
        if (l != null && !l.isEmpty()) {
            meta.put("limits", l.toMap());
        }
        return meta;
    }

    private String composePrompt(Workflow wf, String skillSuffix, VariableSubstitution sub) {
        StringBuilder sb = new StringBuilder();
        List<Workflow.PromptDef> prompts = new ArrayList<>(wf.prompts());
        boolean injected = false;
        for (int i = 0; i < prompts.size(); i++) {
            Workflow.PromptDef p = prompts.get(i);
            String content = sub.substitute(p.content());
            if (!injected && "system".equalsIgnoreCase(p.role()) && !skillSuffix.isEmpty()) {
                content = content + skillSuffix;
                injected = true;
            }
            if (i > 0) sb.append("\n\n");
            sb.append("[").append(p.role().toUpperCase(Locale.ROOT)).append("]\n")
                    .append(content);
        }
        if (!injected && !skillSuffix.isEmpty()) {
            // Workflow had no system prompt — inject one so the
            // skill suffix is still delivered to the model.
            sb.insert(0, "[SYSTEM]\nYou have the following skills available:\n"
                    + skillSuffix + "\n\n");
        }
        return sb.toString();
    }

    private String composePlanBlock(Workflow wf, VariableSubstitution sub) {
        if (wf.todos() == null || wf.todos().isEmpty()) return "";
        StringBuilder sb = new StringBuilder("## Plan (pre-seeded TODOs)\n");
        int n = 0;
        for (String t : wf.todos()) {
            String rendered = sub.substitute(t);
            sb.append("- [ ] ").append(rendered).append('\n');
            n++;
        }
        sb.append("\nBefore you do anything else, call the `todo_write` tool ")
                .append("with these ").append(n).append(" items so the TODO board reflects the plan.");
        return sb.toString();
    }

    private static String formatErrors(String name, List<ValidationError> errors) {
        StringBuilder sb = new StringBuilder("workflow '")
                .append(name).append("' has ").append(errors.size())
                .append(" validation error(s):\n");
        for (ValidationError e : errors) {
            sb.append("  - ").append(e.path()).append(": ").append(e.message()).append('\n');
        }
        return sb.toString();
    }
}
