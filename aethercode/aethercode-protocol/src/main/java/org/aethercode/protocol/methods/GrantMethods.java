/*
 * Copyright (c) 2026 maijun
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

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

    // ------------------------------------------------------------------
    //  R367 — direct passthroughs.
    //
    //  Until this round the ONLY public member was registerAll, so
    //  `grants/*` was reachable exclusively through a
    //  JsonRpcDispatcher. The HTTP+WS daemon does not use a
    //  dispatcher — it has a hand-rolled switch — which meant there
    //  was literally no way to route grants/* over HTTP even after
    //  the daemon was fixed. A method class that can only be reached
    //  through one transport is a one-transport feature.
    //
    //  These four mirror registerAll exactly: same handler, same
    //  arguments, no behaviour of their own.
    // ------------------------------------------------------------------

    /** {@code grants/list}. */
    public java.util.Map<String, Object> list(Object params) {
        return handlers.list(params);
    }

    /** {@code grants/revoke}. */
    public java.util.Map<String, Object> revoke(Object params) {
        return handlers.revoke(params);
    }

    /** {@code grants/clear}. */
    public java.util.Map<String, Object> clear(Object params) {
        return handlers.clear(params);
    }

    /** {@code grants/setPreset}. */
    public java.util.Map<String, Object> setPreset(Object params) {
        return handlers.setPreset(params);
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
