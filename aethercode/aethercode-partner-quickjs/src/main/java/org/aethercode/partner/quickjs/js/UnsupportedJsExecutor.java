package org.aethercode.partner.quickjs.js;

import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;

/**
 * Stub {@link JsExecutor} that throws {@link UnsupportedOperationException}
 * for every operation.
 *
 * <p>Used as the default binding so the partner package compiles and
 * its non-execution paths (prompt rendering, snapshot encoding, PTC
 * filtering, subagent event shaping) can be unit tested before a real
 * JavaScript engine is wired in.</p>
 */
final class UnsupportedJsExecutor implements JsExecutor {

    static final UnsupportedJsExecutor INSTANCE = new UnsupportedJsExecutor();

    private UnsupportedJsExecutor() {}

    @Override
    public JsWorker createWorker(String name) {
        throw new UnsupportedOperationException(
                "QuickJS JavaScript execution is not available: no JsExecutor binding is registered. "
                        + "Wire a GraalVM JS, javax.script, or JNI-based binding before invoking eval().");
    }

    @Override
    public <T> T runIsolated(Callable<T> task) {
        throw new UnsupportedOperationException(
                "QuickJS JavaScript execution is not available: no JsExecutor binding is registered.");
    }

    @Override
    public <T> CompletableFuture<T> runIsolatedAsync(Callable<T> task) {
        CompletableFuture<T> failed = new CompletableFuture<>();
        failed.completeExceptionally(new UnsupportedOperationException(
                "QuickJS JavaScript execution is not available: no JsExecutor binding is registered."));
        return failed;
    }
}
