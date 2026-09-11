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
