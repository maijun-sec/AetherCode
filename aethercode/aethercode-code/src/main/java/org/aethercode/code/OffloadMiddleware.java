package org.aethercode.code;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Offload middleware (stub).
 *
 * <p>Java-native port of the Python {@code deepagents_code.offload_middleware}
 * module. The Java port exposes the helper that runs the offload turn
 * the server initiates when the conversation hits a context limit. The
 * full implementation lands with the deepagents-core middleware port.</p>
 */
public final class OffloadMiddleware {
    private OffloadMiddleware() {}

    private static final Logger LOG = LoggerFactory.getLogger(OffloadMiddleware.class);

    /** Run the offload turn. Returns a placeholder. */
    public static Object runOffloadTurn(Object runtime) {
        LOG.info("runOffloadTurn (stub)");
        return new Object();
    }
}
