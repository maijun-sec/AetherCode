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

package org.aethercode.core.tips;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ThreadLocalRandom;

/**
 * a tiny "tip of the day" pool. Modelled on the TS
 * {@code services/tips/}. Every time the user starts a session, the REPL
 * can pull a random tip from the pool to print on the banner. Tips are
 * plain strings, no markdown — the caller formats.
 */
public final class Tips {

    private static final List<String> POOL = List.of(
            "Press ESC in the prompt to enter vim mode (q to leave).",
            "Use /plan to see the agent's reasoning before it touches anything.",
            "Use --model to switch the model mid-session.",
            "/history shows the last 40 lines of the scrollback.",
            "Multi-line input: type, then press Enter twice (empty line) to send.",
            "Type /tools to see the tool pool, /state for session facts.",
            "/resume <id> jumps into a previous session's transcript.",
            "/sessions lists every session this engine has touched.",
            "Plan mode locks the agent behind /approve — perfect for risky edits.",
            "Use /style to switch between terse, explanatory, and json output.",
            "Read the system prompt: /plan-print shows what the model sees.",
            "Tool calls stream live — you'll see them appear in the scrollback.",
            "If the agent loops, Ctrl-C and rephrase — the model has a fresh start.",
            "/cost reports the cumulative token usage for this session.",
            "Bridge mode: the IDE plugin can drive a remote agent via WebSocket."
    );

    private Tips() {}

    public static List<String> all() { return new ArrayList<>(POOL); }

    public static int size() { return POOL.size(); }

    /** return a random tip. Use {@link ThreadLocalRandom} for cheap, thread-safe randomness. */
    public static String random() {
        return POOL.get(ThreadLocalRandom.current().nextInt(POOL.size()));
    }

    /** deterministic random — useful for tests. */
    public static String random(Random rng) {
        return POOL.get(rng.nextInt(POOL.size()));
    }

    /** rotate by index (mod size) — for predictable per-day display. */
    public static String at(int index) {
        return POOL.get(Math.floorMod(index, POOL.size()));
    }
}
