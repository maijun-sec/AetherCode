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

import java.util.Objects;
import java.util.Optional;

/**
 * Subagent model reference.
 *
 * <p>Java-native port of the union
 * {@code str | BaseChatModel} from
 * {@code deepagents.middleware.subagents.SubAgent.model}. The Java
 * port stores either a model spec string (e.g. {@code "openai:gpt-5.5"})
 * or a model object reference (any Java chat-model implementation
 * plugged in by the consumer).</p>
 */
public record SubAgentModel(String spec, Object model) {

    public SubAgentModel {
        if ((spec == null || spec.isBlank()) && model == null) {
            throw new IllegalArgumentException(
                    "SubAgentModel must carry either a spec or a model object");
        }
    }

    public static SubAgentModel fromSpec(String spec) {
        return new SubAgentModel(Objects.requireNonNull(spec, "spec"), null);
    }

    public static SubAgentModel fromModel(Object model) {
        return new SubAgentModel(null, Objects.requireNonNull(model, "model"));
    }

    public Optional<String> specOpt() {
        return spec == null ? Optional.empty() : Optional.of(spec);
    }

    public Optional<Object> modelOpt() {
        return model == null ? Optional.empty() : Optional.of(model);
    }
}
