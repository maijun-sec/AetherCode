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

import java.util.Optional;

/**
 * Input schema for {@code list_async_tasks}.
 *
 * <p>Java-native port of
 * {@code ListAsyncTasksSchema}. The {@code statusFilter} is an
 * {@link Optional} so the model adapter can detect "no filter
 * supplied" and render it as the {@code "all"} sentinel.</p>
 */
public record ListAsyncTasksSchema(AsyncTask.StatusFilter statusFilter) {
    public ListAsyncTasksSchema {
        if (statusFilter == null) statusFilter = AsyncTask.StatusFilter.ALL;
    }
    public static ListAsyncTasksSchema unfiltered() {
        return new ListAsyncTasksSchema(AsyncTask.StatusFilter.ALL);
    }
    public static ListAsyncTasksSchema of(String filter) {
        return new ListAsyncTasksSchema(AsyncTask.StatusFilter.parse(filter));
    }
    public Optional<AsyncTask.StatusFilter> statusFilterOpt() {
        return statusFilter == AsyncTask.StatusFilter.ALL ? Optional.empty() : Optional.of(statusFilter);
    }
}
