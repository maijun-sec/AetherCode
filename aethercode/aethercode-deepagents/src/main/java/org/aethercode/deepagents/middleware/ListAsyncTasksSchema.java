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
