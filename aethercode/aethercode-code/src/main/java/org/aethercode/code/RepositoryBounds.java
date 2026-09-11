package org.aethercode.code;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Repository bounds (stub).
 *
 * <p>Java-native port of the Python {@code deepagents_code._repository_bounds}
 * module. The Java port exposes the surface used to constrain the agent
 * to a project root; the full implementation lands with the
 * deepagents-core backend port.</p>
 */
public final class RepositoryBounds {
    private RepositoryBounds() {}

    /** Repository-bounds config. */
    public record Bounds(
            String projectRoot,
            boolean readOnly,
            java.util.List<String> allowedPaths) {
    }

    /** Read the bounds for a project root. */
    public static Bounds forRoot(String projectRoot) {
        return new Bounds(projectRoot, false, java.util.List.of());
    }
}
