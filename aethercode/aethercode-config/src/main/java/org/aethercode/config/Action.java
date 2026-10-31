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

package org.aethercode.config;

/**
 * Permission matrix action. Resolution order (top wins):
 * <ol>
 *   <li>{@link #DENY} — block unconditionally, no prompt</li>
 *   <li>{@link #ASK} — surface a confirmation prompt to the user</li>
 *   <li>{@link #ALLOW} — auto-approve, no prompt</li>
 * </ol>
 * The matrix returns one of these. The engine may further override based on
 * session mode (e.g. BYPASS_PERMISSIONS short-circuits ASK to ALLOW).
 */
public enum Action {
    ALLOW,
    ASK,
    DENY;

    public boolean isAllow() { return this == ALLOW; }
    public boolean isAsk()   { return this == ASK; }
    public boolean isDeny()  { return this == DENY; }

    public static Action parse(String s) {
        if (s == null) return null;
        try {
            return Action.valueOf(s.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
