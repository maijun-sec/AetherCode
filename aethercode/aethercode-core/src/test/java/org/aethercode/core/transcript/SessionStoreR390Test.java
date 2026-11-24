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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * R390: the persisted session facts must SURVIVE a later touch.
 *
 * <p>★ THE DEFECT ★
 *
 * <p>{@link SessionStoreSqlite#upsert} used to write the proposed row's
 * value over the stored one, unconditionally. Every caller of
 * {@link SessionStore#touch} passes {@code null} for the fields it does
 * not own — the engine's per-message append path passes
 * {@code firstPrompt=null, title=null, worktree=null, model=null}. So
 * the FIRST message append wiped the session's first prompt and its
 * title, and the session list had nothing left to show.
 *
 * <p>The symptom the user reported was "a long task finishes and the
 * session still shows no name" — the name was not slow to appear, it was
 * being DELETED on every message.
 *
 * <p>★ THE TWO DIRECTIONS ARE NOT THE SAME ★
 *
 * <ul>
 *   <li>{@code cwd} / {@code worktree} / {@code model} are "current
 *       binding" facts: the LAST non-null value wins, because
 *       {@code setCwd()} replaces them.</li>
 *   <li>{@code first_prompt} / {@code title} are IDENTITY facts: the
 *       FIRST non-null value wins, because a later message must not
 *       rewrite the session's identity and a rename must survive every
 *       later touch.</li>
 * </ul>
 */
class SessionStoreR390Test {

    private static SessionStore storeIn(Path tmp) {
        return new SessionStore(tmp.resolve(".aethercode/sessions"));
    }

    @Test
    void aLaterTouchDoesNotNullOutTheFirstPrompt(@TempDir Path tmp) throws Exception {
        SessionStore store = storeIn(tmp);
        store.touch("s1", "D:/p", null, null, "the original prompt", null, 0);
        // the engine's per-message append path: firstPrompt / title are null
        store.touch("s1", "D:/p", null, null, null, null, 0);
        var info = store.list().stream().filter(s -> s.id().equals("s1")).findFirst().orElseThrow();
        assertThat(info.firstPrompt()).isEqualTo("the original prompt");
    }

    @Test
    void theFirstPromptWinsOverALaterOne(@TempDir Path tmp) throws Exception {
        SessionStore store = storeIn(tmp);
        store.touch("s1", "D:/p", null, null, "the FIRST prompt", null, 0);
        store.touch("s1", "D:/p", null, null, "a LATER prompt", null, 0);
        var info = store.list().stream().filter(s -> s.id().equals("s1")).findFirst().orElseThrow();
        assertThat(info.firstPrompt()).isEqualTo("the FIRST prompt");
    }

    @Test
    void aRenameSurvivesALaterTouch(@TempDir Path tmp) throws Exception {
        SessionStore store = storeIn(tmp);
        store.touch("s1", "D:/p", null, null, "prompt", null, 0);
        assertThat(store.rename("s1", "my renamed session")).isTrue();
        store.touch("s1", "D:/p", null, null, null, null, 0);
        var info = store.list().stream().filter(s -> s.id().equals("s1")).findFirst().orElseThrow();
        assertThat(info.title()).isEqualTo("my renamed session");
    }

    @Test
    void aLaterTouchDoesNotNullOutTheCwd(@TempDir Path tmp) throws Exception {
        SessionStore store = storeIn(tmp);
        store.touch("s1", "D:/p", null, null, "prompt", null, 0);
        store.touch("s1", null, null, null, null, null, 0);
        var info = store.list().stream().filter(s -> s.id().equals("s1")).findFirst().orElseThrow();
        assertThat(info.cwd()).isEqualTo("D:/p");
    }

    @Test
    void setCwdStillReplacesTheCwd(@TempDir Path tmp) throws Exception {
        SessionStore store = storeIn(tmp);
        store.touch("s1", "D:/p", null, null, "prompt", null, 0);
        store.touch("s1", "D:/other", null, null, null, null, 0);
        var info = store.list().stream().filter(s -> s.id().equals("s1")).findFirst().orElseThrow();
        assertThat(info.cwd()).isEqualTo("D:/other");
    }

    @Test
    void theMessageCountIsTheRealCount(@TempDir Path tmp) throws Exception {
        SessionStore store = storeIn(tmp);
        store.touch("s1", "D:/p", null, null, "prompt", null, 0);
        // a byteDelta > 0 bumps the counter by one, the way the
        // engine's append listener does.
        store.touch("s1", "D:/p", null, null, null, null, 100);
        store.touch("s1", "D:/p", null, null, null, null, 100);
        store.touch("s1", "D:/p", null, null, null, null, 100);
        var info = store.list().stream().filter(s -> s.id().equals("s1")).findFirst().orElseThrow();
        assertThat(info.messageCount()).isEqualTo(3);
    }

    @Test
    void theSessionInfoCarriesThePersistedFacts(@TempDir Path tmp) throws Exception {
        SessionStore store = storeIn(tmp);
        store.touch("s1", "D:/p", null, null, "the prompt", "the name", 0);
        var info = store.list().stream().filter(s -> s.id().equals("s1")).findFirst().orElseThrow();
        assertThat(info.title()).isEqualTo("the name");
        assertThat(info.firstPrompt()).isEqualTo("the prompt");
        assertThat(info.displayTitle()).isEqualTo("the name");
    }
}
