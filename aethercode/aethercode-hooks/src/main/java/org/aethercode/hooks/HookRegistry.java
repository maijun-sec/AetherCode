package org.aethercode.hooks;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Tiny in-process hook registry. Run hooks sequentially per kind; the first {@code Block}
 * halts the action.
 */
public class HookRegistry {

    private final List<Hook> hooks = new CopyOnWriteArrayList<>();

    public HookRegistry register(Hook h) {
        hooks.add(h);
        return this;
    }

    /** register a hook only if no instance of the same class
     *  is already present. Returns true if the hook was added,
     *  false if a duplicate was suppressed. Used by
     *  {@code AetherCodeEngine} to add the built-in safety
     *  hooks without doubling up when the caller already wired
     *  their own instance. */
    public boolean registerIfAbsent(Hook h) {
        if (h == null) return false;
        for (Hook existing : hooks) {
            if (existing != null && existing.getClass() == h.getClass()) {
                return false;
            }
        }
        hooks.add(h);
        return true;
    }

    /** snapshot of the registered hooks. Exposed for the
     *  engine's "register built-in if not present" check and
     *  for tests. Returns a defensive copy. */
    public List<Hook> snapshot() {
        return List.copyOf(hooks);
    }

    public CompletableFuture<Hook.Outcome> runAll(Hook.Kind kind, Hook.HookContext ctx) {
        return CompletableFuture.supplyAsync(() -> {
            // short-circuit chain. First Block wins
            // (we halt). A ContinueWithResult is collected
            // from the FIRST hook that returns one — later
            // hooks are not invoked (we'd otherwise have
            // to merge two replacement bodies, which is
            // ambiguous). If multiple hooks want to
            // mutate, the first one registered wins; the
            // prior round era convention is "one mutating hook
            // per tool kind" so this is fine in practice.
            Hook.Outcome pendingMutation = null;
            for (Hook h : hooks) {
                if (h.kind() != kind) continue;
                Hook.Outcome o = h.run(ctx).join();
                if (o instanceof Hook.Outcome.Block b) return b;
                if (o instanceof Hook.Outcome.ContinueWithResult cw
                        && pendingMutation == null) {
                    pendingMutation = cw;
                }
            }
            if (pendingMutation != null) return pendingMutation;
            return new Hook.Outcome.Continue();
        });
    }
}
