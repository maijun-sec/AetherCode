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

package org.aethercode.code.hooks;

import java.util.List;
import java.util.Map;

/**
 * Validated Hooks v2 configuration types.
 *
 * <p>Java-native port of the {@code deepagents_code.hooks.models.config}
 * module. The shapes match the Pydantic models but use plain Java records
 * so the loader can populate them without a runtime validator.</p>
 */
public final class HookConfigTypes {

    private HookConfigTypes() {}

    /** One command-form handler entry. */
    public record CommandHandlerSpec(
            String type,
            String command,
            List<String> argv,
            Double timeout,
            String statusMessage) {

        public CommandHandlerSpec {
            if (type == null) {
                type = "command";
            }
            if (argv == null) {
                argv = List.of();
            } else {
                argv = List.copyOf(argv);
            }
        }
    }

    /**
     * One matcher group within an event block. The {@code hooks} list is the
     * ordered list of handlers invoked when the matcher matches.
     */
    public record MatcherGroup(String matcher, List<CommandHandlerSpec> hooks) {

        public MatcherGroup {
            if (matcher == null) {
                matcher = "*";
            } else if (matcher.isEmpty()) {
                matcher = "*";
            }
            if (hooks == null) {
                hooks = List.of();
            } else {
                hooks = List.copyOf(hooks);
            }
        }
    }

    /** Validated hook configuration. Keys are the canonical {@link HookEvent} values. */
    public record HooksConfig(Map<HookEvent, List<MatcherGroup>> hooks) {

        public HooksConfig {
            hooks = hooks == null ? Map.of() : Map.copyOf(hooks);
            for (Map.Entry<HookEvent, List<MatcherGroup>> e : hooks.entrySet()) {
                if (e.getValue() == null) {
                    throw new IllegalArgumentException(
                            "hooks[" + e.getKey() + "] must not be null");
                }
            }
        }
    }
}
