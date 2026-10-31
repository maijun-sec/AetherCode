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

package org.aethercode.bridge.backends;

import org.aethercode.bridge.SwarmCoordinator;

/**
 * pluggable teammate backend. Modelled on the TS
 * {@code src/utils/swarm/backends/registry.ts} — the registry maps a backend name
 * to an implementation that knows how to spawn a teammate and ship tasks to it.
 *
 * <p>prior round ships:
 * <ul>
 *   <li>{@code in-process} — already in {@link SwarmCoordinator} (prior round)</li>
 *   <li>{@code tmux} — spawn a tmux window per teammate</li>
 *   <li>{@code iterm2} — same idea, iTerm2 escape sequences</li>
 * </ul>
 */
public interface TeammateBackend {

    String name();

    /**
     * Spawn a teammate. Returns an opaque id; the bridge uses it to send tasks and to
     * recognise the teammate's reply stream.
     */
    String spawn(SwarmCoordinator.TeammateSpec spec) throws Exception;

    /** Send a task; the future completes when the teammate replies. */
    java.util.concurrent.CompletableFuture<String> sendTask(String teammateId, String task);

    /** Tear down. */
    void retire(String teammateId);
}
