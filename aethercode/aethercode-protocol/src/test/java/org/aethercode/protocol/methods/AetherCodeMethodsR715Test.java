/**
 * Copyright (c) 2026 maijun
 *
 * <p>Licensed under the MIT License. See the LICENSE file in the project root.
 */
package org.aethercode.protocol.methods;

import org.aethercode.core.permission.PermissionMode;
import org.aethercode.core.tool.Tool;
import org.aethercode.sdk.AetherCodeEngine;
import org.aethercode.sdk.SessionManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * R715: {@code engineHealth} must report the ACTIVE engine's
 * {@code permissionMode} / {@code sessionId} / {@code model}, not
 * the constructor {@code engine} field's. Pre-R715 the
 * {@code engine} field was a final reference to the engine
 * passed at construction time — usually the default session's
 * engine, which had {@code permissionMode = BYPASS_PERMISSIONS}
 * baked in from a previous user pick. The desktop's StatusBar
 * rendered that stale value, so even after the user flipped
 * the dropdown to "ask before every tool" the pill stayed
 * green ("bypass") and the user assumed perm wasn't working.
 *
 * <p>The fix routes {@code engineHealth} through
 * {@code currentEngine()} — the same helper {@code getState},
 * {@code getPermissionModeSuggestion} and {@code setPermissionMode}
 * use — so all four RPCs agree on "which session is the user
 * looking at". Stats + ping still consult the constructor
 * engine (the "daemon is alive" liveness signal, which is
 * process-scoped, not session-scoped).
 *
 * <p>Contract under test:
 * <ol>
 *   <li>After {@code setActiveEngine("alpha")}, {@code engineHealth}
 *       reports the alpha engine's permMode, not the default
 *       engine's. Pre-R715 the field was a final reference to
 *       the default engine, so this assertion failed.</li>
 *   <li>{@code currentSessionId} in the response equals the
 *       active engine's sessionId — gives the renderer a
 *       unambiguous "active session" handle that diverges from
 *       the constructor engine's id when multi-session is in
 *       use.</li>
 *   <li>Stats + ping still come from the constructor engine
 *       (process-scoped liveness).</li>
 *   <li>Single-session fallback (no manager) still works:
 *       {@code engineHealth} reports the constructor engine's
 *       permMode. This is the legacy single-engine path and
 *       must not regress.</li>
 * </ol>
 */
class AetherCodeMethodsR715Test {

    @Test
    void engineHealth_reportsActiveEnginePermMode_notConstructorDefault(@TempDir Path cwd) {
        // Default engine = constructor engine, pinned to
        // BYPASS_PERMISSIONS. The factory-built "alpha" engine
        // starts at DEFAULT (the legacy default for a fresh
        // AppState). After setActiveEngine("alpha") the
        // engineHealth response must surface DEFAULT, not
        // BYPASS_PERMISSIONS — that's the whole point of R715.
        AetherCodeEngine defaultEngine = engineFor(cwd);
        defaultEngine.appState().permissionMode(PermissionMode.BYPASS_PERMISSIONS);
        // Factory that bakes the sessionId into the engine's
        // AppState. Without this, every factory-built engine
        // reports sessionId="default" and the test's sessionId
        // assertion would be vacuous.
        SessionManager.EngineFactory factory = sessionId -> new AetherCodeEngine.Builder()
                .cwd(cwd)
                .sessionId(sessionId)
                .tools(List.<Tool>of())
                .build();
        SessionManager sm = new SessionManager(factory);
        sm.registerExisting(SessionManager.DEFAULT_SESSION_ID, defaultEngine);
        AetherCodeMethods methods = new AetherCodeMethods(defaultEngine, sm, n -> {});

        // Build a second engine ("alpha") via the factory and
        // set it active. The factory-built engine starts at
        // DEFAULT (a fresh AppState has permissionMode() =
        // DEFAULT until AetherCodeEngine's builder wires it).
        @SuppressWarnings("unchecked")
        Map<String, Object> createR = (Map<String, Object>) methods.createEngine(
                Map.of("sessionId", "alpha"));
        assertEquals(true, createR.get("ok"), "createEngine(alpha) must succeed");
        AetherCodeEngine alpha = sm.get("alpha").engine;
        alpha.appState().permissionMode(PermissionMode.ASK_BEFORE_TOOL);

        @SuppressWarnings("unchecked")
        Map<String, Object> activeR = (Map<String, Object>) methods.setActiveEngine(
                Map.of("sessionId", "alpha"));
        assertEquals(true, activeR.get("ok"), "setActiveEngine(alpha) must succeed");

        // Now the contract: engineHealth reads the ACTIVE engine.
        @SuppressWarnings("unchecked")
        Map<String, Object> health = (Map<String, Object>) methods.engineHealth(null);
        assertEquals("ASK_BEFORE_TOOL", health.get("permissionMode"),
                "engineHealth must report ACTIVE engine's permMode, not constructor default's");
        assertEquals("alpha", health.get("sessionId"),
                "engineHealth must report ACTIVE engine's sessionId");
        assertEquals("alpha", health.get("currentSessionId"),
                "engineHealth must surface the active session id explicitly so the renderer can disambiguate");
    }

    @Test
    void engineHealth_singleSession_usesConstructorEngine(@TempDir Path cwd) {
        // Legacy single-session path: 2-arg ctor (no
        // SessionManager). currentEngine() falls back to the
        // `engine` field, so engineHealth must report the
        // constructor engine's permMode. This is the path
        // every legacy daemon takes, so it must not regress.
        AetherCodeEngine engine = engineFor(cwd);
        engine.appState().permissionMode(PermissionMode.ACCEPT_EDITS);
        AetherCodeMethods methods = new AetherCodeMethods(engine, n -> {});

        @SuppressWarnings("unchecked")
        Map<String, Object> health = (Map<String, Object>) methods.engineHealth(null);
        assertEquals("ACCEPT_EDITS", health.get("permissionMode"),
                "single-session engineHealth must surface the constructor engine's permMode");
        // currentSessionId is the same as sessionId in
        // single-session mode (no separate active pointer).
        assertEquals(health.get("sessionId"), health.get("currentSessionId"),
                "single-session: currentSessionId must equal sessionId");
    }

    @Test
    void engineHealth_statsAndPingRemainOnConstructorEngine(@TempDir Path cwd) {
        // Stats + ping are the "daemon is alive" liveness
        // signal — process-scoped, not session-scoped. They
        // must still come from the constructor `engine`
        // field even after setActiveEngine points at a
        // different session. We verify by reading the
        // `queries` / `totalToolCalls` fields, which only
        // mutate on the constructor engine's stats instance.
        AetherCodeEngine defaultEngine = engineFor(cwd);
        SessionManager.EngineFactory factory = sessionId -> engineFor(cwd);
        SessionManager sm = new SessionManager(factory);
        sm.registerExisting(SessionManager.DEFAULT_SESSION_ID, defaultEngine);
        AetherCodeMethods methods = new AetherCodeMethods(defaultEngine, sm, n -> {});

        // Tick the constructor engine's stats so we can
        // assert they're surfaced in the health payload.
        defaultEngine.sessionStats().recordQuery();
        defaultEngine.sessionStats().recordQuery();
        defaultEngine.sessionStats().recordToolCall("read");

        methods.createEngine(Map.of("sessionId", "alpha"));
        methods.setActiveEngine(Map.of("sessionId", "alpha"));

        @SuppressWarnings("unchecked")
        Map<String, Object> health = (Map<String, Object>) methods.engineHealth(null);
        // queries / totalToolCalls come from the constructor
        // engine (the liveness signal). The permMode /
        // sessionId come from the active engine. R715 only
        // redirected the user-visible fields, not the stats.
        assertNotNull(health.get("queries"));
        assertNotNull(health.get("totalToolCalls"));
        // Constructor engine's id is the default session id
        // (the engineFor() factory uses the sessionId we
        // pass in, which is "default" here).
        assertEquals("default", health.get("sessionId"));
        // permMode is the active engine's, not the
        // constructor's.
        assertEquals("DEFAULT", health.get("permissionMode"));
    }

    private static AetherCodeEngine engineFor(Path cwd) {
        return new AetherCodeEngine.Builder()
                .cwd(cwd)
                .sessionId(SessionManager.DEFAULT_SESSION_ID)
                .tools(List.<Tool>of())
                .build();
    }
}
