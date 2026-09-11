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
