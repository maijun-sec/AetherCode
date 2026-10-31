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

import org.aethercode.code.hooks.HookDomainEvents.Decision;

import java.time.Instant;
import java.util.UUID;

/**
 * Server↔client interrupt transport payloads.
 *
 * <p>Java-native port of the {@code deepagents_code.hooks.models.transport}
 * module. The carrier envelope is sent through the LangGraph interrupt
 * channel so the client runtime can fulfill the request.</p>
 */
public final class HookTransportTypes {

    private HookTransportTypes() {}

    /**
     * A versioned hook invocation request emitted by the server.
     */
    public record HookInvocationRequest(
            int protocolVersion,
            UUID invocationId,
            String snapshotId,
            String runId,
            HookInvocation invocation,
            Instant deadline) {

        public HookInvocationRequest {
            if (invocationId == null) {
                throw new IllegalArgumentException("invocationId must not be null");
            }
            if (snapshotId == null || snapshotId.isEmpty()) {
                throw new IllegalArgumentException("snapshotId must be non-blank");
            }
        }
    }

    /**
     * The client runtime's reply to a {@link HookInvocationRequest}.
     */
    public record HookInvocationResponse(
            int protocolVersion,
            UUID invocationId,
            String snapshotId,
            Decision decision) {
    }
}
