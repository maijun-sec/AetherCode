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

package org.aethercode.code;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Configurable model middleware (stub).
 *
 * <p>Java-native port of the Python {@code deepagents_code.configurable_model}
 * module. The Java port exposes the small surface used by the agent
 * graph factory when swapping the active model; the full implementation
 * lands with the deepagents-core middleware port.</p>
 */
public final class ConfigurableModel {
    private ConfigurableModel() {}

    private static final Logger LOG = LoggerFactory.getLogger(ConfigurableModel.class);

    /** Resolve the model spec for a context. */
    public static String resolveModelSpec(Object context) {
        if (context instanceof Map<?, ?> m) {
            Object spec = m.get("model");
            if (spec instanceof String s && !s.isEmpty()) return s;
        }
        return null;
    }

    /** Resolve the per-run model parameters. */
    public static Map<String, Object> resolveModelParams(Object context) {
        if (context instanceof Map<?, ?> m) {
            Object params = m.get("model_params");
            if (params instanceof Map<?, ?> p) {
                Map<String, Object> out = new LinkedHashMap<>();
                for (Map.Entry<?, ?> e : p.entrySet()) {
                    if (e.getKey() != null) out.put(e.getKey().toString(), e.getValue());
                }
                return out;
            }
        }
        return Map.of();
    }
}
