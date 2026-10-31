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

package org.aethercode.deepagents.langchain_compat.middleware;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * LangChain-compatible interrupt-on config.
 *
 * <p>Java-native port of
 * {@code langchain.agents.middleware.InterruptOnConfig}. Maps a
 * tool name to a boolean (interrupt before/after the call) or
 * to a config map ({@code allowedDecisions},
 * {@code description}, {@code argsValidator}, etc.).</p>
 */
public final class InterruptOnConfig {
    private final boolean enabled;
    private final Set<String> allowedDecisions;
    private final String description;
    private final List<String> argsValidator;

    public InterruptOnConfig(boolean enabled,
                              Set<String> allowedDecisions,
                              String description,
                              List<String> argsValidator) {
        this.enabled = enabled;
        this.allowedDecisions = allowedDecisions == null
                ? Set.of("approve", "edit", "reject")
                : Set.copyOf(allowedDecisions);
        this.description = description;
        this.argsValidator = argsValidator == null ? List.of() : List.copyOf(argsValidator);
    }

    public static InterruptOnConfig of(boolean enabled) {
        return new InterruptOnConfig(enabled, null, null, null);
    }

    @SuppressWarnings("unchecked")
    public static InterruptOnConfig fromMap(Map<String, Object> map) {
        Objects.requireNonNull(map, "map");
        boolean enabled = (Boolean) map.getOrDefault("enabled", true);
        Set<String> allowed = map.get("allowed_decisions") instanceof List<?>
                ? Set.copyOf((List<String>) map.get("allowed_decisions"))
                : null;
        String description = (String) map.get("description");
        List<String> argsValidator = map.get("args_validator") instanceof List<?>
                ? List.copyOf((List<String>) map.get("args_validator"))
                : null;
        return new InterruptOnConfig(enabled, allowed, description, argsValidator);
    }

    public boolean enabled() { return enabled; }
    public Set<String> allowedDecisions() { return allowedDecisions; }
    public String description() { return description; }
    public List<String> argsValidator() { return argsValidator; }
}
