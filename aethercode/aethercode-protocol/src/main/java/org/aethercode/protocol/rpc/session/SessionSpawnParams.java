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

package org.aethercode.protocol.rpc.session;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Map;

/**
 * Phase 1.2 (T-1-12 / design.md §3.1): params for {@code session/spawn}.
 *
 * <p>Wire format:
 * <pre>
 *   { prompt, cwd, model?, workflow?, parentId?, title? }
 * </pre>
 *
 * <p>{@code prompt} and {@code cwd} are required. The optional
 * fields let the caller pre-pin a model (mid-session switch
 * support is the job of {@code model/set} but spawning with a
 * model is fine), a workflow to run on launch, the parent
 * session id when the spawn is a long-running task's child, and
 * a user-supplied title (otherwise the engine auto-generates
 * one from the first user message).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record SessionSpawnParams(
        @JsonProperty("prompt") String prompt,
        @JsonProperty("cwd") String cwd,
        @JsonProperty("model") String model,
        @JsonProperty("workflow") String workflow,
        @JsonProperty("parentId") String parentId,
        @JsonProperty("title") String title,
        @JsonProperty("config") Map<String, Object> config
) {
    public SessionSpawnParams {
        if (prompt == null || prompt.isBlank()) {
            throw new IllegalArgumentException("prompt is required");
        }
        if (cwd == null || cwd.isBlank()) {
            throw new IllegalArgumentException("cwd is required");
        }
    }
}
