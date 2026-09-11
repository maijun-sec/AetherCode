package org.aethercode.permission.flow;

import org.aethercode.permission.grants.Grant;
import org.aethercode.permission.grants.GrantDecision;
import org.aethercode.permission.grants.GrantPaths;
import org.aethercode.permission.grants.GrantScope;
import org.aethercode.permission.grants.GrantsFile;
import org.aethercode.permission.grants.GrantsStorage;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * T-240..T-241 / design.md §3.1.2 + §3.3: look up the active
 * grants for a given (scope, category) tuple across the three
 * grant layers.
 *
 * <p>Resolution order: <strong>session &gt; project &gt; user</strong>.
 * A session deny beats a project allow beats a user allow (see
 * {@link #denyWins(List, List)} for the actual algorithm).
 *
 * <p>Filter pipeline (in this order, applied to every layer):
 * <ol>
 *   <li>scope matches the layer we're reading</li>
 *   <li>category matches (exact, case-sensitive)</li>
 *   <li>the grant is not expired (see
 *       {@link Grant#isExpired(long)})</li>
 * </ol>
 *
 * <p>The resolver does not know the file locations by itself —
 * the caller passes in the three {@link Path}s (or {@code null}
 * for layers that don't apply, e.g. user scope when running
 * without a home directory). This keeps the class testable
 * (a temp-dir test can wire up synthetic paths without
 * touching the real {@code ~/.aethercode} tree).
 */
public class GrantResolver {

    private final GrantsStorage storage;
    private final long now;

    public GrantResolver() {
        this(new GrantsStorage(), System.currentTimeMillis());
    }

    public GrantResolver(GrantsStorage storage) {
        this(storage, System.currentTimeMillis());
    }

    /** Constructor used by tests to freeze "now" so the
     *  {@link Grant#isExpired} check is deterministic. */
    public GrantResolver(GrantsStorage storage, long now) {
        this.storage = Objects.requireNonNull(storage, "storage");
        this.now = now;
    }

    /** T-240: list every non-expired grant for {@code category}
     *  at the session layer. The list may be empty. */
    public List<Grant> sessionGrants(String sessionId, String category) {
        if (sessionId == null || sessionId.isBlank()) return List.of();
        return readActive(GrantPaths.sessionGrantsFile(cwd(), sessionId), category, GrantScope.SESSION);
    }

    /** T-240: list every non-expired grant for {@code category}
     *  at the project layer. */
    public List<Grant> projectGrants(String projectId, String category) {
        if (projectId == null || projectId.isBlank()) return List.of();
        return readActive(GrantPaths.projectGrantsFile(cwd()), category, GrantScope.PROJECT);
    }

    /** T-240: list every non-expired grant for {@code category}
     *  at the user layer. The {@code userHome} may be null (e.g.
     *  sandbox without a home) — in that case the user layer is
     *  empty. */
    public List<Grant> userGrants(Path userHome, String category) {
        Path p = GrantPaths.userGrantsFile(userHome);
        if (p == null) return List.of();
        return readActive(p, category, GrantScope.USER);
    }

    /** T-240: convenience — gather every active grant for
     *  {@code category} across all three layers. The order is
     *  [session, project, user]; callers that need the
     *  resolution policy should use {@link #denyWins} on the
     *  result. */
    public List<Grant> allGrants(String sessionId, String projectId,
                                 Path userHome, String category) {
        List<Grant> out = new ArrayList<>();
        out.addAll(sessionGrants(sessionId, category));
        out.addAll(projectGrants(projectId, category));
        out.addAll(userGrants(userHome, category));
        return out;
    }

    /** T-241: deny wins. Walk the (ordered!) list of active
     *  grants for a category; the first {@link GrantDecision#DENY}
     *  we see is the final answer (regardless of scope). If
     *  there is no deny, the first {@link GrantDecision#ALLOW}
     *  wins. Returns {@code null} when no active grant matches —
     *  the caller (the consent checker) then falls back to its
     *  default behaviour (prompt or low-risk auto-allow).
     *
     *  <p>Per design.md §3.1.2: "a session deny wins over a
     *  project allow wins over a user allow". This method
     *  implements that literally: the first deny in the list
     *  short-circuits; absent a deny, the first allow wins.
     */
    public Grant denyWins(List<Grant> active) {
        if (active == null || active.isEmpty()) return null;
        for (Grant g : active) {
            if (g.decision() == GrantDecision.DENY) return g;
        }
        for (Grant g : active) {
            if (g.decision() == GrantDecision.ALLOW) return g;
        }
        return null;
    }

    // ------------------------------------------------------------------
    //  helpers
    // ------------------------------------------------------------------

    private List<Grant> readActive(Path file, String category, GrantScope scope) {
        if (file == null || category == null) return List.of();
        GrantsFile grants;
        try {
            grants = storage.read(file);
        } catch (RuntimeException e) {
            // A broken grants file should not blow up the
            // permission check. Surface to the caller as "no
            // active grants at this layer" so the rest of the
            // pipeline (the consent prompt) can still run.
            return List.of();
        }
        List<Grant> out = new ArrayList<>();
        for (Grant g : grants.grants()) {
            if (g.scope() != scope) continue;
            if (!category.equals(g.category())) continue;
            if (g.isExpired(now)) continue;
            out.add(g);
        }
        return out;
    }

    /** The project cwd the resolver reads from. Hard-wired to
     *  {@code "."} here — the consent flow owns the project
     *  root via its constructor. The resolver is intentionally
     *  a pure file-walker, so the cwd injection point is
     *  small (one method). Tests override {@link #cwd()} by
     *  subclassing or by passing absolute paths to the layer
     *  methods. */
    protected Path cwd() {
        return Path.of(".");
    }
}
