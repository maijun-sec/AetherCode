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

package org.aethercode.bridge;

import org.aethercode.core.tool.Tool;
import org.aethercode.sdk.AetherCodeEngine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Swarm coordinator. Spawns multiple AetherCode agents in-process and coordinates
 * them via a shared {@link Blackboard}. Modelled on the TS
 * {@code src/utils/swarm/backends/registry.ts} — the {@code in-process} backend.
 *
 * <p>prior round ships:
 * <ul>
 *   <li>Spawn a teammate engine (any of the standard tools, no special isolation)</li>
 *   <li>Send a task to a teammate, get the result back</li>
 *   <li>Read a shared blackboard map</li>
 * </ul>
 *
 * <p>prior round will add the tmux / iTerm backends and the remote-spawn path via BridgeClient.
 *
 * <p>R6: the blackboard is now an injected interface. The default is
 * {@link InMemoryBlackboard}; pass a {@link SqliteBlackboard} to persist across
 * process restarts or share state between multiple coordinators.
 */
public class SwarmCoordinator {

    private static final Logger LOG = LoggerFactory.getLogger(SwarmCoordinator.class);

    private final Map<String, AetherCodeEngine> teammates = new ConcurrentHashMap<>();
    private final Blackboard blackboard;

    public SwarmCoordinator() { this(new InMemoryBlackboard()); }
    public SwarmCoordinator(Blackboard blackboard) { this.blackboard = blackboard; }

    public String spawn(List<Tool> tools) {
        String id = "teammate-" + UUID.randomUUID().toString().substring(0, 8);
        AetherCodeEngine engine = AetherCodeEngine.builder()
                .tools(tools == null ? java.util.List.of() : tools)
                .build();
        teammates.put(id, engine);
        LOG.info("spawned teammate {}", id);
        return id;
    }

    public void retire(String id) { teammates.remove(id); }

    public List<String> roster() { return List.copyOf(teammates.keySet()); }

    public CompletableFuture<String> sendTask(String id, String task) {
        AetherCodeEngine engine = teammates.get(id);
        if (engine == null) return CompletableFuture.failedFuture(new IllegalArgumentException("unknown teammate: " + id));
        StringBuilder out = new StringBuilder();
        return CompletableFuture.supplyAsync(() -> {
            // R746b: close the stream. sendTask already returns a
        // CompletableFuture the caller can time out on, so this site
        // gets try-with-resources only — see
        // docs/R746-consumer-guard-rollout.md.
            try (java.util.stream.Stream<org.aethercode.core.stream.StreamEvent> stream = engine.query(task)) {
                stream.forEach(ev -> {
                    if (ev instanceof org.aethercode.core.stream.StreamEvent.TextDelta td) out.append(td.text());
                });
            }
            String result = out.toString();
            blackboard.write(id + ":last_result", result);
            return result;
        });
    }

    public void writeBlackboard(String key, Object value) { blackboard.write(key, value); }
    public Object readBlackboard(String key) { return blackboard.read(key); }
    public Map<String, Object> blackboard() { return blackboard.snapshot(); }
    public Blackboard blackboardBackend() { return blackboard; }

    public AetherCodeEngine teammate(String id) { return teammates.get(id); }

    /** spec passed to pluggable backends. */
    public record TeammateSpec(String id, List<org.aethercode.core.tool.Tool> tools, java.util.Map<String, String> env) {}
}
