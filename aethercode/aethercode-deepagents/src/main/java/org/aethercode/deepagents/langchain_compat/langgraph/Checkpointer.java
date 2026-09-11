package org.aethercode.deepagents.langchain_compat.langgraph;

import org.bsc.langgraph4j.RunnableConfig;
import org.bsc.langgraph4j.checkpoint.Checkpoint;
import org.bsc.langgraph4j.checkpoint.MemorySaver;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * LangGraph-compatible {@code Checkpointer} interface.
 *
 * <p>Java-native port of
 * {@code langgraph.types.Checkpointer}. Minimal surface: get
 * and put thread state. The {@link MemoryCheckpointer}
 * provides an in-process implementation.</p>
 *
 * <p>Phase A1 (2026-08-28): the {@link MemoryCheckpointer}
 * implementation now delegates to the native langgraph4j
 * {@link MemorySaver}. The shim retains the simple
 * {@code (threadId, state, metadata) -> id} signature and
 * stores the {@code metadata} in an auxiliary map keyed by the
 * langgraph4j-assigned checkpoint id (langgraph4j's
 * {@code Checkpoint} record does not have a metadata slot, so
 * we keep the metadata in a parallel map).</p>
 */
public interface Checkpointer {
    /** Identifier for this checkpointer (for diagnostics). */
    String name();

    /**
     * Fetch the latest checkpoint for a thread.
     *
     * @return the checkpoint state, or {@code null} when no
     *         checkpoint exists for the thread.
     */
    Map<String, Object> get(String threadId);

    /**
     * Persist a checkpoint for the thread. Returns the assigned
     * checkpoint id, or {@code null} if the implementation does
     * not generate one.
     */
    String put(String threadId, Map<String, Object> state, Map<String, Object> metadata);

    /**
     * List the available checkpoint ids for a thread, in
     * insertion order.
     */
    List<String> list(String threadId);

    /**
     * In-process implementation backed by the native langgraph4j
     * {@link MemorySaver}.
     */
    class MemoryCheckpointer implements Checkpointer {
        private final String name;
        private final MemorySaver saver = new MemorySaver();
        // langgraph4j's Checkpoint record has no metadata field, so we
        // keep the metadata in a side map keyed by checkpoint id.
        private final java.util.concurrent.ConcurrentMap<String, java.util.concurrent.ConcurrentMap<String, Map<String, Object>>> metadataStore
                = new java.util.concurrent.ConcurrentHashMap<>();
        private final AtomicLong counter = new AtomicLong();

        public MemoryCheckpointer() { this("memory"); }
        public MemoryCheckpointer(String name) { this.name = name; }

        @Override public String name() { return name; }

        private static RunnableConfig rcFor(String threadId) {
            return RunnableConfig.builder().threadId(threadId).build();
        }

        @Override
        public Map<String, Object> get(String threadId) {
            return saver.get(rcFor(threadId)).map(Checkpoint::getState).orElse(null);
        }

        @Override
        public String put(String threadId, Map<String, Object> state, Map<String, Object> metadata) {
            Map<String, Object> safeState = state == null ? Map.of() : state;
            // langgraph4j Checkpoint.Builder requires non-null nodeId;
            // we use a placeholder because the shim tracks no node id.
            Checkpoint ckpt = Checkpoint.builder()
                    .nodeId("__shim__")
                    .nextNodeId("__shim__")
                    .state(safeState)
                    .build();
            try {
                RunnableConfig updated = saver.put(rcFor(threadId), ckpt);
                String id = updated.checkPointId().orElse("ckpt-" + counter.incrementAndGet());
                if (metadata != null && !metadata.isEmpty()) {
                    metadataStore
                            .computeIfAbsent(threadId, k -> new java.util.concurrent.ConcurrentHashMap<>())
                            .put(id, Map.copyOf(metadata));
                }
                return id;
            } catch (Exception e) {
                throw new RuntimeException("MemoryCheckpointer.put failed", e);
            }
        }

        @Override
        public List<String> list(String threadId) {
            List<String> ids = new ArrayList<>();
            for (Checkpoint c : saver.list(rcFor(threadId))) {
                ids.add(c.getId());
            }
            return ids;
        }

        /** Read the metadata for a given checkpoint id. */
        public Map<String, Object> getMetadata(String threadId, String id) {
            java.util.concurrent.ConcurrentMap<String, Map<String, Object>> thread =
                    metadataStore.get(threadId);
            if (thread == null) return null;
            return thread.get(id);
        }
    }
}
