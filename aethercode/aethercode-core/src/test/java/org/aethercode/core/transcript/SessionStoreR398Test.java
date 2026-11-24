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

package org.aethercode.core.transcript;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * R398 (2026-11-24) — WHICH session was active.
 *
 * <p>THE DEFECT. A daemon restart brought the engine back on its
 * STARTUP session id — the literal {@code "default"} — so
 * {@code listSessions.current} said {@code "default"} and the desktop
 * adopted it. The user was dropped into a session that was not theirs.
 * In the live case it was worse: {@code "default"} is also the id of
 * a real on-disk session (the user's old RAG conversation) and that one
 * had been TRASHED, so the next message would have landed in the trash.
 *
 * <p>WHY THIS IS A NEW FACT AND NOT {@code last_used_at}. The index
 * already tracks {@code last_used_at}, but that records the last time
 * a session was <em>written</em>, not the last time it was
 * <em>opened</em>. "Switch to an old session and read it" leaves
 * {@code last_used_at} alone, so restoring by it would put the user
 * back in the wrong conversation. The session the user was in has to
 * be recorded explicitly.
 *
 * <p>WHAT THIS FILE PINS. The pointer itself: it round-trips, it
 * overwrites, it can be cleared, and — the part that matters most —
 * a MISSING, BLANK or CORRUPT pointer is "we do not know", never an
 * exception. A corrupt pointer must not be able to take the daemon
 * down on every start.
 */
class SessionStoreR398Test {

    @Test
    void markActiveThenReadRoundTrips(@TempDir Path tmp) throws Exception {
        SessionStore store = new SessionStore(tmp);
        assertThat(store.activeSessionId()).isEmpty();

        store.markActive("sess-alpha");

        assertThat(store.activeSessionId()).contains("sess-alpha");
    }

    @Test
    void thePointerIsASingleIdInTheSessionsDir(@TempDir Path tmp) throws Exception {
        SessionStore store = new SessionStore(tmp);
        store.markActive("sess-alpha");

        // It lives NEXT TO the transcripts, not in a per-user config
        // file: the session library is the thing that owns the
        // sessions, and the library is per-directory (R392 pinned it).
        Path pointer = tmp.resolve(SessionStore.ACTIVE_SESSION_FILE);
        assertThat(Files.exists(pointer)).isTrue();
        assertThat(Files.readString(pointer, StandardCharsets.UTF_8).trim())
                .isEqualTo("sess-alpha");
        // And it is NOT a transcript: the directory scan must not pick
        // it up as a session.
        assertThat(pointer.getFileName().toString()).doesNotEndWith(".jsonl");
    }

    @Test
    void markActiveOverwritesThePreviousId(@TempDir Path tmp) throws Exception {
        SessionStore store = new SessionStore(tmp);
        store.markActive("sess-alpha");
        store.markActive("sess-beta");

        assertThat(store.activeSessionId()).contains("sess-beta");
    }

    @Test
    void markActiveIgnoresABlankId(@TempDir Path tmp) throws Exception {
        SessionStore store = new SessionStore(tmp);
        store.markActive("sess-alpha");

        store.markActive("   ");
        store.markActive(null);

        // A blank id must NOT erase the recorded one: "I do not know"
        // is not the same as "there is nothing".
        assertThat(store.activeSessionId()).contains("sess-alpha");
    }

    @Test
    void clearActiveForgetsThePointer(@TempDir Path tmp) throws Exception {
        SessionStore store = new SessionStore(tmp);
        store.markActive("sess-alpha");

        store.clearActive();

        assertThat(store.activeSessionId()).isEmpty();
        assertThat(Files.exists(tmp.resolve(SessionStore.ACTIVE_SESSION_FILE))).isFalse();
    }

    @Test
    void aBlankPointerReadsAsUnknown(@TempDir Path tmp) throws Exception {
        SessionStore store = new SessionStore(tmp);
        Files.writeString(tmp.resolve(SessionStore.ACTIVE_SESSION_FILE), "   \n",
                StandardCharsets.UTF_8);

        assertThat(store.activeSessionId()).isEmpty();
    }

    @Test
    void aCorruptPointerReadsAsUnknownAndDoesNotThrow(@TempDir Path tmp) throws Exception {
        SessionStore store = new SessionStore(tmp);
        // Not valid UTF-8: the read must not blow up.
        Files.write(tmp.resolve(SessionStore.ACTIVE_SESSION_FILE),
                new byte[]{(byte) 0xff, (byte) 0xfe, (byte) 0x00, (byte) 0x01});

        assertThatCode(() -> {
            Optional<String> got = store.activeSessionId();
            assertThat(got).isEmpty();
        }).doesNotThrowAnyException();
    }

    @Test
    void aMissingPointerReadsAsUnknown(@TempDir Path tmp) throws Exception {
        SessionStore store = new SessionStore(tmp);
        assertThat(store.activeSessionId()).isEmpty();
    }

    @Test
    void thePointerSurvivesANewStoreOnTheSameDir(@TempDir Path tmp) throws Exception {
        // This is the daemon-restart case: a NEW SessionStore over the
        // SAME directory must find the recorded id. (The daemon does
        // exactly this on every start.)
        new SessionStore(tmp).markActive("sess-alpha");

        SessionStore afterRestart = new SessionStore(tmp);

        assertThat(afterRestart.activeSessionId()).contains("sess-alpha");
    }
}
