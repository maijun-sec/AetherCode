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
 * Helpers for attaching Hooks v2 session identity to graph context.
 *
 * <p>Java-native port of the Python
 * {@code deepagents_code.hooks.context} module. The graph context
 * carries a {@code hooks_snapshot_id} and the set of server-owned
 * events the client runtime wants emitted; this helper stamps both
 * onto a mutable per-run context map.</p>
 */
public final class Context {

    private Context() {}

    /**
     * Attach Hooks v2 snapshot identity and server event gates to
     * {@code context}.
     *
     * @param context   mutable per-run graph context
     * @param runtime   session hooks runtime, or {@code null} when
     *                  hooks are unavailable
     * @param promptId  optional per-turn prompt id
     * @return the same context map, updated in place
     */
    public static Map<String, Object> applyHooksContext(
            Map<String, Object> context,
            Runtime.HooksRuntime runtime,
            String promptId) {
        if (context == null) return null;
        if (runtime == null) {
            context.remove("hooks_snapshot_id");
            context.remove("hooks_server_events");
        } else {
            context.put("hooks_snapshot_id", runtime.snapshotId());
            context.put("hooks_server_events", List.copyOf(runtime.configuredServerEvents()));
        }
        if (promptId != null) {
            context.put("prompt_id", promptId);
        } else {
            context.remove("prompt_id");
        }
        return context;
    }
}
