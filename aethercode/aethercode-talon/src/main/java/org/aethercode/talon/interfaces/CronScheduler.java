/**
 * Copyright (c) 2026 maijun
 *
 * <p>Licensed under the MIT License. See the LICENSE file in the project root.
 */
package org.aethercode.talon.interfaces;

import java.util.concurrent.CompletableFuture;

/**
 * Scheduler integration managed by the Talon host.
 *
 * <p>Java-native port of {@code deepagents_talon.interfaces.CronScheduler}.</p>
 */
public interface CronScheduler {

    /** Start the scheduler ticker. */
    CompletableFuture<Void> start();

    /** Stop the scheduler ticker and release resources. */
    CompletableFuture<Void> stop();
}
