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

package org.aethercode.core.fs.backend;

import java.util.concurrent.CompletableFuture;

/**
 * Extension of {@link BackendProtocol} that adds shell command execution.
 *
 * <p>Java-native port of deepagents <code>SandboxBackendProtocol</code>.
 * Designed for backends running in isolated environments (containers,
 * VMs, remote hosts). Adds {@code execute()} and {@code aexecute()} for
 * shell commands, and an {@code id} property.</p>
 */
public interface SandboxBackendProtocol extends BackendProtocol {

    /** Unique identifier for the sandbox backend instance. */
    String id();

    /**
     * Execute a shell command in the sandbox environment.
     *
     * @param command full shell command string
     * @param timeout maximum time in seconds; {@code null} = backend default
     */
    ExecuteResponse execute(String command, Integer timeout);

    default ExecuteResponse execute(String command) {
        return execute(command, null);
    }

    default CompletableFuture<ExecuteResponse> aexecute(String command, Integer timeout) {
        return CompletableFuture.supplyAsync(() -> execute(command, timeout));
    }

    default CompletableFuture<ExecuteResponse> aexecute(String command) {
        return aexecute(command, null);
    }
}
