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
