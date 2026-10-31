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

package org.aethercode.tasks.channel;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collection;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * R-P1-T08 (app-spec/tasks.md §1.1): a per-supervisor registry
 * of {@link ResumableChannel}s keyed by connection id. Each
 * APP connection (TUI, desktop, CLI) gets its own channel so
 * consent prompts are scoped to the right surface; this is the
 * "per-session ack map" the spec refers to.
 *
 * <p>The registry is the supervisor's side of the consent
 * protocol: the {@code task/connected} RPC allocates a channel
 * for the new connection; {@code task/consent <seq>, <choice>}
 * looks it up and resolves the pending ack. On
 * {@code task/disconnected} the channel is closed and removed.
 */
public final class ChannelRegistry implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(ChannelRegistry.class);

    private final Map<String, ResumableChannel> channels = new ConcurrentHashMap<>();
    private final ResumableChannelFactory factory;
    private final AtomicInteger created = new AtomicInteger(0);

    public ChannelRegistry() {
        this(ResumableChannel.InMemory::new);
    }

    public ChannelRegistry(ResumableChannelFactory factory) {
        this.factory = Objects.requireNonNull(factory, "factory");
    }

    /**
     * Get or create the channel for {@code connectionId}.
     * Idempotent: a second call for the same id returns the
     * existing instance.
     */
    public ResumableChannel getOrCreate(String connectionId) {
        Objects.requireNonNull(connectionId, "connectionId");
        return channels.computeIfAbsent(connectionId, id -> {
            ResumableChannel ch = factory.create(id);
            created.incrementAndGet();
            LOG.debug("channel registry: created {} (total {})", id, created.get());
            return ch;
        });
    }

    /**
     * Remove and close the channel for {@code connectionId}.
     * No-op if the id is unknown. Returns true if a channel
     * was actually removed.
     */
    public boolean remove(String connectionId) {
        Objects.requireNonNull(connectionId, "connectionId");
        ResumableChannel ch = channels.remove(connectionId);
        if (ch == null) return false;
        try { ch.close(); }
        catch (RuntimeException e) {
            LOG.warn("close() of channel {} threw: {}", connectionId, e.getMessage());
        }
        return true;
    }

    /** Number of active channels. */
    public int size() { return channels.size(); }

    /** Snapshot of all active channels. */
    public Collection<ResumableChannel> all() { return channels.values(); }

    /**
     * Look up a channel by id without creating it. Returns
     * empty if unknown.
     */
    public java.util.Optional<ResumableChannel> find(String connectionId) {
        return java.util.Optional.ofNullable(channels.get(connectionId));
    }

    /** Total number of channels ever created (for tests). */
    public int createdCount() { return created.get(); }

    @Override
    public void close() {
        // Copy first; remove() mutates the map.
        for (String id : new java.util.ArrayList<>(channels.keySet())) {
            remove(id);
        }
    }

    /** Factory so callers can swap in a test double or a metrics wrapper. */
    @FunctionalInterface
    public interface ResumableChannelFactory {
        ResumableChannel create(String connectionId);
    }
}
