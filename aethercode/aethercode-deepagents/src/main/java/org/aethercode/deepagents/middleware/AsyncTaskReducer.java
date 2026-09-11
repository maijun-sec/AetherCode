package org.aethercode.deepagents.middleware;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The {@code async_tasks} state reducer.
 *
 * <p>Java-native port of
 * {@code deepagents.middleware.async_subagents._tasks_reducer}.
 * Merges task updates into the existing tasks dict: a write with
 * the same {@code task_id} overwrites the prior entry; new task
 * ids are appended.</p>
 */
public final class AsyncTaskReducer {
    private AsyncTaskReducer() {}

    public static Map<String, AsyncTask> reduce(Map<String, AsyncTask> existing,
                                                  Map<String, AsyncTask> update) {
        Map<String, AsyncTask> merged = new LinkedHashMap<>(
                existing == null ? Map.of() : existing);
        if (update != null) merged.putAll(update);
        return merged;
    }
}
