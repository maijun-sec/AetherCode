package org.aethercode.tools.task;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * R372.3: per-agent concurrency limiter.
 *
 * <p>Each agent name has its own quota: the maximum number
 * of concurrent in-flight subagents of that name. A
 * spawn-by-default-when-allowed caller calls
 * {@link #tryAcquire(String, String)} <i>before</i>
 * registering a job; a quota denial returns
 * {@link #AcquireResult#denied()} so the caller can
 * refuse to spawn (typically with a "agent X is busy"
 * error returned to the model).
 *
 * <h3>Why per-agent</h3>
 *
 * <p>Different agents have different cost profiles — a
 * heavy code-reviewer should run one at a time
 * (the LLM context is expensive to refresh); a cheap
 * "summarize this paragraph" sub-agent can safely run
 * twenty in parallel. Per-agent quotas let the caller
 * tune concurrency to the cost of each role.
 *
 * <h3>Release semantics</h3>
 *
 * <p>{@link #release(String, String, String)} is keyed on
 * the {@code tokenId} returned from
 * {@link #tryAcquire}. This avoids the classic
 * "release-something-you-didn't-acquire" bug that
 * per-thread semaphores suffer from when one thread
 * releases another thread's permit.
 *
 * <h3>Default quota</h3>
 *
 * <p>1 (serial). Built-in agents typically cannot make
 * meaningful progress concurrently — two parallel
 * "summarize this" sub-agents are slower than one after
 * another because they compete for the same chat-client
 * quota. Custom agents opt into higher concurrency by
 * calling {@link #setQuota(String, int)} from their
 * startup hook (or by editing
 * {@code ~/.aethercode/agents.yaml} — the format is the
 * engine's, not the registry's, so the integration is
 * out of scope for R372.3).
 */
public final class SubagentConcurrencyLimiter {

    private static final Logger LOG = LoggerFactory.getLogger(SubagentConcurrencyLimiter.class);

    /** default per-agent quota. 1 = serial (the safest
     *  default — concurrent subagents of the same role
     *  rarely help). */
    public static final int DEFAULT_QUOTA = 1;

    /** result of {@link #tryAcquire}. */
    public record AcquireResult(boolean acquired, String tokenId, int currentInFlight, int quota) {
        public static AcquireResult denied(int quota) {
            return new AcquireResult(false, "", 0, quota);
        }
    }

    private static final class Slot {
        int inFlight;
        int quota;
        final java.util.LinkedHashMap<String, Boolean> tokens = new java.util.LinkedHashMap<>();
        Slot(int q) { this.quota = q; }
    }

    private final Map<String, Slot> slots = new LinkedHashMap<>();

    /** Set the quota for {@code agentName}. A non-positive
     *  quota is treated as {@link #DEFAULT_QUOTA}. The
     *  setter is idempotent — calling it twice with the
     *  same value is a no-op. */
    public synchronized void setQuota(String agentName, int quota) {
        if (agentName == null) return;
        int q = quota <= 0 ? DEFAULT_QUOTA : quota;
        Slot s = slots.computeIfAbsent(agentName, k -> new Slot(q));
        s.quota = q;
        LOG.info("concurrency quota for agent '{}' set to {}", agentName, q);
    }

    /** peek the current in-flight count and quota for
     *  {@code agentName}. Returns {@code (0, default)} for
     *  unknown agents. */
    public synchronized Snapshot snapshot(String agentName) {
        Slot s = slots.get(agentName);
        if (s == null) return new Snapshot(0, DEFAULT_QUOTA);
        return new Snapshot(s.inFlight, s.quota);
    }

    /** attempt to acquire one slot for {@code agentName}.
     *  Returns {@link AcquireResult#denied()} when the
     *  quota is exhausted; the caller is responsible for
     *  returning a clean error to the model rather than
     *  blocking. {@code jobHint} is an identifier the
     *  caller can use to audit the {@link #release} (it
     *  has no effect on the concurrency decision — it's
     *  purely for the audit log so a debug dump shows
     *  which logical job held the permit). */
    public synchronized AcquireResult tryAcquire(String agentName, String jobHint) {
        Slot s = slots.computeIfAbsent(agentName, k -> new Slot(DEFAULT_QUOTA));
        if (s.inFlight >= s.quota) {
            return AcquireResult.denied(s.quota);
        }
        s.inFlight++;
        String tokenId = "conc-" + Long.toHexString(System.nanoTime())
                + "-" + Integer.toHexString(s.tokens.size());
        s.tokens.put(tokenId, Boolean.TRUE);
        if (jobHint != null && !jobHint.isBlank()) {
            LOG.debug("concurrency acquired for agent '{}' (job={}, token={}, inFlight={}/{})",
                    agentName, jobHint, tokenId, s.inFlight, s.quota);
        }
        return new AcquireResult(true, tokenId, s.inFlight, s.quota);
    }

    /** release a previously-acquired slot. {@code tokenId}
     *  must match a live permit — releasing an unknown
     *  token is logged as a warning and treated as a
     *  no-op (so a buggy caller can't corrupt the slot's
     *  in-flight count). */
    public synchronized void release(String agentName, String tokenId, String jobHint) {
        Slot s = slots.get(agentName);
        if (s == null) return;
        if (tokenId == null || s.tokens.remove(tokenId) == null) {
            LOG.warn("concurrency release for agent '{}' (job={}) with unknown token '{}' — ignored",
                    agentName, jobHint, tokenId);
            return;
        }
        s.inFlight = Math.max(0, s.inFlight - 1);
        LOG.debug("concurrency released for agent '{}' (job={}, inFlight={}/{})",
                agentName, jobHint, s.inFlight, s.quota);
    }

    /** R374.2: clear all slots so a fresh test (or a
     *  daemon restart in a future round) can start with
     *  an empty concurrency table. The default-quota
     *  rule (setQuota(<0) = DEFAULT_QUOTA) means a
     *  slot that was created by a prior test will be
     *  reset to 1 when it next sees a register() — a
     *  blank reset here is the most predictable for
     *  callers that want a true clean state. */
    public synchronized void reset() {
        slots.clear();
    }

    public record Snapshot(int inFlight, int quota) {}
}