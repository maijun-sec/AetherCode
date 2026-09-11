package org.aethercode.core.tool;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;
import org.aethercode.core.tool.ToolHook.Context;
import org.aethercode.core.tool.ToolHook.Result;

/**
 * registry of {@link ToolHook}s. Hooks are run in registration
 * order on {@code pre} and reverse order on {@code post} (LIFO).
 *
 * <p>Hook resolution:
 * <ul>
 *   <li>Filter: a hook with non-empty {@code toolFilter} only runs when
 *       the tool name is in its list.</li>
 *   <li>Mutation: a {@code pre} hook can return a new context to override
 *       the input; subsequent hooks see the new input.</li>
 *   <li>Denial: the first non-null {@code deny} short-circuits the call.</li>
 * </ul>
 */
public class ToolHookRegistry {

    private final List<ToolHook> hooks = new CopyOnWriteArrayList<>();

    public ToolHookRegistry add(ToolHook hook) {
        if (hook != null) hooks.add(hook);
        return this;
    }

    public ToolHookRegistry addAll(List<ToolHook> more) {
        if (more != null) more.forEach(this::add);
        return this;
    }

    public int size() { return hooks.size(); }
    public List<ToolHook> hooks() { return List.copyOf(hooks); }

    public void clear() { hooks.clear(); }

    /**
     * resolve a pre-execution pipeline. Returns the (possibly
     * mutated) context, or a {@code Result} if any hook denied the call.
     */
    public HookPreOutcome runPre(String toolName, Map<String, Object> input) {
        Context ctx = new Context(toolName, input, new java.util.HashMap<>());
        for (ToolHook h : hooks) {
            if (!matches(h, toolName)) continue;
            ctx = h.pre(ctx);
        }
        for (ToolHook h : hooks) {
            if (!matches(h, toolName)) continue;
            Result d = h.deny(ctx);
            if (d != null) return new HookPreOutcome(ctx, d);
        }
        return new HookPreOutcome(ctx, null);
    }

    /**
     * resolve the post-execution pipeline. Hooks are run in
     * reverse order (LIFO) so the most recently registered hook sees
     * the result first.
     */
    public Result runPost(Context ctx, Result result) {
        Result current = result;
        for (int i = hooks.size() - 1; i >= 0; i--) {
            ToolHook h = hooks.get(i);
            if (!matches(h, ctx.toolName())) continue;
            current = h.post(ctx, current);
        }
        return current;
    }

    private static boolean matches(ToolHook h, String toolName) {
        List<String> filter = h.toolFilter();
        if (filter == null || filter.isEmpty()) return true;
        return filter.contains(toolName);
    }

    /** outcome of the pre-execution phase. */
    public record HookPreOutcome(Context context, Result denial) {
        public boolean denied() { return denial != null; }
    }

    /** helper to build a deny hook from a predicate. */
    public static ToolHook denyIf(java.util.function.Predicate<Context> pred, Function<Context, Result> resultFor) {
        return new ToolHook() {
            @Override public Result deny(Context ctx) {
                return pred.test(ctx) ? resultFor.apply(ctx) : null;
            }
        };
    }

    /** helper to build a pre-hook that mutates the input map. */
    public static ToolHook transformInput(Function<Map<String, Object>, Map<String, Object>> fn) {
        return new ToolHook() {
            @Override public Context pre(Context ctx) {
                return new Context(ctx.toolName(), fn.apply(ctx.input()), ctx.attributes());
            }
        };
    }
}
