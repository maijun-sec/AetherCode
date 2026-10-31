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

package org.aethercode.mcp;

import java.util.HashMap;
import java.util.Map;

/**
 * second-layer stampede defense on top of {@link McpAuthCache}.
 *
 * <p>The prior round cache is a 15-minute "needs auth" record — once a server is flagged, the
 * next 15 minutes of tool calls short-circuit to {@code needs-auth} instead of
 * triggering a refresh. But it does not bound the *attempts* themselves: a buggy
 * caller could call {@code mcp auth <server>} twice in two seconds and burn two
 * browser launches.
 *
 * <p>This rate limiter is the second layer: a per-server cooldown window. While the
 * first attempt is in flight (and for {@link #cooldownMs} after each attempt), any
 * subsequent attempt is rejected with {@link #tryAcquire(String) == false}.
 *
 * <p>Defaults: 60-second cooldown, no limit on successful auths (the cooldown only
 * fires between attempts, not after success). Callers can clear the record after
 * a successful auth via {@link #clear(String)}.
 */
public class AuthRateLimiter {

    private final long cooldownMs;
    private final Map<String, Long> lastAttemptAt = new HashMap<>();

    public AuthRateLimiter() { this(60_000L); }
    public AuthRateLimiter(long cooldownMs) { this.cooldownMs = cooldownMs; }

    /**
     * Reserve a slot for {@code serverId}. Returns true if this caller may now
     * attempt the OAuth dance; false if the cooldown is still active.
     */
    public synchronized boolean tryAcquire(String serverId) {
        long now = System.currentTimeMillis();
        Long last = lastAttemptAt.get(serverId);
        if (last != null && now - last < cooldownMs) return false;
        lastAttemptAt.put(serverId, now);
        return true;
    }

    /** Force-clear the cooldown (e.g. after a successful auth so the user can re-attempt on demand). */
    public synchronized void clear(String serverId) {
        lastAttemptAt.remove(serverId);
    }

    public synchronized long remainingMs(String serverId) {
        Long last = lastAttemptAt.get(serverId);
        if (last == null) return 0;
        long remain = cooldownMs - (System.currentTimeMillis() - last);
        return Math.max(0, remain);
    }

    public long cooldownMs() { return cooldownMs; }
}
