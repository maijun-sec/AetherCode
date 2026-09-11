package org.aethercode.tasks.engine.core;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Minimal in-memory supervisor store. This is the deepagents-tasks
 * analog of AetherCode's SupervisorStore (which is backed by SQLite
 * via the JDBC driver). For Phase 1.1 (T-1-01..T-1-24) we only need:
 * <ul>
 *   <li>create / fetch a child by id (used by the guards),</li>
 *   <li>read / write the {@code state} pointer (hash + size) — used
 *       by {@code StateCheckpointCodec} for T-1-02,</li>
 *   <li>update the child's status (used by the guards to pause),</li>
 *   <li>read / write the JSON {@code config} blob (carries
 *       {@link TaskLimits}).</li>
 * </ul>
 *
 * <p>The store is intentionally in-memory; the on-disk / SQLite
 * version is left for a follow-up round (AetherCode's
 * {@code SupervisorStore} uses {@code org.xerial:sqlite-jdbc}; we
 * keep the deepagents-tasks module JDBC-free so the build remains
 * JDK-only). The interface mirrors the SQLite schema, so swapping
 * in a JDBC implementation later is a local change.
 *
 * <p>Thread-safety: backed by a {@link ConcurrentHashMap}; status
 * transitions are atomic via a small {@code synchronized} block.
 */
public final class SupervisorStore implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(SupervisorStore.class);

    public static final int CURRENT_SCHEMA_VERSION = 1;

    private final Map<String, ChildRecord> children = new ConcurrentHashMap<>();
    private final Object transitionLock = new Object();

    public SupervisorStore() { }

    public int schemaVersion() { return CURRENT_SCHEMA_VERSION; }

    // -- children ---------------------------------------------------------

    public String createChild(String cwd, String prompt, String parentSessionId,
                              String configJson) {
        Objects.requireNonNull(cwd, "cwd");
        String id = UUID.randomUUID().toString();
        ChildRecord r = new ChildRecord(
                id, parentSessionId, cwd, ChildStatus.QUEUED, prompt,
                System.currentTimeMillis(), null, null,
                configJson, null, 0L, null, null);
        children.put(id, r);
        LOG.debug("created child {} in {}", id, cwd);
        return id;
    }

    public Optional<ChildRecord> getChild(String id) {
        return Optional.ofNullable(children.get(id));
    }

    /**
     * Update the lifecycle status. Stamps {@code started_at} on
     * the first transition to {@link ChildStatus#RUNNING} and
     * {@code ended_at} on the first transition to a terminal
     * state. Returns the previous status (empty if the child is
     * unknown).
     */
    public Optional<ChildStatus> updateStatus(String id, ChildStatus next) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(next, "next");
        ChildRecord prev = children.get(id);
        if (prev == null) return Optional.empty();
        Optional<String> err = prev.status().validateTransition(next);
        if (err.isPresent()) {
            throw new IllegalStateException("child " + id + ": " + err.get());
        }
        if (prev.status() == next) return Optional.of(prev.status());
        synchronized (transitionLock) {
            // re-read under the lock
            prev = children.get(id);
            if (prev == null) return Optional.empty();
            ChildStatus was = prev.status();
            Long startedAt = prev.startedAtMs();
            if (next == ChildStatus.RUNNING && startedAt == null) {
                startedAt = System.currentTimeMillis();
            }
            Long endedAt = prev.endedAtMs();
            if (next.isTerminal() && endedAt == null) {
                endedAt = System.currentTimeMillis();
            }
            children.put(id, new ChildRecord(
                    prev.id(), prev.parentSessionId(), prev.cwd(), next,
                    prev.prompt(), prev.createdAtMs(), startedAt, endedAt,
                    prev.config(), prev.stateHash(), prev.stateSize(),
                    prev.limitsHit(), prev.error()));
            return Optional.of(was);
        }
    }

    public void setError(String id, String error) {
        ChildRecord prev = children.get(id);
        if (prev == null) return;
        children.put(id, withError(prev, error));
    }

    public void setConfig(String id, String configJson) {
        ChildRecord prev = children.get(id);
        if (prev == null) return;
        children.put(id, withConfig(prev, configJson));
    }

    public Optional<String> getConfig(String id) {
        return Optional.ofNullable(children.get(id)).map(ChildRecord::config);
    }

    /** Atomically replace the state-blob pointer (hash + size). */
    public void setStatePointer(String id, String hash, long sizeBytes) {
        ChildRecord prev = children.get(id);
        if (prev == null) return;
        children.put(id, withStatePointer(prev, hash, sizeBytes));
    }

    public void clearStatePointer(String id) {
        setStatePointer(id, null, 0L);
    }

    public Optional<StatePointer> getStatePointer(String id) {
        return Optional.ofNullable(children.get(id))
                .filter(c -> c.stateHash() != null)
                .map(c -> new StatePointer(c.stateHash(), c.stateSize()));
    }

    public List<ChildRecord> listByStatus(ChildStatus status) {
        List<ChildRecord> out = new ArrayList<>();
        for (ChildRecord c : children.values()) {
            if (status == null || c.status() == status) out.add(c);
        }
        out.sort((a, b) -> Long.compare(a.createdAtMs(), b.createdAtMs()));
        return out;
    }

    /** Children that need to be resumed on supervisor startup. */
    public List<ChildRecord> listResumable() {
        List<ChildRecord> out = new ArrayList<>();
        for (ChildRecord c : children.values()) {
            if (c.status() == ChildStatus.QUEUED
                    || c.status() == ChildStatus.RUNNING
                    || c.status() == ChildStatus.PAUSED) {
                out.add(c);
            }
        }
        out.sort((a, b) -> Long.compare(a.createdAtMs(), b.createdAtMs()));
        return out;
    }

    /** Wipe all state (test helper). */
    public void clear() { children.clear(); }

    public int size() { return children.size(); }

    // -- internals --------------------------------------------------------

    private static ChildRecord withError(ChildRecord p, String error) {
        return new ChildRecord(p.id(), p.parentSessionId(), p.cwd(), p.status(),
                p.prompt(), p.createdAtMs(), p.startedAtMs(), p.endedAtMs(),
                p.config(), p.stateHash(), p.stateSize(), p.limitsHit(), error);
    }

    private static ChildRecord withConfig(ChildRecord p, String config) {
        return new ChildRecord(p.id(), p.parentSessionId(), p.cwd(), p.status(),
                p.prompt(), p.createdAtMs(), p.startedAtMs(), p.endedAtMs(),
                config, p.stateHash(), p.stateSize(), p.limitsHit(), p.error());
    }

    private static ChildRecord withStatePointer(ChildRecord p, String hash, long size) {
        return new ChildRecord(p.id(), p.parentSessionId(), p.cwd(), p.status(),
                p.prompt(), p.createdAtMs(), p.startedAtMs(), p.endedAtMs(),
                p.config(), hash, size, p.limitsHit(), p.error());
    }

    @Override
    public void close() { children.clear(); }

    /** Pointer into the off-process state file (T-1-02). */
    public record StatePointer(String hash, long sizeBytes) {
        public StatePointer {
            Objects.requireNonNull(hash, "hash");
        }
    }

    /** Child record (a slimmed-down version of AetherCode's). */
    public record ChildRecord(
            String id,
            String parentSessionId,
            String cwd,
            ChildStatus status,
            String prompt,
            long createdAtMs,
            Long startedAtMs,
            Long endedAtMs,
            String config,
            String stateHash,
            long stateSize,
            String limitsHit,
            String error) {

        public Instant createdAt() { return Instant.ofEpochMilli(createdAtMs); }

        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", id);
            if (parentSessionId != null) m.put("parentSessionId", parentSessionId);
            m.put("cwd", cwd);
            m.put("status", status.name());
            if (prompt != null) m.put("prompt", prompt);
            m.put("createdAtMs", createdAtMs);
            if (startedAtMs != null) m.put("startedAtMs", startedAtMs);
            if (endedAtMs != null) m.put("endedAtMs", endedAtMs);
            if (config != null) m.put("config", config);
            if (stateHash != null) m.put("stateHash", stateHash);
            m.put("stateSize", stateSize);
            return m;
        }
    }
}
