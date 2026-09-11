package org.aethercode.cli;

import org.aethercode.core.transcript.SessionStore;
import org.aethercode.sdk.AetherCodeEngine;
import org.aethercode.core.tool.Tool;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * R148 tests: SessionStore wire-up in DaemonRunner.
 *
 * <p>The daemon must install a default
 * {@link SessionStore} on the engine when the CLI
 * didn't supply one, so the {@code createSession},
 * {@code loadSession}, {@code listSessions},
 * {@code deleteSession} RPCs work end-to-end.
 *
 * <p>These tests cover the {@link
 * DaemonRunner#ensureSessionStore} helper + the
 * {@link DaemonRunner#resolveSessionsDir} path
 * resolution. The {@code AetherCodeEngine.Builder}
 * builds an engine without a store; the helper
 * detects that and installs a fresh one.
 */
class DaemonRunnerR148Test {

    private static AetherCodeEngine engineWithoutStore(Path cwd) {
        // Builder without sessionStore(...) — the same
        // shape DaemonRunner sees on a daemon started
        // without --sessions-dir.
        return new AetherCodeEngine.Builder()
                .cwd(cwd)
                .tools(List.<Tool>of())
                .build();
    }

    @Test
    void ensureSessionStore_installsDefaultWhenAbsent(@TempDir Path cwd) {
        AetherCodeEngine engine = engineWithoutStore(cwd);
        assertThat(engine.sessionStore()).isNull();

        DaemonRunner.ensureSessionStore(engine, "test");

        SessionStore store = engine.sessionStore();
        assertThat(store).isNotNull();
        // Default fallback ends with
        // `.aethercode/sessions`. The exact
        // root depends on `user.dir` (which
        // the test runner sets to the
        // project dir, not the TempDir);
        // the suffix is the contract.
        assertThat(store.dir().toString())
                .endsWith(".aethercode" + java.io.File.separator + "sessions");
        assertThat(Files.isDirectory(store.dir())).isTrue();
    }

    @Test
    void ensureSessionStore_isNoOpWhenAlreadyWired(@TempDir Path cwd) throws Exception {
        // Pre-wire a store. The helper must NOT
        // swap it out (the engine's setter throws on
        // a second set with a different instance).
        Path customDir = Files.createTempDirectory("r148-custom-");
        SessionStore existing = new SessionStore(customDir);
        AetherCodeEngine engine = new AetherCodeEngine.Builder()
                .cwd(cwd)
                .sessionStore(existing)
                .tools(List.<Tool>of())
                .build();
        assertThat(engine.sessionStore()).isSameAs(existing);

        DaemonRunner.ensureSessionStore(engine, "test");

        // Same instance, same dir — no swap.
        assertThat(engine.sessionStore()).isSameAs(existing);
        assertThat(engine.sessionStore().dir()).isEqualTo(customDir);
    }

    @Test
    void ensureSessionStore_handlesNullEngineGracefully() {
        // Defensive: a null engine must not crash the
        // daemon startup. The helper should be a no-op
        // so a misuse (e.g. an injected null engine in
        // a future test) doesn't 5-minute-timeout the
        // user's first query.
        DaemonRunner.ensureSessionStore(null, "test");
    }

    @Test
    void resolveSessionsDir_fallsBackToCwdDotAethercode(@TempDir Path cwd) throws Exception {
        Path resolved = DaemonRunner.resolveSessionsDir();
        // When AETHERCODE_SESSIONS_DIR is unset
        // (the test runner doesn't set it), the
        // helper falls back to <user.dir>/.aethercode/
        // sessions. user.dir is the project's CWD in
        // a Maven test run.
        assertThat(resolved).isNotNull();
        assertThat(resolved.toString()).endsWith(".aethercode" + java.io.File.separator + "sessions");
    }

    @Test
    void resolveSessionsDir_isAbsoluteAndNormalized() {
        // The helper normalises the result so the
        // engine doesn't see "./.aethercode/./sessions"
        // paths that confuse the file watcher.
        Path resolved = DaemonRunner.resolveSessionsDir();
        assertThat(resolved.isAbsolute()).isTrue();
        assertThat(resolved.toString()).doesNotContain("./");
    }
}
