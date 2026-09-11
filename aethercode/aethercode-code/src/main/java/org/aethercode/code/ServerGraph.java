package org.aethercode.code;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Server-graph entry point (stub).
 *
 * <p>Java-native port of the Python {@code deepagents_code.server_graph}
 * module. The Java port returns a placeholder until the deepagents-core
 * graph factory is wired through.</p>
 */
public final class ServerGraph {
    private ServerGraph() {}

    private static final Logger LOG = LoggerFactory.getLogger(ServerGraph.class);

    /** Build the server graph. Returns a placeholder. */
    public static Object makeGraph() {
        LOG.info("makeGraph (stub); the full port returns a deepagents-core Pregel graph");
        return new Object();
    }
}
