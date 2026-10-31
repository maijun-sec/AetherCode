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

package org.aethercode.partner.quickjs;

/**
 * REPL state persistence mode. 1:1 port of the Python
 * {@code PersistenceMode = Literal["thread", "turn", "call"]}.
 *
 * <ul>
 *   <li>{@link #THREAD} &mdash; state persists across calls and across turns.</li>
 *   <li>{@link #TURN} &mdash; state persists across calls within a turn only.</li>
 *   <li>{@link #CALL} &mdash; each eval call runs in a fresh REPL.</li>
 * </ul>
 */
public enum PersistenceMode {
    THREAD("thread"),
    TURN("turn"),
    CALL("call");

    private final String wire;

    PersistenceMode(String wire) {
        this.wire = wire;
    }

    public String wireName() {
        return wire;
    }

    public static PersistenceMode fromWire(String name) {
        if (name == null) return THREAD;
        return switch (name) {
            case "thread" -> THREAD;
            case "turn" -> TURN;
            case "call" -> CALL;
            default -> throw new IllegalArgumentException(
                    "`mode` must be one of 'thread', 'turn', or 'call'.");
        };
    }
}
