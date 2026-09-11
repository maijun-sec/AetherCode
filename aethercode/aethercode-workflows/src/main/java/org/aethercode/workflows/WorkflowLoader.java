package org.aethercode.workflows;

import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * SnakeYAML → {@link Workflow} (schema v1). The loader is the only
 * place that knows about YAML; everything else in the engine sees
 * immutable records. Schema validation that requires semantic
 * judgement (kebab-case names, prompt content non-empty, etc.) is
 * the validator's job.
 *
 * <p>Security: we use {@link SafeConstructor} with a {@link LoaderOptions}
 * cap on document size. The default constructor allows arbitrary
 * Java types, which is a known gadget chain risk; a workflow file
 * is user-authored content and must not be allowed to instantiate
 * arbitrary classes. {@code SafeConstructor} only permits the
 * standard YAML scalar / list / map types.
 */
public final class WorkflowLoader {

    /** Cap on a single YAML document. 1 MiB is generous — a real
     *  workflow is a few KB; anything bigger is a bug or an attack. */
    private static final int MAX_DOC_BYTES = 1 << 20;

    public WorkflowLoader() {}

    /**
     * Load a workflow from a file on disk. Throws
     * {@link WorkflowParserException} on any structural problem.
     */
    public Workflow load(Path file) {
        Objects.requireNonNull(file, "file");
        try (InputStream in = Files.newInputStream(file)) {
            return parse(in, file);
        } catch (IOException e) {
            throw new WorkflowParserException("cannot read file: " + e.getMessage(), file, e);
        }
    }

    /**
     * Load a workflow from a UTF-8 string. Useful for tests and
     * for the {@code workflow/upsert} RPC (the client supplies
     * the YAML body, not a path).
     */
    public Workflow loadFromString(String yaml) {
        Objects.requireNonNull(yaml, "yaml");
        return parse(new java.io.ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8)), null);
    }

    /**
     * Load a workflow from a classpath resource (used to ship the
     * four built-in workflows under {@code /workflows/*.yaml}).
     */
    public Workflow loadResource(String resourcePath) {
        Objects.requireNonNull(resourcePath, "resourcePath");
        ClassLoader cl = Thread.currentThread().getContextClassLoader();
        if (cl == null) cl = WorkflowLoader.class.getClassLoader();
        try (InputStream in = cl.getResourceAsStream(resourcePath)) {
            if (in == null) {
                throw new WorkflowParserException("resource not found on classpath: " + resourcePath);
            }
            return parse(in, null);
        } catch (IOException e) {
            throw new WorkflowParserException("cannot read resource: " + e.getMessage(), e);
        }
    }

    // -- core parser -----------------------------------------------------

    private Workflow parse(InputStream in, Path source) {
        LoaderOptions opts = new LoaderOptions();
        // SnakeYAML 2.x: alias-count cap moved from setMaxAliases to
        // setMaxAliasesForCollections; the default of 50 is already
        // plenty for workflow YAML (a workflow has < 50 top-level
        // keys + collection entries combined). Duplicate keys are
        // rejected up-front so a typo can't silently shadow a real
        // value, and the document size cap is 1 MiB to bound the
        // attack surface from user-authored content.
        opts.setAllowDuplicateKeys(false);
        opts.setWrappedToRootException(false);
        opts.setCodePointLimit(MAX_DOC_BYTES);
        Yaml yaml = new Yaml(new SafeConstructor(opts));
        Object root;
        try (Reader reader = new java.io.InputStreamReader(in, StandardCharsets.UTF_8)) {
            root = yaml.load(reader);
        } catch (java.io.IOException ioe) {
            throw new WorkflowParserException("cannot read YAML: " + ioe.getMessage(), source, ioe);
        } catch (org.yaml.snakeyaml.error.YAMLException e) {
            throw new WorkflowParserException("malformed YAML: " + e.getMessage(), source, e);
        }
        if (root == null) {
            throw new WorkflowParserException("empty document", source);
        }
        if (!(root instanceof Map<?, ?> map)) {
            throw new WorkflowParserException(
                    "workflow root must be a mapping, got " + root.getClass().getSimpleName(), source);
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> raw = (Map<String, Object>) map;
        return toWorkflow(raw, source);
    }

    @SuppressWarnings("unchecked")
    private Workflow toWorkflow(Map<String, Object> raw, Path source) {
        int version = intOrDefault(raw.get("version"), 0);
        if (version != Workflow.CURRENT_VERSION) {
            throw new WorkflowParserException(
                    "unsupported schema version " + version
                            + " (this build understands v" + Workflow.CURRENT_VERSION + ")",
                    source);
        }
        String name = stringOrThrow(raw, "name", source);
        String description = stringOrDefault(raw, "description", "");
        Map<String, Workflow.InputDef> inputs = parseInputs(raw.get("inputs"), source);
        List<String> skills = parseStringList(raw.get("skills"), "skills", source);
        List<Workflow.PromptDef> prompts = parsePrompts(raw.get("prompts"), source);
        List<String> todos = parseStringList(raw.get("todos"), "todos", source);
        Workflow.Limits limits = parseLimits(raw.get("limits"));
        return new Workflow(version, name, description, inputs, skills, prompts, todos, limits, raw);
    }

    private Map<String, Workflow.InputDef> parseInputs(Object node, Path source) {
        Map<String, Workflow.InputDef> out = new LinkedHashMap<>();
        if (node == null) return out;
        if (!(node instanceof Map<?, ?> map)) {
            throw new WorkflowParserException("inputs must be a mapping", source);
        }
        for (Map.Entry<?, ?> e : map.entrySet()) {
            String key = String.valueOf(e.getKey());
            if (!(e.getValue() instanceof Map<?, ?> im)) {
                throw new WorkflowParserException(
                        "input '" + key + "' must be a mapping", source);
            }
            Map<String, Object> m = (Map<String, Object>) im;
            String type = stringOrDefault(m, "type", "string");
            boolean required = boolOrDefault(m.get("required"), false);
            String description = stringOrDefault(m, "description", "");
            List<String> values = parseStringList(m.get("values"),
                    "inputs." + key + ".values", source);
            Object defaultValue = m.get("default");
            out.put(key, new Workflow.InputDef(type, required, description, values, defaultValue));
        }
        return out;
    }

    private List<Workflow.PromptDef> parsePrompts(Object node, Path source) {
        List<Workflow.PromptDef> out = new ArrayList<>();
        if (node == null) return out;
        if (!(node instanceof List<?> list)) {
            throw new WorkflowParserException("prompts must be a list", source);
        }
        for (int i = 0; i < list.size(); i++) {
            Object item = list.get(i);
            if (!(item instanceof Map<?, ?> pm)) {
                throw new WorkflowParserException(
                        "prompts[" + i + "] must be a mapping", source);
            }
            Map<String, Object> m = (Map<String, Object>) pm;
            String role = stringOrDefault(m, "role", "user");
            String content = stringOrDefault(m, "content", "");
            out.add(new Workflow.PromptDef(role, content));
        }
        return out;
    }

    private Workflow.Limits parseLimits(Object node) {
        if (node == null) return Workflow.Limits.empty();
        if (!(node instanceof Map<?, ?> map)) return Workflow.Limits.empty();
        Map<String, Object> m = (Map<String, Object>) map;
        Long wallClock = longOrNull(m.get("wallClockMs"));
        Long tokens    = longOrNull(m.get("tokens"));
        Long calls     = longOrNull(m.get("calls"));
        Long writes    = longOrNull(m.get("fileWrites"));
        Long network   = longOrNull(m.get("network"));
        return new Workflow.Limits(wallClock, tokens, calls, writes, network);
    }

    private List<String> parseStringList(Object node, String fieldName, Path source) {
        if (node == null) return List.of();
        if (!(node instanceof List<?> list)) {
            throw new WorkflowParserException(fieldName + " must be a list", source);
        }
        List<String> out = new ArrayList<>(list.size());
        for (Object item : list) {
            if (item == null) {
                throw new WorkflowParserException(
                        fieldName + " entries must be non-null", source);
            }
            out.add(item.toString());
        }
        return out;
    }

    // -- scalar coercion helpers -----------------------------------------

    private static String stringOrThrow(Map<String, Object> map, String key, Path source) {
        Object v = map.get(key);
        if (v == null || v.toString().isBlank()) {
            throw new WorkflowParserException("missing required field: " + key, source);
        }
        return v.toString();
    }

    private static String stringOrDefault(Map<String, Object> map, String key, String dflt) {
        Object v = map.get(key);
        return v == null ? dflt : v.toString();
    }

    private static int intOrDefault(Object v, int dflt) {
        if (v == null) return dflt;
        if (v instanceof Number n) return n.intValue();
        try { return Integer.parseInt(v.toString()); }
        catch (NumberFormatException nfe) { return dflt; }
    }

    private static boolean boolOrDefault(Object v, boolean dflt) {
        if (v == null) return dflt;
        if (v instanceof Boolean b) return b;
        String s = v.toString().trim().toLowerCase();
        if (s.equals("true") || s.equals("yes") || s.equals("y") || s.equals("1")) return true;
        if (s.equals("false") || s.equals("no") || s.equals("n") || s.equals("0")) return false;
        return dflt;
    }

    private static Long longOrNull(Object v) {
        if (v == null) return null;
        if (v instanceof Number n) return n.longValue();
        try { return Long.parseLong(v.toString().trim()); }
        catch (NumberFormatException nfe) { return null; }
    }
}
