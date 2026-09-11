package org.aethercode.permission;

import org.aethercode.config.Action;
import org.aethercode.config.OpKind;
import org.aethercode.config.OpKindDetector;
import org.aethercode.config.PermissionMatrix;
import org.aethercode.core.engine.PermissionPolicy;
import org.aethercode.core.permission.PermissionResult;
import org.aethercode.core.tool.Tool;

import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Logger;

/**
 * A {@link ProjectPermissionPolicy} that consults a {@link PermissionMatrix}
 * BEFORE the deny/ask/allow rule list. The matrix is the new first line of
 * defence: a per-tool × per-path × per-op-kind table loaded from
 * {@code .aethercode/config.json}.
 *
 * <p>Resolution order (matrix path):
 * <ol>
 *   <li>Detect {@link OpKind} from {@code tool} + {@code input}.</li>
 *   <li>Compute path (best-effort: tool's "file_path"/"path"/"command" field).</li>
 *   <li>Look up the matrix: first match wins (path-glob, then op-kind, with
 *       a {@code *} fallback).</li>
 *   <li>Apply the matrix {@link Action}:
 *     <ul>
 *       <li>{@code DENY} → return {@link PermissionResult.Deny} immediately,
 *           do not consult rules or mode.</li>
 *       <li>{@code ALLOW} → return {@link PermissionResult.Allow} immediately,
 *           do not consult rules or mode (overrides BYPASS_PERMISSIONS too
 *           — explicit allow wins over session posture).</li>
 *       <li>{@code ASK} → fall through to the inner
 *           {@link ProjectPermissionPolicy#check} (rules → mode).</li>
 *     </ul>
 *   </li>
 * </ol>
 *
 * <p>Read-only op kinds ({@link OpKind#READ}, {@link OpKind#LIST}) bypass
 * the matrix when the tool itself is read-only — we trust the tool's own
 * {@code isReadOnly} declaration. This is a defence in depth: a tool
 * claiming to be read-only cannot be turned into a denial-of-service vector
 * by an entry that accidentally maps it to ASK.
 *
 * <p>Setting a non-null matrix is what activates this policy. If the
 * matrix has no entry for the tool, the inner {@link ProjectPermissionPolicy}
 * handles the call as if this wrapper weren't there.
 */
public class MatrixPermissionPolicy extends ProjectPermissionPolicy {

    private static final Logger LOG = Logger.getLogger(MatrixPermissionPolicy.class.getName());

    private final PermissionMatrix matrix;
    private final Path projectRoot;
    // shared across all sessions; consulted when the matrix says ASK.
    // Volatile because the daemon swaps it after construction.
    private volatile org.aethercode.config.SkipConfirmationRegistry skipRegistry = null;
    // listener fired when the skip counter is consumed (every
    // call that short-circuits) OR when the counter transitions to 0.
    // The engine wires this to the JSON-RPC NOTIFY_SKIP_CONFIRMATION
    // notification. Volatile because the daemon swaps it after
    // construction.
    private volatile java.util.function.IntConsumer onSkipConsumed = null;
    // per-tool consume listener. Receives the tool name +
    // the remaining count after the consume. Lets the engine
    // build per-tool adoption stats ("which tools were
    // auto-allowed most often").
    private volatile java.util.function.BiConsumer<String, Integer> onToolSkipConsumed = null;
    // skip-low listener. Fired ONCE per session when the
    // counter crosses DOWN through the waterline (e.g. 5 -> 4
    // with waterline=5, or 100 -> 4 with waterline=5).
    // Receives (sessionId, newRemaining). Defaults to a no-op.
    // The waterline is read from AetherCodeConfig.skipLowWaterline
    // at construction time; setting it on the policy directly
    // is also supported via setLowWaterline().
    private volatile java.util.function.BiConsumer<String, Integer> onSkipLow = null;
    // waterline; <= 0 disables the listener. Default 5.
    private volatile int lowWaterline = 5;

    public MatrixPermissionPolicy(PermissionMatrix matrix,
                                  SettingsPermissions rules,
                                  org.aethercode.core.permission.PermissionMode mode,
                                  ToolPermissionPrompter prompter,
                                  Path projectRoot) {
        super(rules, mode, prompter);
        this.matrix = matrix == null ? new PermissionMatrix() : matrix;
        this.projectRoot = projectRoot;
    }

    public PermissionMatrix matrix() { return matrix; }
    public Path projectRoot() { return projectRoot; }

    /** install the shared per-session skip-confirmation registry.
     *  When set, the policy short-circuits ASK -> ALLOW while the
     *  session's counter is positive. */
    public void setSkipConfirmationRegistry(org.aethercode.config.SkipConfirmationRegistry r) {
        this.skipRegistry = r;
    }
    public org.aethercode.config.SkipConfirmationRegistry skipConfirmationRegistry() {
        return skipRegistry;
    }

    /** install a listener fired on every consumed skip-round.
     *  The listener receives the REMAINING count after the consume. */
    public void setOnSkipConsumed(java.util.function.IntConsumer listener) {
        this.onSkipConsumed = listener;
    }

    /** install a per-tool consume listener. Receives
     *  (toolName, remaining) on every consumed skip-round.
     *  Defaults to a no-op. */
    public void setOnToolSkipConsumed(java.util.function.BiConsumer<String, Integer> listener) {
        this.onToolSkipConsumed = listener;
    }

    /** install a skip-low listener. Receives
     *  (sessionId, newRemaining) ONCE per session when the
     *  counter crosses DOWN through the waterline. The
     *  listener is fired only when previousRemaining was
     *  strictly greater than the waterline and the new
     *  remaining is at or below it. Defaults to a no-op. */
    public void setOnSkipLow(java.util.function.BiConsumer<String, Integer> listener) {
        this.onSkipLow = listener;
    }

    /** configure the waterline. The default is 5. Set
     *  to 0 (or negative) to disable the skip-low listener. */
    public void setLowWaterline(int waterline) {
        this.lowWaterline = waterline;
    }

    /** accessor for the current waterline. */
    public int lowWaterline() { return lowWaterline; }

    /**
     * Return a copy of this policy with the matrix replaced. The mode, prompter,
     * and rules are shared with the parent (prior round semantics).
     * also carries the waterline.
     */
    public MatrixPermissionPolicy withMatrix(PermissionMatrix newMatrix) {
        MatrixPermissionPolicy copy = new MatrixPermissionPolicy(newMatrix, /* rules */ this.rules(),
                this.mode(), this.prompter(), this.projectRoot);
        copy.setLowWaterline(this.lowWaterline);
        return copy;
    }

    /**
     * override {@link ProjectPermissionPolicy#withMode} so the
     * returned policy is a {@code MatrixPermissionPolicy} (not a plain
     * {@code ProjectPermissionPolicy} like the parent's implementation).
     * legacy the parent's {@code withMode} ran for any
     * {@code instanceof ProjectPermissionPolicy} target (and a
     * {@code MatrixPermissionPolicy} is a {@code ProjectPermissionPolicy}),
     * so {@code AetherCodeEngine.setPermissionMode} would silently drop the
     * matrix whenever the user changed the mode dropdown:
     * <ul>
     *   <li>The matrix's per-tool / per-path / per-op-kind deny + allow
     *       rules were no longer consulted.</li>
     *   <li>The {@link SkipConfirmationRegistry} short-circuit (prior round) was
     *       also gone, because the new plain policy was a different
     *       instance and the registry is read on every
     *       {@link #check} call.</li>
     *   <li>{@code ConfigWatcher} (prior round) could no longer push a refreshed
     *       matrix via {@link #withMatrix}, because its
     *       {@code if (this.policy instanceof MatrixPermissionPolicy mpp)}
     *       guard was now false.</li>
     * </ul>
     * The user observed this as the perm-mode appearing to "jump between
     * 始终授权 and 询问" — the status bar's {@code BYPASS_PERMISSIONS} was
     * honoured on the immediate next call (the new plain policy did
     * allow it), but a subsequent mode flip back to {@code ACCEPT_EDITS}
     * or a config-driven matrix reload would silently lose the matrix
     * half of the policy, leaving the user with a "smart" mode that no
     * longer consulted {@code .aethercode/config.json}.
     *
     * <p>This override mirrors {@link #withMatrix} — the new
     * {@code MatrixPermissionPolicy} shares the same matrix, rules,
     * prompter, projectRoot, and lowWaterline. The prior round
     * {@code currentSubTaskId} is intentionally NOT copied here because:
     * <ol>
     *   <li>The engine's {@code StreamingToolExecutor.setPolicy}
     *       (prior round) re-pushes the live sub-task id to the new policy
     *       via the public {@code setCurrentSubTaskId} setter right
     *       after this method returns, so an in-flight
     *       {@code ACCEPT_TASK} boundary is still preserved.</li>
     *   <li>{@code currentSubTaskId} is private on the parent
     *       {@code ProjectPermissionPolicy}, so direct read access
     *       from the subclass isn't possible without widening
     *       visibility — and the {@code setCurrentSubTaskId} setter
     *       is the supported hook for the engine to drive this
     *       field.</li>
     * </ol>
     * The prior round / R108 listeners are intentionally NOT copied — the
     * engine re-installs them via {@code installPolicyListeners} on every
     * {@code swapPolicy} call (prior round), so re-installing them here too
     * would just shadow the engine's wrapper layer.
     */
    @Override
    public MatrixPermissionPolicy withMode(org.aethercode.core.permission.PermissionMode newMode) {
        MatrixPermissionPolicy copy = new MatrixPermissionPolicy(
                this.matrix, /* rules */ this.rules(),
                newMode == null ? org.aethercode.core.permission.PermissionMode.DEFAULT : newMode,
                this.prompter(), this.projectRoot);
        copy.setLowWaterline(this.lowWaterline);
        return copy;
    }

    @Override
    public CompletableFuture<PermissionResult> check(
            Tool tool, Map<String, Object> input, Tool.CallContext ctx) {

        // Defensive: trust the tool's own read-only declaration. A tool that
        // reports isReadOnly=true is auto-allowed without consulting the
        // matrix — the matrix is for guarding mutations.
        if (tool.isReadOnly(input)) {
            return CompletableFuture.completedFuture(new PermissionResult.Allow(input));
        }

        OpKind opKind = OpKindDetector.detect(tool.name(), input, projectRoot);
        String path = extractPath(tool, input);
        Action action = matrix.lookup(tool.name(), path, opKind);

        if (action.isDeny()) {
            String msg = "denied by R98 matrix: " + tool.name()
                    + (path != null ? " on " + path : "")
                    + " (op=" + opKind + ")";
            LOG.fine(msg);
            return CompletableFuture.completedFuture(PermissionResult.Deny.of(msg));
        }
        if (action.isAllow()) {
            // Explicit allow from the matrix short-circuits the rules +
            // mode chain. The user said "this specific path / op is fine".
            return CompletableFuture.completedFuture(new PermissionResult.Allow(input));
        }
        // action.isAsk() -> fall through to the inner policy (rules -> mode),
        // UNLESS the session has skip-rounds remaining. The skip counter
        // is decremented per call, so once it hits 0 the normal flow
        // resumes automatically. This is the RPC escape hatch.
        if (skipRegistry != null && ctx != null
                && skipRegistry.consumeOne(ctx.sessionId())) {
            int newRemaining = skipRegistry.remaining(ctx.sessionId());
            // notify the listener (engine wires this to the
            // JSON-RPC notification) so the UI can update its
            // status bar without polling getState.
            if (onSkipConsumed != null) {
                try {
                    onSkipConsumed.accept(newRemaining);
                } catch (RuntimeException ignore) {
                    // A failing listener must never break a permission check.
                }
            }
            // per-tool adoption. The listener is
            // independently try/catch'd so a per-tool stat
            // failure can't take down the global notification.
            if (onToolSkipConsumed != null) {
                try {
                    onToolSkipConsumed.accept(tool.name(), newRemaining);
                } catch (RuntimeException ignore) {
                    // defensive — see above
                }
            }
            // skip-low listener. Fire ONCE per session when
            // the counter crosses DOWN through the waterline.
            // previousRemaining = newRemaining + 1 (consumeOne
            // just decremented by 1). The condition is strict:
            // previous > waterline AND new <= waterline. Set
            // waterline <= 0 to disable entirely.
            if (onSkipLow != null && lowWaterline > 0) {
                int previous = newRemaining + 1;
                if (previous > lowWaterline && newRemaining <= lowWaterline) {
                    try {
                        onSkipLow.accept(ctx.sessionId(), newRemaining);
                    } catch (RuntimeException ignore) {
                        // defensive — see above
                    }
                }
            }
            return CompletableFuture.completedFuture(new PermissionResult.Allow(input));
        }
        return super.check(tool, input, ctx);
    }

    private static String extractPath(Tool tool, Map<String, Object> input) {
        if (input == null) return null;
        Object p = input.get("file_path");
        if (p == null) p = input.get("path");
        if (p == null) {
            String name = tool.name();
            if (name.equalsIgnoreCase("bash") || name.equalsIgnoreCase("shell")) {
                Object c = input.get("command");
                if (c != null) {
                    String s = c.toString();
                    // Heuristic: only the first path-looking token of a bash
                    // command is meaningful for the matrix. Most matrix
                    // entries for bash use "*" anyway.
                    int sp = s.indexOf(' ');
                    return sp > 0 ? s.substring(0, sp) : s;
                }
            }
        }
        return p == null ? null : p.toString();
    }

    // --- package-private accessors used by withMatrix --------------------------
}
