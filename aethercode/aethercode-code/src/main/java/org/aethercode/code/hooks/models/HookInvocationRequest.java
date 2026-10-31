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

package org.aethercode.code.hooks.models;

import java.time.Instant;
import java.util.UUID;

/**
 * Versioned hook invocation transport models.
 *
 * <p>Java 21 port of the Python
 * {@code deepagents_code.hooks.models.transport} module. The current
 * protocol version is {@code 1}.</p>
 */
public final class HookInvocationRequest {
    private HookInvocationRequest() {}

    /** Current protocol version. */
    public static final int PROTOCOL_VERSION = 1;

    /**
     * Request sent for a server-owned hook invocation.
     */
    public record Request(
            int protocolVersion,
            UUID invocationId,
            String snapshotId,
            String runId,
            HookInvocation invocation,
            Instant deadline) {
        public Request {
            if (protocolVersion != PROTOCOL_VERSION) {
                throw new IllegalArgumentException(
                        "unsupported protocol version: " + protocolVersion);
            }
        }
    }

    /**
     * Response returned for a server-owned hook invocation.
     */
    public record Response(
            int protocolVersion,
            UUID invocationId,
            String snapshotId,
            HookDecision decision) {
        public Response {
            if (protocolVersion != PROTOCOL_VERSION) {
                throw new IllegalArgumentException(
                        "unsupported protocol version: " + protocolVersion);
            }
        }
    }
}
