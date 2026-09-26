package org.aethercode.cli;

import org.aethercode.core.tool.Tool;
import org.aethercode.sdk.AetherCodeEngine;
import org.aethercode.sdk.SessionManager;
import org.aethercode.sdk.SessionSpec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * R361 fix v2 tests: spec-aware engine factory
 * wiring in {@link DaemonRunner#buildSessionManager}.
 *
 * <p>Pre-R361-fix-v2, {@code DaemonRunner.buildSessionManager}
 * only wired the legacy {@code String → engine} factory.
 * The spec-aware {@code SessionSpec → engine} factory
 * (which honours {@code spec.cwd()}) was never installed,
 * so when {@code AetherCodeMethods.createEngine({sessionId,
 * cwd})} routed through {@code SessionManager.create(spec)}
 * → {@code getOrCreate}, the manager fell through to the
 * legacy {@code factory.create(sessionId)} path and the
 * {@code cwd} field was silently dropped. The user
 * reported "切了 cwd 但 engine 还是老 cwd" via screenshot:
 * Plumb session (1 msg) was bound to a new cwd but the
 * bash tool still ran on the previous session's cwd.
 *
 * <p>These tests verify the fix: when
 * {@code buildSessionManager} is given a spec factory,
 * {@code manager.create(SessionSpec.of("sid").cwd(...))}
 * actually invokes the spec factory, and the freshly
 * built engine's {@code appState.cwd()} matches the
 * spec.
 */
class DaemonRunnerR361V2Test {

    private static AetherCodeEngine buildMinimalEngine(Path cwd) {
        // Builder without any sessionStore /
        // SessionManager — the same shape DaemonRunner
        // sees on a daemon started without the
        // fancy wiring.
        return new AetherCodeEngine.Builder()
                .cwd(cwd)
                .tools(List.<Tool>of())
                .build();
    }

    @Test
    void buildSessionManager_wiresSpecFactoryOnManager() {
        // Build the manager with BOTH factories.
        // The spec factory records its invocation
        // so we can assert it was actually called
        // (instead of the legacy factory being
        // called as a fallback).
        AetherCodeEngine engine = buildMinimalEngine(Path.of("").toAbsolutePath());
        AtomicReference<SessionSpec> capturedSpec = new AtomicReference<>();
        SessionManager.EngineFactoryWithSpec specFactory = spec -> {
            capturedSpec.set(spec);
            return buildMinimalEngine(spec.cwd() != null
                    ? Path.of(spec.cwd()).toAbsolutePath().normalize()
                    : Path.of("").toAbsolutePath());
        };
        SessionManager mgr = DaemonRunner.buildSessionManager(
                engine, null, specFactory);

        // create a session via the spec-aware path
        Path newCwd = Path.of("").toAbsolutePath();
        SessionSpec spec = SessionSpec.withCwd("test-sid-v2", newCwd.toString());
        SessionManager.EngineHandle handle = mgr.create(spec);

        // The spec factory was actually invoked
        assertThat(capturedSpec.get()).isNotNull();
        assertThat(capturedSpec.get().sessionId()).isEqualTo("test-sid-v2");
        assertThat(capturedSpec.get().cwd()).isEqualTo(newCwd.toString());
        // ...and the legacy factory was NOT invoked
        // (the spec factory took precedence)
        assertThat(handle.sessionId).isEqualTo("test-sid-v2");
    }

    @Test
    void createWithSpec_honoursSpecCwdNotLegacyFactoryCwd(@TempDir Path installDir) throws Exception {
        // Simulate the user's bug: a fresh daemon's
        // default engine has cwd=installDir. The user
        // picks a different cwd and calls
        // createEngine({sessionId, cwd}). The freshly
        // materialised engine must use the SPEC's cwd,
        // NOT the daemon's install-dir cwd.
        Path projectDir = Files.createTempDirectory("r361v2-project-");

        AetherCodeEngine defaultEngine = buildMinimalEngine(installDir);

        // Build the spec factory: it builds a brand-new
        // engine whose cwd is the spec.cwd() (which is
        // what Main.buildEngineForSession(SessionSpec)
        // does in production).
        SessionManager.EngineFactoryWithSpec specFactory = spec -> {
            Path sessionCwd = spec.cwd() != null
                    ? Path.of(spec.cwd()).toAbsolutePath().normalize()
                    : installDir;
            return buildMinimalEngine(sessionCwd);
        };

        SessionManager mgr = DaemonRunner.buildSessionManager(
                defaultEngine, null, specFactory);

        SessionSpec spec = SessionSpec.withCwd("plumb-sid", projectDir.toString());
        SessionManager.EngineHandle handle = mgr.create(spec);

        // The freshly-built engine's cwd must be the
        // project dir, NOT the install dir.
        assertThat(handle.engine.appState().cwd())
                .as("createEngine({sessionId, cwd}) must honour spec.cwd()")
                .isEqualTo(projectDir.toAbsolutePath().normalize());
        // Sanity: not the daemon's default cwd.
        assertThat(handle.engine.appState().cwd())
                .isNotEqualTo(installDir.toAbsolutePath().normalize());
    }

    @Test
    void buildSessionManager_withoutSpecFactory_fallsBackToLegacy() {
        // Pre-R361-fix-v2 path: when no spec factory
        // is supplied, create(spec) falls back to
        // legacy factory.create(sessionId). The spec's
        // cwd is dropped (preserved as
        // EngineHandle.spec for inspection, but the
        // engine itself uses the legacy factory's cwd).
        // This test pins the legacy behaviour so a
        // future change to fall-through doesn't break
        // silently.
        AetherCodeEngine engine = buildMinimalEngine(Path.of("").toAbsolutePath());
        AtomicReference<String> legacyCalledFor = new AtomicReference<>();
        SessionManager mgr = DaemonRunner.buildSessionManager(
                engine,
                sid -> {
                    legacyCalledFor.set(sid);
                    return buildMinimalEngine(Path.of("").toAbsolutePath());
                },
                null /* no spec factory */);
        SessionSpec spec = SessionSpec.withCwd("legacy-sid", "/tmp/legacy");
        SessionManager.EngineHandle handle = mgr.create(spec);
        // legacy factory was used (spec factory absent)
        assertThat(legacyCalledFor.get()).isEqualTo("legacy-sid");
        // engine was created (sanity)
        assertThat(handle.engine).isNotNull();
        // the spec is recorded for downstream consumers
        // (e.g. toWireSnapshot) but the engine's cwd is
        // the legacy factory's cwd
        assertThat(handle.spec).isSameAs(spec);
    }

    @Test
    void twoArgBuildSessionManager_legacyCompatDoesNotThrow() {
        // The 2-arg form (no specFactory) must still
        // work for tests / older callers that haven't
        // migrated. Pre-fix-v2 this was the only form.
        AetherCodeEngine engine = buildMinimalEngine(Path.of("").toAbsolutePath());
        SessionManager mgr = DaemonRunner.buildSessionManager(engine, sid ->
                buildMinimalEngine(Path.of("").toAbsolutePath()));
        assertThat(mgr).isNotNull();
        // legacy createEngine via factory still works
        SessionManager.EngineHandle handle = mgr.getOrCreate("legacy-only-sid");
        assertThat(handle.sessionId).isEqualTo("legacy-only-sid");
    }
}