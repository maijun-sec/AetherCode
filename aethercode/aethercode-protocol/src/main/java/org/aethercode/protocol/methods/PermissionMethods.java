package org.aethercode.protocol.methods;

import org.aethercode.permission.audit.GrantsAuditLog;
import org.aethercode.permission.categorize.DefaultRules;
import org.aethercode.permission.categorize.RiskCategorizer;
import org.aethercode.permission.categorize.ToolCall;
import org.aethercode.permission.flow.ConsentChecker;
import org.aethercode.permission.flow.ConsentDecision;
import org.aethercode.permission.grants.Grant;
import org.aethercode.permission.grants.GrantPaths;
import org.aethercode.permission.grants.GrantScope;
import org.aethercode.permission.grants.GrantsFile;
import org.aethercode.permission.grants.GrantsStorage;
import org.aethercode.protocol.jsonrpc.JsonRpcError;
import org.aethercode.protocol.jsonrpc.JsonRpcProtocolException;
import org.aethercode.protocol.server.JsonRpcDispatcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * T-275 / design.md §3.6: registers the five
 * {@code permission/*} JSON-RPC methods on a
 * {@link JsonRpcDispatcher} and wires them to the live
 * consent flow ({@link ConsentChecker}) + the
 * on-disk grant store ({@link GrantsStorage}) + the
 * audit log ({@link GrantsAuditLog}).
 *
 * <p>Methods exposed:
 * <ul>
 *   <li>{@code permission/check}  — T-270: classify a tool call
 *       and return the decision ({@code allow} / {@code deny} /
 *       {@code prompt}). The daemon uses this to drive its
 *       pre-flight check before every tool call.</li>
 *   <li>{@code permission/prompt} — T-271: synchronous variant of
 *       {@code check} that returns the same shape. The TUI calls
 *       this when the user is actively looking at the prompt
 *       (the difference from {@code check} is that {@code prompt}
 *       also writes a {@code PROMPTED} audit-log entry so the
 *       "user decided" event is captured).</li>
 *   <li>{@code permission/list}   — T-272: enumerate every active
 *       grant at the requested scope (or all three scopes when
 *       the parameter is absent).</li>
 *   <li>{@code permission/revoke} — T-273: delete a single grant
 *       by id. The grant's scope is inferred from the id's
 *       on-disk location, so the caller only needs the id.</li>
 *   <li>{@code permission/clear}  — T-274: bulk-delete every
 *       grant in a scope ({@code session} / {@code project} /
 *       {@code user}). Returns the number revoked.</li>
 * </ul>
 *
 * <p>Wire format (per design.md §3.6):
 * <pre>
 *   "permission/check":  { tool, args, sessionId } → { allow, reason?, requiresPrompt, risk, categories }
 *   "permission/prompt": { tool, args, sessionId } → { allow, reason, requiresPrompt, risk, categories, drivingGrantId? }
 *   "permission/list":   { scope? }                → Grant[]
 *   "permission/revoke": { id }                    → { ok: true }
 *   "permission/clear":  { scope }                 → { revoked: number }
 * </pre>
 *
 * <p>Paths: the class resolves grants.json files via
 * {@link GrantPaths}, which gives {@code <userHome>/.aethercode/...}.
 * A null userHome is allowed (sandbox) and the user layer
 * silently returns an empty list.
 */
public final class PermissionMethods {

    private static final Logger LOG = LoggerFactory.getLogger(PermissionMethods.class);

    public static final String METHOD_CHECK   = "permission/check";
    public static final String METHOD_PROMPT  = "permission/prompt";
    public static final String METHOD_LIST    = "permission/list";
    public static final String METHOD_REVOKE  = "permission/revoke";
    public static final String METHOD_CLEAR   = "permission/clear";

    private final ConsentChecker checker;
    private final GrantsStorage storage;
    private final GrantsAuditLog auditLog;
    private final Path userHome;
    private final String projectId;
    private final Path cwd;

    /**
     * Build a {@code PermissionMethods} wired to the live
     * {@code ConsentChecker}, storage, and audit log. The
     * {@code userHome} is the conventional
     * {@code ~/.aethercode} root; {@code projectId} is the
     * project label (often the cwd's basename) and {@code cwd}
     * is the working directory the grant files are relative to.
     * A null userHome is permitted for sandbox runs.
     */
    public PermissionMethods(ConsentChecker checker,
                             GrantsStorage storage,
                             GrantsAuditLog auditLog,
                             Path userHome,
                             String projectId,
                             Path cwd) {
        this.checker = Objects.requireNonNull(checker, "checker");
        this.storage = Objects.requireNonNull(storage, "storage");
        this.auditLog = Objects.requireNonNull(auditLog, "auditLog");
        this.userHome = userHome;
        this.projectId = projectId;
        this.cwd = cwd == null ? Path.of(".") : cwd;
    }

    /**
     * Convenience constructor that builds a default
     * {@link ConsentChecker} backed by the default
     * {@link RiskCategorizer} and {@code GrantsStorage}. The
     * caller is expected to have set the project id already
     * (the resolver's scope check is a no-op for an empty id,
     * so missing it just means the project layer is empty).
     */
    public static PermissionMethods defaults(Path userHome,
                                             String projectId,
                                             Path cwd) {
        RiskCategorizer categorizer = new RiskCategorizer(DefaultRules.all());
        GrantsStorage storage = new GrantsStorage();
        ConsentChecker checker = new ConsentChecker(
                categorizer,
                new org.aethercode.permission.flow.GrantResolver(storage),
                userHome, projectId, cwd);
        GrantsAuditLog audit = GrantsAuditLog.forUserHome(userHome);
        return new PermissionMethods(checker, storage, audit, userHome, projectId, cwd);
    }

    // ------------------------------------------------------------------
    //  Registration
    // ------------------------------------------------------------------

    public void registerAll(JsonRpcDispatcher dispatcher) {
        Objects.requireNonNull(dispatcher, "dispatcher");
        dispatcher.register(METHOD_CHECK,  this::check);
        dispatcher.register(METHOD_PROMPT, this::prompt);
        dispatcher.register(METHOD_LIST,   this::list);
        dispatcher.register(METHOD_REVOKE, this::revoke);
        dispatcher.register(METHOD_CLEAR,  this::clear);
    }

    // ------------------------------------------------------------------
    //  T-270 / T-271 — permission/check + permission/prompt
    // ------------------------------------------------------------------

    /**
     * {@code permission/check}. Returns a {@code {allow, reason,
     * requiresPrompt, risk, categories}} object. Pure — does not
     * write to the audit log (the daemon is expected to ask
     * {@code permission/prompt} when the user actually faces a
     * prompt).
     */
    public Map<String, Object> check(Object params) {
        CheckArgs args = parseCheckArgs(params);
        ToolCall call = new ToolCall(args.tool, args.args);
        ConsentDecision decision = checker.check(call, args.sessionId);
        return decisionToMap(decision, /*writeAudit=*/ false, null);
    }

    /**
     * {@code permission/prompt}. Like {@code check} but also
     * records a {@code PROMPTED} audit entry. The
     * {@code actor} field on the audit entry is sourced from
     * the {@code actor} param (defaults to {@code "system"}).
     */
    public Map<String, Object> prompt(Object params) {
        CheckArgs args = parseCheckArgs(params);
        ToolCall call = new ToolCall(args.tool, args.args);
        ConsentDecision decision = checker.check(call, args.sessionId);
        // Write audit — only for allow/deny outcomes (the
        // PROMPT case itself doesn't need an entry yet; one
        // is written when the user actually answers).
        if (decision.outcome() != ConsentDecision.Outcome.PROMPT) {
            auditLog.logPrompted(
                    scopeFor(decision),
                    scopeIdFor(decision),
                    firstCategory(decision),
                    decision.isAllow()
                            ? org.aethercode.permission.grants.GrantDecision.ALLOW
                            : org.aethercode.permission.grants.GrantDecision.DENY,
                    decision.reason(),
                    null,
                    summariseCall(call),
                    args.actor);
        }
        return decisionToMap(decision, /*writeAudit=*/ true, args.actor);
    }

    // ------------------------------------------------------------------
    //  T-272 — permission/list
    // ------------------------------------------------------------------

    /**
     * {@code permission/list}. Returns every active (non-expired)
     * grant at the requested scope, or all three scopes when
     * {@code scope} is absent. Session grants are scoped to the
     * {@code sessionId} param (default: none → empty).
     */
    public List<Map<String, Object>> list(Object params) {
        List<String> scopes = new ArrayList<>();
        String sessionId = null;
        if (params instanceof Map<?, ?> m) {
            Object s = m.get("scope");
            Object sid = m.get("sessionId");
            if (s != null) {
                String wire = String.valueOf(s);
                scopes.add(wire);
            } else {
                scopes.add("session");
                scopes.add("project");
                scopes.add("user");
            }
            if (sid != null) sessionId = String.valueOf(sid);
        } else {
            scopes.add("session");
            scopes.add("project");
            scopes.add("user");
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (String wire : scopes) {
            GrantScope scope = GrantScope.fromWire(wire);
            Path file = grantsFileFor(scope, sessionId);
            if (file == null) continue;
            GrantsFile gf;
            try {
                gf = storage.read(file);
            } catch (RuntimeException e) {
                LOG.debug("permission/list: read failed for {}: {}", file, e.toString());
                continue;
            }
            long now = System.currentTimeMillis();
            for (Grant g : gf.grants()) {
                if (g.isExpired(now)) continue;
                if (g.scope() != scope) continue;
                out.add(grantToMap(g));
            }
        }
        return out;
    }

    // ------------------------------------------------------------------
    //  T-273 — permission/revoke
    // ------------------------------------------------------------------

    /**
     * {@code permission/revoke}. Locates the grant by id
     * (scanning all three layers), removes it, and writes the
     * updated file. The id is opaque to the caller — the
     * resolver figures out the scope.
     */
    public Map<String, Object> revoke(Object params) {
        if (!(params instanceof Map<?, ?> m)) {
            throw new JsonRpcProtocolException(
                    "permission/revoke: params must be an object",
                    JsonRpcError.invalidParams("expected object"));
        }
        Object idRaw = m.get("id");
        if (idRaw == null) {
            throw new JsonRpcProtocolException(
                    "permission/revoke: id is required",
                    JsonRpcError.invalidParams("missing id"));
        }
        String id = String.valueOf(idRaw);
        String actor = m.get("actor") == null ? "system" : String.valueOf(m.get("actor"));

        // 1) User + project layers (single file each).
        for (GrantScope scope : new GrantScope[]{GrantScope.USER, GrantScope.PROJECT}) {
            Path file = grantsFileFor(scope, null);
            if (file == null || !Files.exists(file)) continue;
            Map<String, Object> hit = tryRevokeFromFile(file, id, actor);
            if (hit != null) return hit;
        }
        // 2) Session layer: walk every session subdir under
        //    <cwd>/.aethercode/sessions/.
        Path sessionsRoot = cwd.resolve(GrantPaths.PROJECT_DIR)
                .resolve(GrantPaths.SESSIONS_SUBDIR);
        if (Files.isDirectory(sessionsRoot)) {
            try (java.util.stream.Stream<Path> stream = Files.list(sessionsRoot)) {
                List<Path> dirs = stream.filter(Files::isDirectory).toList();
                for (Path d : dirs) {
                    Path file = d.resolve(GrantsFile.FILE_NAME);
                    if (!Files.exists(file)) continue;
                    Map<String, Object> hit = tryRevokeFromFile(file, id, actor);
                    if (hit != null) return hit;
                }
            } catch (java.io.IOException ioe) {
                // best-effort; fall through to the not-found
                // exception.
            }
        }
        throw new JsonRpcProtocolException(
                "permission/revoke: id not found: " + id,
                JsonRpcError.of(-32604, "id not found: " + id));
    }

    /** Revoke a grant by id from {@code file}. Returns the
     *  success map when found, null otherwise. */
    private Map<String, Object> tryRevokeFromFile(Path file, String id, String actor) {
        GrantsFile gf;
        try {
            gf = storage.read(file);
        } catch (RuntimeException e) {
            return null;
        }
        for (Grant g : gf.grants()) {
            if (!id.equals(g.id())) continue;
            // Re-write the file without the matching grant.
            List<Grant> remaining = new ArrayList<>(gf.grants());
            remaining.removeIf(x -> id.equals(x.id()));
            GrantsFile next = new GrantsFile(GrantsFile.CURRENT_SCHEMA_VERSION, remaining);
            storage.write(file, next);
            // Audit.
            auditLog.logRevoked(g, actor);
            return Map.of("ok", Boolean.TRUE, "id", id);
        }
        return null;
    }

    // ------------------------------------------------------------------
    //  T-274 — permission/clear
    // ------------------------------------------------------------------

    /**
     * {@code permission/clear}. Removes every grant at the
     * requested scope, writes a fresh empty file, and writes
     * one {@code REVOKED} audit entry per removed grant.
     * Returns the number of grants removed.
     */
    public Map<String, Object> clear(Object params) {
        if (!(params instanceof Map<?, ?> m)) {
            throw new JsonRpcProtocolException(
                    "permission/clear: params must be an object",
                    JsonRpcError.invalidParams("expected object"));
        }
        Object s = m.get("scope");
        if (s == null) {
            throw new JsonRpcProtocolException(
                    "permission/clear: scope is required",
                    JsonRpcError.invalidParams("missing scope"));
        }
        GrantScope scope = GrantScope.fromWire(String.valueOf(s));
        String sessionId = m.get("sessionId") == null ? null : String.valueOf(m.get("sessionId"));
        String actor = m.get("actor") == null ? "system" : String.valueOf(m.get("actor"));

        Path file = grantsFileFor(scope, sessionId);
        if (file == null) {
            // No file at this layer (e.g. user layer in
            // sandbox) — nothing to clear.
            return Map.of("revoked", 0);
        }
        GrantsFile gf;
        try {
            gf = storage.read(file);
        } catch (RuntimeException e) {
            // Treat read failure as "nothing to clear".
            return Map.of("revoked", 0);
        }
        long now = System.currentTimeMillis();
        List<Grant> alive = new ArrayList<>();
        List<Grant> removed = new ArrayList<>();
        for (Grant g : gf.grants()) {
            if (g.isExpired(now)) continue;
            if (g.scope() != scope) continue;
            if (scope == GrantScope.SESSION && sessionId != null
                    && !sessionId.equals(g.scopeId())) {
                // Session-scope clear with an explicit
                // sessionId should not touch other sessions.
                alive.add(g);
                continue;
            }
            removed.add(g);
        }
        // Write back the survivors as a fresh file.
        GrantsFile next = new GrantsFile(GrantsFile.CURRENT_SCHEMA_VERSION, alive);
        storage.write(file, next);
        // One REVOKED audit entry per removed grant.
        auditLog.logRevokedBulk(removed, actor);
        return Map.of("revoked", removed.size());
    }

    // ------------------------------------------------------------------
    //  helpers
    // ------------------------------------------------------------------

    /** Map a {@link ConsentDecision} to the wire shape used by
     *  both {@code permission/check} and {@code permission/prompt}.
     */
    private static Map<String, Object> decisionToMap(ConsentDecision d, boolean writeAudit, String actor) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("allow", d.isAllow());
        m.put("requiresPrompt", d.isPrompt());
        m.put("reason", d.reason());
        m.put("risk", d.risk().wire());
        m.put("categories", d.categories());
        if (d instanceof ConsentDecision.Allow a) {
            a.drivingGrantOpt().ifPresent(g -> m.put("drivingGrantId", g.id()));
        } else if (d instanceof ConsentDecision.Deny den) {
            den.drivingGrantOpt().ifPresent(g -> m.put("drivingGrantId", g.id()));
        }
        m.put("outcome", d.outcome().name().toLowerCase());
        return m;
    }

    private CheckArgs parseCheckArgs(Object params) {
        String tool;
        Map<String, Object> args;
        String sessionId;
        String actor;
        if (!(params instanceof Map<?, ?> m)) {
            throw new JsonRpcProtocolException(
                    "permission/check: params must be an object",
                    JsonRpcError.invalidParams("expected object"));
        }
        Object t = m.get("tool");
        if (t == null) {
            throw new JsonRpcProtocolException(
                    "permission/check: tool is required",
                    JsonRpcError.invalidParams("missing tool"));
        }
        tool = String.valueOf(t);
        Object a = m.get("args");
        if (a == null) {
            args = Map.of();
        } else if (a instanceof Map<?, ?> am) {
            @SuppressWarnings("unchecked")
            Map<String, Object> cast = (Map<String, Object>) am;
            args = cast;
        } else {
            throw new JsonRpcProtocolException(
                    "permission/check: args must be an object",
                    JsonRpcError.invalidParams("args must be an object"));
        }
        Object sid = m.get("sessionId");
        sessionId = sid == null ? null : String.valueOf(sid);
        Object ac = m.get("actor");
        actor = ac == null ? "system" : String.valueOf(ac);
        return new CheckArgs(tool, args, sessionId, actor);
    }

    /** Resolve the on-disk file for {@code scope} (and an
     *  optional sessionId for the session layer). */
    private Path grantsFileFor(GrantScope scope, String sessionId) {
        return switch (scope) {
            case USER    -> GrantPaths.userGrantsFile(userHome);
            case PROJECT -> GrantPaths.projectGrantsFile(cwd);
            case SESSION -> sessionId == null || sessionId.isBlank()
                    ? null
                    : GrantPaths.sessionGrantsFile(cwd, sessionId);
        };
    }

    private static GrantScope scopeFor(ConsentDecision d) {
        if (d instanceof ConsentDecision.Allow a) {
            return a.drivingGrantOpt().map(Grant::scope).orElse(GrantScope.SESSION);
        }
        if (d instanceof ConsentDecision.Deny den) {
            return den.drivingGrantOpt().map(Grant::scope).orElse(GrantScope.SESSION);
        }
        return GrantScope.SESSION;
    }

    private static String scopeIdFor(ConsentDecision d) {
        if (d instanceof ConsentDecision.Allow a) {
            return a.drivingGrantOpt().map(Grant::scopeId).orElse("");
        }
        if (d instanceof ConsentDecision.Deny den) {
            return den.drivingGrantOpt().map(Grant::scopeId).orElse("");
        }
        return "";
    }

    private static String firstCategory(ConsentDecision d) {
        return d.categories().isEmpty() ? "" : d.categories().get(0);
    }

    private static String summariseCall(ToolCall call) {
        Object cmd = call.args().get("command");
        if (cmd != null) return call.tool() + " " + cmd;
        Object p = call.args().get("path");
        if (p != null) return call.tool() + " " + p;
        return call.tool();
    }

    private static Map<String, Object> grantToMap(Grant g) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", g.id());
        m.put("scope", g.scope().wire());
        m.put("scopeId", g.scopeId());
        m.put("category", g.category());
        m.put("decision", g.decision().wire());
        m.put("reason", g.reason());
        m.put("createdAt", g.createdAt());
        if (g.expiresAt() != null) m.put("expiresAt", g.expiresAt());
        return m;
    }

    /** Mutable holder for permission/check params. */
    private record CheckArgs(String tool,
                             Map<String, Object> args,
                             String sessionId,
                             String actor) {}
}
