package org.aethercode.protocol.methods;

import org.aethercode.protocol.server.JsonRpcDispatcher;
import org.aethercode.tasks.rpc.GrantHandlers;

import java.util.Map;
import java.util.Objects;

/**
 * Phase 1.2 (T-1-18): protocol-side wrapper that registers
 * the four {@code grants/*} JSON-RPC methods on a
 * {@link JsonRpcDispatcher}. The actual handler logic lives
 * in {@link GrantHandlers} (in aethercode-tasks) — this
 * class is the thin adapter that exposes the new method
 * names without aethercode-tasks needing to import
 * aethercode-protocol.
 *
 * <p>The two layers communicate via {@link java.util.function.Function}
 * handles so aethercode-tasks never compiles against a
 * protocol type. See {@link GrantHandlers} for the
 * full rationale.
 *
 * <p>Wire format matches the spec:
 * <pre>
 *   grants/list    { scope?, category?, sessionId? } -> { grants: [...] }
 *   grants/revoke  { id } -> { ok, id }
 *   grants/clear   { scope, sessionId? } -> { ok, revoked, scope }
 *   grants/setPreset { preset: "permissive"|"cautious"|"strict" }
 *                  -> { ok, preset }
 * </pre>
 */
public final class GrantMethods {

    public static final String METHOD_LIST       = "grants/list";
    public static final String METHOD_REVOKE     = "grants/revoke";
    public static final String METHOD_CLEAR      = "grants/clear";
    public static final String METHOD_SET_PRESET = "grants/setPreset";

    private final GrantHandlers handlers;

    public GrantMethods(GrantHandlers handlers) {
        this.handlers = Objects.requireNonNull(handlers, "handlers");
    }

    /** Register the four {@code grants/*} methods on
     *  {@code dispatcher}. */
    public void registerAll(JsonRpcDispatcher dispatcher) {
        Objects.requireNonNull(dispatcher, "dispatcher");
        dispatcher.register(METHOD_LIST,       handlers::list);
        dispatcher.register(METHOD_REVOKE,     handlers::revoke);
        dispatcher.register(METHOD_CLEAR,      handlers::clear);
        dispatcher.register(METHOD_SET_PRESET, handlers::setPreset);
    }

    /**
     * Convenience: build a {@link GrantMethods} that
     * delegates to the given {@link PermissionMethods}. The
     * caller is expected to have set up the
     * {@code userHome} / {@code projectId} / {@code cwd}
     * on the permission methods.
     *
     * <p>The preset writer writes to
     * {@code <userHome>/.aethercode/permissions.json};
     * pass {@code null} to disable writing (tests, sandboxed
     * daemons).
     */
    public static GrantMethods from(PermissionMethods perm, java.nio.file.Path permissionsFile) {
        GrantHandlers handlers = new GrantHandlers(
                permissionsFile,
                perm::list,
                (scope, sessionId) -> {
                    java.util.Map<String, Object> p = new java.util.LinkedHashMap<>();
                    p.put("scope", scope);
                    if (sessionId != null && !sessionId.isBlank()) {
                        p.put("sessionId", sessionId);
                    }
                    Object res = perm.clear(p);
                    if (res instanceof java.util.Map<?, ?> m) {
                        Object r = m.get("revoked");
                        if (r instanceof Number n) return n.intValue();
                    }
                    return 0;
                },
                perm::revoke,
                GrantMethods::defaultPresetWriter);
        return new GrantMethods(handlers);
    }

    private static void defaultPresetWriter(java.nio.file.Path file, String preset, Object ignored) {
        if (file == null) return;
        try {
            java.nio.file.Files.createDirectories(file.getParent());
            String body = "{\"version\":1,\"preset\":\"" + preset + "\"}\n";
            java.nio.file.Files.writeString(file, body);
        } catch (java.io.IOException e) {
            throw new IllegalArgumentException(
                    "could not write permissions file: " + e.getMessage());
        }
    }

    /** Direct access for callers that want the underlying
     *  handler (e.g. integration tests that exercise the
     *  methods without going through the dispatcher). */
    public GrantHandlers handlers() { return handlers; }
}
