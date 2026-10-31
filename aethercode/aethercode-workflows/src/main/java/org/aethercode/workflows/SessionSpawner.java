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

package org.aethercode.workflows;

import java.util.Map;

/**
 * Pluggable bridge between the workflow engine and whatever
 * spawns sessions (the supervisor in production, a fake in
 * tests). The engine never depends on aethercode-tasks directly
 * — it talks to a {@code SessionSpawner}. This keeps the engine
 * unit-testable and lets Java-B's T-2-10 / T-2-11 work ship a
 * supervisor-backed implementation while the engine code in this
 * module stays unchanged.
 */
@FunctionalInterface
public interface SessionSpawner {

    /**
     * Spawn a session with the given prompt, cwd, model, and
     * optional config blob (limits + workflow metadata). Returns
     * a {@link SessionRef} whose id uniquely identifies the row.
     *
     * @param prompt  the final, substituted prompt to send to the
     *                LLM (system + user messages, joined with a
     *                double newline)
     * @param cwd     the working directory the session runs in
     * @param model   the model id (e.g. {@code "claude-opus-4-1"});
     *                may be null to use the supervisor's default
     * @param config  optional per-session config (limits map,
     *                workflow name, etc.)
     */
    SessionRef spawn(String prompt, String cwd, String model,
                     Map<String, Object> config);
}
