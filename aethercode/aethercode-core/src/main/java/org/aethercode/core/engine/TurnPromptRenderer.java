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
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package org.aethercode.core.engine;

import org.aethercode.core.tool.Tool;

import java.nio.file.Path;
import java.util.List;

/**
 * R409: renders the system prompt for ONE turn.
 *
 * <h2>Why the prompt cannot be a frozen string</h2>
 * <p>Before R409 the engine rendered the system prompt ONCE, at construction
 * time, and every turn started from that frozen string. Two things in it were
 * already wrong by the time the first turn ran:
 *
 * <ul>
 *   <li>the <b>tool table</b> was rendered from the FULL tool pool, while the
 *       API call carried the pool PRUNED to the turn's profile (R401). The
 *       model therefore read a tool table that advertised tools it could not
 *       call — and R408 measured it doing exactly that: 48 calls to
 *       {@code bash} under a profile that had no shell, each answered
 *       "unknown tool", and the model hunting the whole tree for the "real"
 *       name of a tool that was never going to exist;</li>
 *   <li>the <b>working directory</b> was pinned to the cwd the engine was
 *       BUILT with. A later {@code switchProject} changed the session's cwd
 *       but not the prompt, so the prompt and the session anchor disagreed
 *       (R408's S2: the model said the system prompt said one directory
 *       while the session was in another).</li>
 * </ul>
 *
 * <p>Both are per-turn facts. A prompt that is rendered once cannot carry
 * per-turn facts, so the rendering itself has to move to the turn.
 *
 * <h2>Why an interface and not a builder call</h2>
 * <p>The prompt's SECTION FORMAT lives in {@code aethercode-prompts}
 * ({@code SystemPrompt}); {@code aethercode-core} does not depend on that
 * module. Rather than duplicating the format here, core asks for the string
 * and the SDK — which owns the {@code SystemPrompt} object — answers. The
 * format stays in one place and the per-turn FACT (which tools, which cwd)
 * stays in the other.
 *
 * @see org.aethercode.core.engine.QueryEngine
 */
public interface TurnPromptRenderer {

    /**
     * Render the base system prompt for one turn.
     *
     * @param turnTools the tools the model may call in THIS turn — already
     *                  pruned to the turn's profile, never the full pool.
     *                  The prompt's tool table must list exactly these.
     * @param cwd       the session's CURRENT working directory, or null when
     *                  the session has none (in which case the caller keeps
     *                  the previous environment section).
     * @return the base prompt for this turn (without the per-turn memory /
     *         plan / posture / language blocks — the engine appends those).
     */
    String render(List<Tool> turnTools, Path cwd);
}
