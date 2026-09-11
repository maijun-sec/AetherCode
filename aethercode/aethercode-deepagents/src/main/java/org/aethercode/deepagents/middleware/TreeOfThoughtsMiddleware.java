package org.aethercode.deepagents.middleware;

import org.aethercode.core.runtime.AgentState;
import org.aethercode.core.runtime.ContentBlock;
import org.aethercode.core.runtime.Message;
import org.aethercode.core.runtime.Message.AIMessage;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * R240 (O-4): a {@link Middleware} that nudges the agent to
 * <em>think in branches</em> on hard decisions.
 *
 * <p>Why a middleware and not a separate agent? The deepagents
 * architecture is already a graph of middleware + a chat model.
 * Adding ToT as another middleware keeps the seam narrow: it
 * exposes three normal tools ({@value #TOOL_THINK_BRANCHES},
 * {@value #TOOL_SELECT_BRANCH}, {@value #TOOL_PRUNE_BRANCH})
 * the model can call, and records the state of the active
 * tree under {@link #BRANCHES_KEY} in {@code AgentState}.
 *
 * <h2>Tools</h2>
 * <ul>
 *   <li>{@code think_branches(branches: string[], rationale: string)} —
 *       explore the decision space by listing 2..5 alternative
 *       courses of action. Each branch is a one-line summary.
 *       The middleware records the list and assigns each one
 *       a stable id.</li>
 *   <li>{@code select_branch(branchId: string, reason: string)} —
 *       commit to one of the active branches as the path forward.
 *       The id is recorded under {@link #CURRENT_KEY}.</li>
 *   <li>{@code prune_branch(branchId: string, reason: string)} —
 *       mark a branch as a dead end (stays in the list, but tagged
 *       {@code dead: true}).</li>
 * </ul>
 *
 * <h2>State</h2>
 *
 * <p>Under {@link #BRANCHES_KEY} the middleware stores a list
 * of {@link Branch} records. Under {@link #CURRENT_KEY} it stores
 * the id of the currently-active branch. Both are empty until
 * the model calls {@code think_branches}.
 *
 * <h2>Why opt-in</h2>
 *
 * <p>ToT costs LLM calls. The default {@link CreateDeepAgent} does
 * <em>not</em> add this middleware; callers wire it in explicitly
 * when they want the model to spend time exploring before acting.
 * Use {@link CreateDeepAgent#create(Object, java.util.List, String, java.util.List, java.util.List, java.util.List, java.util.List, java.util.List, org.aethercode.core.fs.backend.BackendProtocol, Map, Object, Class, Class, String)}
 * with a fresh {@link TreeOfThoughtsMiddleware} in the
 * {@code middleware} argument, or instantiate it directly for
 * custom wiring.
 */
public class TreeOfThoughtsMiddleware implements Middleware {

    /** Extension key for the current branch tree. */
    public static final String BRANCHES_KEY = "__tot_branches__";
    /** Extension key for the current branch id (or {@code null}). */
    public static final String CURRENT_KEY  = "__tot_current__";
    /** Extension key for a monotonically-increasing think depth. */
    public static final String DEPTH_KEY    = "__tot_depth__";

    /** Tool name: list candidate branches. */
    public static final String TOOL_THINK_BRANCHES = "think_branches";
    /** Tool name: commit to one branch. */
    public static final String TOOL_SELECT_BRANCH  = "select_branch";
    /** Tool name: mark a branch as dead. */
    public static final String TOOL_PRUNE_BRANCH   = "prune_branch";

    /** Minimum number of branches the model must list. */
    public static final int MIN_BRANCHES = 2;
    /** Maximum number of branches the model may list. */
    public static final int MAX_BRANCHES = 5;
    /** Default maximum length of a single branch text (characters). */
    public static final int MAX_BRANCH_TEXT = 240;

    private final String fragment;

    /**
     * Create a middleware with the default system-prompt fragment.
     * The fragment tells the model when to call {@code think_branches}
     * and how to use the rest of the suite.
     */
    public TreeOfThoughtsMiddleware() {
        this(DEFAULT_PROMPT_FRAGMENT);
    }

    /**
     * Create a middleware with a custom prompt fragment. Useful for
     * tests and for callers that want to bias the model toward a
     * different decision style (e.g. "favour breadth over depth").
     */
    public TreeOfThoughtsMiddleware(String promptFragment) {
        this.fragment = Objects.requireNonNull(promptFragment, "promptFragment");
    }

    /** The default prompt fragment. Stable text so tests can match on it. */
    public static final String DEFAULT_PROMPT_FRAGMENT =
            "Before any non-trivial decision, use think_branches to " +
            "list 2-5 candidate approaches. Pick the most promising " +
            "with select_branch, and prune dead ends with prune_branch.";

    @Override
    public String name() { return "TreeOfThoughtsMiddleware"; }

    @Override
    public AgentState beforeModel(AgentState state, Runtime runtime) {
        // Nothing to inject per-call: the fragment lives in the
        // system prompt (CreateDeepAgent routes it through
        // composed). But we do want to bump the depth on every
        // call, so a "fresh" tree starts at depth 0 and grows.
        Integer depth = currentDepth(state);
        return state.withExtension(DEPTH_KEY, depth + 1);
    }

    @Override
    public AgentState afterModel(AgentState state, AIMessage ai, Runtime runtime) {
        // Validate the model's tool use. If the model called
        // think_branches, we accept the listed branches and
        // record them; if it called select_branch / prune_branch,
        // we apply the mutation. We do NOT throw on validation
        // errors — the model is sometimes wrong, and we'd rather
        // record the attempt than crash the iteration.
        List<Map<String, Object>> ops = scanToolUses(ai);
        if (ops.isEmpty()) return state;
        AgentState next = state;
        for (Map<String, Object> op : ops) {
            String tool = (String) op.get("tool");
            @SuppressWarnings("unchecked")
            Map<String, Object> args = (Map<String, Object>) op.get("args");
            switch (tool) {
                case TOOL_THINK_BRANCHES -> next = applyThinkBranches(next, args);
                case TOOL_SELECT_BRANCH  -> next = applySelectBranch(next, args);
                case TOOL_PRUNE_BRANCH   -> next = applyPruneBranch(next, args);
                default -> { /* ignore other tools */ }
            }
        }
        return next;
    }

    // -----------------------------------------------------------------
    //  State helpers
    // -----------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private static List<Branch> branches(AgentState state) {
        Object raw = state.extensions().get(BRANCHES_KEY);
        if (raw instanceof List<?> list) {
            List<Branch> out = new ArrayList<>();
            for (Object o : list) {
                if (o instanceof Branch b) out.add(b);
            }
            return out;
        }
        return List.of();
    }

    private static String currentBranchId(AgentState state) {
        Object raw = state.extensions().get(CURRENT_KEY);
        return raw instanceof String s ? s : null;
    }

    private static int currentDepth(AgentState state) {
        Object raw = state.extensions().get(DEPTH_KEY);
        if (raw instanceof Integer i) return i;
        if (raw instanceof Number n) return n.intValue();
        return 0;
    }

    /** Visible for the test suite: render the current branches. */
    public static List<Branch> readBranches(AgentState state) {
        return branches(state);
    }

    /** Visible for the test suite: render the current branch id. */
    public static String readCurrent(AgentState state) {
        return currentBranchId(state);
    }

    // -----------------------------------------------------------------
    //  Tool-application logic
    // -----------------------------------------------------------------

    private AgentState applyThinkBranches(AgentState state, Map<String, Object> args) {
        if (args == null) return state;
        Object raw = args.get("branches");
        if (!(raw instanceof List<?> rawList)) return state;
        if (rawList.size() < MIN_BRANCHES || rawList.size() > MAX_BRANCHES) {
            return state; // out of range; ignore
        }
        String rationale = stringOrEmpty(args.get("rationale"));
        List<Branch> existing = branches(state);
        int nextOrdinal = existing.size();
        List<Branch> merged = new ArrayList<>(existing);
        for (Object o : rawList) {
            if (!(o instanceof String s)) continue;
            String text = s.length() > MAX_BRANCH_TEXT
                    ? s.substring(0, MAX_BRANCH_TEXT - 1) + "…"
                    : s;
            merged.add(new Branch("b" + nextOrdinal++, text, rationale, false));
        }
        return state.withExtension(BRANCHES_KEY, merged);
    }

    private AgentState applySelectBranch(AgentState state, Map<String, Object> args) {
        if (args == null) return state;
        String id = stringOrEmpty(args.get("branchId"));
        if (id.isEmpty()) return state;
        List<Branch> existing = branches(state);
        boolean found = false;
        for (Branch b : existing) {
            if (b.id().equals(id)) { found = true; break; }
        }
        if (!found) return state; // unknown id; ignore
        return state.withExtension(CURRENT_KEY, id);
    }

    private AgentState applyPruneBranch(AgentState state, Map<String, Object> args) {
        if (args == null) return state;
        String id = stringOrEmpty(args.get("branchId"));
        String reason = stringOrEmpty(args.get("reason"));
        if (id.isEmpty()) return state;
        List<Branch> existing = branches(state);
        List<Branch> updated = new ArrayList<>(existing.size());
        boolean found = false;
        for (Branch b : existing) {
            if (b.id().equals(id)) {
                found = true;
                updated.add(new Branch(b.id(), b.text(), reason.isEmpty() ? b.rationale() : reason, true));
            } else {
                updated.add(b);
            }
        }
        if (!found) return state;
        return state.withExtension(BRANCHES_KEY, updated);
    }

    // -----------------------------------------------------------------
    //  AI message scan
    // -----------------------------------------------------------------

    private static List<Map<String, Object>> scanToolUses(AIMessage ai) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (ContentBlock b : ai.content()) {
            if (b instanceof ContentBlock.ToolUseBlock tu) {
                String name = tu.name();
                if (TOOL_THINK_BRANCHES.equals(name)
                        || TOOL_SELECT_BRANCH.equals(name)
                        || TOOL_PRUNE_BRANCH.equals(name)) {
                    Map<String, Object> entry = new LinkedHashMap<>();
                    entry.put("id", tu.id());
                    entry.put("tool", name);
                    entry.put("args", tu.input());
                    out.add(entry);
                }
            }
        }
        return out;
    }

    private static String stringOrEmpty(Object o) {
        return o == null ? "" : o.toString();
    }

    // -----------------------------------------------------------------
    //  Branch record
    // -----------------------------------------------------------------

    /**
     * One candidate approach the model has surfaced. Stored
     * verbatim in the agent state under {@link #BRANCHES_KEY}.
     *
     * @param id         stable id assigned by the middleware
     * @param text       one-line summary of the approach
     * @param rationale  why the model proposed this branch
     *                   (free text)
     * @param dead       {@code true} once the model has pruned it
     */
    public record Branch(String id, String text, String rationale, boolean dead) {
        public Branch {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(text, "text");
            rationale = rationale == null ? "" : rationale;
        }

        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", id);
            m.put("text", text);
            m.put("rationale", rationale);
            m.put("dead", dead);
            return m;
        }
    }
}
