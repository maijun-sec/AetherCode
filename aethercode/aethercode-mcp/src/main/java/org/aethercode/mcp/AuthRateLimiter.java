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
