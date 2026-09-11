package org.aethercode.deepagents.langchain_compat.langgraph_sdk;

import java.util.List;
import java.util.Map;

/**
 * LangGraph SDK sync client interface.
 *
 * <p>Java-native port of
 * {@code langgraph_sdk.client.SyncLangGraphClient}. Same surface
 * as {@link LangGraphClient} but synchronous. Provided for
 * callers that need a blocking API; the async client is
 * preferred for new code.</p>
 */
public interface SyncLangGraphClient {

    String createThread(Map<String, Object> metadata);

    String startRun(String threadId, String assistantId,
                     Map<String, Object> input, Map<String, Object> config);

    Run getRun(String threadId, String runId);

    void cancelRun(String threadId, String runId);

    List<Run> listRuns(String threadId, String statusFilter);

    Map<String, Object> getThreadValues(String threadId);

    void updateRun(String threadId, String runId, Map<String, Object> input);

    /** Adapt an {@link LangGraphClient} as a {@link SyncLangGraphClient}
     *  by blocking on the async methods. */
    static SyncLangGraphClient fromAsync(LangGraphClient async) {
        return new SyncLangGraphClient() {
            @Override public String createThread(Map<String, Object> metadata) {
                return async.createThread(metadata);
            }
            @Override public String startRun(String threadId, String assistantId,
                                              Map<String, Object> input, Map<String, Object> config) {
                return async.startRun(threadId, assistantId, input, config);
            }
            @Override public Run getRun(String threadId, String runId) {
                return async.getRun(threadId, runId);
            }
            @Override public void cancelRun(String threadId, String runId) {
                async.cancelRun(threadId, runId);
            }
            @Override public List<Run> listRuns(String threadId, String statusFilter) {
                return async.listRuns(threadId, statusFilter);
            }
            @Override public Map<String, Object> getThreadValues(String threadId) {
                return async.getThreadValues(threadId);
            }
            @Override public void updateRun(String threadId, String runId, Map<String, Object> input) {
                async.updateRun(threadId, runId, input);
            }
        };
    }
}
