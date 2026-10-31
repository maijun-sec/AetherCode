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

package org.aethercode.sdk;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * prior round.1 tests: per-session cwd via SessionSpec.
 *
 * <p>Tests the SessionSpec record itself + the
 * validation logic. The SessionManager
 * integration (creating a real engine) is tested
 * at the protocol layer (see
 * {@code AetherCodeMethodsCreateSessionR97MTest})
 * because AetherCodeEngine is too heavy to
 * construct in a unit test.
 */
class SessionManagerR97MTest {

    @Test
    void sessionSpecValidatesInputs() {
        // prior round.1: sessionId is required; cwd and
        // worktree are mutually exclusive.
        assertThrows(IllegalArgumentException.class,
                () -> new SessionSpec(null, null, null, null),
                "blank sessionId must be rejected");
        assertThrows(IllegalArgumentException.class,
                () -> new SessionSpec("s1", "/tmp", "wt", null),
                "cwd + worktree must be rejected as mutually exclusive");
        // Valid specs
        SessionSpec a = new SessionSpec("s1", "/tmp", null, null);
        assertEquals("/tmp", a.cwd());
        assertNull(a.worktree());
        SessionSpec b = new SessionSpec("s1", null, "main-wt", null);
        assertEquals("main-wt", b.worktree());
    }

    @Test
    void sessionSpecEffectiveCwd() {
        // The spec returns the cwd if set, else
        // falls back to the daemon's default.
        SessionSpec a = new SessionSpec("s1", "/tmp", null, null);
        assertEquals("/tmp", a.effectiveCwd("/default"));
        SessionSpec b = new SessionSpec("s1", null, null, null);
        assertEquals("/default", b.effectiveCwd("/default"));
    }

    @Test
    void sessionSpecOfFactoryMethods() {
        // prior round.1: the convenience factories build
        // the right shape. Used by the RPC layer
        // and the legacy call sites.
        SessionSpec a = SessionSpec.of("s1");
        assertEquals("s1", a.sessionId());
        assertNull(a.cwd());
        assertNull(a.worktree());
        assertNull(a.model());

        SessionSpec b = SessionSpec.withCwd("s1", "/tmp/r97m");
        assertEquals("s1", b.sessionId());
        assertEquals("/tmp/r97m", b.cwd());
        assertNull(b.worktree());

        SessionSpec c = SessionSpec.withWorktree("s1", "feature-x");
        assertEquals("s1", c.sessionId());
        assertEquals("feature-x", c.worktree());
        assertNull(c.cwd());
    }

    @Test
    void sessionSpecEqualsAndHashCode() {
        // prior round.1: the spec is the engine handle's
        // identity, so equals/hashCode need to be
        // consistent for downstream caching.
        SessionSpec a = SessionSpec.withCwd("s1", "/tmp");
        SessionSpec b = SessionSpec.withCwd("s1", "/tmp");
        SessionSpec c = SessionSpec.withCwd("s1", "/var");
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
        assertNotEquals(a, c);
    }

    @Test
    void sessionSpecWireSnapshot() {
        // prior round.1: the wire snapshot includes all
        // four fields. Used by the listSessions
        // RPC and the TUI LeftPanel.
        SessionSpec a = SessionSpec.withCwd("s1", "/tmp");
        Map<String, Object> snap = a.toWireSnapshot();
        assertEquals("s1", snap.get("sessionId"));
        assertEquals("/tmp", snap.get("cwd"));
        assertNull(snap.get("worktree"));
        assertNull(snap.get("model"));
    }

    @Test
    void sessionSpecWithBlankFieldsDefaultsToNull() {
        // prior round.1: blank strings are treated as
        // null. The wire format uses null for
        // "absent" — never "".
        SessionSpec a = new SessionSpec("s1", "", "", "");
        assertNull(a.cwd());
        assertNull(a.worktree());
        assertNull(a.model());
    }
}
