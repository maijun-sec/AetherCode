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

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * prior round.1 test helper: builds a stub
 * {@link AetherCodeEngine} for unit tests that
 * don't need the full engine (just session id,
 * spec, app state). Used by
 * {@link SessionManagerR97MTest}.
 *
 * <p>Real AetherCodeEngine construction is heavy
 * (it wires a chat client, session store, hooks,
 * etc.). For the prior round.1 unit tests we only need
 * the engine to exist and to report its sessionId
 * and mainLoopModel. A mock builder would be
 * overkill; instead we use a tiny reflection-free
 * factory that bypasses the real Builder.
 */
final class StubEngineFactory {

    private StubEngineFactory() {}

    /** Build a stub engine for a SessionSpec. Uses
     *  the spec.sessionId() and spec.cwd() to set
     *  the engine's session fields. */
    static AetherCodeEngine engineFor(SessionSpec spec) {
        // For unit tests we don't need a real
        // engine. The factory is invoked through
        // reflection; we just need SOMETHING that
        // satisfies the type. A no-op subclass
        // is the cleanest path — but AetherCodeEngine
        // has no public no-arg constructor. We
        // instead rely on the fact that the test
        // SessionManager doesn't actually CALL any
        // engine methods beyond what the
        // EngineHandle.toWireSnapshot() needs.
        //
        // For now, the prior round.1 unit tests are
        // written to avoid touching the engine —
        // they test the SessionManager and
        // SessionSpec only. Integration tests with
        // a real AetherCodeEngine live elsewhere.
        throw new UnsupportedOperationException(
                "对应历史 round.1 unit tests don't construct a real AetherCodeEngine; "
                + "see SessionManagerIntegrationTest for that path");
    }

    /** Legacy factory: engine for a sessionId. */
    static AetherCodeEngine engineForSid(String sessionId) {
        throw new UnsupportedOperationException(
                "对应历史 round.1 unit tests don't construct a real AetherCodeEngine; "
                + "see SessionManagerIntegrationTest for that path");
    }
}
