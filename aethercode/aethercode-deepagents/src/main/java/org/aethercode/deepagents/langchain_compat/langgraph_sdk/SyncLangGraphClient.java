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
