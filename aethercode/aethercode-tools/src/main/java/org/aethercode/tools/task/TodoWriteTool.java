package org.aethercode.tools.task;

import org.aethercode.core.tool.Tool;
import org.aethercode.core.tool.ToolDef;
import org.aethercode.core.tool.Tools;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Update the in-session todo list. Mirrors the TS {@code TodoWriteTool}. The list is
 * per-session and lives on the {@link org.aethercode.core.app.AppState} via the call context.
 *
 * <p>Each invocation replaces the entire list — the model is expected to send the full set
 * each turn so the list is always self-consistent. The TUI subscribes to a callback to
 * re-render whenever the list changes.
 *
 * <p>R85: each top-level todo may carry a {@code subtasks[]} field
 * (smaller business-concept units). The engine watches the
 * transition between sub-task states and emits
 * {@code SubTaskStart}/{@code SubTaskEnd} events so the renderer
 * can group model calls + tool calls under the sub-task card. See
 * {@link org.aethercode.core.stream.StreamEvent.SubTaskStart}.
 */
public class TodoWriteTool {

    public static final String NAME = "todo_write";
    public static final String STATE_KEY = "_todo_state";

    public static Tool build() {
        // sub-task schema. Each subtask is a small object with id
        // (assigned by the model — required for stable identity across
        // todo_write + sub_todo_write calls), content (what we're
        // doing), status (pending / in_progress / completed / failed /
        // skipped), and an optional summary (one-line recap the model
        // writes when closing the sub-task).
        Map<String, Object> subProps = new LinkedHashMap<>();
        subProps.put("id",       Tools.stringProp("Stable identifier. Reuse the same id across calls."));
        subProps.put("content",  Tools.stringProp("Short description of this business unit."));
        subProps.put("status",   Tools.stringProp("One of: pending, in_progress, completed, failed, skipped."));
        subProps.put("summary",  Tools.stringProp("Optional one-line summary written when the sub-task closes."));
        Map<String, Object> subSchema = new LinkedHashMap<>();
        subSchema.put("type", "object");
        subSchema.put("properties", subProps);
        subSchema.put("required", List.of("id", "content", "status"));

        Map<String, Object> subs = new LinkedHashMap<>();
        subs.put("type", "array");
        subs.put("description", "Optional sub-tasks (business-concept units) under this top-level todo.");
        subs.put("items", subSchema);

        Map<String, Object> itemSchema = new LinkedHashMap<>();
        itemSchema.put("type", "object");
        Map<String, Object> itemProps = new LinkedHashMap<>();
        itemProps.put("content",     Tools.stringProp("Short description."));
        itemProps.put("status",      Tools.stringProp("One of: pending, in_progress, completed, cancelled, skipped."));
        itemProps.put("active_form", Tools.stringProp("Present-tense form shown to the user."));
        itemProps.put("subtasks",    subs);
        itemSchema.put("properties", itemProps);
        itemSchema.put("required", List.of("content", "status"));

        Map<String, Object> items = new LinkedHashMap<>();
        items.put("type", "array");
        items.put("description", "The full new state of the todo list.");
        items.put("items", itemSchema);

        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        Map<String, Object> sp = new LinkedHashMap<>();
        sp.put("todos", items);
        schema.put("properties", sp);
        schema.put("required", List.of("todos"));

        return Tools.build(new ToolDef(
                NAME,
                "Update the in-session todo list. The full new list is sent on every call. "
                + "Each top-level todo may carry a `subtasks[]` field for finer-grained "
                + "business units the engine tracks separately.",
                schema,
                (input, ctx) -> CompletableFuture.completedFuture(call(input, ctx))
        ));
    }

    @SuppressWarnings("unchecked")
    public static Tool.ToolResult call(Map<String, Object> input, Tool.CallContext ctx) {
        Object todos = input.get("todos");
        if (!(todos instanceof List<?> list)) {
            return Tool.ToolResult.error("todos must be a list");
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object o : list) {
            if (!(o instanceof Map<?, ?> m)) {
                return Tool.ToolResult.error("each todo must be an object");
            }
            String content = str(m.get("content"));
            String status = str(m.get("status"));
            if (content == null || content.isBlank()) return Tool.ToolResult.error("todo content is required");
            if (!isStatus(status)) return Tool.ToolResult.error("invalid status: " + status);
            Map<String, Object> todo = new LinkedHashMap<>();
            todo.put("content", content);
            todo.put("status", status);
            if (m.get("active_form") != null) todo.put("active_form", m.get("active_form").toString());
            // parse the subtasks[] field. Each sub-task must
            // carry a stable id (we generate one if the model forgot)
            // so subsequent sub_todo_write calls can target it.
            List<Map<String, Object>> subtasksOut = new ArrayList<>();
            Object subs = m.get("subtasks");
            if (subs instanceof List<?> subList) {
                int idx = 0;
                for (Object se : subList) {
                    if (!(se instanceof Map<?, ?> sm)) {
                        return Tool.ToolResult.error("each subtask must be an object");
                    }
                    String sId = str(sm.get("id"));
                    if (sId == null || sId.isBlank()) sId = "sub-" + UUID.randomUUID().toString().substring(0, 8);
                    String sContent = str(sm.get("content"));
                    String sStatus = str(sm.get("status"));
                    if (sContent == null || sContent.isBlank()) {
                        return Tool.ToolResult.error("subtask content is required");
                    }
                    if (!isSubTaskStatus(sStatus)) {
                        return Tool.ToolResult.error("invalid subtask status: " + sStatus);
                    }
                    Map<String, Object> sub = new LinkedHashMap<>();
                    sub.put("id", sId);
                    sub.put("index", idx++);
                    sub.put("content", sContent);
                    sub.put("status", sStatus);
                    if (sm.get("summary") != null) sub.put("summary", sm.get("summary").toString());
                    subtasksOut.add(sub);
                }
            }
            if (!subtasksOut.isEmpty()) todo.put("subtasks", subtasksOut);
            out.add(todo);
        }
        ctx.setExtra(STATE_KEY, List.copyOf(out));
        // surface the new list to AppState so the TUI / CLI can render it.
        // The CallContext carries an "app_state" extra when invoked through the
        // engine (StreamingToolExecutor) or the spring-ai adapter; absent in
        // isolated test contexts, in which case we fall back silently.
        Object appState = ctx.extra("app_state");
        if (appState instanceof org.aethercode.core.app.AppState as) {
            as.setTodoList(out);
        }
        return Tool.ToolResult.of("updated todo list (" + out.size() + " items, "
                + totalSubtasks(out) + " sub-tasks)");
    }

    @SuppressWarnings("unchecked")
    public static List<Map<String, Object>> currentList(Tool.CallContext ctx) {
        Object v = ctx.extra(STATE_KEY);
        if (v instanceof List<?> l) return (List<Map<String, Object>>) l;
        return List.of();
    }

    public static boolean isReadOnly(Map<String, Object> input) { return false; }
    public static boolean isConcurrencySafe(Map<String, Object> input) { return false; }

    private static String str(Object o) { return o == null ? null : o.toString(); }
    /** allowed top-level todo statuses. {@code cancelled}
     *  is added so the model can mark a todo it can't finish
     *  (missing test infra, blocker from the user) and the
     *  boulder hook will skip it on the next idle. {@code skipped}
     *  is the older synonym kept for backward compat. */
    private static boolean isStatus(String s) {
        return s != null && (s.equals("pending") || s.equals("in_progress") || s.equals("completed")
                || s.equals("cancelled") || s.equals("skipped"));
    }
    /** sub-tasks have 5 statuses (top-level todos only have 3).
     *  {@code failed} and {@code skipped} are write-once terminal
     *  states the model uses when a sub-task cannot finish; the
     *  engine doesn't act on them differently, but the renderer
     *  paints them red/grey. */
    private static boolean isSubTaskStatus(String s) {
        return s != null && (s.equals("pending") || s.equals("in_progress")
                || s.equals("completed") || s.equals("failed") || s.equals("skipped"));
    }

    private static int totalSubtasks(List<Map<String, Object>> todos) {
        int n = 0;
        for (Map<String, Object> t : todos) {
            Object s = t.get("subtasks");
            if (s instanceof List<?> l) n += l.size();
        }
        return n;
    }
}
