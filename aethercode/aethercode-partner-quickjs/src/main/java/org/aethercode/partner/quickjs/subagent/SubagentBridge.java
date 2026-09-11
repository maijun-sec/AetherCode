package org.aethercode.partner.quickjs.subagent;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.aethercode.partner.quickjs.format.Format;
import org.aethercode.partner.quickjs.prompt.ReplPrompt;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.function.Consumer;

/**
 * QuickJS adapter for the Deep Agents <code>task</code> subagent tool.
 *
 * <p>1:1 port of the Python
 * <code>langchain_quickjs._subagent</code> module. Locates the Deep
 * Agents task tool in the agent's toolset, dispatches subagent calls
 * from inside the JS REPL, and emits lifecycle events on the custom
 * stream.</p>
 */
public final class SubagentBridge {

    private static final Logger LOGGER = LoggerFactory.getLogger(SubagentBridge.class);

    /** Discriminator value for subagent events on the custom stream. */
    public static final String SUBAGENT_STREAM_EVENT_TYPE = "subagent";

    /** Maximum serialized size of an accepted {@code response_schema}. */
    public static final int SCHEMA_MAX_BYTES = 4096;

    /** Maximum nesting depth allowed in a {@code response_schema}. */
    public static final int SCHEMA_MAX_DEPTH = 5;

    /** Maximum total property count across a {@code response_schema}. */
    public static final int SCHEMA_MAX_PROPERTIES = 32;

    /** Input field names that identify the Deep Agents task tool. */
    public static final Set<String> SUBAGENT_TASK_TOOL_FIELDS = Set.of("description", "subagent_type");

    /** Reserved subagent task tool name. */
    public static final String TASK_TOOL_NAME = "task";

    /** Return the task tool name (a stable alias for {@link #TASK_TOOL_NAME}). */
    public static String taskToolName() {
        return TASK_TOOL_NAME;
    }

    /** Character cap on the {@code description} carried in a start event. */
    public static final int EVENT_DESCRIPTION_MAX_CHARS = 200;

    /** Character cap on an explicit {@code label} carried in a start event. */
    public static final int EVENT_LABEL_MAX_CHARS = 120;

    /** Character cap on a {@code label} derived from the description fallback. */
    public static final int EVENT_LABEL_FALLBACK_MAX_CHARS = 60;

    /** Default schema title injected when the model's schema is missing one. */
    public static final String DEFAULT_SCHEMA_TITLE = "subagent_response";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private SubagentBridge() {}

    // -----------------------------------------------------------------
    //  Stream emission
    // -----------------------------------------------------------------

    /**
     * Emit a subagent lifecycle event on the custom stream. Any
     * failure is swallowed so observability never breaks dispatch.
     */
    public static void emitSubagentEvent(Consumer<Map<String, Object>> streamWriter,
                                         SubagentStreamEvent event) {
        if (streamWriter == null) return;
        try {
            streamWriter.accept(toWireMap(event));
        } catch (RuntimeException e) {
            // Observability must not break dispatch.
            LOGGER.debug("Failed to emit subagent stream event (id={}, phase={})",
                    event.id(), event.phase(), e);
        }
    }

    private static Map<String, Object> toWireMap(SubagentStreamEvent event) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("type", event.type());
        out.put("phase", event.phase());
        out.put("id", event.id());
        switch (event) {
            case SubagentStartEvent start -> {
                out.put("subagent_type", start.subagentType());
                out.put("label", start.label() == null ? "" : start.label());
                out.put("description", start.description() == null ? "" : start.description());
                start.evalId().ifPresent(eid -> out.put("eval_id", eid));
            }
            case SubagentCompleteEvent complete -> {
                out.put("duration_ms", complete.durationMs());
                complete.evalId().ifPresent(eid -> out.put("eval_id", eid));
            }
            case SubagentErrorEvent error -> {
                out.put("duration_ms", error.durationMs());
                out.put("error", error.error() == null ? "" : error.error());
                error.evalId().ifPresent(eid -> out.put("eval_id", eid));
            }
        }
        return out;
    }

    private static String eventLabel(String label, String description) {
        String explicit = label == null ? "" : label.strip().replaceAll("\\s+", " ");
        if (!explicit.isEmpty()) {
            return explicit.length() > EVENT_LABEL_MAX_CHARS
                    ? explicit.substring(0, EVENT_LABEL_MAX_CHARS) : explicit;
        }
        String desc = description == null ? "" : description.strip().replaceAll("\\s+", " ");
        if (desc.length() > EVENT_LABEL_FALLBACK_MAX_CHARS) {
            return desc.substring(0, EVENT_LABEL_FALLBACK_MAX_CHARS);
        }
        return desc;
    }

    // -----------------------------------------------------------------
    //  Task tool detection
    // -----------------------------------------------------------------

    /**
     * Minimal task-tool contract that the bridge can talk to. The
     * Python port uses {@code BaseTool}; the Java port mirrors that
     * via this interface so the bridge can be tested without a full
     * {@code Tool} implementation.
     */
    public interface TaskTool {
        String name();

        /** Input field names this tool accepts (read from its args schema). */
        Set<String> inputFieldNames();

        /**
         * Invoke the tool with the given input map and optional
         * runtime/config. Returns the tool's native return value.
         */
        CompletableFuture<Object> arun(Map<String, Object> input,
                                        Object runtime,
                                        Map<String, Object> config,
                                        String toolCallId);
    }

    /**
     * Return the Deep Agents task tool that backs top-level
     * {@code task()} &mdash; or {@code null} if none is configured.
     */
    public static TaskTool findSubagentTaskTool(List<? extends TaskTool> tools) {
        if (tools == null) return null;
        for (TaskTool tool : tools) {
            if (TASK_TOOL_NAME.equals(tool.name())
                    && tool.inputFieldNames().containsAll(SUBAGENT_TASK_TOOL_FIELDS)) {
                return tool;
            }
        }
        return null;
    }

    // -----------------------------------------------------------------
    //  Call dispatch
    // -----------------------------------------------------------------

    /**
     * Call the Deep Agents task tool and return a JavaScript-friendly
     * value. Emits <code>start</code> then
     * <code>complete</code>/<code>error</code> lifecycle events on
     * the custom stream.
     */
    public static Object callSubagentTaskTool(TaskTool taskTool,
                                               String description,
                                               String subagentType,
                                               Map<String, Object> responseSchema,
                                               Object runtime,
                                               String label) {
        if (runtime == null) {
            throw new IllegalStateException("task() requires an active ToolRuntime");
        }
        boolean parseJsonOutput = responseSchema != null;
        Map<String, Object> validatedSchema = null;
        if (responseSchema != null) {
            validateResponseSchema(responseSchema);
            validatedSchema = ensureSchemaTitle(responseSchema);
        }

        // Capture the active tool-call id and stream writer off the
        // runtime using reflection-ish getters; the runtime shape is
        // intentionally permissive so the bridge is decoupled from the
        // exact langgraph version.
        String evalId = readRuntimeString(runtime, "tool_call_id");
        Consumer<Map<String, Object>> streamWriter = readStreamWriter(runtime);
        String subagentId = "ptc_" + taskTool.name() + "_" + UUID.randomUUID().toString().substring(0, 8);

        Map<String, Object> args = new LinkedHashMap<>();
        args.put("description", description);
        args.put("subagent_type", subagentType);
        args.put("runtime", runtime);

        SubagentStartEvent start = new SubagentStartEvent(
                subagentId, subagentType,
                eventLabel(label, description),
                description == null ? "" : description.substring(0, Math.min(EVENT_DESCRIPTION_MAX_CHARS, description.length())),
                Optional.ofNullable(evalId),
                Map.of()
        );
        emitSubagentEvent(streamWriter, start);

        Map<String, Object> config = readRuntimeMap(runtime, "config");
        long startedAt = System.nanoTime();
        try {
            CompletableFuture<Object> future = taskTool.arun(args, runtime, config, subagentId);
            Object result = future.get();
            Object output = extractTaskToolOutput(result, parseJsonOutput);
            long durationMs = (System.nanoTime() - startedAt) / 1_000_000L;
            emitSubagentEvent(streamWriter, new SubagentCompleteEvent(
                    subagentId, durationMs, Optional.ofNullable(evalId), Map.of()));
            return output;
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("task() interrupted", ie);
        } catch (ExecutionException ee) {
            long durationMs = (System.nanoTime() - startedAt) / 1_000_000L;
            Throwable cause = ee.getCause() != null ? ee.getCause() : ee;
            String message = cause.getMessage() == null ? cause.toString() : cause.getMessage();
            emitSubagentEvent(streamWriter, new SubagentErrorEvent(
                    subagentId, durationMs, message, Optional.ofNullable(evalId), Map.of()));
            if (cause instanceof RuntimeException re) throw re;
            throw new RuntimeException(message, cause);
        } catch (RuntimeException re) {
            long durationMs = (System.nanoTime() - startedAt) / 1_000_000L;
            String message = re.getMessage() == null ? re.toString() : re.getMessage();
            emitSubagentEvent(streamWriter, new SubagentErrorEvent(
                    subagentId, durationMs, message, Optional.ofNullable(evalId), Map.of()));
            throw re;
        }
    }

    // -----------------------------------------------------------------
    //  Schema validation
    // -----------------------------------------------------------------

    /**
     * Reject schemas that exceed size, depth, or property-count
     * limits. Mirrors the Python {@code _validate_response_schema}.
     */
    public static void validateResponseSchema(Map<String, Object> schema) {
        if (schema == null) return;
        String serialized;
        try {
            serialized = MAPPER.writeValueAsString(schema);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("response_schema is not JSON-serializable", e);
        }
        if (serialized.length() > SCHEMA_MAX_BYTES) {
            throw new IllegalArgumentException(
                    "response_schema exceeds " + SCHEMA_MAX_BYTES
                            + " byte limit (" + serialized.length() + " bytes)");
        }
        int[] propCount = {0};
        checkSchemaNode(schema, 0, propCount);
    }

    @SuppressWarnings("unchecked")
    private static void checkSchemaNode(Map<String, Object> node, int depth, int[] propCount) {
        if (depth > SCHEMA_MAX_DEPTH) {
            throw new IllegalArgumentException(
                    "response_schema exceeds maximum nesting depth of " + SCHEMA_MAX_DEPTH);
        }
        Object props = node.get("properties");
        if (props instanceof Map<?, ?> pm) {
            propCount[0] += pm.size();
            if (propCount[0] > SCHEMA_MAX_PROPERTIES) {
                throw new IllegalArgumentException(
                        "response_schema exceeds maximum of " + SCHEMA_MAX_PROPERTIES + " properties");
            }
            for (Object v : pm.values()) {
                if (v instanceof Map<?, ?> child) {
                    checkSchemaNode((Map<String, Object>) child, depth + 1, propCount);
                }
            }
        }
        Object items = node.get("items");
        if (items instanceof Map<?, ?> child) {
            checkSchemaNode((Map<String, Object>) child, depth + 1, propCount);
        }
    }

    /**
     * Ensure the response schema carries a non-empty top-level
     * {@code title}. Structured output backends that treat a JSON
     * schema as a function require a top-level {@code title} to use
     * as the function name; inject a default when the model's schema
     * is missing one.
     */
    public static Map<String, Object> ensureSchemaTitle(Map<String, Object> schema) {
        if (schema == null) return null;
        Object existing = schema.get("title");
        if (existing instanceof String s && !s.isBlank()) return schema;
        Map<String, Object> next = new LinkedHashMap<>(schema);
        next.put("title", DEFAULT_SCHEMA_TITLE);
        return next;
    }

    // -----------------------------------------------------------------
    //  Output extraction
    // -----------------------------------------------------------------

    /**
     * Extract the JS-friendly return value from a task tool's native
     * return value. When {@code parseJsonOutput} is {@code true} and
     * the value is a JSON string, decode it.
     */
    public static Object extractTaskToolOutput(Object result, boolean parseJsonOutput) {
        Object output = Format.coerceToolOutputForPtc(result);
        if (!parseJsonOutput || !(output instanceof String s)) return output;
        try {
            return MAPPER.readValue(s, Object.class);
        } catch (Exception e) {
            return output;
        }
    }

    // -----------------------------------------------------------------
    //  Runtime reflection helpers
    // -----------------------------------------------------------------

    private static String readRuntimeString(Object runtime, String property) {
        try {
            java.lang.reflect.Method m = runtime.getClass().getMethod(property);
            Object v = m.invoke(runtime);
            return v == null ? null : v.toString();
        } catch (ReflectiveOperationException e) {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> readRuntimeMap(Object runtime, String property) {
        try {
            java.lang.reflect.Method m = runtime.getClass().getMethod(property);
            Object v = m.invoke(runtime);
            return v instanceof Map<?, ?> mp ? (Map<String, Object>) mp : null;
        } catch (ReflectiveOperationException e) {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private static Consumer<Map<String, Object>> readStreamWriter(Object runtime) {
        try {
            java.lang.reflect.Method m = runtime.getClass().getMethod("stream_writer");
            Object v = m.invoke(runtime);
            if (v == null) return null;
            if (v instanceof Consumer<?> c) {
                return (Consumer<Map<String, Object>>) c;
            }
            // Some runtimes expose the stream writer as a 1-arg callable; adapt.
            return (Consumer<Map<String, Object>>) ev -> {
                try {
                    java.lang.reflect.Method invoke = v.getClass().getMethod("invoke", Object.class);
                    invoke.invoke(v, ev);
                } catch (ReflectiveOperationException e) {
                    LOGGER.debug("Failed to invoke runtime stream_writer", e);
                }
            };
        } catch (ReflectiveOperationException e) {
            return null;
        }
    }
}
