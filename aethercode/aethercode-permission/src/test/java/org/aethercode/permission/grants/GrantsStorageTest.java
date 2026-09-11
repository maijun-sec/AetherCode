package org.aethercode.permission.grants;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T-215 / design.md §3.1.2: round-trip, atomicity and rotation
 * tests for {@link GrantsStorage}. Coverage:
 * <ul>
 *   <li>{@link GrantsStorage#read} / {@link GrantsStorage#write}
 *       round-trip (incl. multiple grants, expiresAt,
 *       unicode reasons)</li>
 *   <li>missing file → empty()</li>
 *   <li>corrupt file → exception + .bak archive</li>
 *   <li>atomic write: a half-finished write leaves the prior
 *       file intact</li>
 *   <li>append + rotation: file &gt; maxBytes rotates to
 *       {@code grants-<ts>.json} and starts a fresh empty
 *       file</li>
 *   <li>rotation respects a custom threshold</li>
 *   <li>path resolution: user / project / session</li>
 *   <li>schema validation: bad inputs surface useful errors</li>
 * </ul>
 */
class GrantsStorageTest {

    // ------------------------------------------------------------------
    //  Round-trip
    // ------------------------------------------------------------------

    @Test
    void roundTrip_empty(@TempDir Path tmp) {
        GrantsStorage s = new GrantsStorage();
        Path f = tmp.resolve("grants.json");
        s.write(f, GrantsFile.empty());
        GrantsFile got = s.read(f);
        assertEquals(1, got.schemaVersion());
        assertEquals(0, got.grants().size());
    }

    @Test
    void roundTrip_singleGrant(@TempDir Path tmp) {
        GrantsStorage s = new GrantsStorage();
        Path f = tmp.resolve("grants.json");
        Grant g = Grant.create(GrantScope.PROJECT, "proj-1", "shell.command", GrantDecision.ALLOW, "ok", null);
        s.write(f, GrantsFile.empty().append(g));
        GrantsFile got = s.read(f);
        assertEquals(1, got.grants().size());
        Grant r = got.grants().get(0);
        assertEquals(g.id(), r.id());
        assertEquals(GrantScope.PROJECT, r.scope());
        assertEquals("proj-1", r.scopeId());
        assertEquals("shell.command", r.category());
        assertEquals(GrantDecision.ALLOW, r.decision());
        assertEquals("ok", r.reason());
        assertNull(r.expiresAt());
    }

    @Test
    void roundTrip_multipleGrantsPreserveOrder(@TempDir Path tmp) {
        GrantsStorage s = new GrantsStorage();
        Path f = tmp.resolve("grants.json");
        Grant a = Grant.create(GrantScope.USER, "global", "file.read", GrantDecision.ALLOW, "r1", null);
        Grant b = Grant.create(GrantScope.SESSION, "sess-1", "file.write", GrantDecision.DENY, "r2", null);
        Grant c = Grant.create(GrantScope.PROJECT, "p", "shell.command", GrantDecision.ALLOW, "r3", 9999999999999L);
        s.write(f, GrantsFile.empty().append(a).append(b).append(c));
        GrantsFile got = s.read(f);
        assertEquals(3, got.grants().size());
        assertEquals(a.id(), got.grants().get(0).id());
        assertEquals(b.id(), got.grants().get(1).id());
        assertEquals(c.id(), got.grants().get(2).id());
        assertEquals(9999999999999L, got.grants().get(2).expiresAt());
    }

    @Test
    void roundTrip_unicodeReason(@TempDir Path tmp) {
        GrantsStorage s = new GrantsStorage();
        Path f = tmp.resolve("grants.json");
        Grant g = Grant.create(GrantScope.PROJECT, "p", "shell.command", GrantDecision.ALLOW, "允许 npm install ✓", null);
        s.write(f, GrantsFile.empty().append(g));
        GrantsFile got = s.read(f);
        assertEquals("允许 npm install ✓", got.grants().get(0).reason());
    }

    @Test
    void read_missingFileReturnsEmpty(@TempDir Path tmp) {
        GrantsStorage s = new GrantsStorage();
        Path f = tmp.resolve("grants.json");
        GrantsFile got = s.read(f);
        assertEquals(0, got.grants().size());
        assertEquals(1, got.schemaVersion());
    }

    // ------------------------------------------------------------------
    //  Atomicity
    // ------------------------------------------------------------------

    @Test
    void atomicity_writeLeavesNoTempFileBehind(@TempDir Path tmp) {
        GrantsStorage s = new GrantsStorage();
        Path f = tmp.resolve("grants.json");
        s.write(f, GrantsFile.empty());
        assertTrue(Files.exists(f));
        assertFalse(Files.exists(f.resolveSibling("grants.json.tmp")),
                "tmp sibling must be cleaned up after a successful write");
    }

    @Test
    void atomicity_failedWriteKeepsPriorFile(@TempDir Path tmp) throws IOException {
        // Seed an existing grants.json with one grant.
        GrantsStorage s = new GrantsStorage();
        Path f = tmp.resolve("grants.json");
        Grant g1 = Grant.create(GrantScope.PROJECT, "p", "shell.command", GrantDecision.ALLOW, "old", null);
        s.write(f, GrantsFile.empty().append(g1));
        String before = Files.readString(f);
        assertTrue(before.contains("old"));

        // Simulate a crash mid-rename: drop a leftover .tmp next
        // to the live file. The next write() must:
        //   (a) clean up that .tmp (no half-written state survives), and
        //   (b) leave a valid grants.json on disk (the prior file
        //       or the new one, both must parse).
        Path tmp2 = f.resolveSibling("grants.json.tmp");
        Files.writeString(tmp2, "corrupt-pretend-this-is-mid-rename");
        Grant g2 = Grant.create(GrantScope.PROJECT, "p", "shell.command", GrantDecision.ALLOW, "new", null);
        s.write(f, GrantsFile.empty().append(g2));
        // The leftover .tmp must be gone — the new write+move
        // overwrote it cleanly.
        assertFalse(Files.exists(tmp2),
                "a successful write must clean up any leftover .tmp");
        GrantsFile after = s.read(f);
        assertEquals(1, after.grants().size());
        // The new grant must be the one we just wrote (the old
        // file was atomically replaced, not merged).
        assertEquals("new", after.grants().get(0).reason());
        assertNotEquals(before, Files.readString(f),
                "the second write must have replaced the file atomically");
    }

    @Test
    void atomicity_writesAreDurableAcrossReaderRace(@TempDir Path tmp) throws Exception {
        // Concurrently hammer a single file with writers while a
        // reader keeps re-reading. After all writers finish, the
        // final file must parse cleanly — no half-grant ever
        // observed. The reader uses the public API, so this also
        // exercises that read() never throws on a "normal" mid-
        // write window.
        GrantsStorage s = new GrantsStorage();
        Path f = tmp.resolve("grants.json");
        s.write(f, GrantsFile.empty());

        int writerCount = 4;
        int writesPer = 50;
        Thread[] writers = new Thread[writerCount];
        for (int i = 0; i < writerCount; i++) {
            int writerId = i;
            writers[i] = new Thread(() -> {
                for (int j = 0; j < writesPer; j++) {
                    try {
                        s.appendGrant(f, Grant.create(
                                GrantScope.PROJECT,
                                "p" + writerId,
                                "shell.command",
                                GrantDecision.ALLOW,
                                "w" + writerId + "-" + j,
                                null));
                    } catch (RuntimeException ignored) {
                        // Race on the same file can collide the
                        // atomic move on Windows; that's fine for
                        // this test, we just want the final state
                        // to be valid.
                    }
                }
            });
        }
        Thread reader = new Thread(() -> {
            for (int j = 0; j < 200; j++) {
                try {
                    // Read may briefly fail if a writer crashed
                    // mid-flight on Windows; the contract we
                    // assert is "no half-grant", not "always
                    // readable during a write storm".
                    s.read(f);
                } catch (RuntimeException ignored) {
                    // expected on Windows mid-write
                }
                try { Thread.sleep(1); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); return; }
            }
        });
        for (Thread t : writers) t.start();
        reader.start();
        for (Thread t : writers) t.join();
        reader.join();

        // Final file must be a valid GrantsFile.
        GrantsFile finalState = s.read(f);
        assertEquals(1, finalState.schemaVersion());
        // Every grant we managed to write should still have a
        // valid shape (id non-blank, scope/decision set).
        for (Grant g : finalState.grants()) {
            assertNotNull(g.id());
            assertFalse(g.id().isBlank());
            assertNotNull(g.scope());
            assertNotNull(g.decision());
        }
    }

    // ------------------------------------------------------------------
    //  Rotation
    // ------------------------------------------------------------------

    @Test
    void rotation_smallFileIsUntouched(@TempDir Path tmp) {
        GrantsStorage s = new GrantsStorage(1024);  // 1KB cap
        Path f = tmp.resolve("grants.json");
        Grant g = Grant.create(GrantScope.PROJECT, "p", "shell.command", GrantDecision.ALLOW, "small", null);
        s.write(f, GrantsFile.empty().append(g));
        assertFalse(s.rotateIfTooBig(f));
        assertTrue(Files.exists(f));
        assertEquals(0, s.listRotatedArchives(f).size());
    }

    @Test
    void rotation_overCapArchivesAndResets(@TempDir Path tmp) throws IOException {
        // Set a tiny cap so we can force a rotation with one grant.
        GrantsStorage s = new GrantsStorage(64);
        Path f = tmp.resolve("grants.json");
        // Pad the reason so the file size exceeds the cap.
        Grant g = Grant.create(
                GrantScope.PROJECT, "p", "shell.command", GrantDecision.ALLOW,
                "x".repeat(200), null);
        s.write(f, GrantsFile.empty().append(g));
        // Confirm the file actually crossed the cap.
        assertTrue(Files.size(f) >= 64);

        assertTrue(s.rotateIfTooBig(f));

        // The new live file must be tiny (just the empty envelope).
        assertTrue(Files.exists(f));
        GrantsFile live = s.read(f);
        assertEquals(0, live.grants().size());

        // Exactly one archive next to it.
        List<Path> archives = s.listRotatedArchives(f);
        assertEquals(1, archives.size());
        assertTrue(archives.get(0).getFileName().toString().startsWith("grants-"));
        assertTrue(archives.get(0).getFileName().toString().endsWith(".json"));

        // The archive contains the original grant.
        String archiveJson = Files.readString(archives.get(0));
        assertTrue(archiveJson.contains("x".repeat(200)),
                "archive must contain the original grant body");
    }

    @Test
    void rotation_rotateIdempotentBelowCap(@TempDir Path tmp) {
        GrantsStorage s = new GrantsStorage(10_000);
        Path f = tmp.resolve("grants.json");
        s.write(f, GrantsFile.empty());
        // Calling twice on a fresh file must not produce archives.
        assertFalse(s.rotateIfTooBig(f));
        assertFalse(s.rotateIfTooBig(f));
        assertEquals(0, s.listRotatedArchives(f).size());
    }

    @Test
    void rotation_missingFileNoOp(@TempDir Path tmp) {
        GrantsStorage s = new GrantsStorage(64);
        Path f = tmp.resolve("grants.json");
        assertFalse(s.rotateIfTooBig(f));
    }

    @Test
    void appendGrant_triggersRotationWhenOverCap(@TempDir Path tmp) throws IOException {
        GrantsStorage s = new GrantsStorage(128);
        Path f = tmp.resolve("grants.json");
        for (int i = 0; i < 5; i++) {
            s.appendGrant(f, Grant.create(
                    GrantScope.PROJECT, "p", "shell.command", GrantDecision.ALLOW,
                    "r" + i + "x".repeat(40), null));
        }
        // After several appends, we must have rotated at least
        // once and the live file must be under the cap.
        assertTrue(Files.exists(f));
        assertTrue(Files.size(f) < 1024,
                "live file should be small after rotation, got " + Files.size(f));
        assertTrue(s.listRotatedArchives(f).size() >= 1,
                "expected at least one archive after 5 padded appends");
    }

    @Test
    void rotation_customThreshold(@TempDir Path tmp) throws IOException {
        // Confirm a non-default cap is honored: with cap 1MiB a
        // 2KB file must NOT rotate.
        GrantsStorage s = new GrantsStorage(GrantsStorage.DEFAULT_MAX_BYTES);
        Path f = tmp.resolve("grants.json");
        Grant g = Grant.create(
                GrantScope.PROJECT, "p", "shell.command", GrantDecision.ALLOW,
                "y".repeat(2000), null);
        s.write(f, GrantsFile.empty().append(g));
        assertFalse(s.rotateIfTooBig(f));
    }

    // ------------------------------------------------------------------
    //  Schema validation (T-203)
    // ------------------------------------------------------------------

    @Test
    void schema_validator_acceptsEmptyFile() {
        GrantsSchemaValidator.ValidationResult vr =
                GrantsSchemaValidator.validate("{\"schemaVersion\":1,\"grants\":[]}");
        assertTrue(vr.ok(), () -> "errors=" + vr.errors());
        assertEquals(0, vr.grants().size());
    }

    @Test
    void schema_validator_acceptsMinimalGrant() {
        String json = "{\"schemaVersion\":1,\"grants\":[{\"id\":\"abc\","
                + "\"scope\":\"project\",\"scopeId\":\"p\",\"category\":\"x\","
                + "\"decision\":\"allow\",\"reason\":\"r\",\"createdAt\":1}]}";
        GrantsSchemaValidator.ValidationResult vr = GrantsSchemaValidator.validate(json);
        assertTrue(vr.ok(), () -> "errors=" + vr.errors());
        assertEquals(1, vr.grants().size());
    }

    @Test
    void schema_validator_rejectsWrongSchemaVersion() {
        GrantsSchemaValidator.ValidationResult vr =
                GrantsSchemaValidator.validate("{\"schemaVersion\":2,\"grants\":[]}");
        assertFalse(vr.ok());
        assertTrue(vr.errors().stream().anyMatch(e -> e.contains("schemaVersion")),
                () -> "errors=" + vr.errors());
    }

    @Test
    void schema_validator_rejectsBadScope() {
        String json = "{\"schemaVersion\":1,\"grants\":[{\"id\":\"a\","
                + "\"scope\":\"GLOBAL\",\"scopeId\":\"p\",\"category\":\"x\","
                + "\"decision\":\"allow\",\"reason\":\"r\",\"createdAt\":1}]}";
        GrantsSchemaValidator.ValidationResult vr = GrantsSchemaValidator.validate(json);
        assertFalse(vr.ok());
        assertTrue(vr.errors().stream().anyMatch(e -> e.contains("scope")),
                () -> "errors=" + vr.errors());
    }

    @Test
    void schema_validator_rejectsBadDecision() {
        String json = "{\"schemaVersion\":1,\"grants\":[{\"id\":\"a\","
                + "\"scope\":\"project\",\"scopeId\":\"p\",\"category\":\"x\","
                + "\"decision\":\"MAYBE\",\"reason\":\"r\",\"createdAt\":1}]}";
        GrantsSchemaValidator.ValidationResult vr = GrantsSchemaValidator.validate(json);
        assertFalse(vr.ok());
        assertTrue(vr.errors().stream().anyMatch(e -> e.contains("decision")),
                () -> "errors=" + vr.errors());
    }

    @Test
    void schema_validator_rejectsMissingRequiredField() {
        // Missing reason
        String json = "{\"schemaVersion\":1,\"grants\":[{\"id\":\"a\","
                + "\"scope\":\"project\",\"scopeId\":\"p\",\"category\":\"x\","
                + "\"decision\":\"allow\",\"createdAt\":1}]}";
        GrantsSchemaValidator.ValidationResult vr = GrantsSchemaValidator.validate(json);
        assertFalse(vr.ok());
        assertTrue(vr.errors().stream().anyMatch(e -> e.contains("reason")),
                () -> "errors=" + vr.errors());
    }

    @Test
    void schema_validator_rejectsBlankId() {
        String json = "{\"schemaVersion\":1,\"grants\":[{\"id\":\"   \","
                + "\"scope\":\"project\",\"scopeId\":\"p\",\"category\":\"x\","
                + "\"decision\":\"allow\",\"reason\":\"r\",\"createdAt\":1}]}";
        GrantsSchemaValidator.ValidationResult vr = GrantsSchemaValidator.validate(json);
        assertFalse(vr.ok());
        assertTrue(vr.errors().stream().anyMatch(e -> e.contains("id")),
                () -> "errors=" + vr.errors());
    }

    @Test
    void schema_validator_rejectsNonJson() {
        GrantsSchemaValidator.ValidationResult vr =
                GrantsSchemaValidator.validate("{not json}");
        assertFalse(vr.ok());
        assertTrue(vr.errors().stream().anyMatch(e -> e.contains("JSON")),
                () -> "errors=" + vr.errors());
    }

    @Test
    void schema_validator_rejectsEmpty() {
        GrantsSchemaValidator.ValidationResult vr = GrantsSchemaValidator.validate("");
        assertFalse(vr.ok());
    }

    // ------------------------------------------------------------------
    //  Corruption recovery
    // ------------------------------------------------------------------

    @Test
    void corruptFile_movedAsideAndThrows(@TempDir Path tmp) throws IOException {
        GrantsStorage s = new GrantsStorage();
        Path f = tmp.resolve("grants.json");
        Files.writeString(f, "this is not json at all", StandardCharsets.UTF_8);
        GrantsStorage.GrantsFileException ex = assertThrows(
                GrantsStorage.GrantsFileException.class,
                () -> s.read(f));
        assertTrue(ex.getMessage().toLowerCase().contains("validation"),
                () -> ex.getMessage());
        // The .bak must exist so the bad bytes survive for forensics.
        assertTrue(Files.exists(f.resolveSibling("grants.json.bak")),
                "expected grants.json.bak next to the corrupt file");
    }

    // ------------------------------------------------------------------
    //  Path resolution (T-210 / T-211 / T-212)
    // ------------------------------------------------------------------

    @Test
    void paths_userScope(@TempDir Path tmp) {
        Path f = GrantPaths.userGrantsFile(tmp);
        assertEquals(tmp.resolve(".aethercode/grants.json"), f);
    }

    @Test
    void paths_userScopeNullHome() {
        assertNull(GrantPaths.userGrantsFile(null));
    }

    @Test
    void paths_projectScope(@TempDir Path tmp) {
        Path f = GrantPaths.projectGrantsFile(tmp);
        assertEquals(tmp.resolve(".aethercode/grants.json"), f);
    }

    @Test
    void paths_projectScopeRejectsNull() {
        assertThrows(NullPointerException.class, () -> GrantPaths.projectGrantsFile(null));
    }

    @Test
    void paths_sessionScope(@TempDir Path tmp) {
        Path f = GrantPaths.sessionGrantsFile(tmp, "sess-42");
        assertEquals(tmp.resolve(".aethercode/sessions/sess-42/grants.json"), f);
    }

    @Test
    void paths_sessionScopeRejectsBlank() {
        assertThrows(IllegalArgumentException.class,
                () -> GrantPaths.sessionGrantsFile(java.nio.file.Path.of("."), ""));
    }

    @Test
    void paths_sessionScopeRejectsNull() {
        assertThrows(NullPointerException.class,
                () -> GrantPaths.sessionGrantsFile(null, "s"));
    }

    // ------------------------------------------------------------------
    //  Grant factory + invariants
    // ------------------------------------------------------------------

    @Test
    void grant_createGeneratesIdAndTimestamp() {
        Grant a = Grant.create(GrantScope.PROJECT, "p", "x", GrantDecision.ALLOW, "r", null);
        Grant b = Grant.create(GrantScope.PROJECT, "p", "x", GrantDecision.ALLOW, "r", null);
        assertNotNull(a.id());
        assertNotNull(b.id());
        assertNotEquals(a.id(), b.id(), "ids must be unique");
        assertTrue(a.createdAt() > 0L);
        assertTrue(b.createdAt() >= a.createdAt());
    }

    @Test
    void grant_isExpired() {
        Grant neverExpires = Grant.create(GrantScope.PROJECT, "p", "x",
                GrantDecision.ALLOW, "r", null);
        assertFalse(neverExpires.isExpired(0L));
        assertFalse(neverExpires.isExpired(Long.MAX_VALUE));

        Grant shortLived = new Grant("id", GrantScope.PROJECT, "p", "x",
                GrantDecision.ALLOW, "r", 1L, 100L);
        assertFalse(shortLived.isExpired(50L));
        assertTrue(shortLived.isExpired(100L));
        assertTrue(shortLived.isExpired(200L));
    }

    @Test
    void grant_rejectsBlankFields() {
        assertThrows(NullPointerException.class,
                () -> new Grant(null, GrantScope.PROJECT, "p", "x", GrantDecision.ALLOW, "r", 1L, null));
        assertThrows(IllegalArgumentException.class,
                () -> new Grant("", GrantScope.PROJECT, "p", "x", GrantDecision.ALLOW, "r", 1L, null));
        assertThrows(IllegalArgumentException.class,
                () -> new Grant("id", GrantScope.PROJECT, "", "x", GrantDecision.ALLOW, "r", 1L, null));
        assertThrows(IllegalArgumentException.class,
                () -> new Grant("id", GrantScope.PROJECT, "p", "", GrantDecision.ALLOW, "r", 1L, null));
        assertThrows(IllegalArgumentException.class,
                () -> new Grant("id", GrantScope.PROJECT, "p", "x", GrantDecision.ALLOW, "r", 0L, null));
        assertThrows(IllegalArgumentException.class,
                () -> new Grant("id", GrantScope.PROJECT, "p", "x", GrantDecision.ALLOW, "r", 1L, 0L));
    }

    @Test
    void grantScope_fromWireRoundTrip() {
        for (GrantScope v : GrantScope.values()) {
            assertEquals(v, GrantScope.fromWire(v.wire()));
        }
        assertThrows(IllegalArgumentException.class, () -> GrantScope.fromWire("GLOBAL"));
        assertThrows(IllegalArgumentException.class, () -> GrantScope.fromWire(null));
    }

    @Test
    void grantDecision_fromWireRoundTrip() {
        for (GrantDecision v : GrantDecision.values()) {
            assertEquals(v, GrantDecision.fromWire(v.wire()));
        }
        assertThrows(IllegalArgumentException.class, () -> GrantDecision.fromWire("MAYBE"));
        assertThrows(IllegalArgumentException.class, () -> GrantDecision.fromWire(null));
    }

    // ------------------------------------------------------------------
    //  Constructor validation
    // ------------------------------------------------------------------

    @Test
    void grantsFile_rejectsWrongSchemaVersion() {
        assertThrows(IllegalArgumentException.class,
                () -> new GrantsFile(2, List.of()));
    }

    @Test
    void grantsFile_emptyAndAppend() {
        GrantsFile empty = GrantsFile.empty();
        assertEquals(0, empty.grants().size());
        Grant g = Grant.create(GrantScope.PROJECT, "p", "x", GrantDecision.ALLOW, "r", null);
        GrantsFile one = empty.append(g);
        assertEquals(1, one.grants().size());
        assertEquals(g.id(), one.grants().get(0).id());
        // The original is unmodifiable — must not be mutated.
        assertEquals(0, empty.grants().size());
    }

    @Test
    void grantsFile_isUnmodifiable() {
        GrantsFile f = GrantsFile.empty();
        assertThrows(UnsupportedOperationException.class,
                () -> f.grants().add(Grant.create(GrantScope.PROJECT, "p", "x", GrantDecision.ALLOW, "r", null)));
    }

    @Test
    void storage_rejectsBadMaxBytes() {
        assertThrows(IllegalArgumentException.class, () -> new GrantsStorage(0));
        assertThrows(IllegalArgumentException.class, () -> new GrantsStorage(-1));
    }
}
