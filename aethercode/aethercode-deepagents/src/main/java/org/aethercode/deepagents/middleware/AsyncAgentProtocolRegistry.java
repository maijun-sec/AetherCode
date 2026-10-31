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
import java.util.Locale;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Registry for {@link AsyncAgentProtocolClient} implementations.
 *
 * <p>Java-native port of the client-cache behavior in
 * {@code deepagents.middleware.async_subagents._ClientCache}. The
 * Python port lazily creates a per-(url, headers) LangGraph SDK
 * client on first use; the Java port uses an in-process
 * {@link ConcurrentMap} plus a {@link ServiceLoader} fallback so
 * a downstream consumer can register a real SDK adapter via
 * {@code META-INF/services}.</p>
 *
 * <p>The default registered client is
 * {@link AsyncAgentProtocolClient#NOT_INSTALLED}, which throws
 * {@link AsyncSubAgentUnavailableError} on every call. Consumers
 * wire a real client by calling {@link #register}.</p>
 */
public final class AsyncAgentProtocolRegistry {
    private static final ConcurrentMap<String, AsyncAgentProtocolClient> CLIENTS = new ConcurrentHashMap<>();

    static {
        // Seed the not-installed client so callers always have a
        // name to look up.
        register(AsyncAgentProtocolClient.NOT_INSTALLED);

        for (AsyncAgentProtocolClient client : ServiceLoader.load(AsyncAgentProtocolClient.class)) {
            register(client);
        }
    }

    private AsyncAgentProtocolRegistry() {}

    /**
     * Register a client. Subsequent registrations for the same
     * {@link AsyncAgentProtocolClient#name() name} replace the prior
     * one.
     */
    public static void register(AsyncAgentProtocolClient client) {
        if (client == null) return;
        CLIENTS.put(client.name().toLowerCase(Locale.ROOT), client);
    }

    /**
     * Return the client for {@code name}, or
     * {@link AsyncAgentProtocolClient#NOT_INSTALLED} if no real
     * client is registered.
     */
    public static AsyncAgentProtocolClient get(String name) {
        if (name == null) return AsyncAgentProtocolClient.NOT_INSTALLED;
        return CLIENTS.getOrDefault(name.toLowerCase(Locale.ROOT),
                AsyncAgentProtocolClient.NOT_INSTALLED);
    }

    /** Whether a real (non-default) client is currently registered. */
    public static boolean isAvailable() {
        for (AsyncAgentProtocolClient c : CLIENTS.values()) {
            if (c != AsyncAgentProtocolClient.NOT_INSTALLED) return true;
        }
        return false;
    }

    /** Test hook: drop all clients and re-seed the not-installed default. */
    static void clear() {
        CLIENTS.clear();
        register(AsyncAgentProtocolClient.NOT_INSTALLED);
    }

    /** Helper: build a {@code langchain} request-input payload with
     *  a single user message. Mirrors the Python port's
     *  {@code {"messages": [{"role": "user", "content": description}]}}. */
    public static Map<String, Object> userInputMessage(String content) {
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("role", "user");
        message.put("content", content == null ? "" : content);
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("messages", java.util.List.of(message));
        return input;
    }
}
