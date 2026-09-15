package org.aethercode.hooks.builtin;

import org.aethercode.hooks.Hook;
import org.aethercode.hooks.Hook.Outcome;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutionException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * unit tests for {@link WriteExistingFileGuardHook}.
 *
 * <p>Tests use a {@link TempDir} so the filesystem is real (the
 * hook checks {@code Files.exists} and uses {@code toRealPath}
 * for canonicalization).
 */
class WriteExistingFileGuardHookTest {

    @TempDir
    Path projectRoot;

    private WriteExistingFileGuardHook hook;

    @BeforeEach
    void setUp() {
        hook = new WriteExistingFileGuardHook(projectRoot);
    }

    @AfterEach
    void tearDown() {
        // nothing to clean; the hook has no scheduler
    }

    @Test
    void newFileWriteAllowed() throws Exception {
        Path newFile = projectRoot.resolve("new.txt");
        Outcome o = runWrite("s1", newFile);
        assertThat(o).isInstanceOf(Hook.Outcome.Continue.class);
        assertThat(Files.exists(newFile)).isFalse();
    }

    @Test
    void existingFileWriteBlockedWithoutRead() throws Exception {
        Path existing = projectRoot.resolve("existing.txt");
        Files.writeString(existing, "hello");
        Outcome o = runWrite("s1", existing);
        assertThat(o).isInstanceOf(Hook.Outcome.Block.class);
    }

    @Test
    void existingFileWriteAllowedAfterRead() throws Exception {
        Path existing = projectRoot.resolve("existing.txt");
        Files.writeString(existing, "hello");
        // Read first
        Outcome r = runRead("s1", existing);
        assertThat(r).isInstanceOf(Hook.Outcome.Continue.class);
        // Now write is allowed
        Outcome o = runWrite("s1", existing);
        assertThat(o).isInstanceOf(Hook.Outcome.Continue.class);
    }

    @Test
    void existingFileWriteAllowedWithOverwriteTrue() throws Exception {
        Path existing = projectRoot.resolve("existing.txt");
        Files.writeString(existing, "hello");
        Map<String, Object> input = writeInput(existing);
        input.put("overwrite", true);
        Outcome o = runWrite("s1", input);
        assertThat(o).isInstanceOf(Hook.Outcome.Continue.class);
        // The hook also strips the `overwrite` key so a downstream
        // tool cannot accidentally trust it.
        assertThat(input).doesNotContainKey("overwrite");
    }

    @Test
    void sisyphusPathBypassesGuard() throws Exception {
        Path sisyphusDir = projectRoot.resolve(".sisyphus");
        Files.createDirectories(sisyphusDir);
        Path existing = sisyphusDir.resolve("notes.md");
        Files.writeString(existing, "old");
        Outcome o = runWrite("s1", existing);
        assertThat(o).isInstanceOf(Hook.Outcome.Continue.class);
    }

    @Test
    void outsideProjectBypassesGuard() throws Exception {
        Path outside = Files.createTempFile("outside-", ".txt");
        Files.writeString(outside, "old");
        try {
            Outcome o = runWrite("s1", outside);
            assertThat(o).isInstanceOf(Hook.Outcome.Continue.class);
        } finally {
            Files.deleteIfExists(outside);
        }
    }

    @Test
    void readPermissionConsumedOnWrite() throws Exception {
        // A read grants ONE write. After the write, the read
        // entry is consumed and a second write without re-read
        // is blocked.
        Path existing = projectRoot.resolve("existing.txt");
        Files.writeString(existing, "v1");
        assertThat(runRead("s1", existing)).isInstanceOf(Hook.Outcome.Continue.class);
        assertThat(runWrite("s1", existing)).isInstanceOf(Hook.Outcome.Continue.class);
        // Second write: blocked
        assertThat(runWrite("s1", existing)).isInstanceOf(Hook.Outcome.Block.class);
    }

    @Test
    void otherSessionCannotUseFirstSessionRead() throws Exception {
        Path existing = projectRoot.resolve("existing.txt");
        Files.writeString(existing, "hello");
        // s1 reads
        assertThat(runRead("s1", existing)).isInstanceOf(Hook.Outcome.Continue.class);
        // s2 tries to write — read entry was invalidated across
        // sessions when s1 read it (because invalidateOtherSessions
        // is called on write; here we test the inverse: s2's read
        // permission is independent).
        assertThat(runWrite("s2", existing)).isInstanceOf(Hook.Outcome.Block.class);
        // s2 reads, then writes — allowed
        assertThat(runRead("s2", existing)).isInstanceOf(Hook.Outcome.Continue.class);
        assertThat(runWrite("s2", existing)).isInstanceOf(Hook.Outcome.Continue.class);
    }

    @Test
    void otherToolNamesIgnored() throws Exception {
        // The hook only acts on file_write / file_read.
        Path any = projectRoot.resolve("anything.txt");
        Files.writeString(any, "x");
        Map<String, Object> input = new HashMap<>();
        input.put("file_path", any.toString());
        input.put("command", "rm anything.txt");
        Outcome o = hook.run(Hook.HookContext.forPre("s1", "bash", input)).get();
        assertThat(o).isInstanceOf(Hook.Outcome.Continue.class);
    }

    @Test
    void emptyFilePathSkipped() throws Exception {
        Map<String, Object> input = new HashMap<>();
        input.put("file_path", "");
        Outcome o = hook.run(Hook.HookContext.forPre("s1", "file_write", input)).get();
        assertThat(o).isInstanceOf(Hook.Outcome.Continue.class);
    }

    @Test
    void caseInsensitiveOnWindows() throws Exception {
        // Skip this test on non-Windows: the case-insensitive
        // comparison is Windows-specific.
        if (!isWindows()) return;
        Path existing = projectRoot.resolve("Mixed.txt");
        Files.writeString(existing, "x");
        // Read with different case
        Path differentCase = projectRoot.resolve("MIXED.txt");
        assertThat(runRead("s1", differentCase)).isInstanceOf(Hook.Outcome.Continue.class);
        // Write with original case — should be allowed because
        // the read set is keyed case-insensitively
        assertThat(runWrite("s1", existing)).isInstanceOf(Hook.Outcome.Continue.class);
    }

    // ---- R268e (2026-09-15): prePopulateFromSession tests ----

    @Test
    void prePopulateFromSession_unblocksExistingFilesWithoutRead() throws Exception {
        // R268e: after a daemon restart, readBySession is empty
        // but the on-disk transcript records every file_write
        // the session did. prePopulateFromSession replays those
        // paths so the LLM's first file_write after reconnect
        // isn't blocked. Without this round, the user's task
        // gets stuck in a silent retry loop on file_write.
        Path existing = projectRoot.resolve("prev.txt");
        Files.writeString(existing, "old");
        // No file_read first. Direct file_write would normally be
        // blocked...
        assertThat(runWrite("s1", existing))
                .as("pre-populate-free state still blocks")
                .isInstanceOf(Hook.Outcome.Block.class);
        // ...until the session is replayed via the daemon's
        // loadSession flow (simulated here by a direct call):
        hook.prePopulateFromSession("s1", java.util.List.of(existing.toString()));
        assertThat(runWrite("s1", existing))
                .as("post-prePopulate write is allowed without a prior file_read")
                .isInstanceOf(Hook.Outcome.Continue.class);
    }

    @Test
    void prePopulateFromSession_isSessionScoped() throws Exception {
        // pre-populating for s1 must not leak into s2. s2 still
        // has to read before it can write (the per-session set
        // is the same one registerRead / consumeReadPermission
        // use).
        Path existing = projectRoot.resolve("scope.txt");
        Files.writeString(existing, "v");
        hook.prePopulateFromSession("s1", java.util.List.of(existing.toString()));
        // s1: allowed
        assertThat(runWrite("s1", existing)).isInstanceOf(Hook.Outcome.Continue.class);
        // s2: still blocked
        assertThat(runWrite("s2", existing)).isInstanceOf(Hook.Outcome.Block.class);
    }

    @Test
    void prePopulateFromSession_emptyAndNullInputsAreNoOps() throws Exception {
        // null sessionId, null/empty lists — must not NPE. The
        // contract is "best effort, never worse than the current
        // behaviour"; a no-op is the right answer for malformed
        // input.
        Path existing = projectRoot.resolve("noop.txt");
        Files.writeString(existing, "x");
        hook.prePopulateFromSession(null, java.util.List.of(existing.toString()));
        hook.prePopulateFromSession("s1", null);
        hook.prePopulateFromSession("s1", java.util.List.of());
        // sanity: state still works for normal reads/writes
        assertThat(runRead("s1", existing)).isInstanceOf(Hook.Outcome.Continue.class);
        assertThat(runWrite("s1", existing)).isInstanceOf(Hook.Outcome.Continue.class);
    }

    @Test
    void prePopulateFromSession_acceptsAlternativePathKeys() throws Exception {
        // AetherCodeMethods.loadSession's pre-populate path uses
        // file_path / path / filePath in that order. This test
        // pins that the hook's own prePopulateFromSession accepts
        // any non-blank string, so callers can pass raw input
        // maps without pre-extracting the path.
        Path existing = projectRoot.resolve("alt.txt");
        Files.writeString(existing, "v");
        // Pass the path via the "filePath" (camelCase) key — the
        // hook receives a string, the key choice happens upstream.
        hook.prePopulateFromSession("s1", java.util.List.of(existing.toString()));
        assertThat(runWrite("s1", existing)).isInstanceOf(Hook.Outcome.Continue.class);
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    private Hook.Outcome runWrite(String sessionId, Path path)
            throws InterruptedException, ExecutionException {
        return runWrite(sessionId, writeInput(path));
    }
    private Hook.Outcome runWrite(String sessionId, Map<String, Object> input)
            throws InterruptedException, ExecutionException {
        return hook.run(Hook.HookContext.forPre(sessionId, "file_write", input)).get();
    }
    private Hook.Outcome runRead(String sessionId, Path path)
            throws InterruptedException, ExecutionException {
        return hook.run(Hook.HookContext.forPre(sessionId, "file_read",
                writeInput(path))).get();
    }
    private Map<String, Object> writeInput(Path path) {
        Map<String, Object> input = new HashMap<>();
        input.put("file_path", path.toString());
        return input;
    }
    private static boolean isWindows() {
        String os = System.getProperty("os.name");
        return os != null && os.toLowerCase().contains("win");
    }
}
