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

package org.aethercode.core.middleware;

import org.aethercode.core.runtime.Message;

import java.util.List;
import java.util.Locale;
import java.util.ServiceLoader;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Registry for {@link Summarizer} implementations.
 *
 * <p>Java-native port of the summarization engine lookup in
 * {@code deepagents.middleware.summarization}. The Java port uses
 * an in-process {@link ConcurrentMap} plus a {@link ServiceLoader}
 * fallback so a downstream consumer can register a real LLM-backed
 * summarizer via
 * {@code META-INF/services/org.aethercode.core.middleware.Summarizer}.</p>
 */
public final class SummarizerRegistry {
    private static final ConcurrentMap<String, Summarizer> SUMMARIZERS = new ConcurrentHashMap<>();

    static {
        register(new Summarizer() {
            @Override public String name() { return "not-installed"; }
            @Override public String summarize(List<Message> messages, java.util.Map<String, Object> context) {
                throw new SummarizerUnavailableError("Summarizer not configured");
            }
        });
        for (Summarizer s : ServiceLoader.load(Summarizer.class)) {
            register(s);
        }
    }

    private SummarizerRegistry() {}

    public static void register(Summarizer summarizer) {
        if (summarizer == null) return;
        SUMMARIZERS.put(summarizer.name().toLowerCase(Locale.ROOT), summarizer);
    }

    public static Summarizer get(String name) {
        if (name == null) {
            // Auto-pick the first non-default summarizer.
            return SUMMARIZERS.values().stream()
                    .filter(s -> !"not-installed".equals(s.name()))
                    .findFirst()
                    .orElseGet(() -> SUMMARIZERS.get("not-installed"));
        }
        Summarizer s = SUMMARIZERS.get(name.toLowerCase(Locale.ROOT));
        return s == null ? SUMMARIZERS.get("not-installed") : s;
    }

    public static boolean isAvailable() {
        return SUMMARIZERS.values().stream().anyMatch(s -> !"not-installed".equals(s.name()));
    }

    static void clear() {
        SUMMARIZERS.clear();
        // Re-seed the not-installed default so the registry always has
        // at least one entry.
        register(new Summarizer() {
            @Override public String name() { return "not-installed"; }
            @Override public String summarize(List<Message> messages, java.util.Map<String, Object> context) {
                throw new SummarizerUnavailableError("Summarizer not configured");
            }
        });
    }
}
