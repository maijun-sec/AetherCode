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

import java.util.concurrent.atomic.AtomicReference;

/**
 * LangGraph SDK client factory.
 *
 * <p>Java-native port of
 * {@code langgraph_sdk.get_client} and
 * {@code langgraph_sdk.get_sync_client}. The Java port exposes
 * a single registry: callers register a default client via
 * {@link #setDefault(LangGraphClient)}; {@link #getClient()}
 * returns it (or an {@link LangGraphClient.InMemoryLangGraphClient}
 * when none is registered).</p>
 */
public final class LangGraphClients {
    private static final AtomicReference<LangGraphClient> CLIENT = new AtomicReference<>();

    private LangGraphClients() {}

    public static LangGraphClient getClient() {
        LangGraphClient c = CLIENT.get();
        return c == null ? new LangGraphClient.InMemoryLangGraphClient() : c;
    }

    public static SyncLangGraphClient getSyncClient() {
        return SyncLangGraphClient.fromAsync(getClient());
    }

    public static void setDefault(LangGraphClient client) {
        CLIENT.set(client);
    }

    public static void clear() {
        CLIENT.set(null);
    }
}
