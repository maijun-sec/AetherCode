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

package org.aethercode.tasks.engine.core;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Map;
import java.util.Optional;

/**
 * Parses a child's {@code config} JSON blob into a {@link TaskLimits}
 * record. Used by {@code WallClockGuard} and {@code IdleGuard} to
 * pick up the configured caps (the {@code config} blob is shared
 * with the AetherCode wire format).
 */
public final class LimitsResolver {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    private LimitsResolver() { }

    public static TaskLimits resolve(SupervisorStore.ChildRecord child) {
        if (child == null) return TaskLimits.unlimited();
        return resolve(child.config());
    }

    public static TaskLimits resolve(String configJson) {
        if (configJson == null || configJson.isBlank()) return TaskLimits.unlimited();
        try {
            Map<String, Object> m = MAPPER.readValue(configJson, MAP_TYPE);
            return TaskLimits.fromMap(m);
        } catch (Exception e) {
            return TaskLimits.unlimited();
        }
    }

    /** Same but for tests; lets you pre-deserialise the map. */
    public static TaskLimits resolveMap(Map<String, Object> m) {
        return TaskLimits.fromMap(m);
    }

    /** Serialize a {@link TaskLimits} to a config JSON blob. */
    public static String serialize(TaskLimits limits) {
        try { return MAPPER.writeValueAsString(limits.toMap()); }
        catch (Exception e) { return "{}"; }
    }

    public static Optional<Long> wallClockMs(SupervisorStore.ChildRecord child) {
        return resolve(child).wallClockMsOpt();
    }

    public static Optional<Long> idleMs(SupervisorStore.ChildRecord child, long defaultIdleMs) {
        Optional<Long> explicit = resolve(child).idleMsOpt();
        return explicit.isPresent() ? explicit : Optional.of(defaultIdleMs);
    }
}
