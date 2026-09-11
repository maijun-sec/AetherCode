package org.aethercode.permission.flow;

import org.aethercode.permission.categorize.CategoryResult;
import org.aethercode.permission.categorize.Risk;
import org.aethercode.permission.categorize.RiskCategorizer;
import org.aethercode.permission.categorize.ToolCall;
import org.aethercode.permission.flow.ConsentDecision.Allow;
import org.aethercode.permission.flow.ConsentDecision.Deny;
import org.aethercode.permission.flow.ConsentDecision.Prompt;
import org.aethercode.permission.grants.Grant;
import org.aethercode.permission.grants.GrantDecision;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * T-240..T-244 / design.md §3.3: the entry point for a tool
 * call's consent decision.
 *
 * <p>Algorithm (per design.md §3.3):
 * <ol>
 *   <li>Categorize the call via {@link RiskCategorizer}.</li>
 *   <li>If risk is {@link Risk#LOW} → auto-allow (T-242).</li>
 *   <li>For every category in the result, look up active grants
 *       across session/project/user (T-240).</li>
 *   <li>If any active DENY exists for any category → deny
 *       (T-241, deny wins).</li>
 *   <li>If every category has an active ALLOW grant → allow
 *       (use the first matching allow as the driving grant).</li>
 *   <li>Otherwise: prompt the user (T-243 for medium,
 *       T-244 for high). For medium-risk, consult the
 *       {@link SessionMemory} first so an identical call is
 *       not prompted twice in the same session.</li>
 * </ol>
 *
 * <p>Step 3's "every category covered by allow" check is the
 * per-category lookup: a single grant can cover one category
 * only, so a call with three categories needs three allow
 * grants (or a wildcard). The consent check fails as soon as
 * any category has no allow match.
 */
public final class ConsentChecker {

    private final RiskCategorizer categorizer;
    private final GrantResolver resolver;
    private final SessionMemory sessionMemory;

    /** Path triple for the resolver: userHome, projectId (just
     *  a label, the file path is constructed from cwd),
     *  cwd. Stored here so the checker can pass them through
     *  without leaking the resolver's internal API. */
    private final Path userHome;
    private final String projectId;
    private final Path cwd;

    public ConsentChecker(RiskCategorizer categorizer,
                          GrantResolver resolver,
                          SessionMemory sessionMemory,
                          Path userHome,
                          String projectId,
                          Path cwd) {
        this.categorizer = Objects.requireNonNull(categorizer, "categorizer");
        this.resolver = Objects.requireNonNull(resolver, "resolver");
        this.sessionMemory = Objects.requireNonNull(sessionMemory, "sessionMemory");
        this.userHome = userHome;
        this.projectId = projectId;
        this.cwd = cwd == null ? Path.of(".") : cwd;
    }

    /** Convenience constructor that uses the default
     *  {@link SessionMemory} and the JVM cwd. Suitable for
     *  embedded use; tests should use the full constructor. */
    public ConsentChecker(RiskCategorizer categorizer,
                          GrantResolver resolver,
                          Path userHome,
                          String projectId,
                          Path cwd) {
        this(categorizer, resolver, new SessionMemory(),
                userHome, projectId, cwd);
    }

    /** Run the consent check. */
    public ConsentDecision check(ToolCall call, String sessionId) {
        Objects.requireNonNull(call, "call");
        CategoryResult cat = categorizer.categorize(call);

        // T-242: low-risk is always auto-allow.
        if (cat.risk() == Risk.LOW) {
            return Allow.lowRisk(cat);
        }

        // T-243: medium-risk "once per session" cache. The
        // cache is consulted BEFORE the grant lookup so a
        // "Deny (this once)" in the cache wins over a matching
        // user-scope allow grant (deny wins, by design).
        if (cat.risk() == Risk.MEDIUM) {
            Optional<SessionMemory.Outcome> cached = sessionMemory.recall(call);
            if (cached.isPresent()) {
                return switch (cached.get()) {
                    case ALLOW_ONCE -> new Allow(cat.risk(), cat.categories(),
                            "allowed once this session: " + cat.explain(),
                            null, cat);
                    case DENY_ONCE -> new Deny(
                            "denied once this session: " + cat.explain(),
                            null, cat);
                };
            }
        }

        // T-240: check grants for each category.
        List<Grant> allActive = new ArrayList<>();
        for (String category : cat.categories()) {
            allActive.addAll(resolver.allGrants(sessionId, projectId, userHome, category));
        }

        // T-241: deny wins across the whole call.
        Grant deny = resolver.denyWins(allActive);
        if (deny != null && deny.decision() == GrantDecision.DENY) {
            return new Deny(
                    "denied by grant: " + deny.category() + " (" + deny.reason() + ")",
                    deny, cat);
        }

        // Auto-allow requires an active ALLOW for EVERY category.
        // (If a category had no allow, the call must prompt so
        // the user can decide the missing piece.)
        if (!cat.categories().isEmpty()) {
            boolean allAllowed = true;
            Grant firstAllow = null;
            for (String category : cat.categories()) {
                List<Grant> here = resolver.allGrants(sessionId, projectId, userHome, category);
                Grant g = resolver.denyWins(here);
                if (g == null || g.decision() != GrantDecision.ALLOW) {
                    allAllowed = false;
                    break;
                }
                if (firstAllow == null) firstAllow = g;
            }
            if (allAllowed && firstAllow != null) {
                return new Allow(cat.risk(), cat.categories(),
                        "allowed by grant: " + firstAllow.category()
                                + " (" + firstAllow.reason() + ")",
                        firstAllow, cat);
            }
        }

        // T-243 / T-244: prompt the user. The reason is the
        // categorisation's "explain()" output so the
        // ConsentPrompt can render it directly.
        return new Prompt(cat.risk(), cat.categories(),
                "needs consent: " + cat.explain(), cat);
    }

    // ------------------------------------------------------------------
    //  accessors
    // ------------------------------------------------------------------

    public RiskCategorizer categorizer() { return categorizer; }
    public GrantResolver resolver() { return resolver; }
    public SessionMemory sessionMemory() { return sessionMemory; }
    public Optional<Path> userHome() { return Optional.ofNullable(userHome); }
    public String projectId() { return projectId; }
    public Path cwd() { return cwd; }
}
