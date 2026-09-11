package org.aethercode.code;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * GLM-5p2 profile (stub).
 *
 * <p>Java-native port of the Python {@code deepagents_code._glm_5p2_profile}
 * module. The Java port exposes the surface used by the harness profile
 * registry; the full implementation lands with the deepagents-core
 * profile port.</p>
 */
public final class Glm5p2Profile {
    private Glm5p2Profile() {}

    /** The profile name. */
    public static final String PROFILE_NAME = "glm-5p2";

    /** Profile metadata. */
    public static final Map<String, Object> METADATA = Map.of(
            "name", PROFILE_NAME,
            "version", "0.1.0",
            "description", "GLM 5.2 (preview) harness profile");
}
