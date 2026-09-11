package org.aethercode.tools.task;

import org.aethercode.core.tool.Tool;
import org.aethercode.core.tool.ToolDef;
import org.aethercode.core.tool.Tools;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * incrementally update a single sub-task without re-sending the
 * full top-level todo list. Cheap way for the model to flip
 * {@code in_progress -> completed/failed/skipped} (or write a
 * {@code summary} when closing) without paying the cost of the full
 * {@code todo_write} call. The engine observes the change and emits
 * the matching {@code SubTaskStart} / {@code SubTaskEnd} event.
 *
 * <p>The tool mutates the live todo list held on
 * {@link org.aethercode.core.app.AppState} (set previously by
 * {@link TodoWriteTool}); the new sub-task state is also written
 * back to the call context's extra so the next {@code todo_write}
 * call sees it.
 *
 * <p>Schema:
 * <pre>{@code
 * {
 *   "parent_index": 0,            // index of the top-level todo (0-based)
 *   "subtask_id":   "sub-abc123", // matches the id in the original subtasks[]
 *   "status":       "completed",  // optional; one of: pending, in_progress, completed, failed, skipped
 *   "summary":      "All 12 tests passed" // optional
 * }
 * }</pre>
 */
public class SubTodoWriteTool {

    public static final String NAME = "sub_todo_write";

    public static Tool build() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("parent_index", mapOf("type", "integer",
                "description",
                "0-based index of the parent top-level todo in the current todo list."));
        props.put("subtask_id", Tools.stringProp(
                "Stable id matching the sub-task in the parent's subtasks[] array."));
        props.put("status", mapOf("type", "string",
                "description",
                "New status. One of: pending, in_progress, completed, failed, skipped."));
        props.put("summary", Tools.stringProp(
                "Optional one-line summary written when the sub-task closes (typically set "
                + "together with status=completed/failed/skipped)."));
        schema.put("properties", props);
        schema.put("required", List.of("parent_index", "subtask_id"));

        return Tools.build(new ToolDef(
                NAME,
                "Incrementally update a single sub-task (status / summary) without "
                + "re-sending the full todo list. Cheaper than todo_write for closing "
                + "a sub-task and writing its summary.",
                schema,
                (input, ctx) -> CompletableFuture.completedFuture(call(input, ctx))
        ));
    }

    @SuppressWarnings("unchecked")
    public static Tool.ToolResult call(Map<String, Object> input, Tool.CallContext ctx) {
        Object idxObj = input.get("parent_index");
        if (!(idxObj instanceof Number idxN)) {
            return Tool.ToolResult.error("parent_index must be an integer");
        }
        int parentIndex = idxN.intValue();
        if (parentIndex < 0) return Tool.ToolResult.error("parent_index must be >= 0");

        String subId = str(input.get("subtask_id"));
        if (subId == null || subId.isBlank()) {
            return Tool.ToolResult.error("subtask_id is required");
        }
        String newStatus = str(input.get("status"));
        String newSummary = str(input.get("summary"));

        // Read the current list from the call context first (set by
        // TodoWriteTool earlier in this session). Fall back to AppState
        // if the call context is fresh — both must agree because
        // TodoWriteTool writes through to AppState too.
        List<Map<String, Object>> current = TodoWriteTool.currentList(ctx);
        if (current.isEmpty()) {
            Object appState = ctx.extra("app_state");
            if (appState instanceof org.aethercode.core.app.AppState as) {
                current = new ArrayList<>(as.todoList());
            }
        }
        if (current.isEmpty()) {
            return Tool.ToolResult.error("no todo list yet — call todo_write first");
        }
        if (parentIndex >= current.size()) {
            return Tool.ToolResult.error("parent_index out of range: " + parentIndex
                    + " >= " + current.size());
        }
        Map<String, Object> parent = current.get(parentIndex);
        Object subs = parent.get("subtasks");
        if (!(subs instanceof List<?> subList) || subList.isEmpty()) {
            return Tool.ToolResult.error("parent has no subtasks");
        }

        boolean found = false;
        for (int i = 0; i < subList.size(); i++) {
            Object se = subList.get(i);
            if (!(se instanceof Map<?, ?> sm)) continue;
            if (subId.equals(str(sm.get("id")))) {
                if (newStatus != null) {
                    if (!isValidStatus(newStatus)) {
                        return Tool.ToolResult.error("invalid status: " + newStatus);
                    }
                }
                Map<String, Object> copy = new LinkedHashMap<>();
                for (var e : sm.entrySet()) {
                    Object key = e.getKey();
                    if (key instanceof String ks) copy.put(ks, e.getValue());
                }
                if (newStatus != null) copy.put("status", newStatus);
                if (newSummary != null) copy.put("summary", newSummary);
                // The subList is `List<?>` (narrowed from instanceof);
                // wildcard makes set() awkward. The outer list IS
                // List<Map<String,Object>> (per the parent's
                // expected type) — cast and set.
                @SuppressWarnings({"rawtypes", "unchecked"})
                List rawSub = subList;
                rawSub.set(i, copy);
                found = true;
                break;
            }
        }
        if (!found) {
            return Tool.ToolResult.error("no subtask with id '" + subId
                    + "' under parent " + parentIndex);
        }

        // Write back: both the call context (so the next todo_write
        // sees the change) AND AppState (so the engine + TUI see it
        // immediately).
        ctx.setExtra(TodoWriteTool.STATE_KEY, List.copyOf(current));
        Object appState = ctx.extra("app_state");
        if (appState instanceof org.aethercode.core.app.AppState as) {
            as.setTodoList(current);
        }
        return Tool.ToolResult.of("updated sub-task '" + subId + "'"
                + (newStatus != null ? " -> " + newStatus : "")
                + (newSummary != null ? " (" + newSummary + ")" : ""));
    }

    public static boolean isReadOnly(Map<String, Object> input) { return false; }
    public static boolean isConcurrencySafe(Map<String, Object> input) { return false; }

    private static String str(Object o) { return o == null ? null : o.toString(); }

    /** sub-tasks now accept {@code cancelled} too. Same
     *  semantics as top-level todos — the model can drop a
     *  sub-task it can't finish and the boulder hook will skip
     *  it on the next idle. */
    private static boolean isValidStatus(String s) {
        return s != null && (s.equals("pending") || s.equals("in_progress")
                || s.equals("completed") || s.equals("failed") || s.equals("skipped")
                || s.equals("cancelled"));
    }

    private static Map<String, Object> mapOf(String k1, Object v1, String k2, Object v2) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put(k1, v1);
        m.put(k2, v2);
        return m;
    }
}