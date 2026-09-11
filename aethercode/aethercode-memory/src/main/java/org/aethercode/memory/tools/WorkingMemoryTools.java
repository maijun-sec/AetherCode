package org.aethercode.memory.tools;

import org.aethercode.core.permission.PermissionResult;
import org.aethercode.core.tool.Tool;
import org.aethercode.core.tool.ToolDef;
import org.aethercode.core.tool.Tools;
import org.aethercode.memory.WorkingMemoryBuffer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * the four working-memory tools. prior round created the
 * {@link WorkingMemoryBuffer} and wired the lifecycle, but no one
 * was writing to it. These tools let the model actively curate its
 * own working memory: drop a TODO, paste an EVIDENCE, sketch a
 * PLAN_STEP, etc.
 *
 * <p>The buffer is resolved per-call from {@code CallContext.extras}
 * key {@code "working_memory"}. The {@code StreamingToolExecutor}
 * stashes the current buffer there on every tool call (set by
 * {@code AetherCodeEngine.setMemoryLifecycle}). The tools work even
 * when no buffer is wired — they return a structured "no buffer
 * active" message rather than throwing, so a missing wiring never
 * crashes the agent.
 *
 * <p>Permission model: all four are read/write but bounded to the
 * in-memory buffer (no filesystem access, no network). The
 * default policy is {@code Allow} so the user's permission
 * prompter doesn't have to handle it.
 */
public final class WorkingMemoryTools {

    private static final Logger LOG = LoggerFactory.getLogger(WorkingMemoryTools.class);

    /** CallContext.extras key under which the executor stashes the
     *  current per-query buffer. Tools look it up via this key. */
    public static final String EXTRAS_KEY = "working_memory";

    public static final String NAME_PUT = "wm_put";
    public static final String NAME_GET = "wm_get";
    public static final String NAME_LIST = "wm_list";
    public static final String NAME_CLEAR = "wm_clear";

    private WorkingMemoryTools() {}

    private static java.util.function.BiFunction<Map<String, Object>, Tool.CallContext,
            CompletableFuture<PermissionResult>> allowAll() {
        return (input, ctx) -> CompletableFuture.completedFuture(
                new PermissionResult.Allow(input == null ? Map.of() : input));
    }

    // ----------------------------------------------------------------
    // wm_put — add an entry to the current per-query working buffer
    // ----------------------------------------------------------------
    public static Tool put() {
        return Tools.build(new ToolDef(
                NAME_PUT,
                "Add an entry to the current per-query working memory. " +
                "Use this to drop TODOs, paste EVIDENCE snippets, record " +
                "PLAN_STEP bullets, or store any short text you want " +
                "available later in the same query.",
                wmPutSchema(),
                (input, ctx) -> {
                    WorkingMemoryBuffer buf = currentBuffer(ctx);
                    if (buf == null) {
                        return CompletableFuture.completedFuture(
                                ok("no working buffer active (lifecycle not wired)"));
                    }
                    String kindStr = strField(input, "kind", "TEXT");
                    String content = strField(input, "content", "");
                    if (content.isBlank()) {
                        return CompletableFuture.completedFuture(
                                Tool.ToolResult.error("content must not be blank"));
                    }
                    WorkingMemoryBuffer.Kind kind;
                    try {
                        kind = WorkingMemoryBuffer.Kind.valueOf(kindStr.toUpperCase());
                    } catch (IllegalArgumentException e) {
                        return CompletableFuture.completedFuture(
                                Tool.ToolResult.error("kind must be one of " +
                                        java.util.Arrays.toString(WorkingMemoryBuffer.Kind.values())));
                    }
                    Map<String, String> meta = null;
                    Object metaRaw = input.get("meta");
                    if (metaRaw instanceof Map<?, ?> m) {
                        meta = new LinkedHashMap<>();
                        for (Map.Entry<?, ?> e : m.entrySet()) {
                            meta.put(String.valueOf(e.getKey()), String.valueOf(e.getValue()));
                        }
                    }
                    WorkingMemoryBuffer.Entry e = buf.put(kind, content, meta);
                    return CompletableFuture.completedFuture(
                            ok("put id=" + e.id() + " kind=" + kind.wire() + " size=" + buf.size()));
                },
                allowAll()));
    }

    // ----------------------------------------------------------------
    // wm_get — read a single entry by id
    // ----------------------------------------------------------------
    public static Tool get() {
        return Tools.build(new ToolDef(
                NAME_GET,
                "Read a single working-memory entry by id (use wm_list to find ids).",
                wmIdSchema(),
                (input, ctx) -> {
                    WorkingMemoryBuffer buf = currentBuffer(ctx);
                    if (buf == null) {
                        return CompletableFuture.completedFuture(ok("no working buffer active"));
                    }
                    String id = strField(input, "id", "");
                    if (id.isBlank()) {
                        return CompletableFuture.completedFuture(
                                Tool.ToolResult.error("id must not be blank"));
                    }
                    Optional<WorkingMemoryBuffer.Entry> e = buf.get(id);
                    if (e.isEmpty()) {
                        return CompletableFuture.completedFuture(
                                Tool.ToolResult.error("not found: " + id));
                    }
                    WorkingMemoryBuffer.Entry v = e.get();
                    StringBuilder sb = new StringBuilder();
                    sb.append("id=").append(v.id()).append(" kind=").append(v.kind().wire())
                      .append(" created=").append(v.createdAt()).append("\n")
                      .append(v.content());
                    if (v.meta() != null && !v.meta().isEmpty()) {
                        sb.append("\nmeta: ").append(v.meta());
                    }
                    return CompletableFuture.completedFuture(ok(sb.toString()));
                },
                allowAll()));
    }

    // ----------------------------------------------------------------
    // wm_list — render the full buffer as the "Working memory" section
    // ----------------------------------------------------------------
    public static Tool list() {
        return Tools.build(new ToolDef(
                NAME_LIST,
                "List all entries in the current per-query working memory. " +
                "Returns the same render the system prompt would show.",
                wmEmptySchema(),
                (input, ctx) -> {
                    WorkingMemoryBuffer buf = currentBuffer(ctx);
                    if (buf == null) {
                        return CompletableFuture.completedFuture(ok("no working buffer active"));
                    }
                    return CompletableFuture.completedFuture(
                            ok(buf.render().isEmpty() ? "(empty)" : buf.render()));
                },
                allowAll()));
    }

    // ----------------------------------------------------------------
    // wm_clear — drop everything in the current buffer (rare; mostly
    // useful when the model has resolved a TODO and wants to declutter)
    // ----------------------------------------------------------------
    public static Tool clear() {
        return Tools.build(new ToolDef(
                NAME_CLEAR,
                "Clear all entries in the current per-query working memory. " +
                "Use sparingly — the buffer is auto-cleared on query end.",
                wmEmptySchema(),
                (input, ctx) -> {
                    WorkingMemoryBuffer buf = currentBuffer(ctx);
                    if (buf == null) {
                        return CompletableFuture.completedFuture(ok("no working buffer active"));
                    }
                    int before = buf.size();
                    buf.clear();
                    return CompletableFuture.completedFuture(
                            ok("cleared " + before + " entries"));
                },
                allowAll()));
    }

    /** All four tools in one list, in a stable order. */
    public static List<Tool> all() {
        return List.of(put(), get(), list(), clear());
    }

    // ----------------------------------------------------------------
    // helpers
    // ----------------------------------------------------------------

    /**
     * Look up the current per-query working buffer from the
     * {@code CallContext.extras} map. Returns null when the
     * lifecycle isn't wired (which is the default for tests and
     * the headless / --print path).
     */
    public static WorkingMemoryBuffer currentBuffer(Tool.CallContext ctx) {
        if (ctx == null || ctx.extras() == null) return null;
        Object o = ctx.extras().get(EXTRAS_KEY);
        if (o instanceof WorkingMemoryBuffer b) return b;
        return null;
    }

    private static String strField(Map<String, Object> input, String key, String def) {
        Object v = input == null ? null : input.get(key);
        return v == null ? def : String.valueOf(v);
    }

    private static Tool.ToolResult ok(String s) {
        return Tool.ToolResult.of(s);
    }

    private static Map<String, Object> wmPutSchema() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("kind", Tools.stringProp(
                "Entry kind. One of: TEXT, KEY_VALUE, REFERENCE, PLAN_STEP, EVIDENCE, TODO. " +
                "Default: TEXT."));
        props.put("content", Tools.stringProp("The text to remember. Must be non-blank."));
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("type", "object");
        meta.put("description", "Optional metadata (flat key/value strings).");
        meta.put("additionalProperties", Tools.stringProp("string value"));
        props.put("meta", meta);
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", props);
        schema.put("required", List.of("content"));
        return schema;
    }

    private static Map<String, Object> wmIdSchema() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("id", Tools.stringProp("The entry id returned by wm_put."));
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", props);
        schema.put("required", List.of("id"));
        return schema;
    }

    private static Map<String, Object> wmEmptySchema() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", new LinkedHashMap<>());
        return schema;
    }
}
