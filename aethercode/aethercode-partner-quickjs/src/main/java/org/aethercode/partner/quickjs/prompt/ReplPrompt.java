package org.aethercode.partner.quickjs.prompt;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Prompt / rendering helpers for REPL and PTC system prompts.
 *
 * <p>1:1 port of the Python
 * <code>langchain_quickjs._prompt</code> module. Exposes the
 * {@link #renderReplSystemPrompt}, {@link #renderSubagentSystemPrompt},
 * {@link #renderEvalToolCodeDoc}, {@link #renderEvalToolDescription},
 * and {@link #renderPtcPrompt} functions, plus the supporting
 * case-conversion and JSON-Schema &rarr; TS type renderers.</p>
 */
public final class ReplPrompt {

    private static final Pattern CAMEL_SEP = Pattern.compile("[-_]([a-z])");
    private static final Pattern JS_IDENTIFIER = Pattern.compile("^[A-Za-z_$][A-Za-z0-9_$]*$");

    private static final String REPL_SYSTEM_PROMPT_TEMPLATE =
            "### Interpreter\n\n"
                    + "{repl_intro_line}\n\n"
                    + "{state_persistence_line}\n"
                    + "- Top-level `await` works; Promises resolve before the call returns.\n"
                    + "- Runtime sandbox: no built-in filesystem, network, stdlib, or wall-clock "
                    + "APIs (`fetch`, `require`, `fs`, `process`, real `Date.now()` are "
                    + "unavailable or stubbed).\n"
                    + "{side_effects_line}\n"
                    + "- Timeout: {timeout}s per call. Memory: {memory_limit_mb} MB total.\n"
                    + "- `console.log` output is captured and returned alongside the result.";

    private static final String SUBAGENT_SYSTEM_PROMPT_TEMPLATE;
    static {
        // The Python module stores the multi-line subagent template as
        // a raw triple-quoted string with `{tool_name}` placeholders.
        // We reconstruct the same string here from constant parts.
        StringBuilder sb = new StringBuilder();
        sb.append("\n\n### Dispatching Subagents with `task`\n\n");
        sb.append("`task` is your primitive for running configured subagents from inside the\n");
        sb.append("JavaScript REPL. Your job here is to DISTRIBUTE work, not to do it yourself:\n");
        sb.append("write JavaScript that fans work out to subagents and assembles their results.\n");
        sb.append("You handle the orchestration - fan-out, filtering, deduplication, multi-stage\n");
        sb.append("flow, and synthesis - in plain JavaScript.\n\n");
        sb.append("#### The primitive\n\n");
        sb.append("```javascript\n");
        sb.append("await task({\n");
        sb.append("  description,      // full autonomous task prompt\n");
        sb.append("  subagentType,     // configured subagent name\n");
        sb.append("  label,            // optional short UI label for this dispatch\n");
        sb.append("  responseSchema,   // optional JSON Schema for structured output\n");
        sb.append("}); // -> Promise<unknown>\n");
        sb.append("```\n\n");
        sb.append("`task` runs a full agentic loop for the selected configured subagent. The\n");
        sb.append("subagent can use whatever tools it was configured with, iterate, inspect\n");
        sb.append("context, and return one final result. `subagentType` is required; use one of\n");
        sb.append("the configured subagent names.\n\n");
        sb.append("`description` is the only prompt the subagent receives for this dispatch. Make\n");
        sb.append("it complete: the goal, the constraints, what to inspect, and the exact shape\n");
        sb.append("or level of detail you expect back. Give context as locators — file paths and\n");
        sb.append("symbol names — not as pasted file contents. If you already read a file while\n");
        sb.append("exploring, still pass its path and let the subagent read it; do not paste back\n");
        sb.append("what you read. Each dispatch is stateless from the caller's perspective; you\n");
        sb.append("cannot send follow-up messages to the same subagent run.\n\n");
        sb.append("`label` is optional: when provided, it is shown in the live progress UI\n");
        sb.append("instead of the default description-derived fallback. It is not sent to the\n");
        sb.append("subagent and does not affect execution.\n\n");
        sb.append("`responseSchema` is optional, but set it on any dispatch whose result feeds\n");
        sb.append("later code. A deterministic, typed shape is what lets you compose the next\n");
        sb.append("stage reliably — index it, sort it, compare fields, branch on it, merge it —\n");
        sb.append("instead of parsing free-form text. This is what makes a whole workflow\n");
        sb.append("composable as one script. When provided, the resolved value is already a typed\n");
        sb.append("JavaScript value matching the schema; do not call `JSON.parse` unless the\n");
        sb.append("subagent intentionally returned a JSON string. Dynamic schemas work for\n");
        sb.append("declarative subagents; runnable-backed subagents reject dynamic schemas because\n");
        sb.append("their runnable is already compiled.\n\n");
        sb.append("#### Approval model\n\n");
        sb.append("`task` dispatches from inside the already-running `{tool_name}` call. It\n");
        sb.append("does not route through the parent agent's `ToolNode`-managed `task` tool and\n");
        sb.append("does not trigger parent-level `interrupt_on` / HITL approval for each dispatch.\n");
        sb.append("Declarative subagents still honor approval middleware configured inside their\n");
        sb.append("own spec. If you need approval before launching a subagent from the parent, use\n");
        sb.append("the normal `task` tool outside JavaScript or ensure the `{tool_name}` call\n");
        sb.append("itself is approval-gated.\n\n");
        sb.append("#### Mental model\n\n");
        sb.append("Hold your work in JS: an array of items in, an array of results out. Merge each\n");
        sb.append("dispatch result back onto its item. Multi-stage analysis means: run a pass,\n");
        sb.append("filter or regroup the array in JS, then run another pass over the survivors.\n\n");
        sb.append("You can run the whole workflow in one `{tool_name}` call or split it across\n");
        sb.append("several — both are fine. A single end-to-end script (generate, compare, pick a\n");
        sb.append("winner; or review every item, then synthesize) is clean when you can write it\n");
        sb.append("in one go; splitting is also fine when you want to inspect results between\n");
        sb.append("stages. Either way, don't redo work across calls — reuse what is already in\n");
        sb.append("scope (see \"Reuse what earlier evals left in scope\" below).\n\n");
        sb.append("#### Fan out with bounded concurrency\n\n");
        sb.append("Dispatch independent work in parallel with `Promise.all`, but in explicit\n");
        sb.append("batches around 10 so you do not launch hundreds of subagents at once. The bridge\n");
        sb.append("enforces a hard per-REPL cap of 32 concurrent subagent calls.\n\n");
        sb.append("```javascript\n");
        sb.append("const files = [\"/src/a.ts\", \"/src/b.ts\", \"/src/c.ts\"]; // found while exploring\n");
        sb.append("const batchSize = 10;\n");
        sb.append("const reviewed = [];\n");
        sb.append("for (let i = 0; i < files.length; i += batchSize) {\n");
        sb.append("  const batch = files.slice(i, i + batchSize);\n");
        sb.append("  reviewed.push(...(await Promise.all(batch.map(async (file) => {\n");
        sb.append("    const result = await task({\n");
        sb.append("      description: \"Read \" + file + \" and review it for SQL injection. \" +\n");
        sb.append("        \"Cite line numbers.\",\n");
        sb.append("      subagentType: \"reviewer\",\n");
        sb.append("      responseSchema: {\n");
        sb.append("        type: \"object\",\n");
        sb.append("        properties: {\n");
        sb.append("          vulnerabilities: {\n");
        sb.append("            type: \"array\",\n");
        sb.append("            items: {\n");
        sb.append("              type: \"object\",\n");
        sb.append("              properties: {\n");
        sb.append("                type: { type: \"string\" },\n");
        sb.append("                line: { type: \"number\" },\n");
        sb.append("                evidence: { type: \"string\" },\n");
        sb.append("              },\n");
        sb.append("              required: [\"type\", \"line\", \"evidence\"],\n");
        sb.append("            },\n");
        sb.append("          },\n");
        sb.append("        },\n");
        sb.append("        required: [\"vulnerabilities\"],\n");
        sb.append("      },\n");
        sb.append("    });\n");
        sb.append("    return { file, ...result };\n");
        sb.append("  }))));\n");
        sb.append("}\n");
        sb.append("```\n\n");
        sb.append("#### Explore with your own tools first, then distribute\n\n");
        sb.append("You already have your normal tools for reading, listing, globbing, and\n");
        sb.append("grepping files. Use them to explore and understand the task BEFORE you write\n");
        sb.append("the orchestration script. These are ordinary tool calls, separate from the\n");
        sb.append("`{tool_name}` tool: read the data file, list or glob the directory, grep for\n");
        sb.append("what matters, then decide how to split the work.\n\n");
        sb.append("Never write `{tool_name}` code that spawns a subagent just to read or parse a\n");
        sb.append("file or list a directory. That is a deterministic step you do yourself with a\n");
        sb.append("direct tool call; spending a whole agent loop on it is wasteful.\n\n");
        sb.append("Once you understand the shape of the work, you have creative freedom in how\n");
        sb.append("you split it:\n\n");
        sb.append("- One dispatch per file or per record, when the items are already separate.\n");
        sb.append("- Chunk a large input yourself — read it, split it, optionally write a small\n");
        sb.append("  input file per chunk — and dispatch one subagent per chunk.\n");
        sb.append("- A cheap classification pass first, then deeper dispatches only for the items\n");
        sb.append("  that warrant them.\n\n");
        sb.append("Then write JavaScript in the `{tool_name}` tool that distributes the heavy,\n");
        sb.append("agentic work to subagents with `task()`: analyzing file contents, exploring a\n");
        sb.append("codebase, making judgment calls, rewriting code, or synthesizing a report.\n\n");
        sb.append("Hand each subagent a locator, not a payload. Subagents have their own file\n");
        sb.append("tools, so for anything that lives in a file — a file to review, rewrite, or\n");
        sb.append("audit — pass the path and let the subagent read it. Do NOT read a whole file\n");
        sb.append("just to paste its contents into the description; that bloats every dispatch\n");
        sb.append("and duplicates the file across them. Reserve inline content for small or\n");
        sb.append("derived data that has no path of its own: a single parsed record, or a chunk\n");
        sb.append("you split out of a larger input (write the chunk to its own file and pass that\n");
        sb.append("path if it is large). Assemble the results in JS.\n\n");
        sb.append("#### Compose multiple stages\n\n");
        sb.append("Filter the array in JS between passes. For example: first ask subagents for a\n");
        sb.append("cheap classification, filter to the risky items, then dispatch deeper reviews\n");
        sb.append("only for those items.\n\n");
        sb.append("```javascript\n");
        sb.append("const tagged = await Promise.all(files.map((file) =>\n");
        sb.append("  task({\n");
        sb.append("    description: \"Read \" + file + \" and classify it as handler, util, \" +\n");
        sb.append("      \"test, or config.\",\n");
        sb.append("    subagentType: \"reviewer\",\n");
        sb.append("    responseSchema: {\n");
        sb.append("      type: \"object\",\n");
        sb.append("      properties: { kind: { type: \"string\" }, risky: { type: \"boolean\" } },\n");
        sb.append("      required: [\"kind\", \"risky\"],\n");
        sb.append("    },\n");
        sb.append("  }).then((tag) => ({ file, ...tag }))\n");
        sb.append("));\n\n");
        sb.append("const riskyHandlers = tagged.filter((it) => it.kind === \"handler\" && it.risky);\n");
        sb.append("const deepReviews = await Promise.all(riskyHandlers.map((it) =>\n");
        sb.append("  task({\n");
        sb.append("    description: \"Deep security review of \" + it.file + \". Cite line numbers.\",\n");
        sb.append("    subagentType: \"reviewer\",\n");
        sb.append("  }).then((review) => ({ ...it, review }))\n");
        sb.append("));\n");
        sb.append("```\n\n");
        sb.append("#### Return results via the last expression, not `console.log`\n\n");
        sb.append("The value of the last expression in an `{tool_name}` call (or a resolved\n");
        sb.append("top-level `await`) is returned to you as the result. Make that final\n");
        sb.append("expression the variable holding your result and read it from there.\n");
        sb.append("`console.log` is only for incidental debugging: its output is capped and\n");
        sb.append("truncated, while the returned value is not, so never `console.log` your\n");
        sb.append("actual results.\n\n");
        sb.append("Keep large intermediate sets in JS variables and return only a compact\n");
        sb.append("summary or a small slice, not the entire dataset. To persist full output,\n");
        sb.append("have a subagent write it, or write it with your own file tool outside the\n");
        sb.append("`{tool_name}` call.\n\n");
        sb.append("#### Reuse what earlier evals left in scope\n\n");
        sb.append("The REPL is persistent within a turn: every top-level variable, function, and\n");
        sb.append("class you declare is kept and is available in your next `{tool_name}` call\n");
        sb.append("(each is hoisted to global scope). So if a later step needs something an\n");
        sb.append("earlier eval produced or bound, **reference that variable by name** — do not\n");
        sb.append("write a new literal that re-types data a previous eval already returned or\n");
        sb.append("computed.\n\n");
        sb.append("If you catch yourself pasting a big array or object of values you produced in\n");
        sb.append("an earlier call, that is the tell: the variable is still in scope, so use it.\n");
        sb.append("Re-typing prior results as a fresh literal wastes tokens and drifts from what\n");
        sb.append("actually ran.\n\n");
        sb.append("```javascript\n");
        sb.append("// An earlier eval bound this:\n");
        sb.append("//   const auditResults = await Promise.all(files.map(/* ...audit... */));\n\n");
        sb.append("// A later eval — reference it; do NOT paste the findings back in as a literal:\n");
        sb.append("const findings = auditResults.flatMap((r) =>\n");
        sb.append("  r.findings.map((f) => ({ ...f, file: r.file }))\n");
        sb.append(");\n");
        sb.append("const verified = await Promise.all(findings.map((f) =>\n");
        sb.append("  task({\n");
        sb.append("    description: \"Verify this finding: \" + f.evidence,\n");
        sb.append("    subagentType: \"verifier\",\n");
        sb.append("  }).then((v) => ({ ...f, ...v }))\n");
        sb.append("));\n");
        sb.append("```\n\n");
        sb.append("#### When the user asks for a \"workflow\"\n\n");
        sb.append("If the user's request mentions running a \"workflow\" (or otherwise uses the\n");
        sb.append("word \"workflow\"), fan the work out to subagents rather than doing it all\n");
        sb.append("yourself. Explore with your own tools first as needed, then write JavaScript\n");
        sb.append("in the `{tool_name}` tool that dispatches subagents with `task()` and\n");
        sb.append("assembles their results. The point is to distribute the heavy work in\n");
        sb.append("parallel, not to grind through it one tool call at a time.\n");
        SUBAGENT_SYSTEM_PROMPT_TEMPLATE = sb.toString();
    }

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ReplPrompt() {}

    // -----------------------------------------------------------------
    //  Identifier / case helpers
    // -----------------------------------------------------------------

    /** Convert {@code snake_case} / {@code kebab-case} &rarr; {@code camelCase}. */
    public static String toCamelCase(String name) {
        if (name == null) return null;
        java.util.regex.Matcher m = CAMEL_SEP.matcher(name);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            m.appendReplacement(sb, m.group(1).toUpperCase());
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /** Return whether {@code name} is a valid JavaScript identifier. */
    public static boolean isValidJsIdentifier(String name) {
        return name != null && JS_IDENTIFIER.matcher(name).matches();
    }

    /** Return whether a tool can be exposed as {@code tools.<camelCaseName>}. */
    public static boolean isValidPtcToolName(String name) {
        return isValidJsIdentifier(toCamelCase(name));
    }

    // -----------------------------------------------------------------
    //  REPL system prompt
    // -----------------------------------------------------------------

    /** Persistence mode for the REPL. 1:1 of the Python {@code Literal["thread","turn","call"]}. */
    public enum Mode {
        THREAD("thread"),
        TURN("turn"),
        CALL("call");

        private final String wire;

        Mode(String wire) {
            this.wire = wire;
        }

        public String wireName() {
            return wire;
        }

        public static Mode fromWire(String s) {
            for (Mode m : values()) {
                if (m.wire.equals(s)) return m;
            }
            throw new IllegalArgumentException("Unknown persistence mode: " + s);
        }
    }

    /**
     * Render the base REPL system prompt text for
     * {@code CodeInterpreterMiddleware}.
     *
     * @param ptcAttached controls the "external side effects" bullet:
     *                    when host tools are exposed as the
     *                    {@code tools.*} namespace it points the model
     *                    at the API reference; otherwise it states the
     *                    REPL is pure computation.
     */
    public static String renderReplSystemPrompt(String toolName,
                                                double timeout,
                                                int memoryLimitMb,
                                                Mode mode,
                                                boolean ptcAttached) {
        String sideEffectsLine = ptcAttached
                ? "- External side effects from inside the REPL are only reachable "
                + "via the `tools.*` namespace documented in the API reference below."
                : "- The REPL has no access to host tools, files, or the network: it "
                + "is pure computation. Return values to communicate results.";

        String replIntroLine;
        String statePersistenceLine;
        switch (mode) {
            case CALL -> {
                replIntroLine = "An `" + toolName + "` tool is available. It runs JavaScript in a fresh "
                        + "sandboxed REPL for each invocation.";
                statePersistenceLine = "- State (variables, functions) does not persist across tool calls. "
                        + "Each invocation starts from a blank environment.";
            }
            case THREAD -> {
                replIntroLine = "An `" + toolName + "` tool is available. It runs JavaScript in a persistent "
                        + "REPL.";
                statePersistenceLine = "- State (variables, functions) persists across tool calls and across "
                        + "multiple turns for this conversation thread.";
            }
            default -> {
                replIntroLine = "An `" + toolName + "` tool is available. It runs JavaScript in a persistent "
                        + "REPL.";
                statePersistenceLine = "- State (variables, functions) persists across tool calls within "
                        + "a single turn of conversation. They DO NOT persist across multiple turns.";
            }
        }
        return REPL_SYSTEM_PROMPT_TEMPLATE
                .replace("{repl_intro_line}", replIntroLine)
                .replace("{state_persistence_line}", statePersistenceLine)
                .replace("{side_effects_line}", sideEffectsLine)
                .replace("{timeout}", Double.toString(timeout))
                .replace("{memory_limit_mb}", Integer.toString(memoryLimitMb));
    }

    /** Render guidance for the top-level QuickJS {@code task} global. */
    public static String renderSubagentSystemPrompt(String toolName) {
        return SUBAGENT_SYSTEM_PROMPT_TEMPLATE.replace("{tool_name}", toolName);
    }

    /** Render the eval tool's {@code code} argument description. */
    public static String renderEvalToolCodeDoc(Mode mode) {
        String persistence;
        if (mode == Mode.CALL) {
            persistence = "Each call runs in a fresh REPL environment (no cross-call state).";
        } else if (mode == Mode.THREAD) {
            persistence = "State persists across calls and across turns in this conversation.";
        } else {
            persistence = "State persists across calls within a turn, but resets between turns.";
        }
        return "JavaScript expression or statement(s) to evaluate in the sandboxed REPL. " + persistence;
    }

    /** Render the public eval tool description. */
    public static String renderEvalToolDescription(Mode mode) {
        String stateLine;
        if (mode == Mode.CALL) {
            stateLine = "Each call runs in a fresh sandboxed REPL with no state carried over.";
        } else if (mode == Mode.THREAD) {
            stateLine = "Persistent state is enabled: variables and functions defined in one "
                    + "call are visible to subsequent calls in this conversation.";
        } else {
            stateLine = "Persistent state is enabled within a single turn: variables and "
                    + "functions defined in one call are visible to later calls within "
                    + "the same turn, but reset between turns.";
        }
        return "Execute JavaScript in a sandboxed REPL. " + stateLine
                + " No filesystem, network, or real clock. "
                + "Top-level `await` is supported; a final-expression Promise resolves "
                + "before the call returns.";
    }

    // -----------------------------------------------------------------
    //  PTC prompt
    // -----------------------------------------------------------------

    /** Lightweight tool descriptor used by the PTC prompt renderer. */
    public interface ToolLike {
        String name();
        String description();
        /** JSON schema for the tool's arguments, or {@code null} when none. */
        Object argsSchema();
    }

    /**
     * Build the {@code tools} namespace section of the system prompt.
     * If {@code tools} is empty, returns the empty string so the
     * caller can simply concatenate.
     */
    public static String renderPtcPrompt(List<? extends ToolLike> tools, String toolName) {
        if (tools == null || tools.isEmpty()) return "";
        StringBuilder blocks = new StringBuilder();
        for (ToolLike tool : tools) {
            String camel = toCamelCase(tool.name());
            String schemaJson = safeJsonSchema(tool);
            Map<String, Object> schema = schemaJson == null ? null : parseJsonObject(schemaJson);
            String returnType = renderReturnType(tool);
            String signature = renderSignature(camel, schema, returnType);
            String description = "";
            if (tool.description() != null) {
                String[] lines = tool.description().strip().split("\\R", 2);
                if (lines.length > 0) description = lines[0];
            }
            blocks.append("/** ").append(description).append(" */\n").append(signature).append("\n\n");
        }
        String body = blocks.toString().stripTrailing();
        return "\n\n### API Reference — `tools` namespace\n\n"
                + "The agent tools listed below are exposed on the global object at "
                + "`globalThis.tools` (also reachable as `tools`). Each takes a single "
                + "object argument and returns a Promise that resolves to the tool's "
                + "native value: strings as strings, numbers as numbers, lists as "
                + "arrays, dicts as objects, and `None` as `null`. You do NOT need to "
                + "`JSON.parse` results — they are already typed.\n\n"
                + "Invocation pattern: `await tools.<name>({ ... })`.\n\n"
                + "- Use `await` to get tool results; combine with `Promise.all` for "
                + "independent calls so they run concurrently.\n"
                + "- If the task needs multiple tool calls, prefer one `" + toolName + "` "
                + "invocation that performs all of them rather than splitting the work "
                + "across multiple `" + toolName + "` calls — each round-trip costs a model "
                + "turn.\n"
                + "- Pipeline dependent calls within a single program. If a result from "
                + "one tool is needed as input to a later tool, chain them in one "
                + "program instead of returning the intermediate value to the model.\n"
                + "- If a tool returns an ID or other value that can be passed directly "
                + "into the next tool, trust it and chain the calls instead of stopping "
                + "to double-check it.\n"
                + "- To inspect an intermediate value, `console.log` it inside the same "
                + "program; otherwise, fetch as much information as possible in one "
                + "call.\n"
                + "- Only split work across multiple `" + toolName + "` invocations when "
                + "you genuinely cannot determine what to do next without additional "
                + "model reasoning or user input.\n\n"
                + "Example shape — substitute real tool names:\n\n"
                + "```typescript\n"
                + "const users = await tools.findUsers({ name: \"Ada\" });\n"
                + "const userId = users[0].id;\n"
                + "const [city, normalized] = await Promise.all([\n"
                + "  tools.cityForUser({ user_id: userId }),\n"
                + "  tools.normalize({ name: \"Ada\" }),\n"
                + "]);\n"
                + "console.log({ city, normalized });\n"
                + "```\n\n"
                + "```typescript\n" + body + "\n```";
    }

    private static String safeJsonSchema(ToolLike tool) {
        if (tool.argsSchema() == null) return null;
        // Many Java tool schemas already implement `model_json_schema()`
        // via the Jackson reflection. We attempt that first; fall back
        // to a JSON dump.
        try {
            Object schema = tool.argsSchema();
            if (schema instanceof String s) return s;
            return MAPPER.writeValueAsString(schema);
        } catch (Exception e) {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> parseJsonObject(String json) {
        try {
            return MAPPER.readValue(json, Map.class);
        } catch (Exception e) {
            return null;
        }
    }

    private static String renderSignature(String fnName, Map<String, Object> schema, String returnType) {
        String returnClause = "Promise<" + returnType + ">";
        String defaultSig = "tools." + fnName + "(input: Record<string, unknown>): " + returnClause;
        if (schema == null || !(schema.get("properties") instanceof Map<?, ?>)) {
            return defaultSig;
        }
        Map<String, Object> props = (Map<String, Object>) schema.get("properties");
        @SuppressWarnings("unchecked")
        Set<String> required = propsKeySet(schema.get("required"));
        List<String> fields = new ArrayList<>();
        for (Map.Entry<String, Object> entry : props.entrySet()) {
            String key = entry.getKey();
            Object prop = entry.getValue();
            String optional = required.contains(key) ? "" : "?";
            String typeStr = jsonSchemaToTs(asMap(prop));
            String desc = prop instanceof Map<?, ?> pm && pm.get("description") instanceof String s ? s : null;
            String prefix = (desc == null) ? "" : "/**\n *" + desc + "\n */ ";
            fields.add("  " + prefix + key + optional + ": " + typeStr + ";");
        }
        if (fields.isEmpty()) return defaultSig;
        String body = String.join("\n", fields);
        return "tools." + fnName + "(input: {\n" + body + "\n}): " + returnClause;
    }

    @SuppressWarnings("unchecked")
    private static Set<String> propsKeySet(Object required) {
        if (required instanceof java.util.Collection<?> c) {
            Set<String> out = new java.util.LinkedHashSet<>();
            for (Object o : c) if (o != null) out.add(o.toString());
            return out;
        }
        return Set.of();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object o) {
        if (o instanceof Map<?, ?> m) {
            return (Map<String, Object>) m;
        }
        return Map.of();
    }

    /**
     * Best-effort JSON-Schema &rarr; TS type renderer. Mirrors the
     * shallow Python helper; compound shapes with {@code $ref} /
     * {@code $defs} fall back to {@code unknown}.
     */
    public static String jsonSchemaToTs(Map<String, Object> prop) {
        if (prop == null || prop.isEmpty()) return "unknown";
        if (prop.containsKey("enum")) {
            Object en = prop.get("enum");
            if (en instanceof Iterable<?> iter) {
                List<String> parts = new ArrayList<>();
                for (Object v : iter) {
                    try {
                        parts.add(MAPPER.writeValueAsString(v));
                    } catch (JsonProcessingException e) {
                        parts.add(String.valueOf(v));
                    }
                }
                return String.join(" | ", parts);
            }
        }
        if (prop.containsKey("anyOf")) {
            Object any = prop.get("anyOf");
            if (any instanceof Iterable<?> iter) {
                List<String> parts = new ArrayList<>();
                for (Object v : iter) parts.add(jsonSchemaToTs(asMap(v)));
                // dedupe, preserving order
                LinkedHashMap<String, String> seen = new LinkedHashMap<>();
                for (String p : parts) seen.put(p, p);
                return String.join(" | ", seen.values());
            }
        }
        Object t = prop.get("type");
        if (t instanceof String type) {
            switch (type) {
                case "string": return "string";
                case "integer", "number": return "number";
                case "boolean": return "boolean";
                case "null": return "null";
                case "array": {
                    Object items = prop.get("items");
                    String inner = (items instanceof Map<?, ?>) ? jsonSchemaToTs(asMap(items)) : "unknown";
                    return inner + "[]";
                }
                case "object": {
                    Object sub = prop.get("properties");
                    if (sub instanceof Map<?, ?> sm && !sm.isEmpty()) {
                        Set<String> required = propsKeySet(prop.get("required"));
                        List<String> fields = new ArrayList<>();
                        for (Map.Entry<?, ?> entry : sm.entrySet()) {
                            String k = String.valueOf(entry.getKey());
                            String opt = required.contains(k) ? "" : "?";
                            fields.add(k + opt + ": " + jsonSchemaToTs(asMap(entry.getValue())));
                        }
                        return "{ " + String.join("; ", fields) + " }";
                    }
                    return "Record<string, unknown>";
                }
                default: return "unknown";
            }
        }
        return "unknown";
    }

    /**
     * Render the return annotation as a TS type, defaulting to
     * {@code unknown}. The Java port cannot introspect Python-style
     * function annotations; it falls back to {@code unknown} unless
     * the tool exposes a {@code returnType} field on its schema. The
     * signature matches the Python port's behavior, which is
     * best-effort.
     */
    public static String renderReturnType(ToolLike tool) {
        // Java port has no Python `inspect.signature` to fall back on.
        // The default is "unknown" — the same value the Python port
        // returns when the function has no return annotation.
        return "unknown";
    }
}
