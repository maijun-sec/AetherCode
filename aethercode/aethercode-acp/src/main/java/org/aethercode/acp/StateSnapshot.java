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

package org.aethercode.acp;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * LangGraph state snapshot, returned by
 * {@link StreamingStateGraph#agetState}.
 *
 * <p>Mirrors {@code langgraph.types.StateSnapshot}. The Java
 * port keeps the surface small: a values map, the next-node
 * list, an optional config, and an optional interrupts list.</p>
 */
public record StateSnapshot(
        Map<String, Object> values,
        List<String> next,
        Optional<Map<String, Object>> config,
        Optional<List<Interrupt>> interrupts) {

    public StateSnapshot {
        values = values == null ? Map.of() : Map.copyOf(values);
        next = next == null ? List.of() : List.copyOf(next);
        config = config == null ? Optional.empty() : config.map(Map::copyOf);
        interrupts = interrupts == null ? Optional.empty() : interrupts;
    }

    /**
     * Pending interrupt. Mirrors {@code langgraph.types.Interrupt}.
     */
    public record Interrupt(String id, Object value) {
    }
}
