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

package org.aethercode.core.runtime.llm;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Edits applied to the auto-added {@code general-purpose} subagent.
 *
 * <p>Java-native port of
 * {@code deepagents.profiles.harness.harness_profiles.GeneralPurposeSubagentProfile}.
 * These settings only affect the default subagent that
 * {@code createDeepAgent} inserts when the caller does not explicitly
 * provide a subagent named {@code general-purpose}.</p>
 */
public record GeneralPurposeSubagentProfile(
        Boolean enabled,
        String description,
        String systemPrompt) {

    private static final Set<String> KNOWN_KEYS = Set.of(
            "enabled", "description", "system_prompt");

    /** Default: inherit / defaults on. */
    public static GeneralPurposeSubagentProfile defaults() {
        return new GeneralPurposeSubagentProfile(null, null, null);
    }

    /**
     * Dump this sub-profile to a plain map.
     *
     * <p>Only fields with non-{@code null} values are emitted so the
     * serialized form round-trips cleanly without forcing {@code null}
     * defaults into the config.</p>
     */
    public Map<String, Object> toMap() {
        Map<String, Object> out = new LinkedHashMap<>();
        if (enabled != null) out.put("enabled", enabled);
        if (description != null) out.put("description", description);
        if (systemPrompt != null) out.put("system_prompt", systemPrompt);
        return out;
    }

    /**
     * Construct a sub-profile from a plain map.
     *
     * @throws IllegalArgumentException on unknown keys or wrong-typed values
     */
    public static GeneralPurposeSubagentProfile fromMap(Map<String, Object> data) {
        if (data == null) return defaults();
        java.util.Set<String> unknown = new java.util.HashSet<>(data.keySet());
        unknown.removeAll(KNOWN_KEYS);
        if (!unknown.isEmpty()) {
            throw new IllegalArgumentException(
                    "Unknown keys in GeneralPurposeSubagentProfile map: " + unknown);
        }
        Object e = data.get("enabled");
        Object d = data.get("description");
        Object s = data.get("system_prompt");
        if (e != null && !(e instanceof Boolean)) {
            throw new IllegalArgumentException(
                    "`enabled` must be Boolean or null, got " + e.getClass().getSimpleName());
        }
        if (d != null && !(d instanceof String)) {
            throw new IllegalArgumentException(
                    "`description` must be String or null, got " + d.getClass().getSimpleName());
        }
        if (s != null && !(s instanceof String)) {
            throw new IllegalArgumentException(
                    "`system_prompt` must be String or null, got " + s.getClass().getSimpleName());
        }
        return new GeneralPurposeSubagentProfile(
                (Boolean) e, (String) d, (String) s);
    }
}
