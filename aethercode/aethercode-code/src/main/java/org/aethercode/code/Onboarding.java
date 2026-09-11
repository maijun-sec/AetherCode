package org.aethercode.code;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * First-run onboarding marker.
 *
 * <p>Java-native port of the Python {@code deepagents_code.onboarding}
 * module. The Java port checks for a marker file under the private state
 * directory to decide whether to show the first-run flow.</p>
 */
public final class Onboarding {
    private Onboarding() {}

    private static final Logger LOG = LoggerFactory.getLogger(Onboarding.class);

    /** Marker filename. */
    public static final String ONBOARDING_MARKER_FILENAME = ".onboarded";

    /** Return the path to the onboarding marker. */
    public static Path markerPath() {
        return Path.of(System.getProperty("user.home"),
                ".deepagents", ".state", ONBOARDING_MARKER_FILENAME);
    }

    /** Whether the first-run flow has already completed. */
    public static boolean hasOnboarded() {
        return Files.exists(markerPath());
    }

    /** Mark onboarding as complete. */
    public static boolean markOnboarded() {
        try {
            Path p = markerPath();
            Path parent = p.getParent();
            if (parent != null) Files.createDirectories(parent);
            Files.writeString(p, "1\n");
            return true;
        } catch (IOException e) {
            LOG.warn("Could not write onboarding marker: {}", e.toString());
            return false;
        }
    }
}
